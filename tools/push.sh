#!/data/data/com.termux/files/usr/bin/bash
# Usage: bash push.sh [zip_path] [repo_dir]
# Unzips the project zip over your repo, commits and pushes (GitHub Actions then builds the APK).
set -euo pipefail
ZIP="${1:-}"
if [ -z "$ZIP" ]; then
  ZIP="$(ls -t "$HOME"/storage/downloads/stark-family-*.zip 2>/dev/null | head -1 || true)"
fi
REPO="${2:-$HOME/stark-family2}"
pkg install -y git unzip >/dev/null 2>&1 || true
[ -n "$ZIP" ] && [ -f "$ZIP" ] || { echo "ZIP नहीं मिला। पहले termux-setup-storage चलाइए, फिर zip को Downloads में रखिए, या पूरा रास्ता दीजिए।"; exit 1; }
echo "ZIP: $ZIP"
if [ ! -d "$REPO/.git" ]; then
  read -r -p "GitHub repo का URL (https://github.com/<user>/<repo>.git): " URL
  git clone "$URL" "$REPO"
fi
cd "$REPO"
TMP="$(mktemp -d)"; unzip -oq "$ZIP" -d "$TMP"
# new files overwrite old ones; your debug.keystore and files not in the zip stay untouched
cp -a "$TMP"/. "$REPO"/; rm -rf "$TMP"
git config user.name >/dev/null 2>&1 || git config user.name "stark-user"
git config user.email >/dev/null 2>&1 || git config user.email "stark-user@users.noreply.github.com"
git checkout -B main >/dev/null 2>&1 || true
git add -A
if git diff --cached --quiet; then echo "कुछ नया नहीं।"; else git commit -m "Update: $(basename "$ZIP" .zip) $(date +%F_%H%M)"; fi
git push -u origin main
echo "✅ Push हो गया। GitHub > Actions में APK build देखिए; relay बदला है तो Render अपने-आप redeploy करेगा।"
