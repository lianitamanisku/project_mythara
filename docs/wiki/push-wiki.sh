#!/usr/bin/env bash
# Push everything in docs/wiki/ to the GitHub wiki repo.
#
# GitHub wikis live in a separate git repository at
# https://github.com/<owner>/<repo>.wiki.git. That repo doesn't exist
# until you initialise the wiki via the web UI — one click on the
# "Create the first page" button in the Wiki tab, save anything,
# done. After that this script can mirror docs/wiki/ → the wiki.
#
# Usage:
#   1. Visit https://github.com/ankurCES/project_mythara/wiki and
#      click "Create the first page". Save it with any content
#      (it'll be overwritten).
#   2. Run this script from the repo root:
#        ./docs/wiki/push-wiki.sh
#   3. Refresh the wiki tab — all pages should be live.

set -euo pipefail

OWNER="ankurCES"
REPO="project_mythara"
WIKI_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "==> Cloning wiki repo..."
if ! git clone --depth 1 "https://github.com/${OWNER}/${REPO}.wiki.git" "$TMP_DIR/wiki" 2>/dev/null; then
    cat <<EOF >&2

ERROR: wiki repo not initialised yet.

Bootstrap first:
  1. Visit https://github.com/${OWNER}/${REPO}/wiki
  2. Click "Create the first page"
  3. Save any content (it'll be overwritten by this script)
  4. Re-run this script

EOF
    exit 1
fi

echo "==> Mirroring docs/wiki/*.md → wiki..."
cd "$TMP_DIR/wiki"

# Copy every .md from docs/wiki to the wiki repo root
cp "${WIKI_DIR}"/*.md .

git add -A
if git diff --staged --quiet ; then
    echo "==> No changes; wiki is already up to date."
    exit 0
fi

git -c user.name="Ankur Nair" -c user.email="ankur.nairit@gmail.com" \
    commit -m "Sync wiki from docs/wiki/" -q

echo "==> Pushing..."
git push -q

echo "==> Done. Visit https://github.com/${OWNER}/${REPO}/wiki"
