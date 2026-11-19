#!/system/bin/sh
# Opens simbridge's screen once after boot. Android gives a background service the microphone only
# if the app was on screen when the service started, and a service started by the boot broadcast
# alone is not. The home key then puts the screen away again.
PKG=io.github.mukhtharcm.simbridge
until [ "$(getprop sys.boot_completed)" = 1 ]; do sleep 5; done
sleep 20
am start -n $PKG/.MainActivity >/dev/null 2>&1
sleep 3
input keyevent KEYCODE_HOME
