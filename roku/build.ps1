# Packages the Roku channel into a sideloadable zip (manifest at the zip root).
param([string]$Out = (Join-Path $PSScriptRoot 'out\CoxTV.zip'))

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

$root = $PSScriptRoot
New-Item -ItemType Directory -Force (Split-Path $Out) | Out-Null
if (Test-Path $Out) { Remove-Item $Out -Force }

# Build the zip by hand so entry names use forward slashes (Compress-Archive on
# Windows PowerShell 5.1 writes backslashes, which Roku rejects).
$files = @(Get-Item (Join-Path $root 'manifest')) +
    @(Get-ChildItem -Recurse -File (Join-Path $root 'source'), (Join-Path $root 'components'), (Join-Path $root 'images'), (Join-Path $root 'fonts'))
$zip = [System.IO.Compression.ZipFile]::Open($Out, 'Create')
try {
    foreach ($f in $files) {
        $rel = $f.FullName.Substring($root.Length + 1).Replace('\', '/')
        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $f.FullName, $rel, 'Optimal')
    }
} finally {
    $zip.Dispose()
}
Write-Host ("Built {0} ({1} files, {2} KB)" -f $Out, $files.Count, [math]::Round((Get-Item $Out).Length / 1KB))
