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
sleep 5; shot guide
for i in $(seq 1 12); do adb shell input tap 700 960; sleep 1.5; done
sleep 2; shot home
tap "关闭" 3; shot home_closed
tap "添加账号" 4; shot account; adb shell input keyevent BACK; sleep 3
tap "下载" 5; shot download
dump; cp u.xml $OUT/download_dump.xml
tap "整合包" 6; shot dl_modpack
tap "模组" 6; shot dl_mods
tap "资源包" 6; shot dl_res
adb shell input keyevent BACK; sleep 3
tap "设置" 4; shot settings
adb shell input swipe 1400 900 1400 300 400; sleep 2; shot settings2
for t in "游戏设置" "控制设置" "手柄设置" "启动器设置" "控制管理" "关于"; do tap "$t" 4; shot "set_$t"; done
adb shell input swipe 1400 900 1400 300 400; sleep 2; shot set_about2
adb shell input keyevent BACK; sleep 3
tap "多人联机" 4; shot online; adb shell input keyevent BACK; sleep 3
shot home_again
# three themes: launcher settings -> pick theme -> settings page + home in that theme
for th in "纯白:white" "纯黑:black" "樱花:sakura"; do
  name=${th%%:*}; key=${th##*:}
  tap "设置" 4; tap "启动器设置" 4; tap "$name" 4; shot "theme_set_$key"
  adb shell input keyevent BACK; sleep 3; shot "theme_home_$key"
done
adb logcat -d | grep -iE "AndroidRuntime|FATAL" | tail -100 > $OUT/logcat.txt
