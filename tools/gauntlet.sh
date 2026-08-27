#!/usr/bin/env bash
# Run "the Gauntlet" for the current bot (TRAINING_ALGORITHM.md step 2/3).
#
# For every opponent and every map, play two headless games (bot as team A, then
# as team B) on the GCE VM, record win/loss, and copy back the replays of games
# the bot LOST.
#
# Usage:
#   tools/gauntlet.sh
#   BOT=bot OPPONENTS="examplefuncsplayer g_iter1" tools/gauntlet.sh
#   MAPS="maze eckleburg" MAXJOBS=4 tools/gauntlet.sh
#   MAPSET=full tools/gauntlet.sh
#
# Output (local, gauntlet/<run-id>/):
#   results.csv   opponent,map,bot_side,winner_side,rounds,bot_result
#   reasons.txt   opponent map side <DominationFactor text>
#   summary.txt   win rate overall and per opponent
#   losses/*.bc22 replays the bot lost (one match each)
set -euo pipefail

VM=battlecode-dev
ZONE=us-west1-b
PROJECT=tvanbelle-vibecode
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# PLAIN ssh/scp with the gcloud-managed key -- `gcloud compute ssh` re-pushes
# keys to instance metadata every call and stalls the guest agent under load.
SSHO=(-i "$HOME/.ssh/google_compute_engine" -o StrictHostKeyChecking=no
      -o UserKnownHostsFile=/dev/null -o ConnectTimeout=20 -o ServerAliveInterval=20
      -o ServerAliveCountMax=3 -o LogLevel=ERROR)
USER_NAME="$(whoami)"
vm_ip () { gcloud compute instances describe "$VM" --zone="$ZONE" --project="$PROJECT" \
             --format='value(networkInterfaces[0].accessConfigs[0].natIP)' 2>/dev/null; }
gssh () { ssh "${SSHO[@]}" "$USER_NAME@$IP" "$1"; }
gscp () { scp "${SSHO[@]}" "$@"; }
wait_ssh () { for _ in $(seq 1 40); do gssh true 2>/dev/null && return 0; sleep 8; done; return 1; }

BOT="${BOT:-bot}"
OPPONENTS="${OPPONENTS:-examplefuncsplayer}"
MAXJOBS="${MAXJOBS:-3}"          # concurrent games on the VM (4 vCPU)
MAPSET="${MAPSET:-loop}"

LOOP_MAPS="maptestsmall eckleburg intersection colosseum chessboard maze sandwich \
jellyfish squer pillars highway fortress valley island_hopping"
if [ "${MAPS:-}" ]; then :
elif [ "$MAPSET" = "full" ]; then MAPS="$(tr '\n' ' ' < "$REPO/tools/bc22-maps.txt")"
else MAPS="$LOOP_MAPS"; fi

RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT="$REPO/gauntlet/$RUN_ID"
mkdir -p "$OUT/losses"
NGAMES=$(( $(echo "$OPPONENTS" | wc -w) * $(echo "$MAPS" | wc -w) * 2 ))
echo "gauntlet $RUN_ID   bot=$BOT   opponents=[$OPPONENTS]   maps=$(echo "$MAPS" | wc -w)   games=$NGAMES   parallel=$MAXJOBS"

state=$(gcloud compute instances describe "$VM" --zone="$ZONE" --project="$PROJECT" --format='value(status)' 2>/dev/null || true)
[ "$state" = RUNNING ] || { echo "  starting VM ..."; gcloud compute instances start "$VM" --zone="$ZONE" --project="$PROJECT" >/dev/null; }
IP="$(vm_ip)"; RVM="$USER_NAME@$IP"
[ -n "$IP" ] || { echo "!! no external IP" >&2; exit 1; }
wait_ssh || { echo "!! cannot reach $RVM" >&2; exit 1; }
gssh 'pkill -9 -f gauntlet_run.sh; pkill -9 -f battlecode.server; pkill -9 -f org.gradle; mkdir -p ~/battlecode22-scaffold/src' 2>/dev/null || true
gscp -r "$REPO/src/." "$RVM:battlecode22-scaffold/src/" >/dev/null

# ---- remote runner: bare `java` per game (1 JVM, no gradle daemon), parallel ----
remote=$(mktemp)
cat > "$remote" <<REMOTE
set -uo pipefail
export JAVA_HOME=\$HOME/jdk8 PATH=\$HOME/jdk8/bin:\$PATH
cd ~/battlecode22-scaffold
./gradlew --no-daemon -q build >/dev/null 2>&1 || { echo "BUILD-FAILED" > gauntlet/results.txt; exit 1; }
BC_JAR=\$(find ~/.gradle -name 'battlecode22-*.jar' | grep -v client | head -1)
SC_JAR=\$(find ~/.gradle -name 'scala-library-2.11.7.jar' | head -1)
CP="build/classes:build/resources/main:\$BC_JAR:\$SC_JAR"
mkdir -p gauntlet; : > gauntlet/results.txt

