param(
    [string]$ReleaseDir = "Solartron-Cut-Release",
    [string]$DistDir = "dist",
    [switch]$SkipBuild,
    [switch]$RunTests,
    [switch]$IncludeLogs
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Resolve-InProjectPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot $PathValue))
}

function Assert-InProject {
    param(
        [string]$PathValue,
        [string]$Name
    )
    $projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\')
    $fullPath = [System.IO.Path]::GetFullPath($PathValue).TrimEnd('\')
    if ($fullPath -ne $projectRoot -and -not $fullPath.StartsWith($projectRoot + '\')) {
        throw "$Name must be inside project root: $projectRoot"
    }
}

function Assert-CommandAvailable {
    param(
        [string]$Command,
        [string]$InstallHint
    )
    if ($null -eq (Get-Command $Command -ErrorAction SilentlyContinue)) {
        throw "Required command '$Command' was not found. $InstallHint"
    }
}

function Build-AndSyncFrontend {
    param([string]$ProjectRoot)

    $frontendPath = Join-Path $ProjectRoot "src\main\resources\cutting-optima"
    $frontendDistPath = Join-Path $frontendPath "dist"
    $staticPath = Join-Path $ProjectRoot "src\main\resources\static"
    $nodeModulesPath = Join-Path $frontendPath "node_modules"

    Assert-InProject $frontendPath "FrontendDir"
    Assert-InProject $frontendDistPath "FrontendDistDir"
    Assert-InProject $staticPath "StaticDir"
    Assert-CommandAvailable "node.exe" "Install Node.js 20 or later and add it to PATH."
    Assert-CommandAvailable "npm.cmd" "Install npm and add it to PATH."

    if (-not (Test-Path -LiteralPath (Join-Path $frontendPath "package.json"))) {
        throw "Frontend package.json not found: $frontendPath"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $frontendPath "package-lock.json"))) {
        throw "Frontend package-lock.json not found: $frontendPath"
    }

    Push-Location $frontendPath
    try {
        if (-not (Test-Path -LiteralPath $nodeModulesPath)) {
            Write-Step "Installing frontend dependencies with npm ci"
            & npm.cmd ci
            if ($LASTEXITCODE -ne 0) {
                throw "npm ci failed with exit code $LASTEXITCODE"
            }
        }

        Write-Step "Building frontend"
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) {
            throw "Frontend build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }

    if (-not (Test-Path -LiteralPath (Join-Path $frontendDistPath "index.html"))) {
        throw "Frontend dist index.html not found: $frontendDistPath"
    }

    Write-Step "Synchronizing frontend assets to Spring Boot static resources"
    if (-not (Test-Path -LiteralPath $staticPath)) {
        New-Item -ItemType Directory -Path $staticPath | Out-Null
    }

    Get-ChildItem -LiteralPath $staticPath -Force | Remove-Item -Recurse -Force
    Get-ChildItem -LiteralPath $frontendDistPath -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $staticPath -Recurse -Force
    }
}

function Build-Backend {
    param(
        [string]$ProjectRoot,
        [bool]$ExecuteTests
    )

    $mavenWrapper = Join-Path $ProjectRoot "mvnw.cmd"
    if (-not (Test-Path -LiteralPath $mavenWrapper)) {
        throw "Maven Wrapper not found: $mavenWrapper"
    }
    Assert-CommandAvailable "java.exe" "Install Java 17 and add it to PATH."

    $mavenArguments = @("clean", "package")
    if (-not $ExecuteTests) {
        $mavenArguments = @("-DskipTests") + $mavenArguments
    }

    $modeText = if ($ExecuteTests) { "with tests" } else { "without tests (fast package)" }
    Write-Step "Building Spring Boot jar $modeText"
    & $mavenWrapper @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed with exit code $LASTEXITCODE"
    }
}

function Find-RunnableJar {
    param([string]$ProjectRoot)

    $jar = Get-ChildItem -Path (Join-Path $ProjectRoot "target") -Filter "*.jar" |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "No runnable jar found under target"
    }
    return $jar
}

function Assert-JarContainsCurrentFrontend {
    param(
        [string]$JarPath,
        [string]$StaticPath
    )

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $staticIndexPath = Join-Path $StaticPath "index.html"
    if (-not (Test-Path -LiteralPath $staticIndexPath)) {
        throw "Static index.html not found: $staticIndexPath"
    }

    $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $indexEntry = $archive.GetEntry("BOOT-INF/classes/static/index.html")
        if ($null -eq $indexEntry) {
            throw "Packaged jar does not contain BOOT-INF/classes/static/index.html"
        }

        $reader = [System.IO.StreamReader]::new($indexEntry.Open(), [System.Text.Encoding]::UTF8, $true)
        try {
            $jarIndex = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }
        $staticIndex = Get-Content -LiteralPath $staticIndexPath -Raw -Encoding UTF8
        if ($jarIndex -ne $staticIndex) {
            throw "Packaged jar contains a stale frontend index.html"
        }

        $assetMatches = [System.Text.RegularExpressions.Regex]::Matches(
            $staticIndex,
            '(?:src|href)="(/assets/[^"?]+)')
        if ($assetMatches.Count -eq 0) {
            throw "No hashed frontend asset was referenced by static index.html"
        }
        foreach ($match in $assetMatches) {
            $assetEntryPath = "BOOT-INF/classes/static" + $match.Groups[1].Value
            if ($null -eq $archive.GetEntry($assetEntryPath)) {
                throw "Packaged jar is missing frontend asset: $assetEntryPath"
            }
        }
    } finally {
        $archive.Dispose()
    }
}

