#!/bin/bash
# v2 end-to-end on the emulator (x86_64 test APK): open the app, then through the core's RPC
# add an offline account, install vanilla, launch it on the runtime module. Screens + logs.
set -x
PKG=cn.eririlily.launcher
GAME=${GAME:-1.20.1}
OUT=shots; mkdir -p $OUT
n=0
shot(){ n=$((n+1)); f=$(printf "%02d_%s" $n "$1"); adb exec-out screencap -p > $OUT/$f.png; }
adb root; sleep 3
adb shell "setprop persist.sys.locale zh-CN; setprop ctl.restart zygote"; sleep 25
adb wait-for-device; adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb shell settings put secure immersive_mode_confirmations confirmed
adb install -r -g apk/*.apk > $OUT/install.txt 2>&1
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 20; shot home

# the core prints its address + token
URL=""
for i in $(seq 1 30); do URL=$(adb logcat -d | grep -o 'EririLily UI: http://127.0.0.1:[0-9]*/#[0-9a-f]*' | tail -1 | sed 's/EririLily UI: //'); [ -n "$URL" ] && break; sleep 2; done
echo "$URL" > $OUT/url.txt
PORT=$(echo "$URL" | sed -E 's#.*:([0-9]+)/.*#\1#'); TOKEN=${URL##*#}
adb forward tcp:$PORT tcp:$PORT
rpc(){ curl -s -m 60 -X POST -H "X-Token: $TOKEN" -H 'Content-Type: application/json' -d "${2:-{\}}" "http://127.0.0.1:$PORT/api/$1"; echo; }
rpc state > $OUT/state0.json
rpc addOffline '{"name":"Steve"}' >> $OUT/rpc.txt
rpc createInstance "{\"game\":\"$GAME\"}" >> $OUT/rpc.txt
# wait for the install (busy goes false and the instance shows up)
for i in $(seq 1 90); do
  rpc state > $OUT/state.json
  python3 - "$OUT/state.json" <<'P' && break
import json,sys
s=json.load(open(sys.argv[1]))
print("busy",s.get("busy"),"instances",[i.get("id") for i in s.get("instances") or []])
sys.exit(0 if not s.get("busy") and s.get("instances") else 1)
P
  sleep 10
done
shot installed
ID=$(python3 -c "import json;s=json.load(open('$OUT/state.json'));print(s['config']['selected'])")
rpc launch "{\"id\":\"$ID\"}" >> $OUT/rpc.txt
for i in 1 2 3 4 5 6 7 8 9; do sleep 20; shot "game_$i"; done
rpc logs > $OUT/core_logs.json
adb pull /data/data/$PKG/files/EririLily/launch $OUT/launch > /dev/null 2>&1
adb shell ps -A > $OUT/ps.txt
adb logcat -d > $OUT/logcat_full.txt
grep -iE "eririlily|AndroidRuntime|FATAL|F DEBUG|pojav|ZLBridge|EGLBridge|LWJGL|jvm" $OUT/logcat_full.txt | tail -500 > $OUT/logcat.txt
