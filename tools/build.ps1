param()

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$prefixStager = Join-Path $PSScriptRoot 'stage-wine-prefix.py'
$upstreamTools = (Resolve-Path (Join-Path $projectRoot '..\prospero-win-upstream\tools')).Path
$wslPrefixSource = '/home/moh/projects/javacraft-ps5/staging/prefixes/minecraft-java'
$wslStager = (& wsl.exe -d Ubuntu -u moh -- wslpath -a $prefixStager).Trim()
$wslUpstreamTools = (& wsl.exe -d Ubuntu -u moh -- wslpath -a $upstreamTools).Trim()
& wsl.exe -d Ubuntu -u moh -- python3 $wslStager --source $wslPrefixSource --upstream-tools $wslUpstreamTools --check
if ($LASTEXITCODE -ne 0) { throw 'A complete initialized Wine prefix is required. Restore it before rebuilding JavaCraft.' }
$wallpaperConverter = Join-Path $PSScriptRoot 'convert-native-background.ps1'
if (Test-Path -LiteralPath (Join-Path $projectRoot 'artwork\minecraft-title-background.png')) {
    & $wallpaperConverter
    if (-not $?) { throw 'The native JavaCraft background could not be prepared.' }
}
$minecraft = Join-Path $env:APPDATA '.minecraft'
$javaCompiler = (Get-Command javac -ErrorAction Stop).Source
$javaHome = Split-Path (Split-Path $javaCompiler -Parent) -Parent
$versionJson = Join-Path $minecraft 'versions\26.3\26.3.json'
if (-not (Test-Path $versionJson)) { throw "Minecraft Java 26.3 is not installed under $minecraft" }
if (-not (Test-Path (Join-Path $javaHome 'bin\javaw.exe'))) { throw "JDK 25 javaw.exe was not found under $javaHome" }

$stagedGame = Join-Path $projectRoot 'stage\game-files'
& python (Join-Path $PSScriptRoot 'stage_game.py') --minecraft $minecraft --java-runtime $javaHome --out $stagedGame
if ($LASTEXITCODE -ne 0) { throw 'Minecraft staging failed.' }

