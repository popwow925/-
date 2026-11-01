#!/usr/bin/env python3
"""用真实的家庭中心服务端(跑在本机、扫过演示影片目录)生成 CI 模拟器用的测试数据:
  fixtures/library.json  真实的 /api/tv/library 输出(只改了电脑名,并把一部影片标成"转码中"演示徽标)
  fixtures/img/<id>.p.jpg / <id>.t.jpg  真实的海报 / 缩略图
  media-map.json         条目 id -> make_media.sh 生成的相对路径,run.sh 按它把影片对应成 <id>.mp4

用法: capture_fixtures.py <服务地址,如 http://localhost:18091>
"""
import json
import sys
import urllib.request
from pathlib import Path

BASE = sys.argv[1].rstrip("/")
OUT = Path(__file__).resolve().parent / "fixtures"
CAPS = "h264,aac,mp3,ac3"

# 演示用的原创简介(按文件名里的片名匹配)
OVERVIEWS = {
    "星河漫游": ("2021", "飞船在银河边缘迷失了航线,三名乘员只能靠一张旧星图和一首老歌,一步步找回回家的路。"),
    "夜航船": ("2019", "一艘夜里才开的小渡船,每晚载着不同的乘客穿过浓雾。船夫发现,每个人的心事都落在了同一个码头。"),
    "山海之间": ("2023", "一位测绘员沿着海岸线走了四十天,在群山与大海交界的地方,遇见了许多慢慢生活的人。"),
    "雾中列车": ("2020", "一列晚点的火车停在大雾里,车厢里的陌生人开始讲各自的故事。"),
    "海边的钢琴": ("2022", "海边小镇的老钢琴被人搬到了沙滩上,一个暑假,全镇的人都来弹过它。"),
    "北纬三十度": ("2018", "沿着北纬三十度线自驾的纪录片:从江南的雨,到高原的雪。"),
}
SERIES = ("小城故事", "一座不大的小城,一家老照相馆,和照片背后那些没来得及说出口的话。")


def get(path, data=None, method=None):
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"} if data else {})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def main():
    lib = json.loads(get("/api/tv/library?caps=" + CAPS))
    # 先补元数据(真实服务端靠 TMDB 补,这里没有网络,手工写一份)
    for it in lib["items"]:
        body = None
        if it["series"] == SERIES[0]:
            body = {"overview": SERIES[1]}
        else:
            for name, (year, ov) in OVERVIEWS.items():
                if it["title"].startswith(name):
                    body = {"title": name, "year": year, "overview": ov}
        if body:
            get("/api/tv/item/%d" % it["id"], json.dumps(body).encode(), "PUT")
    lib = json.loads(get("/api/tv/library?caps=" + CAPS))

    img = OUT / "img"
    img.mkdir(parents=True, exist_ok=True)
    mp = {}
    for it in lib["items"]:
        iid = it["id"]
        for kind, suf in (("poster", "p"), ("thumb", "t")):
            if it[kind]:
                (img / ("%d.%s.jpg" % (iid, suf))).write_bytes(get("/tv/%s/%d" % (kind, iid)))
        if it["series"]:
            mp[iid] = "%s/Season %d/%s S%02dE%02d.mp4" % (it["series"], it["season"], it["series"], it["season"], it["episode"])
        else:
            mp[iid] = "%s (%s).mp4" % (it["title"], it["year"])
        # 演示"电脑正在转码"的徽标
        if it["title"].startswith("雾中列车"):
            it["play"], it["tprogress"] = "transcoding", 0.42
        it["position"], it["watched"], it["last_played"] = 0, False, 0
    lib["server"] = {"name": "客厅电脑", "version": 1}
    lib["continue"] = []
    OUT.mkdir(exist_ok=True)
    (OUT / "library.json").write_text(json.dumps(lib, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT / "media-map.json").write_text(json.dumps(mp, ensure_ascii=False, indent=1), encoding="utf-8")
    print("items:", len(lib["items"]), "images:", len(list(img.glob("*.jpg"))))


main()
