<#
.SYNOPSIS
  Builds CoxTV-Roku-Installer-Windows.zip:
    CoxTV Roku Installer\
      Install CoxTV on Roku.exe     <- double-click
      INSTRUCTIONS.txt
      files\
        Install-CoxTV-Roku.ps1      <- the installer the .exe runs
        CoxTV.ico
  The .exe is compiled from launcher\CoxTVRokuInstaller.cs with the C# compiler built into Windows.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File installer\build-windows-installer.ps1 -Out dist\CoxTV-Roku-Installer-Windows.zip
#>
param([string]$Out = (Join-Path (Split-Path $PSScriptRoot -Parent) 'dist\CoxTV-Roku-Installer-Windows.zip'))
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (-not (Test-Path $csc)) { throw 'The .NET Framework 4 C# compiler (csc.exe) was not found.' }

$work = Join-Path ([IO.Path]::GetTempPath()) ("coxtv-wininst-" + [guid]::NewGuid().ToString('N'))
$top = Join-Path $work 'CoxTV Roku Installer'
$files = Join-Path $top 'files'
New-Item -ItemType Directory -Force $files | Out-Null
try {
    # ---- Icon: the blue play tile from the app icons, as a multi-size .ico (PNG entries) ----
    function TilePng([int]$n) {
        $bmp = New-Object System.Drawing.Bitmap $n, $n, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.SmoothingMode = 'AntiAlias'; $g.PixelOffsetMode = 'HighQuality'
        $g.Clear([System.Drawing.Color]::Transparent)
        $m = [Math]::Max(1, $n * 0.04); $s = $n - 2 * $m; $r = $s * 0.20; $d = $r * 2
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        $p.AddArc($m, $m, $d, $d, 180, 90); $p.AddArc($m + $s - $d, $m, $d, $d, 270, 90)
        $p.AddArc($m + $s - $d, $m + $s - $d, $d, $d, 0, 90); $p.AddArc($m, $m + $s - $d, $d, $d, 90, 90)
        $p.CloseFigure()
        $rect = New-Object System.Drawing.RectangleF $m, $m, $s, $s
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect,
            ([System.Drawing.Color]::FromArgb(255, 92, 170, 255)), ([System.Drawing.Color]::FromArgb(255, 40, 104, 226)), 45
        $g.FillPath($brush, $p)
        $h = $s * 0.42; $w = $h * 0.88; $cx = $n / 2; $cy = $n / 2; $x0 = $cx - $w * 0.40
        $pts = [System.Drawing.PointF[]]@(
            (New-Object System.Drawing.PointF $x0, ($cy - $h / 2)),
            (New-Object System.Drawing.PointF ($x0 + $w), $cy),
            (New-Object System.Drawing.PointF $x0, ($cy + $h / 2)))
        $g.FillPolygon([System.Drawing.Brushes]::White, $pts)
        $ms = New-Object IO.MemoryStream
        $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
        $brush.Dispose(); $p.Dispose(); $g.Dispose(); $bmp.Dispose()
        return , $ms.ToArray()
    }
    $sizes = 16, 24, 32, 48, 64, 128, 256
    $pngs = @($sizes | ForEach-Object { , (TilePng $_) })
    $ico = Join-Path $files 'CoxTV.ico'
    $fs = [IO.File]::Create($ico); $bw = New-Object IO.BinaryWriter $fs
    $bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$sizes.Count)
    $offset = 6 + 16 * $sizes.Count
    for ($i = 0; $i -lt $sizes.Count; $i++) {
        $dim = if ($sizes[$i] -ge 256) { 0 } else { $sizes[$i] }
        $bw.Write([byte]$dim); $bw.Write([byte]$dim); $bw.Write([byte]0); $bw.Write([byte]0)
        $bw.Write([UInt16]1); $bw.Write([UInt16]32)
        $bw.Write([UInt32]$pngs[$i].Length); $bw.Write([UInt32]$offset)
        $offset += $pngs[$i].Length
    }
    foreach ($png in $pngs) { $bw.Write($png) }
    $bw.Close()

    # ---- Launcher .exe ----
    $exe = Join-Path $top 'Install CoxTV on Roku.exe'
    $src = Join-Path $PSScriptRoot 'launcher\CoxTVRokuInstaller.cs'
    $output = & $csc /nologo /target:exe /platform:anycpu /optimize+ "/win32icon:$ico" "/out:$exe" $src
    if ($LASTEXITCODE -ne 0) { throw "csc failed:`n$($output -join "`n")" }

    # ---- Instructions + installer script (CRLF so Notepad/PowerShell are happy) ----
    $utf8 = New-Object Text.UTF8Encoding $false
    foreach ($pair in @(@('INSTRUCTIONS.txt', $top), @('Install-CoxTV-Roku.ps1', $files))) {
        $text = [IO.File]::ReadAllText((Join-Path $PSScriptRoot $pair[0])) -replace "`r`n", "`n" -replace "`n", "`r`n"
        [IO.File]::WriteAllText((Join-Path $pair[1] $pair[0]), $text, $utf8)
    }

    # ---- Zip (top-level folder included, so extracting gives one tidy folder) ----
    $Out = [IO.Path]::GetFullPath($Out)
    New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
    if (Test-Path $Out) { Remove-Item $Out -Force }
    [IO.Compression.ZipFile]::CreateFromDirectory($work, $Out, [IO.Compression.CompressionLevel]::Optimal, $false)
    Write-Host "  Built $Out"
} finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
