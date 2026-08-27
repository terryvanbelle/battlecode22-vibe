#!/usr/bin/env bash
# Thin, reliable wrapper around the battlecode VM using PLAIN ssh/scp with the
# gcloud-managed key (avoids `gcloud compute ssh` re-pushing keys every call,
# which stalls the guest agent under load).
#
#   tools/vm.sh ip                       -> print external IP
#   tools/vm.sh up                       -> ensure RUNNING + reachable
#   tools/vm.sh ssh '<remote command>'   -> run a command on the VM
#   tools/vm.sh run < script.sh          -> pipe a script to `bash -s` on the VM
#   tools/vm.sh push <local> <remote>    -> scp to  ~/<remote> on the VM
#   tools/vm.sh pull <remote> <local>    -> scp from ~/<remote> on the VM
#   tools/vm.sh sync-src                 -> push src/ into the scaffold
set -euo pipefail

VM=battlecode-dev
ZONE=us-west1-b
PROJECT=tvanbelle-vibecode
USER_NAME="$(whoami)"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSHO=(-i "$HOME/.ssh/google_compute_engine" -o StrictHostKeyChecking=no
      -o UserKnownHostsFile=/dev/null -o ConnectTimeout=20 -o ServerAliveInterval=20
      -o ServerAliveCountMax=3 -o LogLevel=ERROR)

ip () { gcloud compute instances describe "$VM" --zone="$ZONE" --project="$PROJECT" \
          --format='value(networkInterfaces[0].accessConfigs[0].natIP)' 2>/dev/null; }

ensure_up () {
  local s
  s=$(gcloud compute instances describe "$VM" --zone="$ZONE" --project="$PROJECT" --format='value(status)' 2>/dev/null || true)
  [ "$s" = RUNNING ] || { echo "starting $VM ..." >&2; gcloud compute instances start "$VM" --zone="$ZONE" --project="$PROJECT" >/dev/null; }
  IP=$(ip); [ -n "$IP" ] || { echo "no external IP" >&2; return 1; }
  for _ in $(seq 1 30); do
    ssh "${SSHO[@]}" "$USER_NAME@$IP" true 2>/dev/null && return 0
    sleep 8
  done
  echo "VM unreachable over ssh" >&2; return 1
}

cmd="${1:-}"; shift || true
case "$cmd" in
  ip)  ip ;;
  up)  ensure_up && echo "$VM up at $(ip)" ;;
  ssh) ensure_up >/dev/null; ssh "${SSHO[@]}" "$USER_NAME@$(ip)" "$@" ;;
  run) ensure_up >/dev/null; ssh "${SSHO[@]}" "$USER_NAME@$(ip)" 'bash -s' ;;
  push) ensure_up >/dev/null; scp "${SSHO[@]}" -r "$1" "$USER_NAME@$(ip):$2" ;;
  pull) ensure_up >/dev/null; scp "${SSHO[@]}" -r "$USER_NAME@$(ip):$1" "$2" ;;
  sync-src)
    ensure_up >/dev/null
    ssh "${SSHO[@]}" "$USER_NAME@$(ip)" 'mkdir -p ~/battlecode22-scaffold/src'
    scp "${SSHO[@]}" -r "$REPO/src/." "$USER_NAME@$(ip):battlecode22-scaffold/src/" ;;
  *) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
