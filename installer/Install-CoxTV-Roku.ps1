# CoxTV Roku installer
# Downloads the newest CoxTV Roku app from GitHub Releases and installs it on a Roku that has
# Developer Mode turned on. Remembers the Roku's IP address and developer password for next time
# (the password is encrypted with Windows DPAPI, so only your Windows account can read it).
#
# Release builds ship it two ways: in CoxTV-Roku-Installer-Windows.zip next to the double-click
# "Install CoxTV on Roku.exe" launcher (installer\build-windows-installer.ps1), and wrapped into
# the single-file CoxTV-Roku-Installer.bat.

$ErrorActionPreference = 'Stop'
$Repo = 'bradenrcox17/CoxTV'
$ZipUrl = "https://github.com/$Repo/releases/latest/download/CoxTV-roku.zip"
$ConfigDir = Join-Path $env:APPDATA 'CoxTV'
$ConfigFile = Join-Path $ConfigDir 'roku-installer.json'

function Say([string]$text, [string]$color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Fail([string]$text) {
    Write-Host ''
    Write-Host "  PROBLEM: $text" -ForegroundColor Red
    Write-Host ''
    exit 1
}

$Host.UI.RawUI.WindowTitle = 'CoxTV Roku Installer'
Say ''
Say '  ===========================================' 'Cyan'
Say '            CoxTV  -  Roku Installer' 'Cyan'
Say '  ===========================================' 'Cyan'
Say ''
Say '  Before you start: your Roku needs Developer Mode turned on, and this'
Say '  PC must be on the same Wi-Fi/network as the Roku. See the install page'
Say "  (https://bradenrcox17.github.io/CoxTV/) if you haven't done that yet."
Say ''

if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) {
    Fail 'curl.exe was not found. This installer needs Windows 10 (version 1803) or newer.'
}

# ---- Saved settings ---------------------------------------------------------
$saved = $null
if (Test-Path $ConfigFile) {
    try { $saved = Get-Content $ConfigFile -Raw | ConvertFrom-Json } catch { $saved = $null }
}

# ---- Roku IP address --------------------------------------------------------
$ip = ''
while ($true) {
    if ($saved -and $saved.ip) {
        $answer = Read-Host "  Roku IP address [press Enter for $($saved.ip)]"
        if (-not $answer) { $answer = $saved.ip }
    } else {
        Say '  Find the IP on your Roku: Settings > Network > About (e.g. 192.168.1.50).'
        $answer = Read-Host '  Roku IP address'
    }
    $answer = $answer.Trim() -replace '^https?://', '' -replace '[:/].*$', ''
    if ($answer -match '^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$' -and
        ($Matches[1..4] | Where-Object { [int]$_ -gt 255 }).Count -eq 0) {
        $ip = $answer
        break
    }
    Say '  That does not look like an IP address. It should be four numbers like 192.168.1.50.' 'Yellow'
}

# ---- Developer password -----------------------------------------------------
$password = $null
if ($saved -and $saved.password -and $saved.ip -eq $ip) {
    $answer = Read-Host '  Developer password [press Enter to use the saved one]' -AsSecureString
    if ($answer.Length -gt 0) { $password = $answer }
    else {
        try { $password = $saved.password | ConvertTo-SecureString } catch { $password = $null }
    }
}
if (-not $password) {
    Say '  This is the password you chose when turning on Developer Mode (user name is always rokudev).'
    $password = Read-Host '  Developer password' -AsSecureString
}
$plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($password))
if (-not $plainPassword) { Fail 'No password entered.' }

# ---- Download ----------------------------------------------------------------
Say ''
Say '  Downloading the latest CoxTV for Roku...'
$zip = Join-Path $env:TEMP 'CoxTV-roku.zip'
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -Uri $ZipUrl -OutFile $zip -UseBasicParsing
} catch {
    Fail "Could not download CoxTV from GitHub. Check this PC's internet connection.`n  ($($_.Exception.Message))"
}
Say ("  Downloaded {0:N0} KB." -f ((Get-Item $zip).Length / 1KB)) 'DarkGray'

# ---- Upload to the Roku ------------------------------------------------------
Say "  Installing on the Roku at $ip ..."
$bodyFile = Join-Path $env:TEMP 'coxtv-roku-response.html'
if (Test-Path $bodyFile) { Remove-Item $bodyFile -Force }
# The password goes to curl in a short-lived config file rather than on the command line (where
# other programs could see it). It sits in your user-only temp folder and is deleted right away.
$escaped = $plainPassword.Replace('\', '\\').Replace('"', '\"')
$curlConfig = Join-Path $env:TEMP ("coxtv-curl-{0}.cfg" -f [guid]::NewGuid().ToString('N'))
[IO.File]::WriteAllText($curlConfig, "user = `"rokudev:$escaped`"`n", (New-Object Text.UTF8Encoding $false))
$ErrorActionPreference = 'Continue'
try {
    $status = & curl.exe --silent --show-error --stderr - --max-time 180 --connect-timeout 8 `
        --digest -K $curlConfig -F 'mysubmit=Install' -F "archive=@$zip" -o $bodyFile -w '%{http_code}' "http://$ip/plugin_install"
    $curlExit = $LASTEXITCODE
} finally {
    [IO.File]::Delete($curlConfig)
    $ErrorActionPreference = 'Stop'
}
$escaped = $null
$plainPassword = $null

if ($curlExit -eq 7 -or $curlExit -eq 28) {
    Fail ("Could not reach a Roku at $ip.`n" +
        "  - Check the IP address (Roku: Settings > Network > About).`n" +
        "  - Make sure this PC and the Roku are on the same network.`n" +
        "  - Make sure Developer Mode is turned on (open http://$ip in a browser to check).")
}
if ($curlExit -ne 0) { Fail "Upload failed (curl error $curlExit): $status" }

$body = if (Test-Path $bodyFile) { Get-Content $bodyFile -Raw } else { '' }
$code = "$status".Trim()
if ($code -eq '401') {
    Fail 'The Roku rejected the developer password. Try again with the password you set when enabling Developer Mode.'
}
if ($code -ne '200') { Fail "The Roku answered with HTTP $code. Is Developer Mode turned on?" }

if ($body -match 'Install Success' -or $body -match 'Identical to previous version') {
    New-Item -ItemType Directory -Force $ConfigDir | Out-Null
    [pscustomobject]@{ ip = $ip; password = ($password | ConvertFrom-SecureString) } |
        ConvertTo-Json | Set-Content -Encoding UTF8 $ConfigFile
    Say ''
    if ($body -match 'Identical') {
        Say '  SUCCESS: CoxTV is already up to date on this Roku.' 'Green'
    } else {
        Say '  SUCCESS: CoxTV is installed! It should open on your TV now.' 'Green'
    }
    Say '  Your Roku IP and password are saved for next time.' 'DarkGray'
    Say ''
    exit 0
}

$detail = [regex]::Match($body, 'Install Failure:[^<"]*').Value
if (-not $detail) { $detail = [regex]::Match($body, "Roku\.Message\(.*?\)\.setTitle\('([^']*)'").Groups[1].Value }
if (-not $detail) { $detail = 'The Roku did not confirm the install.' }
Fail $detail
