#!/usr/bin/env bash
# Verify an automatic adb-reverse fallback can reach both unauthenticated
# listeners. Never enable remote listeners implicitly or open a firewall.
waybeam_require_trusted_lan_binds() {
  local host_ip="$1" plane bind_addr opt_in bind_key ack_key
  for plane in control stream; do
    case "$plane" in
      control)
        bind_key=WBEAM_CONTROL_BIND_ADDR
        ack_key=WBEAM_ALLOW_UNAUTHENTICATED_LAN_CONTROL
        bind_addr="${WBEAM_CONTROL_BIND_ADDR:-127.0.0.1}"
        opt_in="${WBEAM_ALLOW_UNAUTHENTICATED_LAN_CONTROL:-0}"
        ;;
      stream)
        bind_key=WBEAM_STREAM_BIND_ADDR
        ack_key=WBEAM_ALLOW_UNAUTHENTICATED_LAN_STREAM
        bind_addr="${WBEAM_STREAM_BIND_ADDR:-127.0.0.1}"
        opt_in="${WBEAM_ALLOW_UNAUTHENTICATED_LAN_STREAM:-0}"
        ;;
    esac
    if [[ "$opt_in" != "1" || ( "$bind_addr" != "$host_ip" && "$bind_addr" != "0.0.0.0" ) ]]; then
      echo "[android] ERROR: adb reverse unavailable; $plane listener does not explicitly expose $host_ip." >&2
      echo "[android] Configure $bind_key=$host_ip and $ack_key=1 for a trusted LAN, restart the daemon, then retry." >&2
      echo "[android] Both control and video need their own opt-in; no listener will be opened automatically." >&2
      return 1
    fi
  done
}
