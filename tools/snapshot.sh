#!/usr/bin/env bash
# Snapshot the current bot (src/bot/) into the Gauntlet as an opponent
# (TRAINING_ALGORITHM.md step 3: "add it to the Gauntlet").
#
#   tools/snapshot.sh g_iter1
#
# Copies src/bot/*.java to src/<name>/ with `package bot;` rewritten to
# `package <name>;`. The scaffold then runs it by that package name.
set -euo pipefail
cd "$(dirname "$0")/.."

NAME="${1:?usage: tools/snapshot.sh <package-name>}"
case "$NAME" in
  bot|examplefuncsplayer) echo "refusing to overwrite $NAME" >&2; exit 1 ;;
  *[!a-z0-9_]*)           echo "name must be [a-z0-9_]+" >&2; exit 1 ;;
esac

DEST="src/$NAME"
[ -e "$DEST" ] && { echo "$DEST already exists" >&2; exit 1; }
mkdir -p "$DEST"
for f in src/bot/*.java; do
  sed 's/^package bot;/package '"$NAME"';/' "$f" > "$DEST/$(basename "$f")"
done
echo "snapshotted src/bot/ -> $DEST/ (package $NAME)"
echo "add \"$NAME\" to the OPPONENTS list when running tools/gauntlet.sh"
