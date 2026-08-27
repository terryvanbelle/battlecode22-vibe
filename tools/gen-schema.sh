#!/usr/bin/env bash
# Regenerate tools/bc22_schema.py from the Battlecode 2022 FlatBuffers schema.
# Requires flatc (brew install flatbuffers).
set -euo pipefail
cd "$(dirname "$0")"

SCHEMA_URL="https://raw.githubusercontent.com/battlecode/battlecode22/main/schema/battlecode.fbs"

echo "fetching $SCHEMA_URL"
curl -sSL -o battlecode.fbs "$SCHEMA_URL"

command -v flatc >/dev/null || { echo "flatc not found: brew install flatbuffers"; exit 1; }

tmp="$(mktemp -d)"
flatc --python --gen-onefile -o "$tmp" battlecode.fbs
cp "$tmp/battlecode_generated.py" bc22_schema.py
rm -rf "$tmp"
echo "wrote bc22_schema.py ($(flatc --version))"
