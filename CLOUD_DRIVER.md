# Running the training loop on GCloud (not the laptop)

The Claude Code training loop can run on a small always-on GCE VM (`claude-driver`)
instead of the laptop, so it keeps going when the laptop is closed. The driver
only orchestrates; the heavy compute still runs on `battlecode-dev`, which the
driver starts/stops and SSHes into exactly as the laptop did.

## Architecture

```
  claude-driver (e2-small, always on, ~$12/mo)
    └─ runs `claude` in tmux  ──►  git repo at /Users/terryvanbelle/projects/vibe
                                   ~/.claude/... memory (copied from laptop)
                                   ~/.ssh/google_compute_engine (copied from laptop)
                                   BC_SSH_USER=terryvanbelle
    └─ gcloud (VM service account, cloud-platform scope, project editor role)
         starts/stops ──►  battlecode-dev (e2-standard-8, on only during gauntlets)
```

## One-time setup

### 1. Provision (from the laptop)

```
tools/setup-cloud-driver.sh
```

Creates the VM, installs Node + Claude Code + the python venv, clones the repo,
and copies the SSH key and the `~/.claude` memory dir over. Idempotent.

### 2. Authenticate Claude Code (on the driver)

```
gcloud compute ssh claude-driver --zone=us-west1-b --project=tvanbelle-vibecode
claude          # first run: pick auth
```

- **Subscription (recommended):** choose the login option; it prints a URL,
  open it in your laptop browser, approve, paste the code back. One time.
- **API key:** `export ANTHROPIC_API_KEY=sk-ant-...` in `~/.bashrc` instead.
  This bills pay-as-you-go, separate from a Claude subscription.

Type `/exit` once you're authenticated.

### 3. Start the loop (on the driver, in tmux)

```
tmux new -s train
cd /Users/terryvanbelle/projects/vibe
claude
```

Then in Claude: paste the standing prompt

> Follow the steps in TRAINING_ALGORITHM.md to start generating a contest-winning bot for Battlecode 2022

and let it run. Detach with **Ctrl-b d**. The loop keeps running.

`TRAINING_LOG.md` is the running record — a fresh session picks up mid-iteration
from it, so nothing is lost by starting over here rather than migrating the
laptop's session.

## Day to day

| | |
|---|---|
| check in | `gcloud compute ssh claude-driver ... ` then `tmux attach -s train` |
| detach | Ctrl-b d |
| stop the loop | attach, Ctrl-C, `/exit` |
| the driver survives reboots? | the tmux session does not — re-run step 3 after a driver reboot |
| stop paying for the driver | `gcloud compute instances stop claude-driver --zone=us-west1-b --project=tvanbelle-vibecode` (loop pauses until restarted) |
| resize the driver | stop it, `gcloud compute instances set-machine-type claude-driver --machine-type=e2-medium ...`, start |

## Notes / caveats

- **No live handoff of the laptop's current Claude session.** The driver starts a
  fresh session that reads `TRAINING_LOG.md` + memory. Some in-flight nuance is
  lost; the log carries the substance.
- **`git` on the driver:** the loop commits to `main` and pushes. The driver's
  git needs push creds — the repo is cloned over HTTPS, so either use a GitHub
  PAT (`git config credential.helper store` + one manual push) or switch the
  remote to SSH with a deploy key. Until then the loop can commit locally; push
  manually.
- **Both VMs cost money when running.** The driver is small (~$12/mo). Keep
  `battlecode-dev` stopped between gauntlets (the loop already does this).
- **Secrets on the driver:** it holds the `google_compute_engine` SSH key and
  (if used) the Anthropic API key. It's a private VM in your project; treat it
  accordingly. Prefer the VM service account over copying `~/.config/gcloud`.
