#!/usr/bin/env bash
set -euo pipefail

BOX_SERIAL="${BOX_SERIAL:-192.168.50.122:38617}"
TV_HOST="${TV_HOST:-192.168.50.122}"

case "${1:-}" in
  install)
    adb -s "$BOX_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
    ;;
  restart)
    adb -s "$BOX_SERIAL" logcat -c
    adb -s "$BOX_SERIAL" shell am force-stop dev.myimmich.tv
    adb -s "$BOX_SERIAL" shell am start -n dev.myimmich.tv/.MainActivity
    sleep 9
    adb -s "$BOX_SERIAL" logcat -d -s ImmichTV:D | tail -1
    ;;
  pair)
    PIN=$(adb -s "$BOX_SERIAL" logcat -d -s ImmichTV:D | grep -oE 'PIN=[0-9]+' | tail -1 | cut -d= -f2)
    if [ -z "$PIN" ]; then
      echo "no PIN in logcat; run 'box.sh restart' first" >&2
      exit 1
    fi
    curl -s -X POST -H 'Content-Type: application/json' \
      -d "{\"pin\":\"$PIN\"}" "http://$TV_HOST:8321/api/pair"
    echo
    ;;
  state)
    TOKEN="${TOKEN:?set TOKEN from pair output}"
    curl -s "http://$TV_HOST:8321/r/$TOKEN/state"
    echo
    ;;
  cmd)
    TOKEN="${TOKEN:?set TOKEN from pair output}"
    curl -s -X POST -H 'Content-Type: application/json' \
      -d "${2:?json body e.g. '{\"type\":\"next\"}'}" \
      "http://$TV_HOST:8321/r/$TOKEN/command"
    echo
    ;;
  shot)
    adb -s "$BOX_SERIAL" exec-out screencap -p > "${2:-shot.png}"
    echo "wrote ${2:-shot.png}"
    ;;
  log)
    adb -s "$BOX_SERIAL" logcat -d -s ImmichTV:D -s ImmichTV:W | tail -n "${2:-30}"
    ;;
  dream)
    # Google TV hides 3rd-party DreamServices from the screensaver picker;
    # set ours as the system dream component directly (survives reboot).
    adb -s "$BOX_SERIAL" shell settings put secure screensaver_components \
      dev.myimmich.tv/dev.myimmich.tv.SlideshowDreamService
    adb -s "$BOX_SERIAL" shell settings get secure screensaver_components
    ;;
  devices)
    adb devices -l
    ;;
  *)
    echo "usage: BOX_SERIAL=ip:port TV_HOST=ip box.sh {devices|install|restart|pair|state|cmd '<json>'|shot [file]|log [n]|dream}" >&2
    echo ""
    echo "env: BOX_SERIAL (default $BOX_SERIAL), TV_HOST (default $TV_HOST)"
    echo "The adb port changes when the box reboots; find it under"
    echo "Settings > Device Preferences > Developer options > Network debugging,"
    echo "or try: adb mdns services"
    exit 1
    ;;
esac
