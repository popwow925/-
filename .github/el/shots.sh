#!/bin/bash
# rerun 6: cdp suppress_origin
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
# record the core's events (stages, errors, game log lines)
curl -s -N "http://127.0.0.1:$PORT/api/events?t=$TOKEN" > $OUT/events.txt 2>&1 &
EVPID=$!
rpc setConfig '{"source":"official","threads":8}' >> $OUT/rpc.txt
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
# the launcher's own screens, in each theme: tap the dock tabs
SZ=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1); W=${SZ%x*}; H=${SZ#*x}
DEN=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
python3 - "$W" "$H" "$DEN" > $OUT/tabs.txt <<'P'
import sys
W,H,D=int(sys.argv[1]),int(sys.argv[2]),int(sys.argv[3])/160
dw=min(380*D,W-40*D); x0=W/2-dw/2; y=H-24*D-14*D-33*D
print(" ".join(f"{int(x0+dw*(i+.5)/4)},{int(y)}" for i in range(4)))
P
TABS=($(cat $OUT/tabs.txt))
ID0=$(python3 -c "import json;s=json.load(open('$OUT/state.json'));print(s['config']['selected'])")
for theme in sakura white black; do
  rpc setConfig "{\"theme\":\"$theme\"}" >> $OUT/rpc.txt
  rpc setConfig '{"perfHud":"fps"}' >> $OUT/rpc.txt
  sleep 3
  i=0
  for t in home library discover me; do
    adb shell input tap $(echo ${TABS[$i]} | tr ',' ' '); sleep 3; shot "ui_${theme}_$t"; i=$((i+1))
  done
done
rpc setConfig '{"theme":"sakura"}' >> $OUT/rpc.txt
adb shell input tap $(echo ${TABS[0]} | tr ',' ' '); sleep 2
# drive the web UI over DevTools (test builds only) for more states
pip install -q websocket-client >/dev/null 2>&1
APID=$(adb shell pidof $PKG | awk '{print $1}')
adb forward tcp:9222 localabstract:webview_devtools_remote_$APID
js(){ python3 .github/el/cdp.py "$1" >> $OUT/cdp.txt 2>&1; sleep "${2:-2}"; }
js "setConfig({theme:'sakura'}).then(()=>applyTheme('sakura'))" 2
js "push(instancePage('$ID0'))" 3; shot detail
js "while(stack.length) pop()" 1
js "optimizeSheet('$ID0')" 4; shot optimize
js "elBack()" 1
js "newInstanceSheet()" 5; shot newinstance
js "elBack()" 1
js "push(perfPage())" 3; shot perf
js "rendererSheet({refresh(){}})" 2; shot renderer
js "elBack()" 1
js "while(stack.length) pop(); push(skinPage())" 4; shot skin
js "while(stack.length) pop(); accountsSheet()" 3; shot accounts
js "elBack()" 1
js "switchTab('discover')" 1
js "document.querySelector('.search input').value='sodium'; document.querySelector('.search input').dispatchEvent(new Event('input'))" 6; shot search
js "switchTab('home'); setConfig({theme:'black'}).then(()=>applyTheme('black'))" 2
js "push(instancePage('$ID0'))" 3; shot detail_black
js "while(stack.length) pop(); setConfig({theme:'sakura'}).then(()=>applyTheme('sakura')); switchTab('home')" 2
ID=$(python3 -c "import json;s=json.load(open('$OUT/state.json'));print(s['config']['selected'])")
# launch; if the file check hits a rate limit (CI network), wait and try again
for attempt in 1 2 3 4; do
  before=$(grep -c '"state":"error"' $OUT/events.txt)
  rpc launch "{\"id\":\"$ID\"}" >> $OUT/rpc.txt
  ok=1
  for i in $(seq 1 30); do
    sleep 5
    if [ "$(grep -c '"state":"error"' $OUT/events.txt)" -gt "$before" ]; then ok=0; break; fi
    adb shell dumpsys activity activities | grep -q "GameActivity" && break
  done
  [ $ok = 1 ] && break
  echo "launch attempt $attempt failed, retrying" >> $OUT/rpc.txt
  sleep 45
done
for i in 1 2 3 4 5 6; do sleep 20; shot "game_$i"; done
adb shell input tap 1200 26; sleep 3; shot game_cc
adb shell input tap 1200 26; sleep 2
# singleplayer with no worlds opens "create new world" straight away
adb shell input tap 1199 500; sleep 15; shot game_create
adb shell input tap 880 1008; sleep 150; shot game_world_1
for i in 1 2 3 4 5 6; do sleep 20; shot "game_world_$((i+1))"; done
rpc logs > $OUT/core_logs.json
rpc state > $OUT/state_end.json
kill $EVPID 2>/dev/null
adb shell "cd /data/data/$PKG/files/EririLily && tar cf /data/local/tmp/el.tar --exclude=.minecraft --exclude=runtime ." && adb pull /data/local/tmp/el.tar $OUT/el_data.tar > /dev/null 2>&1
adb shell "ls -la /data/data/$PKG/files /data/data/$PKG/files/runtime /data/data/$PKG/files/runtime/java" > $OUT/files_ls.txt 2>&1
adb shell ps -A > $OUT/ps.txt
adb logcat -d > $OUT/logcat_full.txt
grep -iE "eririlily|AndroidRuntime|FATAL|F DEBUG|pojav|ZLBridge|EGLBridge|LWJGL|jvm" $OUT/logcat_full.txt | tail -500 > $OUT/logcat.txt
