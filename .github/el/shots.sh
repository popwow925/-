#!/bin/bash
# Runs inside the emulator job. Edit this file to change what gets captured.
set -x
PKG=cn.eririlily.launcher.android.debug
OUT=shots; mkdir -p $OUT
n=0
shot(){ n=$((n+1)); f=$(printf "%02d_%s" $n "$1"); adb exec-out screencap -p > $OUT/$f.png; adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1; adb pull /sdcard/u.xml $OUT/$f.xml >/dev/null 2>&1; }
adb root; sleep 3
adb shell "setprop persist.sys.locale zh-CN; setprop ctl.restart zygote"; sleep 25
adb wait-for-device; adb shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
adb shell settings put global window_animation_scale 0
adb install -r -g apk/*.apk
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 4; shot splash
sleep 20; shot first
sleep 30; shot later
adb shell input keyevent BACK; sleep 3; shot back
adb logcat -d | grep -iE "eririlily|zalith|AndroidRuntime" | tail -200 > $OUT/logcat.txt
