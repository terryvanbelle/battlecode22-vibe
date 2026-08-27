#!/usr/bin/env bash
# Run one or more Battlecode 2022 matches on the GCE VM and pull artifacts back.
#
# Usage:
#   tools/vm-match.sh <mapA> [mapB ...]                 # examplefuncsplayer vs itself
#   TEAM_A=mybot TEAM_B=examplefuncsplayer tools/vm-match.sh <map>
#
# Starts the VM if stopped. Leaves it running (stop it yourself with:
#   gcloud compute instances stop battlecode-dev --zone=us-west1-b)
set -euo pipefail

VM=battlecode-dev
ZONE=us-west1-b
PROJECT=tvanbelle-vibecode
TEAM_A="${TEAM_A:-examplefuncsplayer}"
TEAM_B="${TEAM_B:-examplefuncsplayer}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

[ "$#" -ge 1 ] || { echo "need at least one map name"; exit 1; }
mkdir -p "$REPO_ROOT/matches" "$REPO_ROOT/logs"

state=$(gcloud compute instances describe "$VM" --zone="$ZONE" --project="$PROJECT" --format='value(status)')
if [ "$state" != "RUNNING" ]; then
  echo "starting VM ($state -> RUNNING)..."
  gcloud compute instances start "$VM" --zone="$ZONE" --project="$PROJECT" >/dev/null
  for i in $(seq 1 15); do
    gcloud compute ssh "$VM" --zone="$ZONE" --project="$PROJECT" --command=true 2>/dev/null && break
    sleep 8
  done
fi

# Push local bot source (if any beyond examplefuncsplayer) to the VM.
gcloud compute ssh "$VM" --zone="$ZONE" --project="$PROJECT" --command="mkdir -p ~/battlecode22-scaffold/src" >/dev/null
gcloud compute scp --recurse "$REPO_ROOT/src/." "$VM:~/battlecode22-scaffold/src/" --zone="$ZONE" --project="$PROJECT" >/dev/null

for MAP in "$@"; do
  echo "=== match: $TEAM_A vs $TEAM_B on $MAP ==="
  gcloud compute ssh "$VM" --zone="$ZONE" --project="$PROJECT" --command="
    export JAVA_HOME=\$HOME/jdk8 PATH=\$HOME/jdk8/bin:\$PATH
    cd ~/battlecode22-scaffold
    ./gradlew --no-daemon run -PteamA=$TEAM_A -PteamB=$TEAM_B -Pmaps=$MAP \
      -Preplay=matches/$TEAM_A-vs-$TEAM_B-on-$MAP.bc22 2>&1 \
      | tee \$HOME/match-$MAP.log | grep -E '\\[server\\]'
  "
  gcloud compute scp "$VM:~/battlecode22-scaffold/matches/$TEAM_A-vs-$TEAM_B-on-$MAP.bc22" "$REPO_ROOT/matches/" --zone="$ZONE" --project="$PROJECT" >/dev/null
  gcloud compute scp "$VM:~/match-$MAP.log" "$REPO_ROOT/logs/" --zone="$ZONE" --project="$PROJECT" >/dev/null
done
echo "artifacts in $REPO_ROOT/matches and $REPO_ROOT/logs"
