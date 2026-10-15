param(
    [string]$HostAddress = '192.168.8.33',
    [int]$Port = 2121
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $projectRoot 'dist\PS5\data\prospero-win\prefixes\minecraft-java\drive_c\JavaCraftPS5'
$updater = Join-Path $PSScriptRoot 'update_game_files.py'
$homebrewUpdater = Join-Path $PSScriptRoot 'update_homebrew_assets.py'
$patchDeployer = Join-Path $PSScriptRoot 'deploy-patch.py'
foreach ($required in @((Join-Path $source 'launch.json'),
                        (Join-Path $source 'bootstrap\javacraft-bootstrap.jar'),
                        $updater, $homebrewUpdater, $patchDeployer)) {
    if (-not (Test-Path $required)) { throw "Required JavaCraft update file is missing: $required" }
}

& python $updater --source $source --host $HostAddress --port $Port
if ($LASTEXITCODE -ne 0) { throw "JavaCraft's game files could not be updated on $HostAddress." }

& python $homebrewUpdater --host $HostAddress --port $Port
if ($LASTEXITCODE -ne 0) { throw "JavaCraft's PS5 home-screen artwork could not be updated on $HostAddress." }

& python $patchDeployer --host $HostAddress --port $Port
if ($LASTEXITCODE -ne 0) { throw "JavaCraft's native launcher or startup files could not be updated on $HostAddress." }
