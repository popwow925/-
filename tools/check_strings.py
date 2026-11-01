#!/usr/bin/env python3
"""Checks that every UI string key used in App/**/*.swift exists in Localizable.xcstrings with BOTH
zh-Hans and en, and that format specifiers match between languages. Run: python3 tools/check_strings.py"""
import json, re, sys
from pathlib import Path
root = Path(__file__).resolve().parent.parent
cat = json.loads((root / "App/Resources/Localizable.xcstrings").read_text())["strings"]

# Dynamic keys: prefix -> enum raw values (taken from the Swift sources).
def enum_cases(src_file, enum_name):
    s = (root / src_file).read_text()
    m = re.search(r"enum %s\b[^{]*\{(.*?)\n\}" % enum_name, s, re.S)
    body = m.group(1)
    out = []
    for line in body.splitlines():
        m2 = re.match(r"\s*case\s+(.+)", line)
        if m2:
            for part in m2.group(1).split("//")[0].split(","):
                nm = re.match(r"\s*(\w+)", part)
                if nm and nm.group(1) not in ("motion", "touch", "social") or nm and "Kind" in enum_name:
                    out.append(nm.group(1))
    return out
dyn = {
    "game.": enum_cases("Sources/FrenemyCore/MiniGames.swift", "MiniGameID"),
    "gamekind.": ["motion", "touch", "social"],
    "phase.": enum_cases("Sources/FrenemyCore/Views.swift", "GamePhase"),
    "tile.": enum_cases("Sources/FrenemyCore/Board.swift", "TileKind"),
    "mission.outcome.": enum_cases("Sources/FrenemyCore/SecretMission.swift", "MissionOutcome"),
}
used, bad = set(), 0
for f in (root / "App").rglob("*.swift"):
    t = f.read_text()
    for m in re.finditer(r'\b(?:LF?|NSLocalizedString)\(\s*"([^"]+)"', t):
        k = m.group(1)
        if "\\(" in k:
            prefix = k.split("\\(")[0]
            if prefix not in dyn:
                print(f"{f.name}: dynamic key with unknown prefix {k}"); bad += 1
            else:
                used.update(prefix + c for c in dyn[prefix])
        else:
            used.add(k)
    for m in re.finditer(r'\bLF?\([^"()]*\?\s*"([a-z_.]+)"\s*:\s*"([a-z_.]+)"', t):   # ternaries inside L(...)
        used.update([m.group(1), m.group(2)])
for k in sorted(used):
    e = cat.get(k)
    if not e:
        print("MISSING key:", k); bad += 1; continue
    loc = e["localizations"]
    zh = loc.get("zh-Hans", {}).get("stringUnit", {}).get("value")
    en = loc.get("en", {}).get("stringUnit", {}).get("value")
    if not zh or not en:
        print("MISSING language:", k); bad += 1; continue
    spec = lambda s: sorted(re.findall(r"%(?:\d\$)?(?:l?l?[dsf@])", s))
    if spec(zh) != spec(en):
        print("FORMAT mismatch:", k); bad += 1
unused = sorted(set(cat) - used)
if unused: print("unused keys (fine, informational):", ", ".join(unused))
print(f"{len(used)} keys used, {bad} problems")
sys.exit(1 if bad else 0)
