# CoxTV

CoxTV IPTV Player for Roku / Fire OS / Android. Live channels from your own M3U playlist or
Xtream login, a TV guide (XMLTV), favorites, and live "what's on now" search, built to stay fast
with 20,000+ channel playlists.

**Install guide:** https://bradenrcox17.github.io/CoxTV/

| Device | Download (always the newest release) |
|---|---|
| Fire TV Stick / Android TV | [CoxTV.apk](https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV.apk) |
| Android phone / tablet | [CoxTV-mobile.apk](https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV-mobile.apk) |
| Roku (Windows installer) | [CoxTV-Roku-Installer.bat](https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV-Roku-Installer.bat) |
| Roku (zip, for manual sideloading) | [CoxTV-roku.zip](https://github.com/bradenrcox17/CoxTV/releases/latest/download/CoxTV-roku.zip) |

CoxTV doesn't include any channels; bring your own playlist from your TV service provider.

## Project layout

| Path | What it is |
|---|---|
| `core/` | Shared Android library: M3U/Xtream/XMLTV parsing, Room database, favorites, live search, player setup, GitHub update checker |
| `app/` | Fire TV app (`com.coxtv`), Compose for TV, D-pad first (also usable by touch) |
| `mobile/` | Phone/tablet app (`com.coxtv.mobile`), Material 3, portrait + landscape, picture-in-picture |
| `roku/` | Roku channel (BrightScript / SceneGraph), see [roku/README.md](roku/README.md) |
| `installer/` | Windows Roku installer (`Install-CoxTV-Roku.ps1` + `.bat`) |
| `docs/` | Install page (GitHub Pages) |
| `scripts/` | Release tooling |
| `version.properties` | Version shared by both Android apps (the Roku manifest is kept in sync) |

## Building

Requires Android Studio (for its JDK and the Android SDK).

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug :mobile:assembleDebug      # debug APKs
powershell -ExecutionPolicy Bypass -File roku\build.ps1      # roku\out\CoxTV.zip
```

## Releasing

Releases are built and signed on your own PC, so the signing key never leaves it.

1. **One time:** create the signing key (stored in `%USERPROFILE%\.coxtv`, never in the repo):
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\create-keystore.ps1
   ```
   Back up `coxtv-release.jks` and its password. Every release must be signed with this key, or
   installed apps can't update. On a new PC: `scripts\create-keystore.ps1 -Import <path to .jks>`.
2. **Each release** (signed in to GitHub with `gh auth login`, on `main`, no uncommitted changes):
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\release.ps1 -Notes "What changed"
   ```
   This bumps the version in both Android apps and the Roku manifest, builds and signs both APKs,
   packages the Roku zip and installer, then commits, tags and publishes a GitHub Release with
   `CoxTV.apk`, `CoxTV-mobile.apk`, `CoxTV-roku.zip` and `CoxTV-Roku-Installer.bat`.
   Use `-Version 1.2.0` to choose the version, or `-BuildOnly` to build into `dist\` without publishing.

The Fire TV and phone apps check `releases/latest` on launch (and from "Check for updates") and
offer to download and install a newer APK.
