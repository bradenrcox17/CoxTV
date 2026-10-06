<#
.SYNOPSIS
  Draws the Android/Fire TV launcher icons and the Fire TV banner in the Roku app's style
  (navy background, blue play tile, two-tone "CoxTV" wordmark). Re-run after design changes.
#>
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot -Parent

$Navy      = [System.Drawing.Color]::FromArgb(255, 7, 16, 36)
$NavyGlow  = [System.Drawing.Color]::FromArgb(255, 30, 78, 160)
$TileLight = [System.Drawing.Color]::FromArgb(255, 92, 170, 255)
$TileDark  = [System.Drawing.Color]::FromArgb(255, 40, 104, 226)
$TvBlue    = [System.Drawing.Color]::FromArgb(255, 77, 166, 255)
$Muted     = [System.Drawing.Color]::FromArgb(255, 150, 168, 196)

function New-Canvas([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'
    $g.PixelOffsetMode = 'HighQuality'; $g.TextRenderingHint = 'AntiAliasGridFit'; $g.CompositingQuality = 'HighQuality'
    return $bmp, $g
}

function RoundedRect([float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $p.AddArc($x, $y, $d, $d, 180, 90); $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90); $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure(); return $p
}

function Draw-NavyBackground($g, [int]$w, [int]$h) {
    $g.Clear($Navy)
    # Soft blue glow from the top centre, like the Roku artwork
    $glow = New-Object System.Drawing.Drawing2D.GraphicsPath
    # Ellipse much larger than the canvas so its edge never shows; centre sits near the top.
    $gw = $w * 2.0; $gh = $h * 2.6
    $glow.AddEllipse(($w - $gw) / 2, -$gh / 2 - $h * 0.15, $gw, $gh)
    $brush = New-Object System.Drawing.Drawing2D.PathGradientBrush $glow
    $brush.CenterColor = $NavyGlow
    $brush.SurroundColors = @([System.Drawing.Color]::FromArgb(0, $Navy))
    $g.FillPath($brush, $glow)
    $brush.Dispose(); $glow.Dispose()
}

function Draw-Triangle($g, [float]$cx, [float]$cy, [float]$size) {
    # Play triangle, nudged right so it looks optically centred
    $h = $size; $w = $size * 0.88
    $x0 = $cx - $w * 0.40
    $pts = [System.Drawing.PointF[]]@(
        (New-Object System.Drawing.PointF $x0, ($cy - $h / 2)),
        (New-Object System.Drawing.PointF ($x0 + $w), $cy),
        (New-Object System.Drawing.PointF $x0, ($cy + $h / 2)))
    $g.FillPolygon([System.Drawing.Brushes]::White, $pts)
}

function Draw-Tile($g, [float]$x, [float]$y, [float]$s, [bool]$border = $true) {
    $r = $s * 0.20
    $path = RoundedRect $x $y $s $s $r
    $rect = New-Object System.Drawing.RectangleF $x, $y, $s, $s
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $TileLight, $TileDark, 45
    $g.FillPath($brush, $path)
    if ($border) {
        $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(120, 190, 220, 255)), ([Math]::Max(1, $s * 0.012))
        $g.DrawPath($pen, $path); $pen.Dispose()
    }
    Draw-Triangle $g ($x + $s / 2) ($y + $s / 2) ($s * 0.42)
    $brush.Dispose(); $path.Dispose()
}

function Draw-Wordmark($g, [float]$x, [float]$cy, [float]$px) {
    $font = New-Object System.Drawing.Font 'Segoe UI', $px, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $fmt = [System.Drawing.StringFormat]::GenericTypographic
    $cox = $g.MeasureString('Cox', $font, 10000, $fmt)
    $top = $cy - $cox.Height / 2
    $g.DrawString('Cox', $font, [System.Drawing.Brushes]::White, $x, $top, $fmt)
    $tvBrush = New-Object System.Drawing.SolidBrush $TvBlue
    $g.DrawString('TV', $font, $tvBrush, $x + $cox.Width + $px * 0.02, $top, $fmt)
    $tv = $g.MeasureString('TV', $font, 10000, $fmt)
    $width = $cox.Width + $px * 0.02 + $tv.Width
    $font.Dispose(); $tvBrush.Dispose()
    return $width
}

