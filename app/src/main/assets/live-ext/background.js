// 内容脚本不能直接联系 App:由后台页转发给 Aura
browser.runtime.onMessage.addListener((msg) => {
  try { browser.runtime.sendNativeMessage("aura", msg); } catch (e) {}
});