# Build the optional BS6 client mod against each staged game release and add it
# to the launch/modpack manifests before copying the game into the PS5 package.
$blackShieldBuilder = Join-Path $projectRoot 'black-shield-community\fabric-mod\build_fabric_mod.py'
if (-not (Test-Path -LiteralPath $blackShieldBuilder)) {
    throw 'The Black Shield Community Fabric source/build script is missing.'
}
$jarTool = (Get-Command jar -ErrorAction Stop).Source
& python $blackShieldBuilder --project (Join-Path $projectRoot 'black-shield-community\fabric-mod') `
    --game $stagedGame --javac $javaCompiler --jar $jarTool
if ($LASTEXITCODE -ne 0) { throw 'The Black Shield Community Fabric mod could not be built.' }

$packageRoot = Join-Path $projectRoot 'dist\PS5'
if (Test-Path $packageRoot) {
    if (-not (Test-Path (Join-Path $packageRoot '.javacraft-package'))) {
        throw "Refusing to replace an unmarked folder: $packageRoot"
    }
}
$existingNativeApp = Join-Path $packageRoot 'data\homebrew\PPSA99995'
if (-not (Test-Path (Join-Path $existingNativeApp 'eboot.bin'))) {
    throw "The current built PS5 app is missing: $existingNativeApp. Keep a copy of the existing package before rebuilding it."
}
$snapshotName = 'native-app-snapshot-' + [guid]::NewGuid().ToString('N')
$nativeSnapshot = Join-Path (Join-Path $projectRoot 'build') $snapshotName
$null = New-Item -ItemType Directory -Path $nativeSnapshot -Force
Get-ChildItem -LiteralPath $existingNativeApp -Force |
    Copy-Item -Destination $nativeSnapshot -Recurse -Force
if (Test-Path $packageRoot) {
    Remove-Item -LiteralPath $packageRoot -Recurse -Force
}
$null = New-Item -ItemType Directory -Path $packageRoot -Force
Set-Content -Path (Join-Path $packageRoot '.javacraft-package') -Value 'JavaCraft PS5 generated package' -Encoding ascii

$homebrew = Join-Path $packageRoot 'data\homebrew'
$null = New-Item -ItemType Directory -Path $homebrew -Force
$appRoot = Join-Path $homebrew 'PPSA99995'
New-Item -ItemType Directory -Path $appRoot -Force | Out-Null
Get-ChildItem -LiteralPath $nativeSnapshot -Force |
    Copy-Item -Destination $appRoot -Recurse -Force
$personalizedEboot = Join-Path $projectRoot '..\prospero-win-upstream\dist\PPSA99995-javacraft-instances\eboot.bin'
if (-not (Test-Path -LiteralPath $personalizedEboot)) {
    $personalizedEboot = Join-Path $projectRoot '..\prospero-win-upstream\dist\PPSA99995-javacraft\eboot.bin'
}
if (Test-Path -LiteralPath $personalizedEboot) {
    Copy-Item -LiteralPath $personalizedEboot -Destination (Join-Path $appRoot 'eboot.bin') -Force
}
$sceSys = Join-Path $appRoot 'sce_sys'
$iconBuilder = Join-Path $PSScriptRoot 'make_icon.py'
if (Test-Path -LiteralPath (Join-Path $projectRoot 'artwork\icon0.png')) {
    & python $iconBuilder
    if ($LASTEXITCODE -ne 0) { throw 'The JavaCraft tile icon could not be built.' }
}
$artworkFiles = @(
    @{ Source = (Join-Path $projectRoot 'artwork\icon0.png'); Name = 'icon0.png' },
    @{ Source = (Join-Path $projectRoot 'artwork\pic0.dds'); Name = 'pic0.dds' },
    @{ Source = (Join-Path $projectRoot 'artwork\pic1.dds'); Name = 'pic1.dds' },
    @{ Source = (Join-Path $projectRoot 'artwork\snd0.at9'); Name = 'snd0.at9' },
    @{ Source = (Join-Path $projectRoot 'artwork\param.json'); Name = 'param.json' },
    @{ Source = (Join-Path $projectRoot 'artwork\javacraft-background.bgrx'); Name = 'javacraft-background.bgrx' }
)
foreach ($asset in $artworkFiles) {
    if (Test-Path -LiteralPath $asset.Source) {
        Copy-Item -LiteralPath $asset.Source -Destination (Join-Path $sceSys $asset.Name) -Force
    }
}
$resolvedBuildRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build')) + [System.IO.Path]::DirectorySeparatorChar
$resolvedSnapshot = [System.IO.Path]::GetFullPath($nativeSnapshot)
if (-not $resolvedSnapshot.StartsWith($resolvedBuildRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean a native snapshot outside the JavaCraft build folder: $resolvedSnapshot"
}
Remove-Item -LiteralPath $nativeSnapshot -Recurse -Force

$consoleLibrary = Join-Path $packageRoot 'data\prospero-win'
$prefixDrive = Join-Path $consoleLibrary 'prefixes\minecraft-java\drive_c\JavaCraftPS5'
$null = New-Item -ItemType Directory -Path $prefixDrive -Force
Get-ChildItem -LiteralPath $stagedGame -Force | Where-Object Name -ne '.javacraft-managed' |
    Copy-Item -Destination $prefixDrive -Recurse -Force
$microsoftClientId = $env:JAVACRAFT_MS_CLIENT_ID
if ([string]::IsNullOrWhiteSpace($microsoftClientId)) {
    $microsoftClientId = '8c13cde3-9c87-49bd-b547-cd73e752261b'
}
if ($microsoftClientId -notmatch '(?i)^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {
    throw 'JAVACRAFT_MS_CLIENT_ID must be a Microsoft Application (client) ID in GUID format.'
}
Set-Content -Path (Join-Path $prefixDrive 'microsoft-client-id.txt') `
    -Value $microsoftClientId.Trim() -Encoding ascii
$gsonRelative = (Get-Content (Join-Path $prefixDrive 'launch.json') -Raw | ConvertFrom-Json).gsonJar
$gsonPath = Join-Path $prefixDrive ('libraries\' + ($gsonRelative -replace '/', '\'))
$classes = Join-Path $projectRoot 'build\classes'
if (Test-Path $classes) { Remove-Item -LiteralPath $classes -Recurse -Force }
$null = New-Item -ItemType Directory -Path $classes -Force
& javac --release 17 -encoding UTF-8 -cp $gsonPath -d $classes `
    (Join-Path $projectRoot 'src\com\xxado\javacraft\JavaCraftBootstrap.java')
if ($LASTEXITCODE -ne 0) { throw 'The sign-in bootstrap did not compile.' }
$bootstrap = Join-Path $prefixDrive 'bootstrap'
$null = New-Item -ItemType Directory -Path $bootstrap -Force
& jar --create --file (Join-Path $bootstrap 'javacraft-bootstrap.jar') `
    --main-class com.xxado.javacraft.JavaCraftBootstrap -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'The sign-in bootstrap JAR could not be created.' }
