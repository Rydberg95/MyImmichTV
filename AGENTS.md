# AGENTS.md

## Required first step

Before doing anything else in this repository, **read `README.md` in full**. It contains essential project context that is not available anywhere else:

- The hardware/software environment this app targets (Xiaomi TV Box S, Immich v3.1.0 with self-signed TLS)
- The session resume checklist (build, install, pair, drive the TV via `tools/box.sh`)
- Feature status and what has been verified on the real box
- Testing techniques (screenshot/pixel-analysis workflow)

Do not start coding, debugging, or running commands until you have read it. If `README.md` appears stale relative to the code, follow the code but flag the discrepancy.

## Project summary

My Immich TV is a borderless, fullscreen-first Android TV app (Kotlin) for browsing an Immich photo library, with a phone-as-remote web interface served by the TV itself. The phone never talks to Immich directly — everything is proxied through the TV app over plain LAN HTTP.

## Key facts

- Build: `./gradlew :app:assembleDebug` (requires Java 17)
- Install/run on box: `tools/box.sh install|restart|pair`
- Drive the TV: `tools/box.sh cmd`, `tools/box.sh state`, `tools/box.sh shot`
- Everything in `tools/` is part of the repo (node deps: `cd tools && npm install` once)

## Remote & versioning

- Remote: `https://gitea.rydberg.lan/elias/MyImmichTV.git` (self-hosted Gitea, `origin`, branch `master`)
- Bump `versionCode`/`versionName` in `app/build.gradle.kts` for every user-visible change; the README's feature-status header carries the same version
- Commit style: milestone-sized commits with full verification notes in the message (see `git log`)
- **Push after committing** when the user asks you to save/publish work — don't leave commits only locally. Include uncommitted-but-finished older work as its own commit rather than mixing it into unrelated new work

When instructions here conflict with `README.md`, `README.md` wins.
