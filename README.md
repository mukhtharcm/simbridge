# simbridge

Turns a rooted Android phone's SIM into a building block: calls, call audio and SMS behind one small API
([docs/API.md](docs/API.md)). The phone's own microphone and speaker stay out of the call; audio goes in and
out digitally.

**Status: calls and SMS over the API; no real client yet.** Call events, dial, answer, hang up, DTMF, two-way
call audio, and reading and sending SMS. `scripts/client.mjs` is a test client only.

## Parts

| Path | What |
|---|---|
| `app/` | The Android app (Kotlin; one dependency, the WebSocket server). |
| `app/src/main/assets/profiles/` | One JSON file per chip family: the mixer commands and audio settings. Add a file to support another phone. |
| `magisk/` | Magisk module that installs the app as a privileged app and sets the audio properties. |
| `scripts/phone.sh` | Build, install and drive it on the phone over ssh. |
| `docs/API.md` | The API that clients will use (draft). |

## Supported phones

| Chip | Verified on | Profile |
|---|---|---|
| Snapdragon 695 (`holi`) | Moto g45 5G, LineageOS 24 (capture and playback, on a live call) | `holi.json` |

## What it needs from the phone

- Root (Magisk) and a `tinymix` binary on the PATH. For now it uses the one gsm2sip's module installs; simbridge
  will ship its own, built from source, before it can replace gsm2sip.
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

## Undo

`scripts/phone.sh off` gives calls back. To give SMS back, on the phone as root:
`cmd role add-role-holder android.app.role.SMS com.android.messaging` (or whichever app had it).
To remove simbridge, on the phone as root: `rm -rf /data/adb/modules/simbridge` and reboot.

## Credits

The mixer commands and the order of the audio set-up for Qualcomm phones come from
[gsm2sip](https://github.com/pulpoff/gsm2sip) (MIT, © pulpoff).
