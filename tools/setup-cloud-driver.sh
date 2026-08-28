#!/usr/bin/env bash
# Provision a GCE "driver" VM that runs the Claude Code training loop unattended,
# so it no longer depends on the laptop being awake. The driver only orchestrates
# -- the heavy compute (builds, gauntlets) still runs on `battlecode-dev`, which
# the driver starts/stops and SSHes into exactly as the laptop did.
#
# Run this FROM THE LAPTOP (it needs the local ~/.ssh/google_compute_engine key
# and the local ~/.claude memory dir). Safe to re-run.
#
#   tools/setup-cloud-driver.sh
#
# After it finishes, see CLOUD_DRIVER.md for the manual steps (auth + start loop).
set -euo pipefail

PROJECT=tvanbelle-vibecode
ZONE=us-west1-b
DRIVER=claude-driver
MACHINE=e2-small                                # 2 vCPU / 2 GB -- resize if tight
PROJ_PATH=/Users/terryvanbelle/projects/vibe    # identical to laptop -> the
                                                # ~/.claude memory dir name matches
BC_SSH_USER=terryvanbelle                       # our key's username on battlecode-dev
REPO_URL=https://github.com/terryvanbelle/battlecode22-vibe.git

g_ssh () { gcloud compute ssh "$DRIVER" --zone="$ZONE" --project="$PROJECT" --command "$1"; }
g_scp () { gcloud compute scp --zone="$ZONE" --project="$PROJECT" "$@"; }

echo "== 1/6  create $DRIVER ($MACHINE) if absent =="
if ! gcloud compute instances describe "$DRIVER" --zone="$ZONE" --project="$PROJECT" >/dev/null 2>&1; then
  gcloud compute instances create "$DRIVER" \
    --project="$PROJECT" --zone="$ZONE" --machine-type="$MACHINE" \
    --image-family=debian-12 --image-project=debian-cloud \
    --boot-disk-size=30GB --boot-disk-type=pd-balanced \
    --scopes=cloud-platform --labels=role=claude-driver
else
  echo "   already exists"
  gcloud compute instances start "$DRIVER" --zone="$ZONE" --project="$PROJECT" >/dev/null 2>&1 || true
fi

echo "== 2/6  wait for ssh =="
for _ in $(seq 1 40); do g_ssh true 2>/dev/null && break; sleep 5; done

echo "== 3/6  base packages + Node + Claude Code =="
g_ssh '
  set -e
  sudo apt-get update -qq
  sudo apt-get install -y -qq git tmux python3-venv jq >/dev/null
  if ! command -v node >/dev/null; then
    curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash - >/dev/null 2>&1
    sudo apt-get install -y -qq nodejs >/dev/null
  fi
  sudo npm i -g @anthropic-ai/claude-code >/dev/null 2>&1
  node --version; claude --version || true
'

echo "== 4/6  clone repo to $PROJ_PATH + python venv + BC_SSH_USER =="
g_ssh "
  set -e
  sudo mkdir -p $(dirname "$PROJ_PATH")
  sudo chown -R \$(whoami): $(dirname "$PROJ_PATH")
  [ -d $PROJ_PATH/.git ] || git clone $REPO_URL $PROJ_PATH
  cd $PROJ_PATH && git pull --ff-only || true
  python3 -m venv tools/.venv
  tools/.venv/bin/pip -q install flatbuffers numpy
  tools/.venv/bin/python tools/test_bc22_replay.py 2>&1 | tail -3
  grep -q BC_SSH_USER ~/.bashrc || echo 'export BC_SSH_USER=$BC_SSH_USER' >> ~/.bashrc
"

echo "== 5/6  copy the battlecode-dev ssh key =="
g_scp "$HOME/.ssh/google_compute_engine" "$HOME/.ssh/google_compute_engine.pub" "$DRIVER":'~/.ssh/'
g_ssh 'chmod 600 ~/.ssh/google_compute_engine'

echo "== 6/6  copy the ~/.claude memory dir =="
MEM="$HOME/.claude/projects/-Users-terryvanbelle-projects-vibe"
g_ssh 'mkdir -p ~/.claude/projects/-Users-terryvanbelle-projects-vibe'
g_scp --recurse "$MEM/memory" "$DRIVER":'~/.claude/projects/-Users-terryvanbelle-projects-vibe/'
if [ -f "$PROJ_PATH/.claude/settings.local.json" ]; then
  g_ssh "mkdir -p $PROJ_PATH/.claude"
  g_scp "$PROJ_PATH/.claude/settings.local.json" "$DRIVER":"$PROJ_PATH/.claude/"
fi

echo
echo "DONE.  Manual steps next -- see CLOUD_DRIVER.md:"
echo "  gcloud compute ssh $DRIVER --zone=$ZONE --project=$PROJECT"
echo "  then:  claude   (authenticate)   ;   tmux new -s train ; cd $PROJ_PATH ; claude"
