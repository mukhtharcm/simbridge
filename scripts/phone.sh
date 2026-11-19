#!/bin/sh
# Build, install and drive simbridge on the phone. Usage: scripts/phone.sh <build|install|update|on|off|log>
#
# PHONE is an ssh host that lands in a shell where `su` works (Termux sshd on the phone).
# OLD_DIALER is the phone app that gets calls back when simbridge is switched off.
set -eu
cd "$(dirname "$0")/.."

PHONE=${PHONE:-moto}
OLD_DIALER=${OLD_DIALER:-com.callagent.gateway}
PKG=io.github.mukhtharcm.simbridge
ZIP=build/simbridge-magisk.zip

# Runs stdin as a root shell script on the phone.
root() { ssh "$PHONE" 'su -c sh'; }

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
    # Installs the module. It takes effect at the next reboot, which this does not do.
    scp -q "$ZIP" "$PHONE:simbridge-magisk.zip"
    root <<EOF
cp /data/data/com.termux/files/home/simbridge-magisk.zip /data/local/tmp/simbridge-magisk.zip
magisk --install-module /data/local/tmp/simbridge-magisk.zip
rm /data/local/tmp/simbridge-magisk.zip
EOF
    echo "installed; reboot the phone, then run: scripts/phone.sh on"
    ;;
update)
    # A new build over the installed one. No reboot: same package, same key.
    scp -q app/build/outputs/apk/release/app-release.apk "$PHONE:simbridge.apk"
    scp -q build/module/tinymix build/module/service.sh "$PHONE:"
    root <<EOF
set -e
M=/data/adb/modules/simbridge
cp /data/data/com.termux/files/home/tinymix /data/data/com.termux/files/home/service.sh \$M/
chown root:root \$M/tinymix \$M/service.sh && chmod 755 \$M/tinymix \$M/service.sh
rm /data/data/com.termux/files/home/tinymix /data/data/com.termux/files/home/service.sh
cp /data/data/com.termux/files/home/simbridge.apk /data/local/tmp/simbridge.apk
pm install -r /data/local/tmp/simbridge.apk
cp /data/local/tmp/simbridge.apk /data/adb/modules/simbridge/system/priv-app/Simbridge/Simbridge.apk
rm /data/local/tmp/simbridge.apk
dumpsys package $PKG | grep -m1 versionName
EOF
    ;;
on)
    # Permissions, root, the API's address and token, and make simbridge the phone app.
    # secrets/api.env holds BIND, PORT and TOKEN. BIND is one address of the phone;
    # 127.0.0.1 keeps the API on the phone itself.
    mkdir -p secrets
    [ -f secrets/api.env ] ||
        printf 'BIND=127.0.0.1\nPORT=8088\nTOKEN=%s\n' "$(openssl rand -hex 24)" > secrets/api.env
    chmod 600 secrets/api.env
    . ./secrets/api.env
    root <<EOF
set -e
pm path $PKG >/dev/null || { echo "simbridge is not installed; did the phone reboot after install?"; exit 1; }
for p in RECORD_AUDIO READ_PHONE_STATE ANSWER_PHONE_CALLS CALL_PHONE POST_NOTIFICATIONS READ_SMS SEND_SMS RECEIVE_SMS; do
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
    # Gives calls back. SMS stays with simbridge; see the README's Undo to hand that back too.
    root <<EOF
cmd role add-role-holder android.app.role.DIALER $OLD_DIALER
echo "phone app is now: \$(cmd role get-role-holders android.app.role.DIALER)"
EOF
    ;;
log)
    # Main buffer only: the kernel buffer on some phones is flooded and slow to read.
    ssh "$PHONE" "su -c 'logcat -b main -v time -s simbridge'"
    ;;
*)
    sed -n '2,5p' "$0"; exit 1
    ;;
esac
