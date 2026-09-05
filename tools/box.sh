#!/usr/bin/env bash
set -euo pipefail

BOX_SERIAL="${BOX_SERIAL:-192.168.50.122:36791}"
TV_HOST="${TV_HOST:-192.168.50.122}"
ADB_TIMEOUT="${ADB_TIMEOUT:-15}"

# adb wrapper: never wedge the session on an unreachable box
abox() {
  local rc=0
  timeout "$ADB_TIMEOUT" adb -s "$BOX_SERIAL" "$@" || rc=$?
  if [ $rc -eq 124 ]; then
    echo "adb timed out after ${ADB_TIMEOUT}s — the box is not answering on $BOX_SERIAL" >&2
    echo "It is probably powered off/standby. Press the physical remote (or run" >&2
    echo "'tools/box.sh wake'), then re-run this command." >&2
  fi
  return $rc
}

# Best-effort wake: WOL magic packet (reaches deep standby if the NIC supports
# it) + KEYCODE_WAKEUP over adb (covers screen-off-but-awake). Harmless if the
# box is already on.
wake() {
  local mac
  mac=$( (ip neigh show "$TV_HOST" 2>/dev/null; arp -n "$TV_HOST" 2>/dev/null) \
    | grep -oiE '([0-9a-f]{2}:){5}[0-9a-f]{2}' | head -1 || true)
  if [ -n "$mac" ]; then
    python3 - "$mac" <<'EOF' || true
import socket, sys
mac = sys.argv[1].replace(':', '').replace('-', '')
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.sendto(bytes.fromhex('FF' * 6 + mac * 16), ('255.255.255.255', 9))
print('WOL magic packet sent to ' + sys.argv[1])
EOF
  else
    echo "no MAC in the local ARP cache for $TV_HOST — skipping WOL" >&2
  fi
  abox shell input keyevent 224 >/dev/null 2>&1 || true   # KEYCODE_WAKEUP
  sleep 3
}

case "${1:-}" in
  wake)
    wake
    abox get-state && echo "box is reachable"
    ;;
  install)
    wake
    abox install -r app/build/outputs/apk/debug/app-debug.apk
    ;;
  restart)
    wake
    abox logcat -c
    # Exit any running dream/screensaver BEFORE killing the process: the
    # screensaver component belongs to this app, and force-stopping the
    # active dream sends the box straight into standby/power-off (only the
    # physical remote wakes it from there).
    abox shell input keyevent 3 >/dev/null   # HOME exits a dream, harmless otherwise
    sleep 1
    abox shell am force-stop dev.myimmich.tv
    abox shell am start -n dev.myimmich.tv/.MainActivity
    sleep 9
    if ! abox shell pidof dev.myimmich.tv >/dev/null; then
      echo "app process not running after restart — check 'tools/box.sh log'" >&2
    fi
    abox logcat -d -s ImmichTV:D | tail -1
    ;;
  pair)
    PIN=$(abox logcat -d -s ImmichTV:D | grep -oE 'PIN=[0-9]+' | tail -1 | cut -d= -f2)
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
    abox exec-out screencap -p > "${2:-shot.png}"
    echo "wrote ${2:-shot.png}"
    ;;
  key)
    abox shell input keyevent "${2:?keyevent code (19=up 20=down 21=left 22=right 23=ok 4=back)}"
    ;;
  log)
    abox logcat -d -s ImmichTV:D -s ImmichTV:W | tail -n "${2:-30}"
    ;;
  dream)
    # Google TV hides 3rd-party DreamServices from the screensaver picker;
    # set ours as the system dream component directly (survives reboot).
    abox shell settings put secure screensaver_components \
      dev.myimmich.tv/dev.myimmich.tv.SlideshowDreamService
    abox shell settings get secure screensaver_components
    ;;
  devices)
    adb devices -l
    ;;
  *)
    echo "usage: BOX_SERIAL=ip:port TV_HOST=ip box.sh {devices|wake|install|restart|pair|state|cmd '<json>'|shot [file]|key <code>|log [n]|dream}" >&2
    echo ""
    echo "env: BOX_SERIAL (default $BOX_SERIAL), TV_HOST (default $TV_HOST), ADB_TIMEOUT (default $ADB_TIMEOUT)"
    echo "The adb port changes when the box reboots; find it under"
    echo "Settings > Device Preferences > Developer options > Network debugging,"
    echo "or try: adb mdns services"
    exit 1
    ;;
esac