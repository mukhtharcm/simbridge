#!/bin/sh
# Remakes the README pictures from the web client with made-up data: docs/hero.png, screens.png and how.png,
# and the stills the video uses (docs/video/assets).
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
V=$PWD/../video/assets && mkdir -p "$V/fonts" && cp ../../client/bricolage-grotesque.woff2 ../../client/icon-512.png "$V/fonts/"
mv "$V/fonts/icon-512.png" "$V/"
shoot() { # name width height url
    "$CHROME" --headless=new --hide-scrollbars --user-data-dir="$T/chrome" --force-device-scale-factor=2 \
        --default-background-color=00000000 --window-size=$2,$3 --virtual-time-budget=5000 \
        --screenshot="$V/$1.png" "http://127.0.0.1:8799/$4" 2>/dev/null
}
for n in 3 4 5; do shoot desk-$n 1180 760 "index.html?n=$n#thread"; done
shoot phone-ringing 410 864 'frame.html#phone:ringing'
shoot phone-call 410 864 'frame.html#phone:%3Ft%3D4%23call'
ls -la ../hero.png ../screens.png ../how.png "$V"
