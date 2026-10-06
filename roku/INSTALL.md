# Installing CoxTV on a Roku TV

You need: the Roku TV and its remote, a Windows PC on the same home network, and this
project folder (`C:\Claude\IPTVPlayer\roku`). The package is `out\CoxTV.zip`.

## 1. Turn on Developer Mode (one time per TV)

1. On the Roku remote press, in order: **Home Home Home, Up Up, Right, Left, Right, Left, Right**.
   (Press them steadily; if nothing happens, press Home and try again.)
2. The **Developer Settings** screen opens and shows the TV's IP address, e.g.
   `http://192.168.1.50:80`. **Write the IP down.**
3. Select **Enable installer and restart**.
4. Accept the **SDK License Agreement**.
5. Create a **web server password** (any password you'll remember - this is your *dev password*;
   the username is always `rokudev`).
6. The TV restarts. Developer Mode stays on until you turn it off.

If you ever need the IP again: **Settings > Network > About** (IP address).

## 2. Allow network control (recommended)

**Settings > System > Advanced system settings > Control by mobile apps > Network access**:
set to **Default** (or Permissive). This lets the install script press Home before installing,
and lets the free Roku mobile app type on the TV (very handy for long URLs in step 4).

## 3. Install the app

### Option A - install script (easiest)

1. On the PC, open **PowerShell** (Start menu, type "PowerShell").
2. Run (replace the IP with your TV's):

   ```powershell
   cd C:\Claude\IPTVPlayer\roku
   powershell -ExecutionPolicy Bypass -File .\deploy.ps1 -RokuIp 192.168.1.50
   ```

3. Enter the dev password when asked. You should see
   `Installed. CoxTV is launching on the Roku.`

To also watch the app's debug log while testing, add `-Console` (stop with Ctrl+C); the log is
saved to `out\console.log`.

### Option B - web browser

1. On the PC, open a browser and go to `http://<TV IP>` (e.g. `http://192.168.1.50`).
2. Sign in with user **rokudev** and your dev password.
3. On **Development Application Installer**, click **Upload**, choose
   `C:\Claude\IPTVPlayer\roku\out\CoxTV.zip`, then click **Install**.
4. The app launches on the TV.

Either way, CoxTV now appears as a tile in the TV's home-screen app list (usually at the end).

## 4. First launch: enter your playlist (one time)

The Setup screen appears.

**Easiest (Xtream Codes providers):** highlight **Enter Xtream login instead**, press **OK**, and
enter three short values when asked - the server (e.g. `example.com:80`), your username, then
your password (press **OK** on each). Both URLs are filled in for you; then choose
**Save and load channels**.

**Or enter URLs directly:**

1. Highlight **Playlist (M3U) URL**, press **OK**, type your provider's M3U link, choose **OK**.
2. Highlight **Guide (XMLTV) URL**, press **OK**, type your provider's XMLTV link, choose **OK**.
3. Highlight **Save and load channels**, press **OK**.

(In the keyboard, press Down past the bottom row to reach **OK**.)

Tip: typing long URLs with the remote is slow. Install the free **Roku** app on your phone,
connect to the TV, open **Remote**, and use its **keyboard** button to type or paste.

The settings are saved on the TV, so you only do this once.

The **first** load downloads and processes everything: channels appear after a short wait and the
guide fills in over a few minutes in the background (you can browse and watch meanwhile). After
that, CoxTV saves its data on the TV and later launches open in seconds.

## Remote controls

| Where | Key | Action |
|---|---|---|
| Home | Left / Right | Move between categories and channels |
| Home | * (Options) | Add / remove favorite |
| Player | Up / Down | Channel up / down |
| Player | OK | Show / hide info |
| Player | Left | Mini guide (OK tunes, * favorites, Back closes) |
| Player | Replay | Previous channel |
| Player | Back | Return to the list |
| Guide | Arrows | Move; Left / Right step through shows |
| Guide | Rewind / Fast-forward | Page up / down |
| Search | Right past the last keyboard column | Jump to results |

## Updating, removing, troubleshooting

- **Update**: run the install step again; it replaces the old version and keeps your settings
  and favorites.
- **Remove**: open `http://<TV IP>`, sign in, click **Delete**. Turning Developer Mode off
  (repeat the button sequence and choose **Disable installer and restart**) also removes it.
- Only **one** sideloaded app can be installed at a time; installing another replaces CoxTV.
- **"The Roku rejected the developer password"**: re-enter it, or reset it from the Developer
  Settings screen (button sequence in step 1).
- **"Upload failed"**: check the TV is on, the IP is right, and the PC is on the same network.
- **Something doesn't play or crashes**: run
  `powershell -ExecutionPolicy Bypass -File .\console.ps1 -RokuIp <TV IP>`, reproduce it,
  and share `out\console.log`.