game () {  # <opp> <map> <side>
  local OPP=\$1 MAP=\$2 SIDE=\$3 TA TB
  if [ "\$SIDE" = A ]; then TA=$BOT; TB=\$OPP; else TA=\$OPP; TB=$BOT; fi
  local REPLAY=gauntlet/\${OPP}__\${MAP}__bot\${SIDE}.bc22 LOG W R RE
  LOG=\$(java -Xmx1g -Dbc.server.mode=headless -Dbc.server.map-path=maps \
    -Dbc.server.robot-player-to-system-out=false -Dbc.server.debug=false \
    -Dbc.engine.debug-methods=false -Dbc.engine.enable-profiler=false -Dbc.engine.show-indicators=true \
    -Dbc.game.team-a="\$TA" -Dbc.game.team-b="\$TB" \
    -Dbc.game.team-a.url=build/classes -Dbc.game.team-b.url=build/classes \
    -Dbc.game.team-a.package="\$TA" -Dbc.game.team-b.package="\$TB" \
    -Dbc.game.maps="\$MAP" -Dbc.server.save-file="\$REPLAY" \
    -cp "\$CP" battlecode.server.Main -c=- 2>&1 || true)
  W=\$(printf '%s\n' "\$LOG"  | sed -n 's/.*(\([AB]\)) wins.*/\1/p' | tail -1)
  R=\$(printf '%s\n' "\$LOG"  | sed -n 's/.*wins (round \([0-9]*\)).*/\1/p' | tail -1)
  RE=\$(printf '%s\n' "\$LOG" | sed -n 's/.*Reason: //p' | tail -1)
  printf 'RESULT %s %s %s %s %s\n' "\$OPP" "\$MAP" "\$SIDE" "\${W:-?}" "\${R:-?}" >> gauntlet/results.txt
  printf 'REASON %s %s %s %s\n'    "\$OPP" "\$MAP" "\$SIDE" "\${RE:-?}"           >> gauntlet/results.txt
}

for OPP in $OPPONENTS; do
  for MAP in $MAPS; do
    for SIDE in A B; do
      while [ "\$(jobs -rp | wc -l)" -ge $MAXJOBS ]; do wait -n; done
      game "\$OPP" "\$MAP" "\$SIDE" &
    done
  done
done
wait
echo GAUNTLET-COMPLETE >> gauntlet/results.txt
REMOTE
gscp "$remote" "$RVM:gauntlet_run.sh" >/dev/null
gssh 'setsid bash -c "bash ~/gauntlet_run.sh > ~/gauntlet_run.log 2>&1" </dev/null >/dev/null 2>&1 &' >/dev/null || true

RES=battlecode22-scaffold/gauntlet/results.txt
echo "  polling every 45s ..."
seen=0; deadline=$(( $(date +%s) + 120*60 ))
while true; do
  sleep 45
  snap=$(gssh "cat $RES 2>/dev/null; echo '@@@'; pgrep -f 'gauntlet_run.sh|battlecode.server' >/dev/null && echo ALIVE") || { echo "  (ssh retry)"; continue; }
  body=${snap%@@@*}; ctl=${snap#*@@@}
  printf '%s\n' "$body" > "$OUT/results.txt"
  n=$(printf '%s\n' "$body" | grep -c '^RESULT ' || true)
  if [ "$n" -gt "$seen" ]; then
    printf '%s\n' "$body" | grep '^RESULT ' | tail -n +"$((seen+1))" | while read -r _ OPP MAP SIDE WIN RND; do
      [ "$WIN" = "$SIDE" ] && r="win " || { [ "$WIN" = "?" ] && r="????" || r="LOSS"; }
      printf '  [%2d/%d] %s %-16s %-14s r%s\n' "$n" "$NGAMES" "$r" "$MAP" "$OPP" "$RND"
    done
    seen=$n
  fi
  grep -q '^BUILD-FAILED' "$OUT/results.txt" && { echo "!! remote build failed" >&2; exit 1; }
  grep -q '^GAUNTLET-COMPLETE' "$OUT/results.txt" && { echo "  complete ($n games)"; break; }
  printf '%s\n' "$ctl" | grep -q ALIVE || { echo "!! runner died at $n/$NGAMES games" >&2; break; }
  [ "$(date +%s)" -gt "$deadline" ] && { echo "!! poll deadline" >&2; break; }
done

# ---- collate ----
{ echo "opponent,map,bot_side,winner_side,rounds,bot_result"
  grep '^RESULT ' "$OUT/results.txt" | while read -r _ OPP MAP SIDE WIN RND; do
    [ "$WIN" = "$SIDE" ] && R=win || { [ "$WIN" = "?" ] && R=unknown || R=loss; }
    echo "$OPP,$MAP,$SIDE,$WIN,$RND,$R"
  done; } > "$OUT/results.csv"
grep '^REASON ' "$OUT/results.txt" | sed 's/^REASON //' > "$OUT/reasons.txt" || true

awk -F, 'NR>1 && $6=="loss"{print $1"__"$2"__bot"$3".bc22"}' "$OUT/results.csv" | while read -r k; do
  [ -n "$k" ] && gscp "$RVM:battlecode22-scaffold/gauntlet/$k" "$OUT/losses/" >/dev/null 2>&1 || true
done

{
  total=$(($(wc -l < "$OUT/results.csv") - 1))
  wins=$(grep -c ',win$' "$OUT/results.csv" || true)
  echo "run $RUN_ID   bot=$BOT"
  awk -v w="$wins" -v t="$total" 'BEGIN{printf "overall: %d/%d wins (%.1f%%)\n", w, t, (t>0)?100*w/t:0}'
  echo
  for OPP in $OPPONENTS; do
    t=$(grep -c "^$OPP," "$OUT/results.csv" || true)
    w=$(grep -c "^$OPP,.*,win$" "$OUT/results.csv" || true)
    awk -v o="$OPP" -v w="$w" -v t="$t" 'BEGIN{printf "  vs %-20s %d/%d (%.0f%%)\n", o, w, t, (t>0)?100*w/t:0}'
  done
  echo
  echo "losses:"
  awk -F, 'NR>1 && $6=="loss"{printf "  %-16s %-14s bot=%s  r%s\n",$2,$1,$3,$5}' "$OUT/results.csv"
} | tee "$OUT/summary.txt"

echo
echo "wrote $OUT/"
rm -f "$remote"
