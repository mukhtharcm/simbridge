#!/bin/sh
# Build, install and set up simbridge. Usage: scripts/phone.sh <command>
#
#   build          runs the tests, builds the app and tinymix, and makes build/simbridge-magisk.zip
#   install [zip]  installs the Magisk module (this build, or a downloaded release); reboot afterwards
#   on             after that reboot: permissions, root, the API's address and token, phone and SMS app
#   update         puts a new build over the installed one, without a reboot
#   off            gives calls and texts back to the phone's own apps
#   web            copies the web client to WEB, an rsync destination
#   log            follows simbridge's log
#
# The phone is reached with adb, or with PHONE=<ssh host> over ssh to a shell where `su` works.
# Settings live in secrets/api.env; see the README.
set -eu
cd "$(dirname "$0")/.."
[ -f secrets/api.env ] && . ./secrets/api.env

PHONE=${PHONE:-adb}
OLD_DIALER=${OLD_DIALER:-com.android.dialer}
OLD_SMS=${OLD_SMS:-com.android.messaging}
PKG=io.github.mukhtharcm.simbridge
ZIP=build/simbridge-magisk.zip
TMP=/data/local/tmp

# root runs stdin as a root shell script on the phone; put copies a file there as $TMP/sb-<name>.
if [ "$PHONE" = adb ]; then
    root() { adb shell su -c sh; }
    put() { adb push "$1" "$TMP/sb-$(basename "$1")" >/dev/null; }
    logs() { adb logcat -b main -v time -s simbridge; }
else
    root() { ssh "$PHONE" 'su -c sh'; }
    put() { ssh "$PHONE" "su -c 'cat > $TMP/sb-$(basename "$1")'" < "$1"; }
    logs() { ssh "$PHONE" "su -c 'logcat -b main -v time -s simbridge'"; }
fi

case "${1:-}" in
build)
    JAVA_HOME=${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home} \
        ./gradlew -q testReleaseUnitTest assembleRelease
    rm -rf build/module && mkdir -p build/module/system/priv-app/Simbridge
    cp -R magisk/. build/module/
    (cd tools/tinymix && GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o ../../build/module/tinymix .)
    cp app/build/outputs/apk/release/app-release.apk build/module/system/priv-app/Simbridge/Simbridge.apk
    rm -f "$ZIP" && (cd build/module && zip -qr "../simbridge-magisk.zip" .)
    echo "built $ZIP"
    ;;
install)
    Z=${2:-$ZIP}
    put "$Z"
    root <<EOF
magisk --install-module $TMP/sb-$(basename "$Z")
rm $TMP/sb-$(basename "$Z")
EOF
    echo "installed; reboot the phone, then run: scripts/phone.sh on"
    ;;
update)
    put app/build/outputs/apk/release/app-release.apk
    put build/module/tinymix
    put build/module/service.sh
    root <<EOF
set -e
M=/data/adb/modules/simbridge
cp $TMP/sb-tinymix \$M/tinymix
cp $TMP/sb-service.sh \$M/service.sh
chown root:root \$M/tinymix \$M/service.sh && chmod 755 \$M/tinymix \$M/service.sh
pm install -r $TMP/sb-app-release.apk
cp $TMP/sb-app-release.apk \$M/system/priv-app/Simbridge/Simbridge.apk
rm $TMP/sb-tinymix $TMP/sb-service.sh $TMP/sb-app-release.apk
dumpsys package $PKG | grep -m1 versionName
EOF
    ;;
on)
    # BIND is the phone address the API listens on: 127.0.0.1 for a web server on the phone to pass
    # through, or the phone's VPN address. TOKEN is made here the first time.
    mkdir -p secrets
    if [ ! -f secrets/api.env ]; then
        printf 'BIND=127.0.0.1\nPORT=8088\nTOKEN=%s\n' "$(openssl rand -hex 24)" > secrets/api.env
        . ./secrets/api.env
    fi
    chmod 600 secrets/api.env
    root <<EOF
set -e
pm path $PKG >/dev/null || { echo "simbridge is not installed; did the phone reboot after install?"; exit 1; }
for p in RECORD_AUDIO READ_PHONE_STATE ANSWER_PHONE_CALLS CALL_PHONE POST_NOTIFICATIONS READ_SMS SEND_SMS RECEIVE_SMS READ_CALL_LOG READ_CONTACTS WRITE_CONTACTS; do
    pm grant $PKG android.permission.\$p
done
appops set --uid $PKG RECORD_AUDIO allow
uid=\$(stat -c %u /data/user/0/$PKG)
if magisk --sqlite "SELECT policy FROM policies WHERE uid=\$uid" | grep -q policy; then
    magisk --sqlite "UPDATE policies SET policy=2, until=0 WHERE uid=\$uid"
else
    magisk --sqlite "INSERT INTO policies (uid,policy,until,logging,notification) VALUES (\$uid,2,0,1,1)"
fi
mkdir -p /data/user/0/$PKG/files
chown \$uid:\$uid /data/user/0/$PKG/files
chmod 771 /data/user/0/$PKG/files
restorecon /data/user/0/$PKG/files
printf '{"bind":"%s","port":%s,"token":"%s"}' "$BIND" "$PORT" "$TOKEN" > /data/user/0/$PKG/files/config.json
chown \$uid:\$uid /data/user/0/$PKG/files/config.json
chmod 600 /data/user/0/$PKG/files/config.json
restorecon /data/user/0/$PKG/files/config.json
am force-stop $PKG
cmd role add-role-holder android.app.role.DIALER $PKG
cmd role add-role-holder android.app.role.SMS $PKG
am start -n $PKG/.MainActivity >/dev/null
sleep 2
input keyevent KEYCODE_HOME
echo "phone app is now: \$(cmd role get-role-holders android.app.role.DIALER)"
echo "SMS app is now: \$(cmd role get-role-holders android.app.role.SMS)"
EOF
    ;;
off)
    # OLD_DIALER and OLD_SMS name the apps that take calls and texts back.
    root <<EOF
cmd role add-role-holder android.app.role.DIALER $OLD_DIALER
cmd role add-role-holder android.app.role.SMS $OLD_SMS || echo "could not hand SMS to $OLD_SMS; set OLD_SMS to your SMS app"
echo "phone app is now: \$(cmd role get-role-holders android.app.role.DIALER)"
echo "SMS app is now: \$(cmd role get-role-holders android.app.role.SMS)"
EOF
    ;;
web)
    [ -n "${WEB:-}" ] || { echo "set WEB in secrets/api.env first"; exit 1; }
    rsync -a --delete client/ "$WEB"
    echo "client copied to $WEB"
    ;;
log)
    logs
    ;;
*)
    sed -n '2,13p' "$0"; exit 1
    ;;
esac
