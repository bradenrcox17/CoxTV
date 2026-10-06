<#
.SYNOPSIS
  Builds and publishes a CoxTV release (Fire TV APK, phone APK, Roku zip, Roku installer).

.DESCRIPTION
  Run on your own PC so the signing key never leaves it.
    1. Bumps the version (version.properties for both Android apps + the Roku manifest)
    2. Builds both release APKs signed with your key (from scripts\create-keystore.ps1)
    3. Packages the Roku zip and the double-click Roku installer
    4. Commits the version bump, tags it, pushes, and creates a GitHub Release

  Release assets always have the same names, so these links always point to the newest build:
    https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV.apk
    https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV-mobile.apk
    https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV-roku.zip
    https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV-Roku-Installer-Windows.zip
    https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV-Roku-Installer.bat

.PARAMETER Version
  New version, e.g. 1.2.0. Default: bump the last number (1.0.3 -> 1.0.4).
.PARAMETER Notes
  Release notes shown on GitHub and in the in-app update prompt. A "which file do I download"
  guide is added below them on GitHub.
.PARAMETER BuildOnly
  Build everything into dist\ without committing, tagging or publishing (version is still bumped
  locally; revert with: git checkout version.properties roku/manifest).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Notes "Faster guide loading"
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Version 1.1.0 -Notes "New phone app"
#>
param(
    [string]$Version,
    [string]$Notes,
    [switch]$BuildOnly
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

function Step([string]$text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Fail([string]$text) { Write-Host "`nRELEASE FAILED: $text" -ForegroundColor Red; exit 1 }
# Windows PowerShell 5.1 turns a native tool's stderr into terminating errors under "Stop";
# run tools whose stderr we want to capture/discard with "Continue".
function Quiet([scriptblock]$block) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $block } finally { $ErrorActionPreference = $saved }
}
function Run([string]$exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { Fail "$exe $($arguments -join ' ') exited with $LASTEXITCODE" }
}

# ---- Preflight ---------------------------------------------------------------
Step 'Checking tools and signing key'
if (-not $env:JAVA_HOME) {
    $jbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path $jbr) { $env:JAVA_HOME = $jbr } else { Fail 'Set JAVA_HOME (Android Studio''s jbr folder).' }
}
$signingFile = Join-Path $env:USERPROFILE '.coxtv\signing.json'
if (-not (Test-Path $signingFile)) { Fail "No signing key. Run scripts\create-keystore.ps1 first." }
$signing = Get-Content $signingFile -Raw | ConvertFrom-Json
if (-not (Test-Path $signing.keystore)) { Fail "Keystore not found: $($signing.keystore)" }
try {
    $secure = $signing.password | ConvertTo-SecureString
} catch {
    Fail 'Could not decrypt the keystore password (signing.json was made on another PC or Windows account). Re-run scripts\create-keystore.ps1 -Import <keystore>.'
}
$ksPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))

