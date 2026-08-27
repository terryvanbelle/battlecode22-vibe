#!/usr/bin/env bash
# Run "the Gauntlet" for the current bot (TRAINING_ALGORITHM.md step 2).
#
# For every opponent and every map, play two headless games (current bot as
# team A, then as team B) on the GCE VM, record win/loss, and copy back the
# replays of games the current bot LOST.
#
# Usage:
#   tools/gauntlet.sh                       # bot vs OPPONENTS over MAPS (defaults below)
#   BOT=bot OPPONENTS="examplefuncsplayer g_iter0" MAPS="maze eckleburg" tools/gauntlet.sh
#   MAPSET=full tools/gauntlet.sh           # use every map in tools/bc22-maps.txt
#
# Output (local):
#   gauntlet/<run-id>/results.csv           opponent,map,bot_side,winner_side,bot_result
#   gauntlet/<run-id>/summary.txt           win rate overall and per opponent
#   gauntlet/<run-id>/losses/*.bc22         replays the current bot lost
set -euo pipefail

VM=battlecode-dev
ZONE=us-west1-b
PROJECT=tvanbelle-vibecode
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Talk to the VM with PLAIN ssh/scp using the gcloud-managed key. `gcloud
# compute ssh` re-pushes SSH keys to instance metadata on every call, which
# backs up the guest agent and makes the VM briefly unreachable under load.
SSHK=~/.ssh/google_compute_engine
SSHO=(-i "$SSHK" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
      -o ConnectTimeout=20 -o ServerAliveInterval=20 -o ServerAliveCountMax=3 -o LogLevel=ERROR)
VM_USER="$(whoami)"

vm_ip () { gcloud compute instances describe "$VM" --zone="$ZONE" --project="$PROJECT" \
             --format='value(networkInterfaces[0].accessConfigs[0].natIP)' 2>/dev/null; }
gssh () { ssh "${SSHO[@]}" "$VM_USER@$VM_IP" "$1"; }
gscp () { scp "${SSHO[@]}" "$@"; }        # caller uses $RVM:path

wait_ssh () { for _ in $(seq 1 30); do gssh 'true' 2>/dev/null && return 0; sleep 10; done; return 1; }

BOT="${BOT:-bot}"
OPPONENTS="${OPPONENTS:-examplefuncsplayer}"
MAPSET="${MAPSET:-loop}"

# A diverse subset for fast iteration (sizes 20-60, all symmetries).
LOOP_MAPS="maptestsmall eckleburg intersection colosseum chessboard maze sandwich \
jellyfish squer pillars highway fortress valley island_hopping"

if [ "${MAPS:-}" ]; then
  :
elif [ "$MAPSET" = "full" ]; then
  MAPS="$(tr '\n' ' ' < "$REPO/tools/bc22-maps.txt")"
else
  MAPS="$LOOP_MAPS"
fi

RUN_ID="$(date +%Y%m%d-%H%M%S)"
OUT="$REPO/gauntlet/$RUN_ID"
mkdir -p "$OUT/losses"
echo "opponent,map,bot_side,winner_side,bot_result" > "$OUT/results.csv"

echo "gauntlet run $RUN_ID"
echo "  bot=$BOT  opponents=[$OPPONENTS]  maps=$(echo $MAPS | wc -w)"

# make sure the VM is up and has our latest source
state=$(gcloud compute instances describe "$VM" --zone="$ZONE" --project="$PROJECT" --format='value(status)')
if [ "$state" != "RUNNING" ]; then
  echo "  starting VM ..."
  gcloud compute instances start "$VM" --zone="$ZONE" --project="$PROJECT" >/dev/null
fi
VM_IP="$(vm_ip)"; RVM="$VM_USER@$VM_IP"
[ -n "$VM_IP" ] || { echo "!! no external IP for $VM" >&2; exit 1; }
wait_ssh || { echo "!! cannot reach $RVM over ssh" >&2; exit 1; }
gssh "pkill -9 -f gauntlet_run.sh; pkill -9 -f battlecode.server; pkill -9 -f org.gradle; mkdir -p ~/battlecode22-scaffold/src" >/dev/null 2>&1 || true
gscp -r "$REPO/src/." "$RVM:battlecode22-scaffold/src/"

