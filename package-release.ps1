param(
    [string]$ReleaseDir = "Solartron-Cut-Release",
    [string]$DistDir = "dist",
    [switch]$SkipBuild,
    [switch]$IncludeLogs
)

$ErrorActionPreference = "Stop"

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

function Build-AndSyncFrontend {
    param(
        [string]$ProjectRoot
    )

    $frontendPath = Join-Path $ProjectRoot "src\main\resources\cutting-optima"
    $frontendDistPath = Join-Path $frontendPath "dist"
    $staticPath = Join-Path $ProjectRoot "src\main\resources\static"

    Assert-InProject $frontendPath "FrontendDir"
    Assert-InProject $frontendDistPath "FrontendDistDir"
    Assert-InProject $staticPath "StaticDir"

    if (-not (Test-Path -LiteralPath (Join-Path $frontendPath "package.json"))) {
        throw "Frontend package.json not found: $frontendPath"
    }

    Write-Host "Building frontend..."
    Push-Location $frontendPath
    try {
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

    Write-Host "Syncing frontend dist to Spring Boot static resources..."
    if (-not (Test-Path -LiteralPath $staticPath)) {
        New-Item -ItemType Directory -Path $staticPath | Out-Null
    }

    Get-ChildItem -LiteralPath $staticPath -Force | Remove-Item -Recurse -Force
    Get-ChildItem -LiteralPath $frontendDistPath -Force |
        ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $staticPath -Recurse -Force
        }
}

$projectRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$releasePath = Resolve-InProjectPath $ReleaseDir
$distPath = Resolve-InProjectPath $DistDir
$stagePath = Join-Path $distPath "release-stage"

Assert-InProject $releasePath "ReleaseDir"
Assert-InProject $distPath "DistDir"
Assert-InProject $stagePath "StageDir"

Set-Location $projectRoot

if (-not $SkipBuild) {
    Build-AndSyncFrontend -ProjectRoot $projectRoot

    Write-Host "Building Spring Boot jar..."
    & (Join-Path $projectRoot "mvnw.cmd") -DskipTests clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed with exit code $LASTEXITCODE"
    }

    $jar = Get-ChildItem -Path (Join-Path $projectRoot "target") -Filter "*.jar" |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $jar) {
        throw "No runnable jar found under target"
    }

    if (-not (Test-Path $releasePath)) {
        New-Item -ItemType Directory -Path $releasePath | Out-Null
    }

    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $releasePath "app.jar") -Force
    Write-Host "Updated app.jar from $($jar.Name)"
}

if (-not (Test-Path $releasePath)) {
    throw "Release directory not found: $releasePath"
}

$releaseFiles = Get-ChildItem -LiteralPath $releasePath -File
if (-not (Test-Path -LiteralPath (Join-Path $releasePath "app.jar"))) {
    throw "Missing release file: app.jar"
}
if (-not (Test-Path -LiteralPath (Join-Path $releasePath "application.properties"))) {
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

if (Test-Path $stagePath) {
    Remove-Item -LiteralPath $stagePath -Recurse -Force
}
New-Item -ItemType Directory -Path $stagePath | Out-Null

$packageRootName = "Solartron-Cut-Release"
$packageRoot = Join-Path $stagePath $packageRootName
New-Item -ItemType Directory -Path $packageRoot | Out-Null

Write-Host "Copying release files..."
foreach ($file in $releaseFiles) {
    Copy-Item -LiteralPath $file.FullName -Destination $packageRoot -Force
}

$exportsDir = Join-Path $packageRoot "exports"
New-Item -ItemType Directory -Path $exportsDir | Out-Null

if ($IncludeLogs) {
    $logsSource = Join-Path $releasePath "logs"
    if (Test-Path $logsSource) {
        Copy-Item -LiteralPath $logsSource -Destination $packageRoot -Recurse -Force
    } else {
        New-Item -ItemType Directory -Path (Join-Path $packageRoot "logs") | Out-Null
    }
} else {
    New-Item -ItemType Directory -Path (Join-Path $packageRoot "logs") | Out-Null
}

if (-not (Test-Path $distPath)) {
    New-Item -ItemType Directory -Path $distPath | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$zipPath = Join-Path $distPath "Solartron-Cut-Release-$timestamp.zip"
if (Test-Path $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}

Write-Host "Creating zip..."
Compress-Archive -Path $packageRoot -DestinationPath $zipPath -Force
Remove-Item -LiteralPath $stagePath -Recurse -Force

$zipItem = Get-Item -LiteralPath $zipPath
$sizeMb = [Math]::Round($zipItem.Length / 1MB, 2)

Write-Host ""
Write-Host "Package created:"
Write-Host "  $zipPath"
Write-Host "  Size: $sizeMb MB"
Write-Host ""
Write-Host "Send this zip file to the recipient. They can unzip it and run the startup .bat file."
