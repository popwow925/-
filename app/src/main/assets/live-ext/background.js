// 内容脚本不能直接联系 App:由后台页转发给 Aura
browser.runtime.onMessage.addListener((msg) => {
  try { browser.runtime.sendNativeMessage("aura", msg); } catch (e) {}
});

// 提速:图片、字体、统计信标看电视用不到,不加载(只拦这几类,脚本 / 网络请求一概不动,免得误伤播放器)
browser.webRequest.onBeforeRequest.addListener(
  function () { return { cancel: true }; },
  { urls: ["https://*/*", "http://*/*"], types: ["image", "imageset", "font", "beacon", "ping"] },
  ["blocking"]
);
