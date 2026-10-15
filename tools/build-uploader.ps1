param(
    [string]$ReleaseTag = (Get-Date -Format 'yyyyMMdd-HHmmss')
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$projectFile = Join-Path $PSScriptRoot 'JavaCraftUploader\JavaCraftUploader.csproj'
$safeTag = $ReleaseTag.Trim()
if ($safeTag -notmatch '^[A-Za-z0-9._-]{1,64}$') {
    throw 'ReleaseTag may contain only letters, numbers, periods, underscores, and hyphens.'
}
$releaseName = 'JavaCraftPS5-Uploader-Windows-x64-' + $safeTag
$outputRoot = Join-Path $projectRoot ('dist\' + $releaseName)
$markerPath = Join-Path $outputRoot '.javacraft-uploader-release'
$archivePath = Join-Path $projectRoot ('dist\' + $releaseName + '.zip')

$distRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'dist')) + [System.IO.Path]::DirectorySeparatorChar
$resolvedOutput = [System.IO.Path]::GetFullPath($outputRoot)
if (-not $resolvedOutput.StartsWith($distRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to publish outside dist: $resolvedOutput"
}
if (Test-Path -LiteralPath $outputRoot) { throw "Release output already exists: $outputRoot" }
if (Test-Path -LiteralPath $archivePath) { throw "Release archive already exists: $archivePath" }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
Set-Content -LiteralPath $markerPath -Value 'Generated JavaCraft PS5 Uploader release' -Encoding ascii

dotnet publish $projectFile -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true `
    -p:EnableCompressionInSingleFile=true -p:DebugType=None -p:DebugSymbols=false -o $outputRoot
if ($LASTEXITCODE -ne 0) { throw 'dotnet publish failed.' }

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'JavaCraftUploader\README.md') -Destination (Join-Path $outputRoot 'README.txt')
Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination (Join-Path $outputRoot 'LICENSE.txt')
Copy-Item -LiteralPath (Join-Path $projectRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $outputRoot 'THIRD_PARTY_NOTICES.txt')
$packageNote = @'
JavaCraft PS5 package

In the JavaCraft project, select dist\PS5\data in the uploader. The package
is kept separate from this Windows executable release. Minecraft game files,
assets, runtime files, and player data are not redistributed in this archive.
'@
Set-Content -LiteralPath (Join-Path $outputRoot 'PACKAGE-README.txt') -Value $packageNote -Encoding utf8
$archiveItems = Get-ChildItem -LiteralPath $outputRoot -Force | Select-Object -ExpandProperty FullName
Compress-Archive -LiteralPath $archiveItems -DestinationPath $archivePath -CompressionLevel Optimal
Write-Host "Uploader ready: $archivePath"
