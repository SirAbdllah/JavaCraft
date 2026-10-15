param(
    [string]$HostAddress = '192.168.8.33',
    [int]$Port = 2121
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$workspaceRoot = Split-Path -Parent $projectRoot
$app = Join-Path $projectRoot 'dist\PS5\data\homebrew\PPSA99995'
$installer = Join-Path $PSScriptRoot 'install_ps5.py'
$prefixTool = Join-Path $workspaceRoot 'prospero-win-upstream\tools\pw_prefix.py'
$cpuDll = Join-Path $workspaceRoot 'artifacts\prospero-win-v0.2.0\PPSA99995\win\wine\lib\wine\x86_64-windows\wowprospero.dll'
foreach ($required in @($app, $installer, $prefixTool, $cpuDll)) {
    if (-not (Test-Path $required)) { throw "Required build output is missing: $required. Run tools/build.ps1 first." }
}

$wslApp = '/mnt/c/Users/xxado/Documents/NewEra/PS5Projects/JavaCraftPS5/dist/PS5/data/homebrew/PPSA99995'
$wslInstaller = '/mnt/c/Users/xxado/Documents/NewEra/PS5Projects/JavaCraftPS5/tools/install_ps5.py'
$wslPrefixTool = '/mnt/c/Users/xxado/Documents/NewEra/PS5Projects/prospero-win-upstream/tools/pw_prefix.py'
$wslCpuDll = '/mnt/c/Users/xxado/Documents/NewEra/PS5Projects/artifacts/prospero-win-v0.2.0/PPSA99995/win/wine/lib/wine/x86_64-windows/wowprospero.dll'
$wslCommand = "python3 '$wslInstaller' --app '$wslApp' --library /home/moh/projects/javacraft-ps5/staging --pw-prefix '$wslPrefixTool' --cpu-dll '$wslCpuDll' --host '$HostAddress' --port '$Port'"
& wsl -d Ubuntu -u moh -- bash -lc $wslCommand
if ($LASTEXITCODE -ne 0) { throw "JavaCraft PS5 could not be installed on $HostAddress. The console must be reachable over FTP." }
