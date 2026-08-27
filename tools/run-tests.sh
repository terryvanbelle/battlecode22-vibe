#!/usr/bin/env bash
# Run the bc22_replay.py test suite.
set -euo pipefail
cd "$(dirname "$0")"

PY=".venv/bin/python"
[ -x "$PY" ] || PY="python3"

exec "$PY" test_bc22_replay.py "$@"
