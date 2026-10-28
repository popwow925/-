#!/bin/bash
set -x
PKG=cn.eririlily.launcher.android.debug
OUT=shots; mkdir -p $OUT
n=0
dump(){ adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml u.xml >/dev/null 2>&1; }
shot(){ n=$((n+1)); f=$(printf "%02d_%s" $n "$1"); adb exec-out screencap -p > $OUT/$f.png; dump; cp u.xml $OUT/$f.xml; }
# tap a node whose text or content-desc contains $1
tap(){ dump; xy=$(python3 - "$1" <<'P'
import sys,re,xml.etree.ElementTree as E
t=sys.argv[1]
for n in E.parse('u.xml').iter('node'):
    if t in (n.get('text') or '') or t in (n.get('content-desc') or ''):
        a=list(map(int,re.findall(r'\d+',n.get('bounds'))));print((a[0]+a[2])//2,(a[1]+a[3])//2);break
P
); [ -n "$xy" ] && adb shell input tap $xy; echo "tap '$1' -> $xy"; sleep ${2:-3}; }
adb root; sleep 3
adb shell "setprop persist.sys.locale zh-CN; setprop ctl.restart zygote"; sleep 25
adb wait-for-device; adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb shell settings put secure immersive_mode_confirmations confirmed
adb install -r -g apk/*.apk
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 15; shot unpack
tap "同意并安装" 5; shot unpacking
for i in $(seq 1 30); do sleep 10; dump; grep -q "同意并安装\|正在\|解压" u.xml || break; done
sleep 5; shot home
tap "启动" 4; shot after_launch_tap
adb shell input keyevent BACK; sleep 3
for t in "设置" "下载" "版本" "账户" "关于"; do tap "$t" 4; shot "tab_$t"; done
adb logcat -d | grep -iE " E |AndroidRuntime" | grep -i eririlily | tail -100 > $OUT/logcat.txt
