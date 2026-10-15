$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$project = Join-Path $PSScriptRoot 'MicrosoftLoginHelper\MicrosoftLoginHelper.csproj'
$out = Join-Path $projectRoot 'dist\JavaCraftLoginHelper'
dotnet publish $project -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -o $out
if ($LASTEXITCODE -ne 0) { throw 'Could not publish the JavaCraft Microsoft sign-in helper.' }
$clientIdFile = Join-Path $out 'microsoft-client-id.txt'
$clientId = $env:JAVACRAFT_MS_CLIENT_ID
if ([string]::IsNullOrWhiteSpace($clientId)) {
    $launcherConfig = Join-Path $projectRoot 'dist\PS5\data\prospero-win\prefixes\minecraft-java\drive_c\JavaCraftPS5\microsoft-client-id.txt'
    if (Test-Path -LiteralPath $launcherConfig) { $clientId = (Get-Content -LiteralPath $launcherConfig -Raw).Trim() }
}
if (-not [string]::IsNullOrWhiteSpace($clientId)) {
    if ($clientId -notmatch '(?i)^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$') {
        throw 'JAVACRAFT_MS_CLIENT_ID must be a Microsoft Application (client) ID in GUID format.'
    }
    Set-Content -LiteralPath $clientIdFile -Value $clientId -Encoding ascii
}
Write-Host "PC sign-in helper ready: $(Join-Path $out 'MicrosoftLoginHelper.exe')"
