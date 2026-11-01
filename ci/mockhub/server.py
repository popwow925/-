#!/usr/bin/env python3
"""CI 用的"模拟家庭中心":只做电视端会调用的那几个接口,返回的 JSON 来自真实家庭中心服务端的实际输出(fixtures/library.json)。
目的:在 GitHub Actions 的模拟器里,让 Aura 桌面连上一个"有影片的家庭中心",截真实的海报墙 / 详情 / 播放画面。
真实的家庭中心(Windows 上运行的那份)不在这个仓库里。

  python3 server.py --port 8080 --media <影片目录> [--fixtures fixtures]
影片文件名按 <条目 id>.mp4 放在 --media 下(make_media.sh 生成后由 run.sh 对应起来)。
"""
import argparse
import json
import os
import re
import socket
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

NAME = "客厅电脑"
HERE = Path(__file__).resolve().parent
LOCK = threading.Lock()
ITEMS = {}      # id -> item dict (来自真实服务端)
ORDER = []      # 库里的顺序
STATE = {}      # id -> {"position","watched","last_played"} 运行中累积的播放进度
ARGS = None


def log(*a):
    print("[mockhub]", *a, flush=True)


def load():
    lib = json.loads((Path(ARGS.fixtures) / "library.json").read_text(encoding="utf-8"))
    for it in lib["items"]:
        ITEMS[it["id"]] = it
        ORDER.append(it["id"])
        STATE[it["id"]] = {"position": it["position"], "watched": it["watched"], "last_played": it["last_played"]}


def item_out(iid):
    it = dict(ITEMS[iid])
    st = STATE[iid]
    it["position"], it["watched"], it["last_played"] = st["position"], st["watched"], st["last_played"]
    return it


def series_sorted(series):
    eps = [ITEMS[i] for i in ORDER if ITEMS[i]["series"] == series]
    return sorted(eps, key=lambda e: (e["season"], e["episode"], e["id"]))


def next_id(iid):
    s = ITEMS[iid]["series"]
    if not s:
        return 0
    eps = series_sorted(s)
    for i, e in enumerate(eps):
        if e["id"] == iid and i + 1 < len(eps):
            return eps[i + 1]["id"]
    return 0


def continue_ids():
    rows = [item_out(i) for i in ORDER]
    half = [r for r in rows if not r["watched"] and r["position"] > 10 and r["play"] in ("direct", "transcoded")]
    half.sort(key=lambda r: r["last_played"], reverse=True)
    ids = [r["id"] for r in half]
    for r in sorted((x for x in rows if x["watched"] and x["series"]), key=lambda x: x["last_played"], reverse=True):
        nid = next_id(r["id"])
        if nid and not STATE[nid]["watched"] and nid not in ids and not any(
                e["id"] in ids for e in series_sorted(r["series"])):
            ids.append(nid)
    return ids


def set_progress(iid, pos, dur):
    it = ITEMS.get(iid)
    if not it:
        return
    dur = float(dur or it["duration"] or 0)
    pos = max(float(pos or 0), 0.0)
    watched = False
    if dur > 0 and (pos >= dur * 0.95 or (dur > 900 and dur - pos <= 60)):
        watched, pos = True, 0.0
    elif pos < 10:
        pos = 0.0
    STATE[iid] = {"position": round(pos), "watched": watched, "last_played": int(time.time())}
    log("progress", iid, "pos=%s" % round(pos), "watched=%s" % watched)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *a):
        log(self.address_string(), fmt % a)

    def send_json(self, obj, code=200):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            return {}

    def serve_file(self, path, ctype):
        try:
            size = os.path.getsize(path)
        except OSError:
            return self.send_json({"error": "not found"}, 404)
        start, end = 0, size - 1
        code = 200
        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng)
            if m:
                if m.group(1):
                    start = int(m.group(1))
                    if m.group(2):
                        end = min(int(m.group(2)), size - 1)
                elif m.group(2):
                    start = max(size - int(m.group(2)), 0)
                if start >= size:
                    self.send_response(416)
                    self.send_header("Content-Range", "bytes */%d" % size)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                code = 206
        n = end - start + 1
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(n))
        if code == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        try:
            with open(path, "rb") as f:
                f.seek(start)
                left = n
                while left > 0:
                    chunk = f.read(min(256 * 1024, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        u = urlparse(self.path)
        parts = [p for p in u.path.split("/") if p]
        with LOCK:
            if parts[:3] == ["api", "tv", "hello"]:
                return self.send_json({"app": "family-hub", "tv": True, "version": 1, "name": NAME})
            if parts[:3] == ["api", "tv", "library"]:
                return self.send_json({
                    "items": [item_out(i) for i in ORDER], "continue": continue_ids(),
                    "server": {"name": NAME, "version": 1}, "ts": int(time.time()),
                })
            if parts[:3] == ["api", "tv", "item"] and len(parts) == 4 and parts[3].isdigit():
                iid = int(parts[3])
                if iid not in ITEMS:
                    return self.send_json({"error": "没有这个影片"}, 404)
                o = item_out(iid)
                return self.send_json({"item": o, "next": next_id(iid),
                                       "stream": "/tv/stream/%d%s" % (iid, "?t=1" if o["play"] == "transcoded" else "")})
        if len(parts) == 3 and parts[0] == "tv" and parts[2].isdigit():
            iid = int(parts[2])
            if parts[1] == "stream":
                return self.serve_file(Path(ARGS.media) / ("%d.mp4" % iid), "video/mp4")
            if parts[1] == "poster":
                return self.serve_file(Path(ARGS.fixtures) / "img" / ("%d.p.jpg" % iid), "image/jpeg")
            if parts[1] == "thumb":
                return self.serve_file(Path(ARGS.fixtures) / "img" / ("%d.t.jpg" % iid), "image/jpeg")
        self.send_json({"error": "not found"}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        parts = [p for p in u.path.split("/") if p]
        b = self.body()
        with LOCK:
            if parts == ["api", "tv", "progress"]:
                set_progress(int(b.get("id") or 0), b.get("position"), b.get("duration"))
                return self.send_json({"ok": True})
            if parts[:3] == ["api", "tv", "item"] and len(parts) == 5 and parts[3].isdigit():
                iid, act = int(parts[3]), parts[4]
                if iid not in ITEMS:
                    return self.send_json({"error": "没有这个影片"}, 404)
                if act == "watched":
                    STATE[iid] = {"position": 0, "watched": True, "last_played": int(time.time())}
                    return self.send_json({"ok": True})
                if act == "unwatch":
                    STATE[iid] = {"position": 0, "watched": False, "last_played": STATE[iid]["last_played"]}
                    return self.send_json({"ok": True})
                if act == "transcode":
                    return self.send_json({"ok": True, "queued": True})
        self.send_json({"error": "not found"}, 404)


def discovery(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("0.0.0.0", 47821))
    log("UDP discovery on 47821")
    while True:
        data, addr = s.recvfrom(1024)
        if data.strip() == b"AURA_HUB_DISCOVER":
            s.sendto(json.dumps({"app": "family-hub", "port": port, "name": NAME}, ensure_ascii=False).encode("utf-8"), addr)
            log("discovery reply ->", addr)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--media", required=True)
    ap.add_argument("--fixtures", default=str(HERE / "fixtures"))
    ARGS = ap.parse_args()
    load()
    threading.Thread(target=discovery, args=(ARGS.port,), daemon=True).start()
    srv = ThreadingHTTPServer(("0.0.0.0", ARGS.port), H)
    log("listening on", ARGS.port, "items:", len(ITEMS))
    srv.serve_forever()
