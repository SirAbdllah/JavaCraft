param(
    [string]$OutputName = 'JavaCraftPS5-Source'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$distRoot = Join-Path $projectRoot 'dist'
$outputRoot = Join-Path $distRoot $OutputName
$marker = Join-Path $outputRoot '.javacraft-source'
$zipPath = Join-Path $distRoot ($OutputName + '.zip')

if (-not (Test-Path -LiteralPath $distRoot)) {
    New-Item -ItemType Directory -Path $distRoot | Out-Null
}
$resolvedDist = [System.IO.Path]::GetFullPath($distRoot) + [System.IO.Path]::DirectorySeparatorChar
$resolvedOutput = [System.IO.Path]::GetFullPath($outputRoot)
if (-not $resolvedOutput.StartsWith($resolvedDist, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to write source export outside dist: $resolvedOutput"
}
if (Test-Path -LiteralPath $outputRoot) {
    if (-not (Test-Path -LiteralPath $marker)) {
        throw "Refusing to replace an unmarked folder: $outputRoot"
    }
    Remove-Item -LiteralPath $outputRoot -Recurse -Force
}
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
Set-Content -LiteralPath $marker -Value 'Generated JavaCraft source-only export' -Encoding ascii

foreach ($file in @('README.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md', '.gitignore')) {
    Copy-Item -LiteralPath (Join-Path $projectRoot $file) -Destination $outputRoot
}
foreach ($directory in @('src', 'tools', 'black-shield-community')) {
    $source = Join-Path $projectRoot $directory
    Get-ChildItem -LiteralPath $source -Recurse -File | Where-Object {
        $_.Extension -notin @('.jar', '.exe', '.dll', '.pkg', '.at9', '.dds', '.bgrx', '.class', '.pdb', '.p12', '.pfx', '.key', '.crt') -and
        $_.FullName -notmatch '[\\/](vendor|obj|bin|build|certs|data|\.gradle|__pycache__)[\\/]'
    } | ForEach-Object {
        $relative = $_.FullName.Substring($source.Length).TrimStart([char]92, [char]47)
        $destination = Join-Path (Join-Path $outputRoot $directory) $relative
        $parent = Split-Path -Parent $destination
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $destination -Force
    }
}

$patchFile = Join-Path $projectRoot '..\artifacts\javacraft-personalization.patch'
if (Test-Path -LiteralPath $patchFile) {
    $patchDirectory = Join-Path $outputRoot 'patches'
    New-Item -ItemType Directory -Path $patchDirectory -Force | Out-Null
    Copy-Item -LiteralPath $patchFile -Destination $patchDirectory
}
$prosperoLicense = Join-Path $projectRoot '..\prospero-win-upstream\LICENSE'
if (Test-Path -LiteralPath $prosperoLicense) {
    $licenseDirectory = Join-Path $outputRoot 'third-party-licenses'
    New-Item -ItemType Directory -Path $licenseDirectory -Force | Out-Null
    Copy-Item -LiteralPath $prosperoLicense -Destination (Join-Path $licenseDirectory 'prospero-win-LICENSE')
}

Get-ChildItem -LiteralPath $outputRoot -Recurse -File | Where-Object {
    $_.Extension -in @('.jar', '.exe', '.dll', '.pkg', '.at9', '.dds', '.bgrx')
} | ForEach-Object { throw "Binary file found in source export: $($_.FullName)" }

$archiveItems = Get-ChildItem -LiteralPath $outputRoot -Force | Select-Object -ExpandProperty FullName
Compress-Archive -LiteralPath $archiveItems -DestinationPath $zipPath -CompressionLevel Optimal
Write-Host "Source-only export ready: $zipPath"