Copy-Item -LiteralPath $gsonPath -Destination $bootstrap -Force
$gsonBootstrapName = Split-Path -Leaf $gsonPath

# Build the Microsoft device-code sign-in as an in-game client mod. Microsoft
# credentials remain on Microsoft's own verification page; this mod only
# switches Minecraft's in-memory account after Xbox/Minecraft checks succeed.
$accountModBuilder = Join-Path $PSScriptRoot 'build_account_mod.py'
$jarTool = (Get-Command jar -ErrorAction Stop).Source
& python $accountModBuilder --project $projectRoot --game $prefixDrive `
    --javac $javaCompiler --jar $jarTool
if ($LASTEXITCODE -ne 0) { throw 'The in-game Microsoft account mod could not be built.' }

$runtimeComponent = (Get-Content (Join-Path $prefixDrive 'launch.json') -Raw | ConvertFrom-Json).runtimeComponent
$javaw = "C:\JavaCraftPS5\runtime\$runtimeComponent\bin\javaw.exe"
$arguments = "-Xms1G -Xmx4G -XX:StackShadowPages=32 --enable-native-access=ALL-UNNAMED --add-exports java.base/jdk.internal.misc=ALL-UNNAMED -Djavacraft.home=C:\JavaCraftPS5 -cp C:\JavaCraftPS5\bootstrap\javacraft-bootstrap.jar;C:\JavaCraftPS5\bootstrap\$gsonBootstrapName com.xxado.javacraft.JavaCraftBootstrap"
if ($arguments.Length -gt 512) { throw "Java arguments exceed Prospero Win's 512-character profile limit ($($arguments.Length))." }
$profile = @"
[application]
id = minecraft-java
name = Minecraft Java 26.3
executable = $javaw
working_directory = C:\JavaCraftPS5
arguments = $arguments
prefix = minecraft-java
runtime = wine-wow64
architecture = pe64
graphics = opengl

[display]
desktop = 1920x1080
scaling = fit
show_fps = false

[input]
mode = xinput
"@
$profiles = Join-Path $consoleLibrary 'profiles'
$null = New-Item -ItemType Directory -Path $profiles -Force
Set-Content -Path (Join-Path $profiles 'minecraft-java.profile') -Value $profile -Encoding ascii
Set-Content -Path (Join-Path $profiles 'profiles.lst') -Value 'minecraft-java.profile' -Encoding ascii

$wslPackagePrefix = (& wsl.exe -d Ubuntu -u moh -- wslpath -a (Join-Path $consoleLibrary 'prefixes\minecraft-java')).Trim()
$wslCpuDll = (& wsl.exe -d Ubuntu -u moh -- wslpath -a (Join-Path $appRoot 'win\wine\lib\wine\x86_64-windows\wowprospero.dll')).Trim()
& wsl.exe -d Ubuntu -u moh -- python3 $wslStager --source $wslPrefixSource --target $wslPackagePrefix `
    --upstream-tools $wslUpstreamTools --cpu-dll $wslCpuDll
if ($LASTEXITCODE -ne 0) { throw 'The initialized Wine prefix could not be staged into the JavaCraft package.' }

Write-Host "JavaCraft PS5 package ready: $packageRoot"
$loaderVersion = (Get-Content (Join-Path $prefixDrive 'launch.json') -Raw | ConvertFrom-Json).fabricLoader
Write-Host "Minecraft Java 26.3 with $loaderVersion and Java runtime $runtimeComponent; native settings are read from launcher-settings.json."
Write-Host "Update the installed game with tools/update-game.ps1 (FTP 192.168.8.33:2121)."
$loginHelperBuilder = Join-Path $PSScriptRoot 'build-login-helper.ps1'
& $loginHelperBuilder
if ($LASTEXITCODE -ne 0) { throw 'The JavaCraft PC sign-in helper could not be built.' }
