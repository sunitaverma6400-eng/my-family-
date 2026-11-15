#!/data/data/com.termux/files/usr/bin/bash
# Usage: bash push_s1.sh [zip_path] [repo_dir]
set -euo pipefail
ZIP="${1:-$HOME/storage/downloads/stark-family-s1.zip}"
REPO="${2:-$HOME/stark-family2}"
pkg install -y git unzip >/dev/null 2>&1 || true
[ -f "$ZIP" ] || { echo "ZIP नहीं मिला: $ZIP (पहले termux-setup-storage चलाइए)"; exit 1; }
if [ ! -d "$REPO/.git" ]; then
  read -r -p "नए GitHub repo का URL (https://github.com/<user>/<repo>.git): " URL
  git clone "$URL" "$REPO"
fi
cd "$REPO"
TMP="$(mktemp -d)"; unzip -oq "$ZIP" -d "$TMP"; cp -a "$TMP"/. "$REPO"/; rm -rf "$TMP"
git config user.name >/dev/null 2>&1 || git config user.name "stark-user"
git config user.email >/dev/null 2>&1 || git config user.email "stark-user@users.noreply.github.com"
git checkout -B main
git add -A
if git diff --cached --quiet; then echo "कुछ नया नहीं।"; else git commit -m "S1: relay + /test page + /diag"; fi
git push -u origin main
echo "✅ Push हो गया। अब Render में इस repo से Blueprint बनाइए (SETUP.md देखिए)।"
