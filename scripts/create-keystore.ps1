<#
.SYNOPSIS
  One-time setup: creates the release signing key for the CoxTV Android apps.

.DESCRIPTION
  Every CoxTV release must be signed with the same key, or Android refuses to install it as an
  update. This creates that key in your user folder (never in the repo):

    %USERPROFILE%\.coxtv\coxtv-release.jks   the keystore
    %USERPROFILE%\.coxtv\signing.json        its path, alias and password; the password is
                                             encrypted with Windows DPAPI, so only your Windows
                                             account on this PC can read it

  scripts\release.ps1 reads signing.json. BACK UP BOTH FILES AND REMEMBER THE PASSWORD: if the
  key is lost, existing installs can't be updated and everyone has to uninstall and reinstall.

.PARAMETER Import
  On a new PC: path to your backed-up coxtv-release.jks. Copies it into place and saves
  signing.json for this PC instead of creating a new key.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\create-keystore.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\create-keystore.ps1 -Import E:\backup\coxtv-release.jks
#>
param([string]$Import)
$ErrorActionPreference = 'Stop'

$dir = Join-Path $env:USERPROFILE '.coxtv'
$keystore = Join-Path $dir 'coxtv-release.jks'
$config = Join-Path $dir 'signing.json'
$alias = 'coxtv'

if (-not $Import -and (Test-Path $keystore)) {
    Write-Host "A keystore already exists at $keystore - not overwriting it." -ForegroundColor Yellow
    Write-Host 'Delete it yourself first only if you are SURE no released build was signed with it.'
    exit 1
}

# keytool ships with Android Studio's bundled JDK.
$keytool = @(
    $(if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' }),
    'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $keytool) { throw 'keytool.exe not found. Install Android Studio or set JAVA_HOME.' }

if ($Import) {
    if (-not (Test-Path $Import)) { throw "Not found: $Import" }
    New-Item -ItemType Directory -Force $dir | Out-Null
    if ((Resolve-Path $Import).Path -ne $keystore) { Copy-Item $Import $keystore -Force }
    $p1 = Read-Host 'Password of the backed-up keystore' -AsSecureString
    $env:COXTV_NEW_KS_PW = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($p1))
    try {
        & $keytool -list -keystore $keystore -alias $alias -storepass:env COXTV_NEW_KS_PW | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Wrong password, or the keystore has no "coxtv" key.' }
    } finally {
        Remove-Item Env:\COXTV_NEW_KS_PW -ErrorAction SilentlyContinue
    }
    [pscustomobject]@{ keystore = $keystore; alias = $alias; password = ($p1 | ConvertFrom-SecureString) } |
        ConvertTo-Json | Set-Content -Encoding UTF8 $config
    Write-Host "Imported. Releases on this PC will be signed with $keystore" -ForegroundColor Green
    exit 0
}

Write-Host 'Choose a password for the CoxTV signing key (at least 8 characters).'
Write-Host 'Write it down somewhere safe - you will need it if you ever move to a new PC.'
while ($true) {
    $p1 = Read-Host 'Password' -AsSecureString
    $p2 = Read-Host 'Repeat password' -AsSecureString
    $plain1 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($p1))
    $plain2 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($p2))
    if ($plain1 -ne $plain2) { Write-Host 'Passwords do not match, try again.' -ForegroundColor Red; continue }
    if ($plain1.Length -lt 8) { Write-Host 'Too short, use at least 8 characters.' -ForegroundColor Red; continue }
    break
}

New-Item -ItemType Directory -Force $dir | Out-Null
# Pass the password through an environment variable so it never appears on a command line.
$env:COXTV_NEW_KS_PW = $plain1
try {
    & $keytool -genkeypair -keystore $keystore -alias $alias -keyalg RSA -keysize 4096 -validity 36500 `
        -storepass:env COXTV_NEW_KS_PW -keypass:env COXTV_NEW_KS_PW -dname 'CN=CoxTV, O=CoxTV' -storetype PKCS12
    if ($LASTEXITCODE -ne 0) { throw "keytool failed (exit $LASTEXITCODE)" }
} finally {
    Remove-Item Env:\COXTV_NEW_KS_PW -ErrorAction SilentlyContinue
}

[pscustomobject]@{
    keystore = $keystore
    alias    = $alias
    password = ($p1 | ConvertFrom-SecureString) # DPAPI: readable only by this Windows user on this PC
} | ConvertTo-Json | Set-Content -Encoding UTF8 $config

Write-Host ''
Write-Host "Created $keystore" -ForegroundColor Green
Write-Host "Saved   $config"
Write-Host ''
Write-Host 'IMPORTANT: back up coxtv-release.jks and your password (e.g. a USB stick or password manager).' -ForegroundColor Yellow
Write-Host 'On a new PC, restore it with: scripts\create-keystore.ps1 -Import <path to coxtv-release.jks>'
