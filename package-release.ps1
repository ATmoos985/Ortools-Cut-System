[CmdletBinding()]
param(
    [string]$ReleaseBaseName = "Solartron-Cut-Release",
    [string]$TemplateReleaseDir = "Solartron-Cut-Release",
    [string]$OutputParent = "",
    [switch]$RunTests,
    [switch]$Zip
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$ProjectRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutputParent)) {
    $OutputParent = $ProjectRoot
}

function Resolve-ProjectPath {
    param([string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return (Join-Path $ProjectRoot $Path)
}

function Copy-ReleaseFile {
    param(
        [string]$FileName,
        [string[]]$Fallbacks,
        [string]$DestinationDir
    )

    $source = Join-Path $TemplatePath $FileName
    if (-not (Test-Path -LiteralPath $source)) {
        $source = $null
        foreach ($fallback in $Fallbacks) {
            $candidate = Resolve-ProjectPath $fallback
            if (Test-Path -LiteralPath $candidate) {
                $source = $candidate
                break
            }
        }
    }

    if ([string]::IsNullOrWhiteSpace($source)) {
        throw "Missing release file: $FileName"
    }

    Copy-Item -LiteralPath $source -Destination (Join-Path $DestinationDir $FileName) -Force
}

function Copy-FirstTemplateFileByExtension {
    param(
        [string]$Extension,
        [string]$DestinationDir,
        [switch]$AllowProjectRootFallback
    )

    $source = Get-ChildItem -LiteralPath $TemplatePath -File |
        Where-Object { $_.Extension -ieq $Extension } |
        Sort-Object Name |
        Select-Object -First 1

    if (($null -eq $source) -and $AllowProjectRootFallback) {
        $source = Get-ChildItem -LiteralPath $ProjectRoot -File |
            Where-Object { $_.Extension -ieq $Extension } |
            Sort-Object Name |
            Select-Object -First 1
    }

    if ($null -eq $source) {
        throw "Missing release file with extension: $Extension"
    }

    Copy-Item -LiteralPath $source.FullName -Destination (Join-Path $DestinationDir $source.Name) -Force
}

Push-Location $ProjectRoot
try {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $releaseName = "$ReleaseBaseName-$timestamp"
    $releasePath = Join-Path (Resolve-ProjectPath $OutputParent) $releaseName
    $TemplatePath = Resolve-ProjectPath $TemplateReleaseDir

    if (-not (Test-Path -LiteralPath $TemplatePath)) {
        throw "Template release directory not found: $TemplatePath"
    }
    if (Test-Path -LiteralPath $releasePath) {
        throw "Release directory already exists: $releasePath"
    }

    $mavenWrapper = Join-Path $ProjectRoot "mvnw.cmd"
    if (Test-Path -LiteralPath $mavenWrapper) {
        $mavenCommand = $mavenWrapper
    } else {
        $mavenCommand = "mvn"
    }

    $mavenArgs = @("-q", "package")
    if (-not $RunTests) {
        $mavenArgs = @("-q", "-DskipTests", "package")
    }

    Write-Host "Building latest jar..."
    & $mavenCommand @mavenArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Maven package failed with exit code $LASTEXITCODE"
    }

    $jar = Get-ChildItem -LiteralPath (Join-Path $ProjectRoot "target") -Filter "*.jar" |
        Where-Object { $_.Name -notlike "*.original" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "No executable jar found under target"
    }

    New-Item -ItemType Directory -Path $releasePath -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $releasePath "exports") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $releasePath "logs") -Force | Out-Null

    Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $releasePath "app.jar") -Force
    Copy-ReleaseFile -FileName "application.properties" `
        -Fallbacks @("src\main\resources\application.properties") `
        -DestinationDir $releasePath
    Copy-FirstTemplateFileByExtension -Extension ".bat" -DestinationDir $releasePath
    Copy-FirstTemplateFileByExtension -Extension ".txt" -DestinationDir $releasePath
    Copy-FirstTemplateFileByExtension -Extension ".xlsx" `
        -DestinationDir $releasePath `
        -AllowProjectRootFallback

    $sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar.FullName).Hash
    $releaseHash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $releasePath "app.jar")).Hash
    if ($sourceHash -ne $releaseHash) {
        throw "Jar hash check failed after copy"
    }

    if ($Zip) {
        $zipPath = "$releasePath.zip"
        Compress-Archive -LiteralPath $releasePath -DestinationPath $zipPath -Force
        Write-Host "Zip created: $zipPath"
    }

    Write-Host "Release created: $releasePath"
    Write-Host "Jar: $($jar.Name)"
    Write-Host "SHA256: $releaseHash"
} finally {
    Pop-Location
}