# Build a remote script and run it on the VM (scp + execute by path; stdin
# piping through `gcloud ssh --command` is unreliable).
#
# Speed: all maps go into ONE `./gradlew run` per side (the engine plays a
# comma-separated map list sequentially into one replay), so we pay JVM/gradle
# startup twice per opponent instead of 2*B times. Robot stdout is turned off
# (-PoutputVerbose=false) since we read instrumentation from the replay.
MAPLIST="$(echo $MAPS | tr ' ' ',')"
remote=$(mktemp)
cat > "$remote" <<REMOTE
set -uo pipefail
export JAVA_HOME=\$HOME/jdk8 PATH=\$HOME/jdk8/bin:\$PATH
cd ~/battlecode22-scaffold
./gradlew --no-daemon -q build >/dev/null 2>&1 || { echo "BUILD-FAILED"; exit 1; }
mkdir -p gauntlet
run_side () {  # <opp> <side> <teamA> <teamB>
  local OPP=\$1 SIDE=\$2 TA=\$3 TB=\$4 MAP=""
  ./gradlew --no-daemon -q run -PteamA=\$TA -PteamB=\$TB -Pmaps=$MAPLIST \
      -PoutputVerbose=false -Preplay=gauntlet/\${OPP}__bot\${SIDE}.bc22 2>&1 \
  | while IFS= read -r line; do
      case "\$line" in
        *" vs. "*" on "*) MAP="\${line##* on }" ;;
        *") wins (round"*)
          W=\$(printf '%s' "\$line" | sed -n 's/.*(\([AB]\)) wins.*/\1/p')
          RND=\$(printf '%s' "\$line" | sed -n 's/.*wins (round \([0-9]*\)).*/\1/p')
          echo "RESULT \$OPP \$MAP \$SIDE \${W:-?} \${RND:-?}" ;;
        *"Reason:"*) echo "REASON \$OPP \$MAP \$SIDE \${line#*Reason: }" ;;
      esac
    done
}
for OPP in $OPPONENTS; do
  run_side "\$OPP" A "$BOT" "\$OPP"
  run_side "\$OPP" B "\$OPP" "$BOT"
done
echo "GAUNTLET-COMPLETE"
REMOTE
gscp "$remote" "$RVM:gauntlet_run.sh"

# Launch the run DETACHED on the VM (a single long-held SSH connection is
# fragile over ~30 min), writing to ~/gauntlet_run.out; then poll with short
# SSH calls until the .done sentinel appears.
gssh "rm -f ~/gauntlet_run.out ~/gauntlet_run.done; setsid bash -c 'bash ~/gauntlet_run.sh > ~/gauntlet_run.out 2>&1; touch ~/gauntlet_run.done' </dev/null >/dev/null 2>&1 &" >/dev/null || true
gssh "sleep 1; test -f ~/gauntlet_run.out && echo started" >/dev/null || true

