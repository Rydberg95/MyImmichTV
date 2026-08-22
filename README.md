# My Immich TV

A borderless, fullscreen-first Android TV app for browsing an [Immich](https://immich.app) photo library — including a phone-as-remote web interface served by the TV itself. Built and tested against **Immich 3.1** behind a reverse proxy with a self-signed certificate.

## Features

- **Fullscreen-first viewer** — photos edge-to-edge on black, crossfade transitions, blurred backdrop for portrait photos
- **D-pad navigation** — left/right to flip, down for filmstrip, up for the source menu (Timeline / Favorites / Albums / Remote pairing)
- **Video playback** — with sound via ExoPlayer, straight from your library
- **Slideshow** — auto-advance (10 s default), shuffle mode, videos advance when they finish
- **Phone remote** — scan the QR on the TV, browse/search your whole library on the phone, tap anything to display it instantly; playback controls included
- **Self-signed TLS** — the app pins your certificate by SHA-256 fingerprint after you verify it once; the phone never talks to Immich directly at all (everything is proxied through the TV over plain LAN HTTP)
- **Screensaver** — register as Android Daydream; shows a shuffled slideshow when the TV idles

## Build

Requirements: JDK 17, Android SDK (platform 36, build-tools 36).

```bash
echo "sdk.dir=$HOME/tools/android-sdk" > local.properties
./gradlew :app:assembleDebug
```

APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Install on the TV box

On the box: **Settings → Device Preferences → About → click Build 7×**, then **Developer options → USB debugging + Network debugging**.

```bash
adb connect <box-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run

1. Launch **My Immich TV** from the apps row
2. Enter your Immich URL (e.g. `https://immich.example.lan`) and an API key created in Immich (Account settings → API keys)
3. The app probes the server. For a self-signed certificate it shows the cert's SHA-256 fingerprint — compare it against your real certificate (e.g. `openssl x509 -fingerprint -sha256 -noout < cert.pem`), then confirm
4. You're in. Config is stored on the device; the API key never leaves it except in requests to your server

## Phone remote

1. On the TV: **up** (source menu) → **Remote**
2. Scan the QR (or type the URL + PIN shown)
3. Browse Timeline / Favorites / Albums, or use smart search ("dog on beach")
4. Tap a thumbnail → it appears on the TV. The bottom bar has prev / slideshow / next / shuffle / info

Multiple phones can pair; tokens are lost when the app restarts (pair again). Pairing PIN changes on every app restart.

## Screensaver (Daydream)

Set it once in the TV's **Settings → Device Preferences → Screensaver**, pick **My Immich TV**. Shuffle-shows your latest months' photos after the idle timeout.

> Note: the screensaver can't be triggered on an emulator (Doze is disabled there) — verify on the real box.

## TV controls

| Key | Action |
|---|---|
| ← / → | previous / next asset |
| ↓ | filmstrip |
| ↑ | source menu (Timeline / Favorites / Albums / Remote) |
| OK | photo: info overlay · video: play/pause |
| ▶︎ / ⏸ (media keys) | slideshow on/off |
| BACK | close overlays |

## Troubleshooting

- **Wrong cert pinned / server moved**: clear app data (`adb shell pm clear dev.myimmich.tv`) and re-run setup
- **Remote unreachable**: check the TV and phone are on the same subnet; port is **8321/TCP**
- **Videos 401 in logs**: the API key changed server-side — redo setup

## Development notes

- Immich v3 returns timeline buckets in a **columnar** format (`id: [...]`, `isImage: [...]`); it is transposed in `AssetDto.fromColumns`
- The web remote is a dependency-free vanilla SPA bundled in `app/src/main/assets/web/`
- Remote server: embedded Ktor (CIO) with token auth; thumbnails buffered, originals streamed with Range passthrough

## Notes

- The API key is stored in the app's private DataStore on the TV only
- Rotate the API key if it was ever shared anywhere else
