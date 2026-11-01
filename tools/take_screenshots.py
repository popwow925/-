#!/usr/bin/env python3
"""Takes App Store screenshots of the REAL app in the iOS Simulator (macOS only).
Usage: python3 tools/take_screenshots.py <path/to/FrenemyIsle.app> <out_dir>
The app is a Debug build; launch argument -screenshot <scene> drives it to each screen (see AppModel.swift)."""
import json, subprocess, sys, time
from pathlib import Path

APP, OUT = sys.argv[1], Path(sys.argv[2])
BUNDLE = "com.example.frenemyisle"
SCENES = ["home", "motion", "sabotage", "award", "bet", "results", "tv"]
LANGS = {"zh-Hans": ("zh_CN", "小明"), "en": ("en_US", "Alex")}
DEVICES = [("iphone69", ["iPhone 16 Pro Max", "iPhone 17 Pro Max", "iPhone 15 Pro Max"]),
           ("ipad13", ["iPad Pro 13-inch (M4)", "iPad Air 13-inch (M3)", "iPad Air 13-inch (M2)"])]

def sh(*a, check=True):
    print("+", " ".join(a), flush=True)
    return subprocess.run(a, check=check, capture_output=True, text=True).stdout

types = {t["name"]: t["identifier"] for t in json.loads(sh("xcrun", "simctl", "list", "-j", "devicetypes"))["devicetypes"]}
runtimes = [r for r in json.loads(sh("xcrun", "simctl", "list", "-j", "runtimes"))["runtimes"]
            if r.get("isAvailable") and r["name"].startswith("iOS")]
runtime = sorted(runtimes, key=lambda r: [int(x) for x in r["version"].split(".")])[-1]["identifier"]
print("runtime", runtime)

for tag, candidates in DEVICES:
    name = next((c for c in candidates if c in types), None)
    if not name:
        print("no device type for", tag, "available:", [n for n in types if "iPad" in n or "iPhone" in n]); continue
    udid = sh("xcrun", "simctl", "create", f"shot-{tag}", types[name], runtime).strip()
    sh("xcrun", "simctl", "boot", udid)
    sh("xcrun", "simctl", "bootstatus", udid, "-b")
    sh("xcrun", "simctl", "status_bar", udid, "override", "--time", "9:41", "--dataNetwork", "wifi",
       "--wifiMode", "active", "--wifiBars", "3", "--cellularMode", "active", "--cellularBars", "4",
       "--batteryState", "charged", "--batteryLevel", "100")
    sh("xcrun", "simctl", "install", udid, APP)
    for lang, (locale, nick) in LANGS.items():
        d = OUT / tag / lang
        d.mkdir(parents=True, exist_ok=True)
        for scene in SCENES:
            sh("xcrun", "simctl", "terminate", udid, BUNDLE, check=False)
            sh("xcrun", "simctl", "launch", udid, BUNDLE,
               "-AppleLanguages", f"({lang})", "-AppleLocale", locale,
               "-screenshot", scene, "-profile.onboarded", "YES", "-profile.nickname", nick)
            time.sleep(6)
            sh("xcrun", "simctl", "io", udid, "screenshot", str(d / f"{scene}.png"))
    sh("xcrun", "simctl", "shutdown", udid, check=False)
print("done")
