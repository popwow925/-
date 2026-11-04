// 央视官方直播页:让视频有声自动播放,铺满全屏,隐藏页面其余部分;开始播放后通知 Aura。
(function () {
  var sent = false;
  function post(type, data) { try { browser.runtime.sendMessage({ type: type, data: data || "" }); } catch (e) {} }
  function tick() {
    try {
      var vs = document.getElementsByTagName("video"), v = null;
      for (var i = 0; i < vs.length; i++) {
        if (!v || vs[i].offsetWidth * vs[i].offsetHeight > v.offsetWidth * v.offsetHeight) v = vs[i];
      }
      if (!window.__auraLogged) {
        window.__auraLogged = 1;
        post("log", "url=" + location.href + " videos=" + vs.length);
      }
      // 让页面按电视屏幕的真实宽度排版,视频才能铺满(否则按 980px 桌面宽度排,只占屏幕一角)
      var mv = document.querySelector("meta[name=viewport]");
      if (!mv) { mv = document.createElement("meta"); mv.name = "viewport"; (document.head || document.documentElement).appendChild(mv); }
      if (mv.content !== "width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1") mv.content = "width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=1";
      if (!v) return;
      if (!v.__aura) {
        v.__aura = 1;
        v.addEventListener("playing", function () { post("playing"); });
        ["error", "stalled", "waiting", "loadedmetadata", "canplay"].forEach(function (n) {
          v.addEventListener(n, function () { post("log", "video event " + n + (v.error ? " " + v.error.message : "")); });
        });
        post("log", "video found");
      }
      var olds = document.querySelectorAll("[data-aura-keep]");
      for (var j = 0; j < olds.length; j++) olds[j].removeAttribute("data-aura-keep");
      var e = v;
      while (e && e.nodeType === 1) { e.setAttribute("data-aura-keep", "1"); e = e.parentElement; }
      var s = document.getElementById("aura_css");
      if (!s) { s = document.createElement("style"); s.id = "aura_css"; (document.head || document.documentElement).appendChild(s); }
      s.textContent = "html,body{overflow:hidden!important;background:#000!important;margin:0!important}" +
        "body *:not([data-aura-keep]):not(video){visibility:hidden!important}" +
        "[data-aura-keep]{transform:none!important;filter:none!important;animation:none!important;transition:none!important}" +
        "video[data-aura-keep]{visibility:visible!important;position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important}";
      v.muted = false; v.volume = 1;
      if ((window.__auraN = (window.__auraN || 0) + 1) % 4 === 1) {
        var er = v.error ? (v.error.code + ":" + v.error.message) : "-";
        post("log", "video state paused=" + v.paused + " ready=" + v.readyState + " net=" + v.networkState +
          " t=" + v.currentTime.toFixed(1) + " size=" + v.videoWidth + "x" + v.videoHeight + " err=" + er +
          " src=" + (v.currentSrc || v.src || "").slice(0, 60));
      }
      if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function (e) { var m = String(e && e.name) + ":" + String(e && e.message); if (window.__auraPE !== m) { window.__auraPE = m; post("log", "play rejected " + m); } }); }
      if (!v.paused && v.currentTime > 0.3 && !sent) { sent = true; post("playing"); }
    } catch (err) { post("log", "js error " + err); }
  }
  setInterval(tick, 700);
  tick();
})();
