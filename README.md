# simbridge

Turns a rooted Android phone's SIM into a building block: calls, call audio and SMS behind one small API
([docs/API.md](docs/API.md)). The phone's own microphone and speaker stay out of the call; audio goes in and
out digitally.

**Status: calls and SMS work over the API, with a web client.** Call events, dial, answer, hang up, DTMF,
two-way call audio, and reading and sending SMS.

## Parts

| Path | What |
|---|---|
| `app/` | The Android app (Kotlin; one dependency, the WebSocket server). |
| `app/src/main/assets/profiles/` | One JSON file per chip family: the mixer commands and audio settings. Add a file to support another phone. |
| `magisk/` | Magisk module that installs the app as a privileged app, sets the audio properties and opens the app once after boot. |
| `tools/tinymix/` | The mixer tool the app runs as root (Go, from gsm2sip). Building needs Go. |
| `scripts/phone.sh` | Build, install and drive it on the phone over ssh. |
| `client/` | The web client: one page plus its app icon and manifest. No build step, no outside requests. |
| `scripts/client.mjs` | A command-line test client. |
| `docs/API.md` | The API that clients use (draft). |

## Supported phones

| Chip | Verified on | Profile |
|---|---|---|
| Snapdragon 695 (`holi`) | Moto g45 5G, LineageOS 24 (capture and playback, on a live call) | `holi.json` |

## What it needs from the phone

- Root (Magisk). The module ships its own `tinymix`, built from the source in `tools/tinymix`, and runs it from
  the module's folder, which only root can write.
- To be a privileged app: capturing call audio needs `CAPTURE_AUDIO_OUTPUT`.
- To be the default phone app: that is how Android hands it the calls.
- To be the default SMS app: Android shows an app its own sent messages only then. simbridge then stores
  incoming and sent texts itself, in Android's own message store. **MMS is not supported** and is not fetched
  while simbridge is the SMS app.

## Build and install

```sh
scripts/phone.sh build      # tests, APK, build/simbridge-magisk.zip
scripts/phone.sh install    # installs the module; then reboot the phone
scripts/phone.sh on         # after the reboot: permissions, root, default phone app
scripts/phone.sh update     # later builds: replace the app, no reboot
scripts/phone.sh web        # copy the web client to the server that serves it
scripts/phone.sh log        # watch a call
node scripts/client.mjs --answer --play hello.wav   # test client: answers, plays a WAV, records the caller
scripts/phone.sh off        # give calls back to the previous phone app
```

The signing key lives in `signing/` (not in git). Whatever is signed with it becomes a privileged app with root
on the phone, so keep it private and use it for nothing else. To make one:

```sh
keytool -genkeypair -keystore signing/release.jks -alias simbridge -keyalg RSA -validity 10000
echo "password=<the password>" > signing/keystore.properties
```

## The web client

The `client/` folder is the whole client: messages, a keypad, and a call screen that can be minimized to a
bar while you do something else. It asks for the bridge's address and token once and keeps them in the browser.
It can be installed as an app (Settings, then Install, in Chrome or Edge; Share, then Add to Home Screen, in
Safari), and can show notifications for calls and texts while it is in the background. Browsers only allow the microphone on `https://` pages and on `http://localhost`, so:

- **On the computer you are sitting at:** serve the folder locally and open it.
  ```sh
  python3 -m http.server 8765 --bind 127.0.0.1 --directory client
  ```
  Then open `http://localhost:8765`. The page talks to the bridge directly, so this computer must be able to
  reach the bridge's address.
- **On other devices, such as a phone:** put the page and the API behind one HTTPS address with a reverse proxy
  you trust. Serve this folder at `/` and pass `/v0` through to the bridge; the page then finds the API at its
  own address and only asks for the token. This is not set up by simbridge. With Caddy:
  ```
  sim.example.com {
      reverse_proxy /v0 127.0.0.1:8088
      root * /srv/simbridge/client
      file_server
  }
  ```

Closing the page during a call hangs up, if no other client is connected.

## Undo

`scripts/phone.sh off` gives calls back. To give SMS back, on the phone as root:
`cmd role add-role-holder android.app.role.SMS com.android.messaging` (or whichever app had it).
To remove simbridge, on the phone as root: `rm -rf /data/adb/modules/simbridge` and reboot.

## Licence and credits

simbridge is MIT-licensed; see [LICENSE](LICENSE).

- The mixer commands and the order of the audio set-up for Qualcomm phones come from
  [gsm2sip](https://github.com/pulpoff/gsm2sip) (MIT, © pulpoff).
- `tools/tinymix` is gsm2sip's tinymix, unchanged, under its own MIT licence in that folder.
- The web client bundles the Bricolage Grotesque typeface (SIL Open Font License; see
  `client/bricolage-grotesque.LICENSE.txt`).

**Use it on your own SIM, and mind your local law.** simbridge answers and places calls and texts as the SIM's
owner, and call audio passes through it. Recording or relaying calls is regulated in many places.