function Assert-ReleaseFiles {
    param([string]$ReleasePath)

    if (-not (Test-Path -LiteralPath $ReleasePath)) {
        throw "Release directory not found: $ReleasePath"
    }
    $releaseFiles = Get-ChildItem -LiteralPath $ReleasePath -File
    if (-not (Test-Path -LiteralPath (Join-Path $ReleasePath "app.jar"))) {
        throw "Missing release file: app.jar"
    }
    if (-not (Test-Path -LiteralPath (Join-Path $ReleasePath "application.properties"))) {
        throw "Missing release file: application.properties"
    }
    if (-not ($releaseFiles | Where-Object { $_.Extension -ieq ".bat" })) {
        throw "Missing release startup .bat file"
    }
    if (-not ($releaseFiles | Where-Object { $_.Extension -ieq ".txt" })) {
        throw "Missing release readme .txt file"
    }
    if (-not ($releaseFiles | Where-Object { $_.Extension -ieq ".xlsx" })) {
        throw "Missing release template .xlsx file"
    }
    return $releaseFiles
}

function Assert-ReleaseZip {
    param([string]$ZipPath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $entryNames = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        $prefix = "Solartron-Cut-Release/"
        foreach ($required in @("app.jar", "application.properties")) {
            if ($entryNames -notcontains ($prefix + $required)) {
                throw "Release zip is missing: $required"
            }
        }
        foreach ($extension in @(".bat", ".txt", ".xlsx")) {
            if (-not ($entryNames | Where-Object {
                        $_.StartsWith($prefix) -and $_.EndsWith($extension, [StringComparison]::OrdinalIgnoreCase)
                    })) {
                throw "Release zip is missing a $extension file"
            }
        }
    } finally {
        $archive.Dispose()
    }
}

$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$releasePath = Resolve-InProjectPath $ReleaseDir
$distPath = Resolve-InProjectPath $DistDir
$stagePath = Join-Path $distPath "release-stage"
$staticPath = Join-Path $projectRoot "src\main\resources\static"

Assert-InProject $releasePath "ReleaseDir"
Assert-InProject $distPath "DistDir"
Assert-InProject $stagePath "StageDir"
Set-Location $projectRoot

if (-not $SkipBuild) {
    Build-AndSyncFrontend -ProjectRoot $projectRoot
    Build-Backend -ProjectRoot $projectRoot -ExecuteTests $RunTests.IsPresent

    $jar = Find-RunnableJar -ProjectRoot $projectRoot
    Assert-JarContainsCurrentFrontend -JarPath $jar.FullName -StaticPath $staticPath

    if (-not (Test-Path -LiteralPath $releasePath)) {
        New-Item -ItemType Directory -Path $releasePath | Out-Null
    }
    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $releasePath "app.jar") -Force
    Write-Host "Updated app.jar from $($jar.Name)"
}

$releaseFiles = @(Assert-ReleaseFiles -ReleasePath $releasePath)
Assert-JarContainsCurrentFrontend -JarPath (Join-Path $releasePath "app.jar") -StaticPath $staticPath

if (Test-Path -LiteralPath $stagePath) {
    Remove-Item -LiteralPath $stagePath -Recurse -Force
}
New-Item -ItemType Directory -Path $stagePath | Out-Null

$packageRoot = Join-Path $stagePath "Solartron-Cut-Release"
New-Item -ItemType Directory -Path $packageRoot | Out-Null

Write-Step "Copying release files"
foreach ($file in $releaseFiles) {
    Copy-Item -LiteralPath $file.FullName -Destination $packageRoot -Force
}
New-Item -ItemType Directory -Path (Join-Path $packageRoot "exports") | Out-Null

if ($IncludeLogs) {
    $logsSource = Join-Path $releasePath "logs"
    if (Test-Path -LiteralPath $logsSource) {
        Copy-Item -LiteralPath $logsSource -Destination $packageRoot -Recurse -Force
    } else {
        New-Item -ItemType Directory -Path (Join-Path $packageRoot "logs") | Out-Null
    }
} else {
    New-Item -ItemType Directory -Path (Join-Path $packageRoot "logs") | Out-Null
}

if (-not (Test-Path -LiteralPath $distPath)) {
    New-Item -ItemType Directory -Path $distPath | Out-Null
}
$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$zipPath = Join-Path $distPath "Solartron-Cut-Release-$timestamp.zip"

Write-Step "Creating and verifying release zip"
Compress-Archive -Path $packageRoot -DestinationPath $zipPath -Force
Assert-ReleaseZip -ZipPath $zipPath
Remove-Item -LiteralPath $stagePath -Recurse -Force

$zipItem = Get-Item -LiteralPath $zipPath
$sizeMb = [Math]::Round($zipItem.Length / 1MB, 2)
Write-Host ""
Write-Host "Package created successfully" -ForegroundColor Green
Write-Host "  Path: $zipPath"
Write-Host "  Size: $sizeMb MB"
Write-Host "  Tests: $(if ($RunTests) { 'executed' } else { 'skipped (use -RunTests to enable)' })"
