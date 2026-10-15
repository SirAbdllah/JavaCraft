param(
    [string]$Source = (Join-Path (Split-Path -Parent $PSScriptRoot) 'artwork\official-wallpapers-extracted\wallpaper_minecraft_pc_bundle_1920x1080.png'),
    [string]$Output = (Join-Path (Split-Path -Parent $PSScriptRoot) 'artwork\javacraft-background.bgrx')
)

$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Source)) { throw "Official Minecraft wallpaper not found: $Source" }
Add-Type -AssemblyName System.Drawing
$width = 1920
$height = 1080
$sourceBitmap = [System.Drawing.Bitmap]::new($Source)
$bitmap = [System.Drawing.Bitmap]::new($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.DrawImage($sourceBitmap, 0, 0, $width, $height)
    $rectangle = [System.Drawing.Rectangle]::new(0, 0, $width, $height)
    $data = $bitmap.LockBits($rectangle, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $pixels = [byte[]]::new($data.Stride * $height)
        [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $pixels, 0, $pixels.Length)
        for ($i = 3; $i -lt $pixels.Length; $i += 4) { $pixels[$i] = 255 }
        [System.IO.File]::WriteAllBytes($Output, $pixels)
    } finally {
        $bitmap.UnlockBits($data)
    }
} finally {
    $graphics.Dispose()
    $bitmap.Dispose()
    $sourceBitmap.Dispose()
}
$expected = $width * $height * 4
if ((Get-Item -LiteralPath $Output).Length -ne $expected) { throw 'The native wallpaper conversion has an unexpected size.' }
Write-Host "Prepared official Minecraft wallpaper for the native PS5 menu: $Output"
