[CmdletBinding()]
param(
    [switch] $RunTests
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string] $Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Assert-WithinDirectory {
    param(
        [Parameter(Mandatory = $true)][string] $ChildPath,
        [Parameter(Mandatory = $true)][string] $ParentPath
    )

    $resolvedChild = [System.IO.Path]::GetFullPath($ChildPath)
    $resolvedParent = [System.IO.Path]::GetFullPath($ParentPath).TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar

    if (-not $resolvedChild.StartsWith($resolvedParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Path safety check failed: $resolvedChild is not under $resolvedParent"
    }
}

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ReleaseDir = Join-Path $ProjectRoot "Solartron-Cut-Release"
$DistDir = Join-Path $ProjectRoot "dist"
$MavenWrapper = Join-Path $ProjectRoot "mvnw.cmd"
$PomFile = Join-Path $ProjectRoot "pom.xml"

Write-Host "Cutting Optimization System - Release Packager" -ForegroundColor Green
Write-Host "Project root: $ProjectRoot"

if (-not (Test-Path -LiteralPath $PomFile)) {
    throw "pom.xml was not found. Please run this script from the project root."
}

if (-not (Test-Path -LiteralPath $MavenWrapper)) {
    throw "mvnw.cmd was not found. Please make sure Maven Wrapper exists."
}

if (-not (Test-Path -LiteralPath $ReleaseDir)) {
    throw "Release directory was not found: $ReleaseDir"
}

if ($RunTests) {
    Write-Step "Build latest jar with Maven"
    $mavenArgs = @("clean", "package")
} else {
    Write-Step "Build latest jar with Maven (tests skipped for quick packaging)"
    $mavenArgs = @("clean", "package", "-DskipTests")
}

Push-Location $ProjectRoot
try {
    & $MavenWrapper @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code: $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

Write-Step "Update app.jar in the Release directory"
$targetDir = Join-Path $ProjectRoot "target"
$jar = Get-ChildItem -LiteralPath $targetDir -Filter "*.jar" |
    Where-Object { $_.Name -notlike "*.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $jar) {
    throw "No Maven-built jar was found in the target directory."
}

$appJar = Join-Path $ReleaseDir "app.jar"
Copy-Item -LiteralPath $jar.FullName -Destination $appJar -Force
Write-Host "Copied: $($jar.Name) -> Solartron-Cut-Release\app.jar"

Write-Step "Clean runtime output in the Release directory"
foreach ($runtimeDirName in @("exports", "logs")) {
    $runtimeDir = Join-Path $ReleaseDir $runtimeDirName
    if (-not (Test-Path -LiteralPath $runtimeDir)) {
        New-Item -ItemType Directory -Path $runtimeDir | Out-Null
        continue
    }

    Assert-WithinDirectory -ChildPath $runtimeDir -ParentPath $ReleaseDir
    Get-ChildItem -LiteralPath $runtimeDir -Force | Remove-Item -Recurse -Force
    Write-Host "Cleaned: Solartron-Cut-Release\$runtimeDirName"
}

Write-Step "Create distributable zip"
if (-not (Test-Path -LiteralPath $DistDir)) {
    New-Item -ItemType Directory -Path $DistDir | Out-Null
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$zipPath = Join-Path $DistDir "Solartron-Cut-Release-$timestamp.zip"
$latestZipPath = Join-Path $DistDir "Solartron-Cut-Release-latest.zip"

if (Test-Path -LiteralPath $zipPath) {
    Remove-Item -LiteralPath $zipPath -Force
}

if (Test-Path -LiteralPath $latestZipPath) {
    Remove-Item -LiteralPath $latestZipPath -Force
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$compressionLevel = [System.IO.Compression.CompressionLevel]::Optimal
$includeBaseDirectory = $true

$createFromDirectoryWithEncoding = [System.IO.Compression.ZipFile].GetMethods() |
    Where-Object {
        $_.Name -eq "CreateFromDirectory" -and
        $_.GetParameters().Count -eq 5
    } |
    Select-Object -First 1

if ($null -ne $createFromDirectoryWithEncoding) {
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $ReleaseDir,
        $zipPath,
        $compressionLevel,
        $includeBaseDirectory,
        [System.Text.Encoding]::UTF8
    )
} else {
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $ReleaseDir,
        $zipPath,
        $compressionLevel,
        $includeBaseDirectory
    )
}

Copy-Item -LiteralPath $zipPath -Destination $latestZipPath -Force

Write-Step "Package result"
Write-Host "Release directory: $ReleaseDir" -ForegroundColor Green
Write-Host "Timestamped zip: $zipPath" -ForegroundColor Green
Write-Host "Latest zip: $latestZipPath" -ForegroundColor Green
Write-Host ""
Write-Host "The zip is created directly from Solartron-Cut-Release. Verify that directory locally before sending the zip."
