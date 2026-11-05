// 内容脚本不能直接联系 App:由后台页转发给 Aura
browser.runtime.onMessage.addListener((msg) => {
  try { browser.runtime.sendNativeMessage("aura", msg); } catch (e) {}
});

// 提速:看电视用不到的东西一律不加载(图片、字体、统计、广告),老电视 CPU 慢,页面越"轻"开得越快
var JUNK = /(google-analytics|googletagmanager|doubleclick|googlesyndication|hm\.baidu|cnzz|umeng|jiathis|bdstatic|eclick|adsame|admaster|miaozhen|sensorsdata|growingio|cdn-go\.cn\/aegis|beacon|\/stat\/|\/ad\/|\/ads\/)/i;
browser.webRequest.onBeforeRequest.addListener(
  function (d) {
    if (d.type === "image" || d.type === "imageset" || d.type === "font" || d.type === "beacon" || d.type === "ping" || d.type === "csp_report") return { cancel: true };
    if (JUNK.test(d.url)) return { cancel: true };
    return {};
  },
  { urls: ["https://*/*", "http://*/*"], types: ["image", "imageset", "font", "beacon", "ping", "csp_report", "script", "xmlhttprequest", "sub_frame", "stylesheet", "other"] },
  ["blocking"]
);