echo "  running $(echo $OPPONENTS | wc -w) opponent(s) x $(echo $MAPS | wc -w) maps x 2 sides on the VM (polling every 45s) ..."
parse_csv () {  # <run.out text>
  echo "opponent,map,bot_side,winner_side,rounds,bot_result" > "$OUT/results.csv"
  printf '%s\n' "$1" | grep '^RESULT ' | while read -r _ OPP MAP SIDE WIN RND; do
    if [ "$WIN" = "$SIDE" ]; then RES=win; elif [ "$WIN" = "?" ]; then RES=unknown; else RES=loss; fi
    echo "$OPP,$MAP,$SIDE,$WIN,${RND:-?},$RES"
  done >> "$OUT/results.csv"
  printf '%s\n' "$1" | grep '^REASON ' | sed 's/^REASON //' > "$OUT/reasons.txt" || true
}
deadline=$(( $(date +%s) + 75*60 ))
seen=0
while true; do
  sleep 45
  snap=$(gssh 'cat ~/gauntlet_run.out 2>/dev/null; echo "@@@"; test -f ~/gauntlet_run.done && echo DONE; pgrep -f gauntlet_run.sh >/dev/null && echo ALIVE') || { printf ' (ssh retry)\n'; continue; }
  body=${snap%%@@@*}; ctl=${snap#*@@@}
  printf '%s\n' "$body" > "$OUT/raw.log"
  n=$(printf '%s\n' "$body" | grep -c '^RESULT ' || true)
  if [ "$n" -gt "$seen" ]; then
    printf '%s\n' "$body" | grep '^RESULT ' | tail -n +"$((seen+1))" | while read -r _ OPP MAP SIDE WIN RND; do
      [ "$WIN" = "$SIDE" ] && r=win || { [ "$WIN" = "?" ] && r=unknown || r=loss; }
      printf '  %-20s %-16s bot=%s -> %-4s (r%s)\n' "$OPP" "$MAP" "$SIDE" "$r" "${RND:-?}"
    done
    seen=$n
  fi
  case "$ctl" in *DONE*) echo "  gauntlet complete ($n games)"; parse_csv "$body"; break ;; esac
  if ! printf '%s\n' "$ctl" | grep -q ALIVE; then
    echo "!! remote run is not alive and not done -- see $OUT/raw.log" >&2
    printf '%s\n' "$body" | tail -15 >&2; parse_csv "$body"; break
  fi
  [ "$(date +%s)" -gt "$deadline" ] && { echo "!! poll deadline hit" >&2; parse_csv "$body"; break; }
done

if [ "$(wc -l < "$OUT/results.csv")" -le 1 ]; then
  echo "!! no game results -- see $OUT/raw.log" >&2
  exit 1
fi
grep -q 'BUILD-FAILED' "$OUT/raw.log" && { echo "!! remote build failed" >&2; exit 1; }

# each side's games are in one multi-match replay (match index = position in the
# map list, 1-based); pull the replays for any side that had a loss.
i=1; for m in $MAPS; do echo "$i $m" >> "$OUT/map-index.txt"; i=$((i+1)); done
awk -F, '$6=="loss"{print $1"__bot"$3".bc22"}' "$OUT/results.csv" | sort -u | while read -r k; do
  [ -n "$k" ] || continue
  gscp "$RVM:battlecode22-scaffold/gauntlet/$k" "$OUT/losses/" || true
done

# summary
{
  total=$(($(wc -l < "$OUT/results.csv") - 1))
  wins=$(grep -c ',win$' "$OUT/results.csv" || true)
  losses=$(grep -c ',loss$' "$OUT/results.csv" || true)
  unknown=$(grep -c ',unknown$' "$OUT/results.csv" || true)
  echo "run $RUN_ID   bot=$BOT"
  pct=$(awk -v w="$wins" -v t="$total" 'BEGIN{printf (t>0)?"%.1f":"0", (t>0)?100*w/t:0}')
  echo "overall: $wins/$total wins (${pct}%)  losses=$losses unknown=$unknown"
  echo
  for OPP in $OPPONENTS; do
    t=$(grep -c "^$OPP," "$OUT/results.csv" || true)
    w=$(grep -c "^$OPP,.*,win$" "$OUT/results.csv" || true)
    op=$(awk -v w="$w" -v t="$t" 'BEGIN{printf "%.0f", (t>0)?100*w/t:0}')
    printf '  vs %-20s %s/%s (%s%%)\n' "$OPP" "$w" "$t" "$op"
  done
  echo
  echo "losses:"
  awk -F, '$6=="loss"{printf "  %s on %s (bot was %s)\n",$1,$2,$3}' "$OUT/results.csv"
} | tee "$OUT/summary.txt"

echo
echo "wrote $OUT/  (results.csv, summary.txt, losses/)"
rm -f "$remote"