if (-not $BuildOnly) {
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Fail 'git is not installed.' }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Fail 'GitHub CLI (gh) is not installed.' }
    Quiet { gh auth status *> $null }
    if ($LASTEXITCODE -ne 0) { Fail 'Not signed in to GitHub. Run: gh auth login' }
    $dirty = git status --porcelain
    if ($dirty) { Fail "Commit or stash your changes first:`n$($dirty -join "`n")" }
    $branch = git rev-parse --abbrev-ref HEAD
    if ($branch -ne 'main') { Fail "Releases are made from main (you are on $branch)." }
}

# ---- Version bump ------------------------------------------------------------
Step 'Bumping version'
$propsFile = Join-Path $root 'version.properties'
$manifestFile = Join-Path $root 'roku\manifest'
$propsBackup = Get-Content $propsFile -Raw
$manifestBackup = Get-Content $manifestFile -Raw

$code = [int]([regex]::Match($propsBackup, 'versionCode=(\d+)').Groups[1].Value)
$name = [regex]::Match($propsBackup, 'versionName=([\d.]+)').Groups[1].Value
if (-not $Version) {
    $parts = @($name.Split('.') | ForEach-Object { [int]$_ })
    while ($parts.Count -lt 3) { $parts += 0 }
    $parts[2]++
    $Version = $parts[0..2] -join '.'
}
if ($Version -notmatch '^\d+\.\d+\.\d+$') { Fail "Version must look like 1.2.3 (got '$Version')." }
$newCode = $code + 1
$tag = "v$Version"
if (-not $BuildOnly -and (git tag --list $tag)) { Fail "Tag $tag already exists." }
Write-Host "  $name ($code) -> $Version ($newCode)"

function Restore-Versions {
    Set-Content -NoNewline -Encoding ascii $propsFile $propsBackup
    Set-Content -NoNewline -Encoding ascii $manifestFile $manifestBackup
}

$v = $Version.Split('.')
Set-Content -NoNewline -Encoding ascii $propsFile (($propsBackup -replace 'versionCode=\d+', "versionCode=$newCode") -replace 'versionName=[\d.]+', "versionName=$Version")
$manifest = $manifestBackup -replace 'major_version=\d+', "major_version=$($v[0])" `
    -replace 'minor_version=\d+', "minor_version=$($v[1])" -replace 'build_version=\d+', "build_version=$($v[2])"
Set-Content -NoNewline -Encoding ascii $manifestFile $manifest

try {
    # ---- Android ---------------------------------------------------------------
    Step 'Building signed release APKs'
    $env:COXTV_KEYSTORE = $signing.keystore
    $env:COXTV_KEY_ALIAS = $signing.alias
    $env:COXTV_KEYSTORE_PASSWORD = $ksPassword
    $env:COXTV_KEY_PASSWORD = $ksPassword
    try {
        Run (Join-Path $root 'gradlew.bat') @('--no-configuration-cache', 'clean', ':app:assembleRelease', ':mobile:assembleRelease')
    } finally {
        'COXTV_KEYSTORE', 'COXTV_KEY_ALIAS', 'COXTV_KEYSTORE_PASSWORD', 'COXTV_KEY_PASSWORD' |
            ForEach-Object { Remove-Item "Env:\$_" -ErrorAction SilentlyContinue }
    }

    $dist = Join-Path $root 'dist'
    if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
    New-Item -ItemType Directory $dist | Out-Null
    Copy-Item 'app\build\outputs\apk\release\app-release.apk' (Join-Path $dist 'CoxTV.apk')
    Copy-Item 'mobile\build\outputs\apk\release\mobile-release.apk' (Join-Path $dist 'CoxTV-mobile.apk')

    # Refuse to publish debug-signed builds: they could never be updated by a real release.
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Recurse -Filter apksigner.bat -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($apksigner) {
        foreach ($apk in 'CoxTV.apk', 'CoxTV-mobile.apk') {
            $certs = Quiet { & $apksigner.FullName verify --print-certs (Join-Path $dist $apk) 2>&1 | Out-String }
            if ($LASTEXITCODE -ne 0) { Fail "$apk failed signature verification:`n$certs" }
            if ($certs -match 'CN=Android Debug') { Fail "$apk is signed with the debug key." }
        }
        Write-Host '  Signatures verified (release key).'
    } else {
        Write-Host '  apksigner not found; skipping signature check.' -ForegroundColor Yellow
    }

    # ---- Roku --------------------------------------------------------------------
    Step 'Packaging Roku app and installer'
    & (Join-Path $root 'roku\build.ps1') -Out (Join-Path $dist 'CoxTV-roku.zip')
    # Self-contained double-click installer: a batch header that runs the PowerShell below it.
    $header = @'
<# : CoxTV Roku installer - double-click this file to run it
@echo off
powershell -NoProfile -ExecutionPolicy Bypass -Command "$f='%~f0'; Invoke-Expression ([IO.File]::ReadAllText($f))"
echo.
pause
goto :eof
#>
'@
    $script = Get-Content (Join-Path $root 'installer\Install-CoxTV-Roku.ps1') -Raw
    $bat = ($header + "`r`n" + $script) -replace "(?<!`r)`n", "`r`n"
    [IO.File]::WriteAllText((Join-Path $dist 'CoxTV-Roku-Installer.bat'), $bat, (New-Object Text.UTF8Encoding $false))
    & (Join-Path $root 'installer\build-windows-installer.ps1') -Out (Join-Path $dist 'CoxTV-Roku-Installer-Windows.zip')

    Get-ChildItem $dist | ForEach-Object { Write-Host ("  {0,-28} {1,8:N0} KB" -f $_.Name, ($_.Length / 1KB)) }
} catch {
    Restore-Versions
    throw
}

if ($BuildOnly) {
    Write-Host "`nBuilt $Version into dist\ (not published)." -ForegroundColor Green
    exit 0
}

# ---- Publish -------------------------------------------------------------------
Step "Publishing $tag"
Run git @('add', 'version.properties', 'roku/manifest')
Run git @('commit', '-m', "Release $tag")
Run git @('tag', '-a', $tag, '-m', "CoxTV $tag")
Run git @('push', 'origin', 'main')
Run git @('push', 'origin', $tag)

# Release notes first (the apps show the text above the marker in their update prompt),
# then a guide so people can tell which download is theirs.
$base = 'https://github.com/bradenrcox17/CoxTV/releases/download/' + $tag
$guide = @"
<!-- downloads -->
---
### Which file do I download?
| Device | Download |
|---|---|
| **Roku** (install from a Windows PC) | [**CoxTV-Roku-Installer-Windows.zip**]($base/CoxTV-Roku-Installer-Windows.zip) - extract it, then double-click **Install CoxTV on Roku** (see INSTRUCTIONS.txt inside) |
| **Fire TV / Android TV** | [CoxTV.apk]($base/CoxTV.apk) |
| **Android phone / tablet** | [CoxTV-mobile.apk]($base/CoxTV-mobile.apk) |

<sub>Also here: CoxTV-roku.zip (Roku app package for manual install) and CoxTV-Roku-Installer.bat (single-file version of the Roku installer). Full guide: https://bradenrcox17.github.io/CoxTV/</sub>
"@
$notesFile = Join-Path $env:TEMP "coxtv-release-notes-$tag.md"
$body = if ($Notes) { $Notes } else { "CoxTV $Version" }
[IO.File]::WriteAllText($notesFile, $body + "`n`n" + $guide, (New-Object Text.UTF8Encoding $false))

# Roku installer first so it heads the asset list.
$order = 'CoxTV-Roku-Installer-Windows.zip', 'CoxTV.apk', 'CoxTV-mobile.apk', 'CoxTV-roku.zip', 'CoxTV-Roku-Installer.bat'
$assets = $order | ForEach-Object { Join-Path $dist $_ }
$ghArgs = @('release', 'create', $tag) + $assets + @('--title', "CoxTV $Version", '--notes-file', $notesFile)
try { Run gh $ghArgs } finally { Remove-Item $notesFile -ErrorAction SilentlyContinue }

Write-Host "`nReleased CoxTV $Version" -ForegroundColor Green
Write-Host '  Fire TV : https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV.apk'
Write-Host '  Phone   : https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV-mobile.apk'
Write-Host '  Roku    : https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV-Roku-Installer-Windows.zip'
