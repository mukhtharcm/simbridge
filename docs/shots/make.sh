#!/bin/sh
# Remakes the README pictures from the web client with made-up data: docs/hero.png, screens.png and how.png.
# Needs Google Chrome. Usage: docs/shots/make.sh
set -eu
cd "$(dirname "$0")"
CHROME=${CHROME:-"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"}
T=$(mktemp -d)
trap 'kill $SERVER 2>/dev/null; rm -rf "$T"' EXIT

cp -R ../../client/. "$T/" && rm "$T/sw.js" && cp frame.html "$T/"
python3 - "$T/index.html" demo.js <<'EOF'
import sys
page, demo = sys.argv[1], open(sys.argv[2]).read()
s = open(page).read().replace("if ('serviceWorker' in navigator) {", "if (false) {")
open(page, 'w').write(s.replace('</body>', f'<script>\n{demo}</script>\n</body>'))
EOF
python3 -m http.server 8799 --bind 127.0.0.1 --directory "$T" >/dev/null 2>&1 & SERVER=$!
sleep 1
for shot in hero:900 screens:830 how:560; do
    size=${shot#*:} shot=${shot%:*}
    "$CHROME" --headless=new --hide-scrollbars --user-data-dir="$T/chrome" --force-device-scale-factor=2 \
        --window-size=1600,$size --virtual-time-budget=5000 --screenshot="$PWD/../$shot.png" \
        "http://127.0.0.1:8799/frame.html#$shot" 2>/dev/null
done
ls -la ../hero.png ../screens.png ../how.png