function Save($bmp, [string]$rel) {
    $path = Join-Path $root $rel
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    Write-Host ("  {0} ({1}x{2})" -f $rel, $bmp.Width, $bmp.Height)
}

$densities = [ordered]@{ mdpi = 1.0; hdpi = 1.5; xhdpi = 2.0; xxhdpi = 3.0; xxxhdpi = 4.0 }

foreach ($module in 'app', 'mobile') {
    Write-Host "== $module launcher icons"
    foreach ($d in $densities.Keys) {
        $k = $densities[$d]
        # Legacy icon (Android 7 / Fire OS 6): the tile itself, 48dp with a small margin
        $n = [int](48 * $k)
        $bmp, $g = New-Canvas $n $n
        $g.Clear([System.Drawing.Color]::Transparent)
        $m = $n * 0.06
        Draw-Tile $g $m $m ($n - 2 * $m) $false
        Save $bmp "$module/src/main/res/mipmap-$d/ic_launcher.png"; $g.Dispose(); $bmp.Dispose()

        # Adaptive icon layers (Android 8+): 108dp; the launcher applies its own mask shape
        $a = [int](108 * $k)
        $bmp, $g = New-Canvas $a $a
        $rect = New-Object System.Drawing.RectangleF 0, 0, $a, $a
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $TileLight, $TileDark, 45
        $g.FillRectangle($brush, $rect); $brush.Dispose()
        Save $bmp "$module/src/main/res/mipmap-$d/ic_launcher_background.png"; $g.Dispose(); $bmp.Dispose()

        $bmp, $g = New-Canvas $a $a
        $g.Clear([System.Drawing.Color]::Transparent)
        Draw-Triangle $g ($a / 2) ($a / 2) ($a * 0.30)   # stays inside the 66dp safe zone
        Save $bmp "$module/src/main/res/mipmap-$d/ic_launcher_foreground.png"; $g.Dispose(); $bmp.Dispose()
    }
    $xml = @'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'@
    $dir = Join-Path $root "$module/src/main/res/mipmap-anydpi-v26"
    New-Item -ItemType Directory -Force $dir | Out-Null
    [IO.File]::WriteAllText((Join-Path $dir 'ic_launcher.xml'), $xml, (New-Object Text.UTF8Encoding $false))
    Write-Host "  $module/src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
}

Write-Host "== Fire TV banner"
foreach ($pair in @(@('xhdpi', 1.0), @('xxhdpi', 1.5), @('xxxhdpi', 2.0))) {
    $k = $pair[1]; $w = [int](320 * $k); $h = [int](180 * $k)
    $bmp, $g = New-Canvas $w $h
    Draw-NavyBackground $g $w $h
    $tile = 70 * $k
    $font = 62 * $k
    # Measure the wordmark on a scratch canvas to centre tile + text together
    $sb, $sg = New-Canvas 10 10
    $textW = Draw-Wordmark $sg 0 5 $font
    $sg.Dispose(); $sb.Dispose()
    $gap = 18 * $k
    $x = ($w - ($tile + $gap + $textW)) / 2
    $cy = $h * 0.47
    Draw-Tile $g $x ($cy - $tile / 2) $tile $true
    [void](Draw-Wordmark $g ($x + $tile + $gap) ($cy + 2 * $k) $font)
    # "LIVE TV" tagline, letter-spaced
    $tf = New-Object System.Drawing.Font 'Segoe UI', (13 * $k), ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
    $mb = New-Object System.Drawing.SolidBrush $Muted
    $tag = 'L I V E   T V'
    $tw = $g.MeasureString($tag, $tf).Width
    $g.DrawString($tag, $tf, $mb, ($w - $tw) / 2, $h * 0.76)
    $tf.Dispose(); $mb.Dispose()
    Save $bmp "app/src/main/res/drawable-$($pair[0])/banner.png"; $g.Dispose(); $bmp.Dispose()
}
Write-Host "Done."
