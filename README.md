# My Immich TV

A borderless, fullscreen-first Android TV app for browsing an [Immich](https://immich.app) photo library, with a phone-as-remote web interface served by the TV itself. Built for and tested against a specific home setup:

| | |
|---|---|
| **TV box** | Xiaomi TV Box S (3rd Gen) — Amlogic S905X5M, Google TV 14 (API 34), **2 GB RAM**, HW decode HEVC/AV1/VP9 up to 4K |
| **Immich** | v3.1.0 behind a reverse proxy, **self-signed TLS** (chain issuer `CN=rydberg_local - ECC Intermediate`) |
| **Server** | `https://immich.rydberg.lan` (LAN DNS → 192.168.50.142) |

The phone never talks to Immich directly — everything is proxied through the TV app over plain LAN HTTP, so the self-signed cert problem is solved exactly once, inside the TV.

---

## Session resume checklist

Starting cold in a new terminal:

```bash
cd ~/my-immich-tv
java -version                 # must be 17 (pacman: jdk17-openjdk)
adb devices -l                # box should be listed; if not, see "Real box" below
./gradlew :app:assembleDebug  # build (~1 min warm, needs network first time)
tools/box.sh install          # install on the TV box
tools/box.sh restart          # force-stop + relaunch; prints PIN line
tools/box.sh pair             # prints pairing token JSON; export TOKEN=<token>
```

Then to drive the TV from this machine as if it were the phone:

```bash
TOKEN=<token-from-pair>
tools/box.sh cmd '{"type":"next"}'
tools/box.sh state
tools/box.sh shot /tmp/s.png && python3 tools/analyze_screenshot.py /tmp/s.png
```

Everything in `tools/` is part of the repo (node deps: `cd tools && npm install` once).

**Where things are:** project `~/my-immich-tv`, Android SDK `~/tools/android-sdk`, Gradle 8.13 dist `~/tools/gradle-8.13` (repo wrapper also uses 8.13), emulator AVD `immich-tv`.

---

## Feature status (v0.4.1, 2026-08-22)

All items marked ✓ are **verified on the real box against the real Immich server**, usually via the pixel-analysis technique described under Testing.

- ✓ Fullscreen borderless photo viewer, crossfade transitions, blur-fill backdrop for portrait photos
- ✓ D-pad control: ←/→ flip, ↓ filmstrip, ↑ source menu (Timeline / Favorites / Albums / Remote), OK = info overlay
- ✓ Video playback with sound (Media3 ExoPlayer), correct pillarbox geometry for portrait videos, OK = play/pause
- ✓ Slideshow: auto-advance (10 s default), shuffle; videos advance on completion
- ✓ Phone remote: QR + PIN pairing, timeline/favorites/albums browsing, smart search, tap-to-display with context (slideshow follows what the phone is browsing), prev/next/play/shuffle/info controls, 2 s state polling
- ✓ Phone-driven first-run setup: scan QR on setup screen, type URL + API key on the phone, confirm cert fingerprint on the phone, TV saves config
- ✓ Self-signed cert pinning (SHA-256 of leaf cert, shown for verification, stored in DataStore)
- ✓ Daydream screensaver registered (`SlideshowDreamService`) — **logic shared with the viewer, but NOT yet exercised on the real box** (emulator images cannot enter Doze; enable it in TV Settings → Screensaver and idle the TV to verify)
- ✗ People browsing (endpoint exists at `/r/{token}/people`, no UI yet)
- ✗ TV-side settings UI (slideshow interval/shuffle only changeable from the phone or defaults)
- ✗ Video seek/scrub from the phone (Range proxy exists; no preview player in the SPA)

---

## Architecture

```
┌─ Phone browser ─────┐  plain HTTP, token-auth   ┌─ TV app ─────────────────────┐
│ SPA (assets/web)    │ ◄───────────────────────► │ RemoteServer (Ktor CIO :8321)│
│ tabs: timeline/     │                           │  ├─ static SPA               │
│ favorites/albums/   │                           │  ├─ /setup/* (PIN-gated)     │
│ search              │                           │  ├─ /api/pair → token        │
└─────────────────────┘                           │  └─ /r/{token}/* proxy+REST  │
                                                  │        │                    │
   TV remote (d-pad) ──► ViewerScreen ◄───────────┘        │ x-api-key + pinned TLS
                                                  ┌────────▼───────────────────┐
                                                  │ Immich 3.1 (reverse proxy) │
                                                  └────────────────────────────┘
```

### File map

```
app/src/main/java/dev/myimmich/tv/
  MainActivity.kt            config state machine: Loading → Setup | Viewer
  MyImmichApp.kt             owns AppSettings + RemoteController; starts RemoteServer
                             at app launch (runs unconfigured for phone setup)
  SlideshowDreamService.kt   Daydream screensaver (shuffled latest months)
  api/ImmichModels.kt        DTOs incl. columnar→row transpose (see API notes)
  api/ImmichClient.kt        REST client (OkHttp + kotlinx.serialization), URL builders
  api/ImmichImageFetcher.kt  Coil Fetcher for ImmichThumb(url) using the authed client
  data/AppSettings.kt        DataStore: server config, slideshow prefs
  tls/TlsSupport.kt          TLS probe / fingerprint capture / pinned trust manager
  remote/RemoteServer.kt     Ktor server: static + setup + viewer proxy endpoints
  remote/RemoteController.kt PIN/sessions, SharedFlow<commands>, StateFlow<viewer+setup state>
  remote/RemoteModels.kt     command/state/context DTOs, RemoteAsset→AssetDto mapper
  remote/QrBitmap.kt         ZXing QR → Bitmap
  repo/LibraryRepository.kt  buckets/assets per source (timeline/favorites/album)
  ui/setup/SetupScreen.kt    QR-first setup, manual d-pad entry as fallback
  ui/viewer/ViewerScreen.kt  the whole TV UX (~600 lines; see below)
  ui/viewer/VideoPlayer.kt   RotatingVideoFrame: TextureView with manual aspect+rotation
  ui/pairing/PairingScreen.kt QR + PIN overlay (menu → Remote)
  ui/theme/Theme.kt
app/src/main/assets/web/     the phone SPA: index.html, app.js (~320 lines), style.css
tools/                       dev/test scripts (see Testing)
```

### ViewerScreen internals (the state machine)

- `source: LibrarySource` — `TIMELINE | FAVORITES | ALBUM(id,name) | SEARCH` (SEARCH is a pseudo-source whose asset list arrives from the phone)
- `months: List<String>` + `assetsByMonth: Map<String, List<AssetDto>>` — lazy per-month paging; `assets` is the flattened list, loaded ~1 month ahead
- `remoteAsset` — single-asset override shown when a tapped asset isn't in the loaded list yet; cleared by manual navigation and slideshow advance
- `pendingShowId/pendingShowBucket/pendingSearchAssets` — resolve a "show" command after the right source/bucket loads
- Slideshow engine: `LaunchedEffect(slideshowOn, index, assets.size, intervalSec, shufflePref)` delays `intervalSec` for images; videos advance via the player's `STATE_ENDED` → `onEnded` callback
- Commands arrive on `RemoteController.commands` (SharedFlow); the collector uses **`rememberUpdatedState`** for the handler — without that, the collector captures the first (empty) composition's state forever (bug fixed once already)
- State is published to `RemoteController.viewerState` after every relevant change; the SPA polls `/r/{token}/state` every 2 s

### Remote protocol

Pairing: PIN shown on TV (also in `adb logcat -s ImmichTV:D`), `POST /api/pair {"pin"}` → `{token}` (kept in phone localStorage). Tokens live in memory only — **app restart invalidates them and rotates the PIN**.

Phone-driven setup (`/setup/*`, all PIN-gated): `submit {pin,url,apiKey}` → TV probes TLS → `AWAITING_CONFIRM` with fingerprint (phone displays it) → `confirm {pin}` → TV validates key against `/api/users/me`, saves, `DONE` → phone auto-pairs.

Viewer endpoints under `/r/{token}/`: `GET state`, `POST command`, `buckets[?album=|favorite=true]`, `assets?bucket=…[&album=|favorite=true]`, `albums`, `search?q=…`, `people`, `thumb/{id}?size=preview|thumbnail`, `original/{id}` (Range passthrough → 206 + Content-Range).

Commands: `{type: show|next|prev|slideshow|shuffle|info, assetId?, assetType?, value?, context?}` where `context = {source: timeline|favorites|album|search, albumId?, albumName?, bucket?, assets?}` — context is what makes the slideshow follow the phone's view.

---

## Immich 3.1 API contract (as reverse-engineered live)

These were all learned the hard way; the OpenAPI spec is not reachable through the proxy (only `/docs` UI HTML), so changes must be probed with curl + a valid key.

| Endpoint | Shape | Gotchas |
|---|---|---|
| `GET /api/server/ping` | 200 text | unauthenticated OK — good reachability probe |
| `GET /api/users/me` | user JSON | validates `x-api-key` |
| `GET /api/timeline/buckets?size=MONTH&isArchived=false` | `[{timeBucket:"2026-08-01", count:n}]` | **bucket IDs are first-of-month dates**, not `YYYY-MM` |
| `GET /api/timeline/bucket?size=MONTH&timeBucket=2026-08-01` | **columnar object** `{id:[…], isImage:[…], duration:[…], fileCreatedAt:[…], city:[…], country:[…], thumbhash:[…], …}` | v3 breaking change; transposed in `AssetDto.fromColumns`. No `type` field — derive from `isImage`. No filename. `duration` is numeric ms |
| `POST /api/search/smart {"query","limit"}` | `{assets:{total,count,items:[…]}}` | items are **row-format** (not columnar); `duration` numeric ms here too (code also tolerates `HH:MM:SS.SSS` strings); extra query params: `page` |
| `GET /api/people` | `{people:[…], hasNextPage, total, hidden}` | wrapper object — decode `.people` |
| `GET /api/albums` | `[{id, albumName, assetCount, …}]` | plain array |
| `GET /api/assets/{id}/thumbnail?size=preview\|thumbnail` | image bytes | **requires `x-api-key`** (401 without, even on LAN) |
| `GET /api/assets/{id}/original` | bytes, Range-capable | requires `x-api-key` |

Header for all authed calls: `x-api-key: <key>`.

---

## Build & toolchain

Pinned on purpose — **the newest androidx stack (Compose BOM ≥ 2026.x, core-ktx 1.19, lifecycle 2.11) requires AGP 9.1+ and compileSdk 37**. This project deliberately stays on the compileSdk-36-era stack; upgrading AGP means Gradle 9.5+ and the AGP-9 built-in-Kotlin migration. Don't bump these casually:

| Component | Version | Note |
|---|---|---|
| Gradle | 8.13 | wrapper + `~/tools/gradle-8.13` |
| AGP | 8.13.0 | |
| Kotlin | 2.3.21 | `kotlinOptions` DSL is an **error** now — use `kotlin { compilerOptions {} }` |
| compose BOM | 2025.12.01 | ui 1.10.0 |
| tv-material | 1.1.0 | |
| coil | 3.5.0 | network internals are **internal** — see Coil notes below |
| media3 | 1.11.0 | |
| ktor | 3.5.2 | server runs on Android fine |
| okhttp | 4.12.0 | matches coil's transitive |
| datastore-preferences | 1.2.1 | |

compileSdk/targetSdk 36, minSdk 30, JDK 17. `largeHeap=true` for the 2 GB box.

### Toolchain environment (this machine)

- JDK 17: `sudo pacman -S jdk17-openjdk` (Arch)
- Android SDK: `~/tools/android-sdk` — cmdline-tools + `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`, `emulator`, `system-images;android-36;android-tv;x86_64`
- `local.properties` → `sdk.dir=/home/nuker/tools/android-sdk` (untracked)
- Emulator AVD `immich-tv` (tv_1080p). Launch headless:
  `~/tools/android-sdk/emulator/emulator -avd immich-tv -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot`
  Then `adb -s emulator-5554 forward tcp:8321 tcp:8321` to reach its remote server at `localhost:8321`.
  Note: Android TV emulator images cannot enter Doze — screensaver cannot be tested there.

### Real box (192.168.50.122)

ADB over network debugging: Settings → Device Preferences → About → click Build 7× → Developer options → Network debugging. The port is random per boot (e.g. `:38617`) — check `adb devices`, `adb mdns services`, or the TV screen. Most dev commands take `-s <serial>`; `tools/box.sh` wraps the common ones (override with `BOX_SERIAL=` env).

Install the app as "unknown sources" when prompted once.

---

## Testing playbook

There is no test suite; verification is empirical, against the real server. Two devices, three techniques:

### 1. API testing with curl

The embedded server is reachable from this machine at `http://192.168.50.122:8321`. After `tools/box.sh restart` (prints the PIN) and `tools/box.sh pair` (prints the token):

```bash
TOKEN=…
curl -s http://192.168.50.122:8321/r/$TOKEN/state
curl -s "http://192.168.50.122:8321/r/$TOKEN/search?q=dog" | python3 -m json.tool | head
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"type":"show","assetId":"<uuid>","assetType":"IMAGE","context":{"source":"search","assets":[…]}}' \
  http://192.168.50.122:8321/r/$TOKEN/command
```

Immich itself can be probed directly (key omitted here — see Security):

```bash
curl -sk -H "x-api-key: $KEY" "https://immich.rydberg.lan/api/timeline/buckets?size=MONTH&isArchived=false"
echo | openssl s_client -connect immich.rydberg.lan:443 -servername immich.rydberg.lan 2>/dev/null \
  | openssl x509 -noout -fingerprint -sha256     # compare against what the app displays
```

### 2. Phone simulation with headless Chromium

`tools/phone_sim.js` (basic sanity: does the SPA pair, render the grid, load images?) uses system Chromium via puppeteer-core. Run: `cd tools && node phone_sim.js 192.168.50.122 <pin>`. It reports DOM state (cells, broken images, months) and every console/network error — this is how the SPA `grid is not defined` bug was found instantly after curl tests had passed.

**Lesson learned (cost hours):** when chaining UI tests, reset shared state between them. The phone's ▶ button *toggles* the TV slideshow; a previous test leaving it on makes the next test's "press play" turn it *off*, producing phantom "slideshow doesn't work" results. When in doubt, drive commands directly over the API where you control the state.

### 3. TV-side verification via screencap + pixel analysis

`adb exec-out screencap -p` on the box is trustworthy (the launcher captures fine). Since a headless session can't look at images, `tools/analyze_screenshot.py` renders PNGs as ASCII art, finds the content bounding box (pillarbox geometry!), and diffs two captures (video frames advancing?):

```bash
tools/box.sh shot a.png; sleep 2; tools/box.sh shot b.png
python3 tools/analyze_screenshot.py a.png
python3 tools/analyze_screenshot.py a.png --diff b.png
```

This caught: the all-black app (thumb 401s), the audio-but-black video (TextureView bug), and measured portrait-video pillarbox at exactly 0.563 aspect.

### Logcat tags

`ImmichTV:D` (server start + PIN, setup completion, image-fetch failures — the fetcher logs failures at W). Filter: `adb -s <dev> logcat -s ImmichTV:D ImmichTV:W`.

---

## Development history & bug stories

Chronological; each bug is worth remembering because the *class* of it recurs.

**M0/M1 — scaffold + setup + viewer.** AGP/Kotlin/BOM pinning dance (newest androidx needs AGP 9.1 — see Build). Coil 3.5 made `OkHttpNetworkFetcherFactory` and `SourceImageSource` internal, so images load through a **custom `Fetcher`** (`ImmichImageFetcher`) that must pass `(source, fileSystem)` to the public `ImageSource()` factory. Setup wizard probes TLS with a recording trust manager, shows the leaf SHA-256, pins it.

**First end-to-end run against real Immich:** cert fingerprint matched openssl byte-for-byte, then the viewer crashed on JSON — **v3 returns timeline buckets columnar** (see API table). Transposed in `AssetDto.fromColumns`.

**M2 — video + albums + slideshow.** ExoPlayer got HTTP 401: the `OkHttpDataSource` builds its own requests without our header — fixed with an interceptor client (the pattern now used for Coil too). Cursor-based source menu (all d-pad handling centralized in the viewer's `onPreviewKeyEvent`; Compose focus juggling on TV is misery — keep it that way).

**M3 — phone remote.** Ktor 3.5 on Android: `respond(obj)` needs the ContentNegotiation plugin (406 otherwise); `writeFully` takes a ByteBuffer and is an extension requiring an import. Command handling had the classic Compose bug: a `LaunchedEffect(Unit)` collector captured the *initial empty* asset list — `next` did nothing. Fix: handler via `rememberUpdatedState`. The `/people` 500 was the response-wrapper decode; search's numeric `duration` broke the parser (now JsonElement, tolerant of both shapes).

**M4 — screensaver + polish.** DreamService registered; emulator can't dream (Doze disabled on ATV images) — still needs on-box verification.

**Phone-driven setup** (so nobody types a 40-char API key with a d-pad): RemoteServer moved to app-startup, runs unconfigured, `/setup/*` PIN-gated; the TV does the TLS probe (phone never needs the cert), fingerprint confirmed on the phone.

**Black screen on the real box (worked on emulator!):** thumbnails 401 — the Coil fetcher was the only client without `x-api-key`. The emulator had masked it. Fix + fetch-failure logging at `ImmichTV:W`.

**Phone grid empty:** SPA threw `ReferenceError: grid is not defined` — a refactor deleted the `const grid` declaration; `node --check` passes (syntax only). curl API tests can't catch JS runtime errors — that's what `phone_sim.js` is for. Added a red on-screen JS-error banner to the SPA.

**Search-tap didn't change TV pixels:** state *said* the right asset (fooled a state-only test) but the render branch used `assets[safeIndex]`, ignoring the `remoteAsset` override. Rule: **verify pixels, not state.**

**Portrait video black, audio fine:** the custom `RotatingVideoFrame` set the TextureView `GONE` until `onVideoSizeChanged` — but a GONE TextureView never creates its SurfaceTexture, so the renderer can never deliver a frame. Keep it laid out. Also: media3's `PlayerView` ignores `unappliedRotationDegrees`, and this Amlogic decoder applies rotation itself (VideoSize arrives pre-swapped, rot=0) — hence a custom frame doing aspect-fit manually instead of `PlayerView`.

**Slideshow followed the timeline when browsing an album on the phone:** `show` commands now carry the phone's browsing context; the TV switches source (incl. the SEARCH pseudo-source carrying the result list) before jumping.

---

## Known issues & TODO (prioritized)

1. **Sessions die on app restart** — PIN rotates, tokens are memory-only. Every app reinstall/restart forces re-pairing. Fix direction: persist tokens in DataStore (accept the PIN rotation, keep tokens), or persist PIN + tokens.
2. **Screensaver unverified on the box** — enable in TV settings and idle-test; logic mirrors the working viewer path.
3. **People browsing** — endpoint ready, SPA tab missing.
4. **Shuffle** has no TV-remote key binding (phone-only). Media FF/REW were the planned keys.
5. **TV settings UI** — interval/shuffle are settings-flow only.
6. **Video scrubbing from phone** — Range proxy works; SPA has no `<video>` preview.
7. `livePhotoVideoId` is carried in models but unused.
8. Slideshow interval is global, not per-run.

---

## Security notes

- **Rotate the API key used during development** — it transited the chat session. Immich → Account settings → API keys → revoke + create new, then redo setup on the TV.
- The API key is stored only in the TV app's private DataStore and used in requests to your server (plus the LAN proxy, token-gated).
- Cert pinning: leaf SHA-256 stored at setup; hostname verification is relaxed *only* while pinning is active (standard for self-signed pinning).
- Remote endpoints require a bearer-style path token; the setup endpoints require the on-screen PIN. Both are LAN-only; nothing is exposed beyond the local network.

---

## Controls

**TV remote:** ←/→ prev/next · ↓ filmstrip · ↑ source menu · OK info (photos) / play-pause (videos) · ▶/⏸ media keys slideshow on/off · BACK closes overlays.

**Phone:** tabs (Timeline / Favorites / Albums / Search with debounced smart search), month chips, thumbnail grid (tap = display on TV, context included), bottom bar ⏮ ▶ ⏭ 🔀 ℹ.

---

## Repository layout & tooling

- `tools/box.sh` — box deploy/PIN/pair/state/cmd/screenshot/log helpers (`BOX_SERIAL`, `TV_HOST` env overrides)
- `tools/phone_sim.js` — headless-Chromium phone simulation (pair via hash pin, report grid/console/network)
- `tools/analyze_screenshot.py` — screencap ASCII art / content extent / frame diff
- `tools/package.json` — puppeteer-core 25.8.0 pinned; `cd tools && npm install`
- `tools/node_modules/` is git-ignored

Commits: see `git log` — milestone-sized commits with full verification notes in the messages.
