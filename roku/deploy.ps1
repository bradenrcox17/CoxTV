# Builds and sideloads CoxTV onto a Roku in developer mode.
#
#   .\deploy.ps1 -RokuIp 192.168.1.50                # prompts for the dev password
#   $env:ROKU_DEV_PASSWORD = '...'; .\deploy.ps1 -RokuIp 192.168.1.50 -Console
#
# -Console streams the BrightScript debug console (port 8085) after installing and
# also writes it to out\console.log.
param(
    [Parameter(Mandatory = $true)][string]$RokuIp,
    [string]$Password = $env:ROKU_DEV_PASSWORD,
    [switch]$NoBuild,
    [switch]$Console
)

$ErrorActionPreference = 'Stop'
$zip = Join-Path $PSScriptRoot 'out\CoxTV.zip'

if (-not $NoBuild) { & (Join-Path $PSScriptRoot 'build.ps1') }
if (-not (Test-Path $zip)) { throw "Package not found: $zip (run build.ps1)" }

if (-not $Password) {
    $secure = Read-Host 'Roku developer password' -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

# Go to the home screen first so a running copy of the channel exits cleanly.
try { Invoke-WebRequest -UseBasicParsing -Method Post "http://${RokuIp}:8060/keypress/Home" -TimeoutSec 5 | Out-Null } catch { }

Write-Host "Uploading to http://$RokuIp/plugin_install ..."
$response = & curl.exe --silent --show-error --max-time 120 --digest --user "rokudev:$Password" `
    -F "mysubmit=Install" -F "archive=@$zip" "http://$RokuIp/plugin_install"
if ($LASTEXITCODE -ne 0) { throw "Upload failed (curl exit code $LASTEXITCODE). Is the Roku on and in developer mode?" }

$text = ($response -join "`n")
if ($text -match 'Install Success') {
    Write-Host 'Installed. CoxTV is launching on the Roku.' -ForegroundColor Green
} elseif ($text -match 'Identical to previous version') {
    Write-Host 'Already installed (identical package). Launching it.' -ForegroundColor Yellow
    try { Invoke-WebRequest -UseBasicParsing -Method Post "http://${RokuIp}:8060/launch/dev" -TimeoutSec 5 | Out-Null } catch { }
} elseif ($text -match '401|Unauthorized') {
    throw 'The Roku rejected the developer password.'
} else {
    $plain = ($text -replace '<[^>]+>', ' ' -replace '\s+', ' ').Trim()
    throw "Install did not report success. Roku said: $($plain.Substring(0, [math]::Min(400, $plain.Length)))"
}

if ($Console) { & (Join-Path $PSScriptRoot 'console.ps1') -RokuIp $RokuIp }
