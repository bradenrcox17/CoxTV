# CoxTV for Roku

BrightScript/SceneGraph port of the CoxTV Fire TV app (M3U + XMLTV).

## Install on a Roku

1. Enable developer mode on the Roku: press **Home ×3, Up ×2, Right, Left, Right, Left, Right**,
   accept the license, and set a developer password. Note the IP it shows.
2. From this folder, build and sideload:

   ```powershell
   .\deploy.ps1 -RokuIp 192.168.1.50            # prompts for the dev password
   .\deploy.ps1 -RokuIp 192.168.1.50 -Console   # ...then streams the debug console (port 8085)
   ```

   Or set `$env:ROKU_DEV_PASSWORD` first to skip the prompt. `.\build.ps1` alone produces
   `out\CoxTV.zip`, which you can also upload by hand at `http://<roku-ip>`.
3. `.\console.ps1 -RokuIp <ip>` streams the debug console any time (also saved to `out\console.log`).

## Remote controls

| Where | Key | Action |
|---|---|---|
| Home | Left / Right | Move between categories and channels |
| Home | * | Toggle favorite |
| Player | Up / Down | Channel up / down |
| Player | OK | Show / hide info |
| Player | Left | Mini guide (OK tunes, * favorites, Back closes) |
| Player | Replay | Previous channel |
| Guide | Arrows | Move; Left/Right walk programmes and scroll time |
| Guide | Rewind / Fast-fwd | Page up / down |
| Guide | Replay | Jump back to now |
| Search | Right past the last keyboard column | Go to results |

## Notes

- Playlist and guide are downloaded and parsed in a background Task (`DataService`), read in
  256 KB chunks. Movie and series entries (`/movie/`, `/series/` URLs in provider `m3u_plus`
  playlists) are skipped, so only live TV is loaded.
- Guide memory is bounded: only programmes for playlist channels from 1 hour ago to 9 hours ahead
  are kept (max 80,000, descriptions trimmed to 160 characters). The guide is cached for 4 hours and
  refreshed every 4 hours while the app is open. Channels are usable while the guide loads.
- After the first load, the parsed channel list and guide are saved as JSON in `cachefs:` and
  restored on later launches in about a second; the playlist refreshes in the background when the
  saved copy is over 12 hours old, and the guide every 4 hours.
- Channel lists load a page at a time (first 200 rows immediately). "All Channels" shows the first
  5,000 channels; every channel is reachable through its category.
- Channel logos are only shown in the player (not in lists, guide or search) to keep big lists fast.
- Search matches shows airing now and channel names, ignoring case, spaces and punctuation
  ("whitesox" finds "White Sox"). It scans with native string search, so specific queries take a
  few milliseconds even across 20,000 channels.
- Measured in a simulator with a 20,224-channel provider playlist (87,863 entries including VOD) and
  an 83 MB guide: first launch about 25-35 s for channels and a few minutes for the guide (in the
  background); repeat launches about 0.3 s for channels and 1.2 s for the guide. Real devices differ.
- Playback prefers HLS: `output=ts` / `.ts` URLs, and extension-less Xtream URLs
  (`http://host/user/pass/<id>`), are tried as `.m3u8` first, then as the original URL. Roku officially supports HLS, DASH, MP4 and MKV but **not raw MPEG-TS**, so
  extension-less TS streams (such as Threadfin's `/stream/<id>`) may not play. If that happens,
  you'll need a source that serves HLS (`.m3u8`), e.g. a provider playlist with HLS URLs.
- Gzipped XMLTV files (`.xml.gz`) are not supported, except when the server uses HTTP gzip encoding.
