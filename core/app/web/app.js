"use strict";
const TOKEN = location.hash.slice(1);
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

async function api(name, params = {}) {
  const r = await fetch("/api/" + name, { method: "POST", headers: { "X-Token": TOKEN, "Content-Type": "application/json" }, body: JSON.stringify(params) });
  const j = await r.json().catch(() => ({ error: "服务无响应" }));
  if (j.error) throw new Error(j.error);
  return j.result;
}
async function call(name, params, okMsg) {
  try { const r = await api(name, params); if (okMsg) toast(okMsg); return r; }
  catch (e) { toast(e.message, true); throw e; }
}

let S = null;           // last state
let lastSkinAcc = null;
// small per-viewer conveniences only; storage can be unavailable, so never rely on it
function store(k, v) { try { localStorage.setItem("el." + k, JSON.stringify(v)); } catch { } }
function stored(k, d = null) { try { const v = localStorage.getItem("el." + k); return v == null ? d : JSON.parse(v); } catch { return d; } }
let lastSpeed = stored("lastSpeed");   // ms
const LOADER = { fabric: "Fabric", quilt: "Quilt", forge: "Forge", neoforge: "NeoForge", "": "原版" };

/* ---------------------------------------------------------------- toasts */
function toast(msg, err = false) {
  const t = document.createElement("div");
  t.className = "toast" + (err ? " err" : "");
  t.innerHTML = `<i></i><span>${esc(msg)}</span>`;
  $("#toasts").append(t);
  setTimeout(() => { t.classList.add("out"); setTimeout(() => t.remove(), 400); }, err ? 5200 : 3000);
}

/* ---------------------------------------------------------------- navigation */
let curPage = "home";
function curInstance() { return (S?.instances || []).find(i => i.id === S?.config?.selected); }
function go(page) {
  if (!$("#page-" + page)) return;
  const prev = curPage; curPage = page;
  if (prev !== page) {
    // the page being left keeps rendering only for its fade-out, then stops costing anything
    const old = $("#page-" + prev);
    old.classList.add("leaving");
    clearTimeout(old._lv); old._lv = setTimeout(() => old.classList.remove("leaving"), 420);
  }
  requestAnimationFrame(moveNavInd);
  if (typeof viewer !== "undefined" && viewer) viewer.setActive(page === "skin");
  $$("#nav a").forEach(a => a.classList.toggle("on", a.dataset.page === page));
  $$(".page").forEach(p => p.classList.toggle("on", p.id === "page-" + page));
  if (page === "resources" && !$("#resList").children.length) search(true);
  if (page === "diagnose") loadLogs();
  if (page === "settings") { loadJava(false); loadOptimize(); }
  if (page === "wiki") renderWikiChips();
  setTimeout(syncWiki, 0); setTimeout(syncWiki, 450); // after the page transition settles
  if (page === "skin") { lastSkinAcc = S?.config?.account; loadSkinPage(); }
}
$$("#nav a").forEach(a => a.onclick = () => go(a.dataset.page));
document.addEventListener("click", e => {
  const g = e.target.closest("[data-goto]"); if (g) go(g.dataset.goto);
  const o = e.target.closest("[data-open]"); if (o) call("open", { what: o.dataset.open });
  const c = e.target.closest("[data-close]"); if (c) c.closest(".modal").hidden = true;
  if (!e.target.closest(".popover") && !e.target.closest("#instSwitch") && !e.target.closest("#accountChip")) $$(".popover").forEach(p => p.hidden = true);
});
$$(".modal").forEach(m => m.addEventListener("mousedown", e => { if (e.target === m) m.hidden = true; }));
document.addEventListener("keydown", e => { if (e.key === "Escape") { $$(".modal,.popover").forEach(m => m.hidden = true); } });

/* ---------------------------------------------------------------- render */
function coverURL(kind, inst, world, v) {
  return `/api/cover?t=${TOKEN}&kind=${kind}&inst=${encodeURIComponent(inst)}${world ? "&world=" + encodeURIComponent(world) : ""}&v=${v}`;
}
function proxied(u) { return /^https:\/\/[^/]*modrinth\.com\//.test(u || "") ? `/api/img?t=${TOKEN}&u=${encodeURIComponent(u)}` : u; }
function thumbFor(inst) {
  if (inst.cover) return coverURL("inst", inst.id, "", inst.cover);
  if (inst.icon) return proxied(inst.icon);
  let h = 0; for (const ch of inst.id) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return inst.icon || `thumb${h % 4}.jpg`;
}
function metaLine(i) {
  const parts = ["Minecraft " + i.game, LOADER[i.loader] ?? i.loader];
  if (i.mods) parts.push(i.mods + " 个模组");
  return parts.join(" · ");
}
function ago(ts) {
  if (!ts) return "还没玩过";
  const d = Date.now() / 1000 - ts;
  if (d < 3600) return Math.max(1, Math.round(d / 60)) + " 分钟前";
  if (d < 86400) return Math.round(d / 3600) + " 小时前";
  return Math.round(d / 86400) + " 天前";
}
function hours(sec) { return sec < 3600 ? Math.round(sec / 60) + " 分钟" : (sec / 3600).toFixed(1) + " 小时"; }

function instCard(i, selected) {
  return `<div class="inst glass" data-inst="${esc(i.id)}">
    <div class="thumb"><img src="${esc(thumbFor(i))}" alt="" loading="lazy"><span class="tag">${esc(LOADER[i.loader] ?? i.loader)} ${esc(i.game)}</span>
    ${selected ? '<span class="sel"><svg viewBox="0 0 24 24"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg></span>' : ""}</div>
    <div class="info"><b>${esc(i.name)}</b><span>${i.pack ? esc(i.pack) + " · " : ""}${ago(i.lastPlayed)}${i.playTime ? " · 共 " + hours(i.playTime) : ""}</span></div></div>`;
}

function gb(mb) { return mb >= 10240 ? (mb / 1024).toFixed(0) : (mb / 1024).toFixed(1); }
function renderMem(c, cur) {
  const total = S.memTotal || 0, free = Math.min(S.memFree || 0, total);
  if (!total) { $("#tileMem").textContent = "—"; $("#tileMemSub").textContent = "无法读取内存信息"; return; }
  const game = c.memoryMB || (cur && cur.loader ? S.autoMemModded : S.autoMem);
  const used = total - free;
  $("#tileMem").innerHTML = `${gb(free)} GB<small>可用 / 共 ${gb(total)} GB</small>`;
  $("#memUsed").style.width = (used / total * 100).toFixed(1) + "%";
  const g = $("#memGame");
  g.style.left = (used / total * 100).toFixed(1) + "%";
  g.style.width = (Math.min(game, free) / total * 100).toFixed(1) + "%";
  const warn = game > free ? "，空闲内存不够，建议关掉一些程序" : "";
  $("#tileMemSub").textContent = `游戏将分配 ${gb(game)} GB（${c.memoryMB ? "手动" : "自动"}）${warn}`;
}
function moveNavInd() {
  const on = $("#nav a.on"), ind = $(".nav-ind");
  if (on && ind) { ind.style.transform = `translateY(${on.offsetTop}px)`; ind.style.height = on.offsetHeight + "px"; }
}
// Keyed, minimal DOM update: an item is only replaced when its HTML changed;
// items that stay keep their DOM (no flash, no replayed entrance animation).
const htmlOf = new WeakMap();
function patchList(box, items, empty = "") {
  if (!items.length) { if (box.dataset.sig !== "empty") { box.innerHTML = empty; box.dataset.sig = "empty"; } return; }
  if (box.dataset.sig === "empty") { box.innerHTML = ""; }
  const first = !box.dataset.sig;
  box.dataset.sig = "list";
  const old = new Map([...box.children].filter(e => e.dataset.k).map(e => [e.dataset.k, e]));
  let prev = null;
  for (const it of items) {
    let el = old.get(it.key);
    if (!el || htmlOf.get(el) !== it.html) {
      const t = document.createElement("template");
      t.innerHTML = it.html.trim();
      const fresh = t.content.firstElementChild;
      fresh.dataset.k = it.key; htmlOf.set(fresh, it.html);
      if (!first) fresh.style.animation = "none"; // only the very first build animates in
      if (el) el.replaceWith(fresh); 
      el = fresh;
    }
    old.delete(it.key);
    const want = prev ? prev.nextElementSibling : box.firstElementChild;
    if (want !== el) box.insertBefore(el, want);
    prev = el;
  }
  old.forEach(e => e.remove());
  [...box.children].forEach(e => { if (!e.dataset.k) e.remove(); });
}

function render() {
  const c = S.config, insts = S.instances || [];
  const cur = insts.find(i => i.id === c.selected);
  // hero
  $("#heroName").textContent = cur ? cur.name : "还没有实例";
  $("#heroMeta").textContent = cur ? metaLine(cur) : "去实例库新建一个，或在资源中心装一个整合包";
  const heroSrc = cur ? (cur.cover ? coverURL("inst", cur.id, "", cur.cover) : (proxied(cur.icon) || "hero.jpg")) : "hero.jpg";
  const hi = $(".hero-art img");
  if (hi.getAttribute("src") !== heroSrc) {
    hi.style.opacity = 0;
    hi.onload = () => hi.style.opacity = 1;
    hi.src = heroSrc;
  }
  const btn = $("#playBtn");
  btn.classList.toggle("running", !!S.running);
  btn.querySelector("span").textContent = S.running ? "结束游戏" : (cur ? "开始游戏" : "新建实例");
  // recent + grid: patched in place, so periodic refreshes never rebuild
  // (and re-animate / re-decode) cards that did not change
  const cards = list => list.map(i => ({ key: i.id, html: instCard(i, i.id === c.selected) }));
  patchList($("#recent"), cards(insts.slice(0, 4)), `<div class="empty">还没有实例。点上面的按钮新建一个吧。</div>`);
  patchList($("#instGrid"), cards(insts).concat([{ key: "+add", html: `<div class="inst glass add" id="addCard"><div><b>＋</b>新建实例</div></div>` }]));
  $("#addCard").onclick = openNew;
  $$("[data-inst]").forEach(el => el.onclick = () => openInstance(el.dataset.inst));
  // tiles
  $("#tileSpeed").textContent = lastSpeed ? (lastSpeed / 1000).toFixed(1) + " s" : "—";
  renderMem(c, cur);
  const SRCN = { auto: "自动", bmclapi: "BMCLAPI", official: "官方" };
  $("#tileSrc").textContent = c.source === "auto" && S.autoSource ? "自动 · " + SRCN[S.autoSource] : (SRCN[c.source] || c.source);
  // account
  const acc = (S.accounts || []).find(a => a.id === c.account);
  $("#accName").textContent = acc ? acc.name : "添加账户";
  $("#accType").textContent = acc ? (acc.type === "msa" ? "微软账户" : "离线账户") : "点击登录";
  if (typeof paintAvatars === "function") paintAvatars();
  if (typeof preloadedFor !== "undefined" && preloadedFor !== null && preloadedFor !== (c.account || "")) schedulePreloadSkin();
  if ($("#page-skin").classList.contains("on") && lastSkinAcc !== c.account) { lastSkinAcc = c.account; loadSkinPage(); }
  patchList($("#accList"), (S.accounts || []).map(a => ({ key: a.id, html: `<div class="acc-row ${a.id === c.account ? "on" : ""}">
    <span class="avatar" data-head="${esc(a.id)}">${esc(a.name[0].toUpperCase())}</span><div><b>${esc(a.name)}</b><small>${a.type === "msa" ? "微软账户" : "离线账户"}</small></div>
    ${a.id === c.account ? "" : `<button class="btn ghost small" data-acc-use="${a.id}">使用</button>`}
    <button class="x" data-acc-del="${a.id}" title="移除">✕</button></div>` })), `<p class="hint">还没有账户</p>`);
  $$("[data-acc-use]").forEach(b => b.onclick = async () => { await call("selectAccount", { id: b.dataset.accUse }); refresh(); });
  $$("[data-acc-del]").forEach(b => b.onclick = async () => { await call("removeAccount", { id: b.dataset.accDel }); refresh(); });
  // resources target
  $("#resTarget").textContent = cur ? `${cur.name}（${cur.game} ${LOADER[cur.loader] ?? ""}）` : "当前实例";
  // diagnosis
  $("#dxDot").hidden = !S.diagnosis;
  renderDx(S.diagnosis);
  renderSettings();
  moveNavInd();
  $("#aboutVer").textContent = "版本 " + S.version;
}

async function refresh() { S = await api("state"); render(); }

/* ---------------------------------------------------------------- launch */
$("#playBtn").onclick = async () => {
  if (S.running) { await call("kill"); return; }
  if (!S.instances?.length) { openNew(); return; }
  if (!S.config.account) { go("settings"); toast("先添加一个账户再开始游戏", true); $("#offName").focus(); return; }
  try { await api("launch", { id: S.config.selected }); launchUI("检查文件", 0); }
  catch (e) { toast(e.message, true); }
};
let launching = false;
function launchUI(stage, frac, info = "") {
  launching = true;
  $("#launchState").hidden = false;
  $("#lsStage").textContent = stage;
  const bar = $("#lsBar").parentElement;
  bar.classList.toggle("indet", frac == null);
  if (frac != null) $("#lsBar").style.width = Math.round(frac * 100) + "%";
  $("#lsInfo").textContent = info;
  $("#playBtn").hidden = true;
}
function launchDone() { launching = false; $("#launchState").hidden = true; $("#playBtn").hidden = false; }

$("#instSwitch").onclick = e => {
  const m = $("#instMenu");
  if (!m.hidden) { m.hidden = true; return; }
  const r = e.currentTarget.getBoundingClientRect();
  m.innerHTML = (S.instances || []).map(i => `<button data-pick="${esc(i.id)}">${esc(i.name)}<small>${esc(i.game)} ${esc(LOADER[i.loader] ?? "")}</small></button>`).join("") +
    `<hr><button data-pick="">＋ 新建实例</button>`;
  m.style.left = r.left + "px"; m.style.top = (r.top - Math.min(360, m.scrollHeight) - 120) + "px";
  m.hidden = false;
  m.style.top = Math.max(12, r.top - m.offsetHeight - 8) + "px";
  $$("[data-pick]", m).forEach(b => b.onclick = async () => {
    m.hidden = true;
    if (!b.dataset.pick) return openNew();
    await call("select", { id: b.dataset.pick }); refresh();
  });
};
$("#accountChip").onclick = e => {
  const m = $("#accMenu");
  if (!m.hidden) { m.hidden = true; return; }
  const r = e.currentTarget.getBoundingClientRect();
  m.innerHTML = (S.accounts || []).map(a => `<button data-pa="${a.id}">${esc(a.name)}<small>${a.type === "msa" ? "微软" : "离线"}${a.id === S.config.account ? " · 当前" : ""}</small></button>`).join("") +
    `<hr><button data-pa="">管理账户…</button>`;
  m.hidden = false;
  m.style.left = r.left + "px"; m.style.top = (r.top - m.offsetHeight - 8) + "px";
  $$("[data-pa]", m).forEach(b => b.onclick = async () => {
    m.hidden = true;
    if (!b.dataset.pa) { go("settings"); return; }
    await call("selectAccount", { id: b.dataset.pa }); refresh();
  });
};

/* ---------------------------------------------------------------- events */
function fmtBytes(n) { return n > 1 << 20 ? (n / (1 << 20)).toFixed(1) + " MB" : Math.round(n / 1024) + " KB"; }
let curTask = null, curStage = "";
function connect() {
  const es = new EventSource("/api/events?t=" + TOKEN);
  es.onmessage = ev => {
    const { type, data } = JSON.parse(ev.data);
    switch (type) {
      case "task":
        if (data.state === "start") {
          curTask = data.label;
          if (!data.label.startsWith("启动")) { $("#taskbar").hidden = false; $("#tbLabel").textContent = data.label; $("#tbStage").textContent = ""; $("#tbInfo").textContent = ""; $("#tbBar").style.width = "0"; }
        } else {
          $("#taskbar").hidden = true; curTask = null;
          if (data.state === "error") { toast(data.error, true); if (launching) launchDone(); }
          if (data.state === "cancelled") { toast("已取消"); if (launching) launchDone(); }
          refresh();
        }
        break;
      case "stage":
        curStage = data;
        if (launching) launchUI(data, null); else $("#tbStage").textContent = data;
        break;
      case "progress": {
        const f = data.total ? data.done / data.total : 0;
        const info = `${data.done}/${data.total} · ${data.speed ? fmtBytes(data.speed) + "/s" : fmtBytes(data.bytes)}`;
        if (launching) launchUI(data.stage, f, info);
        else { $("#tbBar").style.width = (f * 100) + "%"; $("#tbInfo").textContent = info; $("#tbStage").textContent = data.stage; }
        break;
      }
      case "started":
        launchUI("正在打开游戏窗口", null, "Java 已启动");
        appendLog("[EririLily] 已启动 · " + (data.ms / 1000).toFixed(2) + " s", "s");
        refresh();
        break;
      case "ready":
        lastSpeed = data.ms; store("lastSpeed", data.ms);
        launchDone();
        toast(`游戏已启动，用时 ${(data.ms / 1000).toFixed(1)} 秒`);
        refresh();
        break;
      case "log": appendLog(data); break;
      case "logs": appendLogs(data); break;
      case "updated":
        toast(`已更新 ${data.n} 项`);
        if (!$("#instModal").hidden && curInst && curInst.id === data.instance && segVal($("#imKind")) === data.kind) loadContent();
        refresh();
        break;
      case "exit":
        launchDone();
        if (data.diagnosis) { toast("游戏异常退出：" + data.diagnosis.title, true); go("diagnose"); }
        else toast(`游戏已退出，本次游玩 ${hours(data.played)}`);
        refresh();
        break;
      case "installed": toast("安装完成：" + data); refresh(); break;
      case "fixed": toast(data); refresh(); break;
      case "account":
        $("#msCode").hidden = true;
        if (data.error) toast(data.error, true); else toast("已登录 " + data.ok);
        refresh();
        break;
    }
  };
  es.onerror = () => { es.close(); setTimeout(connect, 1500); };
}
$("#tbCancel").onclick = () => call("cancel");

/* ---------------------------------------------------------------- logs */
const LOG_MAX = 4000;
function logClass(l) { return /ERROR|Exception|FATAL|Caused by/.test(l) ? "e" : /WARN/.test(l) ? "w" : l.startsWith("[EririLily]") ? "s" : ""; }
// Lines are queued and written once per frame in one fragment: thousands of
// lines at game start cost a handful of layouts instead of one per line.
let logQ = [], logRaf = 0;
function appendLog(line, cls) { logQ.push([line, cls]); if (!logRaf) logRaf = requestAnimationFrame(flushLog); }
function appendLogs(lines) { for (const l of lines || []) logQ.push([l]); if (!logRaf) logRaf = requestAnimationFrame(flushLog); }
function flushLog() {
  logRaf = 0;
  const v = $("#logView");
  if (v.dataset.empty) { v.textContent = ""; delete v.dataset.empty; }
  const stick = v.scrollTop + v.clientHeight >= v.scrollHeight - 30;
  const q = logQ.length > LOG_MAX ? logQ.slice(-LOG_MAX) : logQ;
  logQ = [];
  const frag = document.createDocumentFragment();
  for (const [line, cls] of q) {
    const d = document.createElement("div");
    d.textContent = line; d.className = cls || logClass(line);
    frag.append(d);
  }
  v.append(frag);
  let extra = v.childElementCount - LOG_MAX;
  while (extra-- > 0) v.firstChild.remove();
  if (stick) v.scrollTop = v.scrollHeight;
}
async function loadLogs() {
  const lines = await api("logs").catch(() => []);
  const v = $("#logView"); v.textContent = ""; logQ = [];
  if (!lines?.length) { v.innerHTML = `<div style="opacity:.5">启动游戏后，日志会实时显示在这里。</div>`; v.dataset.empty = 1; return; }
  appendLogs(lines.slice(-LOG_MAX));
  requestAnimationFrame(() => v.scrollTop = v.scrollHeight);
}
$("#exportLog").onclick = () => call("exportLog", {}, "日志已导出");

function renderDx(d) {
  const el = $("#dxCard");
  const sig = JSON.stringify(d || null);
  if (el.dataset.sig === sig) return; // unchanged: keep the card (no re-animation every refresh)
  el.dataset.sig = sig;
  if (!d) {
    el.innerHTML = `<div class="dx glass ok"><div class="dx-icon"><svg viewBox="0 0 24 24"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg></div>
      <div><h3>一切正常</h3><p>最近一次游戏没有出现问题。如果游戏崩溃，这里会告诉你原因和修复办法——全部在本地分析，日志不会上传。</p></div></div>`;
    return;
  }
  const fixBtn = d.fix === "mods" ? `<button class="btn" data-dxmods>${esc(d.fixLabel)}</button>`
    : d.fix === "log" ? "" : d.fix ? `<button class="btn" data-dxfix>${esc(d.fixLabel)}</button>` : "";
  el.innerHTML = `<div class="dx glass"><div class="dx-icon"><svg viewBox="0 0 24 24"><path d="M12 8v5M12 16.5v.5"/><path d="M10.3 3.9 2.6 17.3A2 2 0 0 0 4.3 20.3h15.4a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg></div>
    <div style="flex:1;min-width:0"><h3>${esc(d.title)}</h3><p>${esc(d.detail)}</p>${d.evidence ? `<code>${esc(d.evidence)}</code>` : ""}
    <div class="row">${fixBtn}${d.report ? `<button class="btn ghost" data-open="report">查看崩溃报告</button>` : ""}</div></div></div>`;
  const fb = $("[data-dxfix]", el);
  if (fb) fb.onclick = () => call("fix", { action: d.fix, arg: d.fixArg, instance: S.config.selected });
  const mb = $("[data-dxmods]", el);
  if (mb) mb.onclick = () => openInstance(S.config.selected);
}

/* ---------------------------------------------------------------- new instance */
let manifest = null;
async function openNew() {
  $("#newModal").hidden = false;
  $("#niName").value = ""; $("#niHint").textContent = "";
  setSeg($("#niLoader"), "");
  $("#niLvRow").hidden = true;
  if (!manifest) {
    $("#niGame").innerHTML = `<option>正在获取版本列表…</option>`;
    try { manifest = await api("versions"); }
    catch (e) { $("#niGame").innerHTML = `<option>获取失败</option>`; $("#niHint").textContent = e.message + "。请检查网络，或在设置里切换下载源。"; return; }
  }
  fillVersions();
}
function fillVersions() {
  const snap = $("#niSnap").checked;
  const vs = manifest.versions.filter(v => snap ? (v.type === "release" || v.type === "snapshot") : v.type === "release");
  $("#niGame").innerHTML = vs.map(v => `<option value="${esc(v.id)}">${esc(v.id)}${v.type === "snapshot" ? "（快照）" : ""}${v.id === manifest.latest.release ? "（最新）" : ""}</option>`).join("");
  loadLoaderVersions();
}
$("#niSnap").onchange = () => manifest && fillVersions();
$("#niGame").onchange = loadLoaderVersions;
function setSeg(seg, v) { $$("button", seg).forEach(b => b.classList.toggle("on", (b.dataset.v ?? b.dataset.k) === v)); }
function segVal(seg) { const b = $("button.on", seg); return b ? (b.dataset.v ?? b.dataset.k) : ""; }
$$("#niLoader button").forEach(b => b.onclick = () => { setSeg($("#niLoader"), b.dataset.v); loadLoaderVersions(); });
async function loadLoaderVersions() {
  const loader = segVal($("#niLoader")), game = $("#niGame").value;
  $("#niLvRow").hidden = !loader;
  $("#niHint").textContent = "";
  $("#niCreate").disabled = false;
  if (!loader || !game) return;
  $("#niLv").innerHTML = `<option>加载中…</option>`;
  try {
    const vs = await api("loaderVersions", { loader, game });
    if (!vs?.length) { $("#niLv").innerHTML = ""; $("#niHint").textContent = `${LOADER[loader]} 还不支持 ${game}`; $("#niCreate").disabled = true; return; }
    const short = v => loader === "forge" ? v.replace(game + "-", "").replace("-" + game, "") : v;
    $("#niLv").innerHTML = vs.slice(0, 60).map(v => `<option value="${esc(v.version)}">${esc(short(v.version))}${loader === "forge" ? (v.stable ? "（推荐）" : "") : (v.stable ? "" : "（测试版）")}</option>`).join("");
    const st = vs.find(v => v.stable); if (st) $("#niLv").value = st.version;
  } catch (e) { $("#niHint").textContent = e.message; }
}
$("#niCreate").onclick = async () => {
  const loader = segVal($("#niLoader"));
  const p = { game: $("#niGame").value, loader, loaderVersion: loader ? $("#niLv").value : "", name: $("#niName").value.trim() };
  if (!p.name && loader) p.name = `${p.game}-${LOADER[loader]}`;
  if (loader === "forge" || loader === "neoforge") $("#niHint").textContent = "Forge / NeoForge 安装需要下载并运行官方安装流程，通常 1–3 分钟。";
  try { await api("createInstance", p); $("#newModal").hidden = true; }
  catch (e) { $("#niHint").textContent = e.message; }
};
$("#newInstBtn").onclick = openNew;

async function importPack(f) {
  const r = await fetch("/api/upload?name=" + encodeURIComponent(f.name), { method: "POST", headers: { "X-Token": TOKEN }, body: f }).catch(() => null);
  const j = r ? await r.json().catch(() => ({})) : { error: "上传失败" };
  if (j.error) toast(j.error, true); else toast("正在导入 " + f.name);
}
$("#mrpackFile").onchange = e => {
  const f = e.target.files[0]; if (!f) return;
  e.target.value = "";
  importPack(f);
};

/* ---------------------------------------------------------------- instance detail */
let curInst = null;
async function openInstance(id) {
  const i = S.instances.find(x => x.id === id); if (!i) return;
  curInst = i;
  $$(".popover").forEach(p => p.hidden = true);
  $("#imThumb").src = thumbFor(i);
  $("#imName").textContent = i.name;
  $("#imMeta").textContent = metaLine(i) + ` · 需要 Java ${i.java}`;
  setSeg($("#imKind"), "world");
  $("#instModal").hidden = false;
  if (S.config.selected !== id) { await api("select", { id }); refresh(); }
  loadContent();
}
/* instance tabs: mods / resource packs / shaders (named + updatable), servers */
const KIND_N = { mod: "模组", resourcepack: "资源包", shader: "光影" };
let UPD = {};              // file -> update from the last check, for the open tab
let contentSeq = 0;
async function loadContent() {
  const kind = segVal($("#imKind"));
  const seq = ++contentSeq;
  $("#imTools").innerHTML = "";
  $("#imList").onclick = $("#imList").onchange = null;
  if (kind === "world") return loadWorlds();
  if (kind === "server") return loadServers(seq);
  UPD = {};
  const id = curInst.id;
  const live = () => seq === contentSeq && curInst?.id === id && segVal($("#imKind")) === kind;
  // the plain list shows at once; names and icons from Modrinth fill in when they arrive
  const list = await api("content", { instance: id, kind }).catch(() => []);
  if (!live()) return;
  renderContent(list || [], kind);
  if (!list?.length) return;
  const info = await api("contentInfo", { instance: id, kind }).catch(() => null);
  if (!live() || !info) return;
  renderContent(info.items || [], kind);
}
function contentTools(kind, items) {
  const n = items.length, ups = Object.keys(UPD).length;
  $("#imTools").innerHTML = !n ? "" : `<span class="hint">${n} 个${KIND_N[kind]}${items.filter(f => !f.enabled).length ? ` · ${items.filter(f => !f.enabled).length} 个已停用` : ""}</span>
    <div class="im-tools-btns">${ups ? `<span class="upd-count">${ups} 项可更新</span><button class="btn small" id="updAll">全部更新</button>` : ""}
    <button class="btn ghost small" id="updCheck">${ups ? "重新检查" : "检查更新"}</button></div>`;
  const ck = $("#updCheck");
  if (ck) ck.onclick = () => checkUpdates(kind);
  const all = $("#updAll");
  if (all) all.onclick = async () => { all.disabled = true; await call("applyUpdates", { instance: curInst.id, kind }).catch(() => { }); };
}
async function checkUpdates(kind) {
  const b = $("#updCheck"); if (b) { b.disabled = true; b.textContent = "检查中…"; }
  try {
    const ups = await api("checkUpdates", { instance: curInst.id, kind });
    UPD = Object.fromEntries((ups || []).map(u => [u.file, u]));
    toast(ups?.length ? `发现 ${ups.length} 项可更新` : "全部是最新版本");
    const info = await api("contentInfo", { instance: curInst.id, kind }).catch(() => null);
    if (info && segVal($("#imKind")) === kind) renderContent(info.items || [], kind);
  } catch (e) { toast(e.message, true); if (b) { b.disabled = false; b.textContent = "检查更新"; } }
}
function renderContent(items, kind) {
  const el = $("#imList");
  contentTools(kind, items);
  if (!items.length) {
    el.innerHTML = `<p class="hint" style="text-align:center;padding:24px">还没有${KIND_N[kind]}。去 <a data-goto="resources" data-close>资源中心</a> 一键安装。</p>`;
    delete el.dataset.sig; return;
  }
  if (!el.dataset.sig) el.innerHTML = "";
  items.sort((x, y) => (x.title || x.name).localeCompare(y.title || y.name, "zh"));
  patchList(el, items.map(f => {
    const u = UPD[f.file];
    const icon = f.icon ? `<img src="${esc(proxied(f.icon))}" alt="" loading="lazy" onerror="this.replaceWith(Object.assign(document.createElement('i'),{className:'ci-ph'}))">` : `<i class="ci-ph"></i>`;
    return { key: f.file, html: `<div class="citem ${f.enabled ? "" : "off"}">
    <label class="sw"><input type="checkbox" ${f.enabled ? "checked" : ""} data-tog="${esc(f.file)}"><i></i></label>
    <span class="ci-icon">${icon}</span>
    <div class="ci-text" title="${esc(f.file)}"><b>${esc(f.title || f.name)}</b><small>${f.version ? esc(f.version) + " · " : ""}${f.title ? esc(f.file) + " · " : ""}${fmtBytes(f.size)}</small></div>
    ${u ? `<button class="btn small upd" data-upd="${esc(f.file)}" title="${esc(u.from || "")} → ${esc(u.to)}">更新到 ${esc(u.to)}</button>` : ""}
    <button class="x" data-cdel="${esc(f.file)}" title="删除">✕</button></div>` };
  }));
  el.onchange = async e => {
    const c = e.target.closest("[data-tog]"); if (!c) return;
    await call("toggleContent", { instance: curInst.id, kind, file: c.dataset.tog }).catch(() => { });
    loadContent(); refresh();
  };
  el.onclick = async e => {
    const d = e.target.closest("[data-cdel]");
    if (d) { if (!confirmTwice(d)) return; await call("deleteContent", { instance: curInst.id, kind, file: d.dataset.cdel }).catch(() => { }); loadContent(); refresh(); return; }
    const u = e.target.closest("[data-upd]");
    if (u) { u.disabled = true; u.textContent = "更新中…"; await call("applyUpdates", { instance: curInst.id, kind, files: [u.dataset.upd] }).catch(() => { }); }
  };
}

// Minecraft §-codes → coloured spans
const MC_COL = { 0: "#000", 1: "#00a", 2: "#0a0", 3: "#0aa", 4: "#a00", 5: "#a0a", 6: "#fa0", 7: "#aaa", 8: "#555", 9: "#55f", a: "#5f5", b: "#5ff", c: "#f55", d: "#f5f", e: "#ff5", f: "#fff" };
function motdHTML(s) {
  let out = "", st = {};
  const open = () => {
    const css = [st.c ? `color:${st.c}` : "", st.l ? "font-weight:700" : "", st.o ? "font-style:italic" : "",
      st.n || st.m ? `text-decoration:${st.n ? "underline " : ""}${st.m ? "line-through" : ""}` : ""].filter(Boolean).join(";");
    return css ? `<span style="${css}">` : "<span>";
  };
  for (const part of String(s || "").split(/(§#[0-9a-fA-F]{6}|§[0-9a-fk-orA-FK-OR])/)) {
    if (!part) continue;
    if (part[0] === "§" && part.length > 1) {
      const c = part.slice(1).toLowerCase();
      if (c[0] === "#") st = { c: c };
      else if (MC_COL[c]) st = { c: MC_COL[c] };
      else if (c === "r") st = {};
      else st[c] = true;
      continue;
    }
    out += open() + esc(part).replace(/\n/g, "<br>") + "</span>";
  }
  return out;
}
function pingBars(ms) {
  const n = ms < 80 ? 5 : ms < 150 ? 4 : ms < 300 ? 3 : ms < 600 ? 2 : 1;
  return `<span class="bars b${n}" title="${ms} ms">${[1, 2, 3, 4, 5].map(i => `<i style="height:${3 + i * 2}px"></i>`).join("")}</span>`;
}
async function loadServers(seq) {
  const id = curInst.id, el = $("#imList");
  $("#imTools").innerHTML = `<div class="srv-add"><input id="srvName" placeholder="名称（可选）" maxlength="40"><input id="srvIP" placeholder="服务器地址，例如 mc.example.com" maxlength="260"><button class="btn small" id="srvAdd">添加</button></div>`;
  const add = async () => {
    const ip = $("#srvIP").value.trim(); if (!ip) { $("#srvIP").focus(); return; }
    try { await api("addServer", { instance: id, name: $("#srvName").value.trim(), ip }); toast("已添加，游戏的多人游戏列表里也能看到"); loadContent(); }
    catch (e) { toast(e.message, true); }
  };
  $("#srvAdd").onclick = add;
  $("#srvIP").onkeydown = e => { if (e.key === "Enter") add(); };
  const list = await api("servers", { instance: id }).catch(e => { toast(e.message, true); return []; });
  if (seq !== contentSeq) return;
  if (!list?.length) {
    el.innerHTML = `<p class="hint" style="text-align:center;padding:24px">还没有服务器。在上面添加一个，或在游戏的「多人游戏」里添加，这里会同步显示。</p>`;
    return;
  }
  el.innerHTML = `<div class="servers">` + list.map((v, k) => `<div class="srv" data-srv="${esc(v.ip)}" style="animation-delay:${k * 35}ms">
    <div class="srv-icon">${v.icon ? `<img src="data:image/png;base64,${esc(v.icon)}" alt="">` : `<img src="${esc(thumbFor(curInst))}" alt="">`}</div>
    <div class="srv-meta"><div class="srv-top"><b>${esc(v.name)}</b><span class="srv-stat hint">正在连接…</span></div>
      <div class="srv-motd hint">${esc(v.ip)}</div></div>
    <div class="srv-acts"><button class="btn small" data-join="${esc(v.ip)}">加入</button><button class="x" data-sdel="${esc(v.ip)}" title="删除">✕</button></div></div>`).join("") + `</div>`;
  el.onclick = async e => {
    const j = e.target.closest("[data-join]");
    if (j) { joinServer(id, j.dataset.join); return; }
    const d = e.target.closest("[data-sdel]");
    if (d) { if (!confirmTwice(d, "删?")) return; await call("removeServer", { instance: id, ip: d.dataset.sdel }).catch(() => { }); loadContent(); }
  };
  el.onchange = null;
  // ping all at once; each row fills in as its answer arrives
  list.forEach(v => api("pingServer", { addr: v.ip }).then(st => {
    if (seq !== contentSeq) return;
    const row = el.querySelector(`[data-srv="${CSS.escape(v.ip)}"]`); if (!row) return;
    const stat = $(".srv-stat", row), motd = $(".srv-motd", row);
    row.classList.toggle("down", !st.online);
    if (!st.online) { stat.textContent = st.error || "离线"; return; }
    stat.innerHTML = `<span>${st.players} / ${st.max}</span>${pingBars(st.ping)}`;
    stat.title = (st.sample || []).join("、");
    motd.innerHTML = motdHTML(st.motd) || esc(v.ip);
    if (st.version) motd.insertAdjacentHTML("beforeend", ` <span class="srv-ver">${esc(st.version.replace(/§./g, ""))}</span>`);
    if (st.favicon && !v.icon) $(".srv-icon img", row).src = st.favicon;
  }).catch(() => { }));
}
async function joinServer(id, addr) {
  $("#instModal").hidden = true; closePalette();
  go("home");
  if (S.running) { toast("游戏已经在运行了", true); return; }
  if (!S.config.account) { go("skin"); toast("先添加一个账户再开始游戏", true); return; }
  if (S.config.selected !== id) { await call("select", { id }); await refresh(); }
  try { await api("launch", { id, server: addr }); launchUI("检查文件", 0); toast("启动后将直接进入 " + addr); }
  catch (e) { toast(e.message, true); }
}
// a two-step confirm without browser dialogs
function confirmTwice(btn, label = "确认？") {
  if (btn.dataset.armed) return true;
  const old = btn.textContent;
  btn.dataset.armed = 1; btn.textContent = label;
  setTimeout(() => { delete btn.dataset.armed; btn.textContent = old; }, 2500);
  return false;
}
$$("#imKind button").forEach(b => b.onclick = () => { setSeg($("#imKind"), b.dataset.k); loadContent(); });
$("#imFolder").onclick = () => call("open", { what: "instance", id: curInst.id });
$("#imPlay").onclick = async () => { $("#instModal").hidden = true; go("home"); await call("select", { id: curInst.id }); await refresh(); $("#playBtn").click(); };
$("#imDelete").onclick = async e => {
  if (!confirmTwice(e.currentTarget, "再点一次删除")) return;
  await call("deleteInstance", { id: curInst.id }, "已删除");
  $("#instModal").hidden = true; refresh();
};
$("#imRename").onclick = () => {
  const h = $("#imName");
  h.contentEditable = "true"; h.focus();
  document.getSelection().selectAllChildren(h);
  const done = async () => {
    h.contentEditable = "false"; h.onblur = h.onkeydown = null;
    const n = h.textContent.trim();
    if (n && n !== curInst.name) { await call("renameInstance", { id: curInst.id, name: n }); refresh(); }
    else h.textContent = curInst.name;
  };
  h.onblur = done;
  h.onkeydown = ev => { if (ev.key === "Enter") { ev.preventDefault(); h.blur(); } };
};

/* ---------------------------------------------------------------- resources */
let resOffset = 0, resTotal = 0, resTimer = null, resSeq = 0;
$$("#resKind button").forEach(b => b.onclick = () => { setSeg($("#resKind"), b.dataset.k); search(true); });
$("#resQuery").oninput = () => { clearTimeout(resTimer); resTimer = setTimeout(() => search(true), 350); };
$("#resMore").onclick = () => search(false);
async function search(reset) {
  const kind = segVal($("#resKind"));
  const cur = S?.instances?.find(i => i.id === S.config.selected);
  if (reset) { resOffset = 0; $("#resList").innerHTML = Array(6).fill('<div class="skeleton"></div>').join(""); }
  const seq = ++resSeq;
  const p = { query: $("#resQuery").value.trim(), kind, offset: resOffset };
  if (cur && kind !== "modpack") { p.game = cur.game; if (kind === "mod" && cur.loader) p.loader = cur.loader; }
  try {
    const r = await api("search", p);
    if (seq !== resSeq) return;
    if (reset) $("#resList").innerHTML = "";
    resTotal = r.total_hits; resOffset += r.hits.length;
    const LD = ["fabric", "forge", "neoforge", "quilt"];
    const vanillaMod = kind === "mod" && cur && !cur.loader;
    $("#resList").insertAdjacentHTML("beforeend", r.hits.map((h, k) => {
      const lds = (h.categories || []).filter(c => LD.includes(c));
      const fits = !cur || !cur.loader || lds.includes(cur.loader) || (cur.loader === "quilt" && lds.includes("fabric"));
      const badges = (kind === "mod" || kind === "modpack") && lds.length
        ? `<span class="ld-tags">${lds.map(l => `<span class="ld ld-${l}${cur && cur.loader === l ? " on" : ""}">${LOADER[l]}</span>`).join("")}</span>` : "";
      const blocked = vanillaMod || (kind === "mod" && !fits);
      return `<div class="res glass" style="animation-delay:${k * 25}ms">
      <img src="${h.icon_url ? `/api/img?t=${TOKEN}&u=${encodeURIComponent(h.icon_url)}` : "logo.png"}" alt="" loading="lazy" onerror="this.onerror=null;this.src='logo.png'">
      <div class="meta"><b>${esc(h.title)}</b>${badges}<p>${esc(h.description)}</p><small>${esc(h.author)} · ${fmtCount(h.downloads)} 次下载</small></div>
      ${blocked ? `<button class="btn small ghost" disabled title="${vanillaMod ? "原版实例不能装模组" : "加载器不匹配"}">${vanillaMod ? "需要加载器" : "不兼容"}</button>`
        : `<button class="btn small" data-install="${esc(h.project_id)}" data-title="${esc(h.title)}">${kind === "modpack" ? "安装" : "一键安装"}</button>`}</div>`;
    }).join(""));
    if (vanillaMod && reset) $("#resList").insertAdjacentHTML("afterbegin", `<div class="res-note glass">「${esc(cur.name)}」是原版实例，模组需要加载器才能运行。去 <a data-goto="instances">实例库</a> 新建一个 Fabric / Forge / NeoForge 实例吧。</div>`);
    if (!resOffset) $("#resList").innerHTML = `<div class="empty">没有找到结果${cur && kind !== "modpack" ? `（已按 ${esc(cur.game)} ${esc(LOADER[cur.loader] ?? "")} 筛选）` : ""}</div>`;
    $("#resMore").hidden = resOffset >= resTotal;
    $$("[data-install]").forEach(b => b.onclick = async () => {
      const t = { project: b.dataset.install, title: b.dataset.title };
      if (kind === "modpack") await call("installPack", t);
      else {
        if (!S.config.selected) { toast("先新建一个实例", true); return; }
        await call("installProject", { ...t, instance: S.config.selected });
      }
      b.textContent = "安装中…"; b.disabled = true;
    });
  } catch (e) {
    if (seq !== resSeq) return;
    $("#resList").innerHTML = `<div class="empty">${esc(e.message)}<br><br><button class="btn ghost small" onclick="search(true)">重试</button></div>`;
  }
}
function fmtCount(n) { return n >= 1e8 ? (n / 1e8).toFixed(1) + " 亿" : n >= 1e4 ? (n / 1e4).toFixed(1) + " 万" : n; }

/* ---------------------------------------------------------------- settings */
function curTheme() { return ["white", "black"].includes(S?.config?.theme) ? S.config.theme : "sakura"; }
function renderSettings() {
  const c = S.config;
  $("#featMap").checked = !c.hideMap; $("#featWiki").checked = !c.hideWiki;
  $("#navWiki").hidden = !!c.hideWiki;
  if (c.hideWiki && curPage === "wiki") go("home");
  document.documentElement.dataset.theme = curTheme();
  $$("[data-theme-pick]").forEach(b => b.classList.toggle("on", b.dataset.themePick === curTheme()));
  $$("[data-cfg]").forEach(el => {
    if (document.activeElement === el) return;
    const v = c[el.dataset.cfg];
    if (el.type === "checkbox") el.checked = !!v; else el.value = (v === 0 && el.type === "number") ? "" : (v ?? "");
  });
  const thr = $("[data-cfg-num=threads]"); thr.value = c.threads; $("#thrVal").textContent = c.threads;
  const auto = !c.memoryMB;
  $("#memAuto").checked = auto;
  $("#memAutoHint").textContent = `当前约 ${S.autoMem} MB，有模组时 ${S.autoMemModded} MB`;
  $("#memRow").style.opacity = auto ? .4 : 1;
  const slider = $("#memSlider");
  slider.max = Math.max(2048, (S.memTotal || 8192) - 1024);
  slider.value = c.memoryMB || S.autoMemModded; slider.disabled = auto;
  $("#memVal").textContent = (c.memoryMB || S.autoMemModded) + " MB";
  setSeg($("#javaMode"), c.javaMode || "auto");
  $("#javaPathRow").hidden = c.javaMode !== "manual";
  setSeg($("#srcSeg"), c.source);
  if (S.msBuiltin) {
    $("#msHint").textContent = "用浏览器登录你的微软账户，正版玩家直接可用";
    $("#msIdLabel").textContent = "自定义 Azure 客户端 ID（可选）";
    $("#msIdHint").textContent = "启动器已内置微软登录，不需要填写。只有想改用自己注册的 Azure 应用时才填。";
    $("[data-cfg=msClientId]").placeholder = "留空则使用内置";
  }
}
async function setCfg(patch) { S.config = await call("setConfig", patch); renderSettings(); render(); }
$$("[data-cfg]").forEach(el => el.onchange = () => {
  const k = el.dataset.cfg;
  setCfg({ [k]: el.type === "checkbox" ? el.checked : el.type === "number" ? (parseInt(el.value) || 0) : el.value });
});
$("[data-cfg-num=threads]").oninput = e => $("#thrVal").textContent = e.target.value;
$("[data-cfg-num=threads]").onchange = e => setCfg({ threads: +e.target.value });
$("#memAuto").onchange = e => setCfg({ memoryMB: e.target.checked ? 0 : S.autoMemModded });
$("#memSlider").oninput = e => $("#memVal").textContent = e.target.value + " MB";
$("#memSlider").onchange = e => setCfg({ memoryMB: +e.target.value });
$$("#javaMode button").forEach(b => b.onclick = () => setCfg({ javaMode: b.dataset.v }));
$$("#srcSeg button").forEach(b => b.onclick = () => setCfg({ source: b.dataset.v }));
$("#javaRefresh").onclick = () => loadJava(true);
async function loadJava(refresh) {
  const el = $("#javaList");
  if (refresh) el.innerHTML = `<p class="hint">正在扫描…</p>`;
  const list = await api("javaList", { refresh }).catch(() => []);
  el.innerHTML = (list || []).map(j => `<div class="java-row"><div><b>Java ${j.major}</b> <span class="hint">${esc(j.version)}${j.managed ? " · 启动器下载" : ""}</span><small title="${esc(j.path)}">${esc(j.path)}</small></div>
    ${S.config.javaMode === "manual" ? `<button class="btn ghost small" data-jp="${esc(j.path)}">使用</button>` : ""}</div>`).join("") ||
    `<p class="hint">没有找到 Java。不用担心，启动时会自动下载合适的版本。</p>`;
  $$("[data-jp]").forEach(b => b.onclick = () => setCfg({ javaPath: b.dataset.jp }));
}
$("#addOffline").onclick = async () => {
  const n = $("#offName").value.trim();
  await call("addOffline", { name: n }, "已添加 " + n);
  $("#offName").value = ""; refresh();
};
$("#offName").onkeydown = e => { if (e.key === "Enter") $("#addOffline").click(); };
$("#msLogin").onclick = async () => {
  const r = await call("msLogin");
  const box = $("#msCode");
  box.hidden = false;
  box.innerHTML = `<span class="hint">浏览器已打开 ${esc(r.url)}，输入下面的代码完成登录</span><b>${esc(r.code)}</b><button class="btn ghost small" id="msCancel">取消</button>`;
  $("#msCancel").onclick = () => { call("msCancel"); box.hidden = true; };
};

/* ---------------------------------------------------------------- petals */
(function petals() {
  const cv = $("#petals"), ctx = cv.getContext("2d");
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  let W, H, ps = [];
  const resize = () => { W = cv.width = innerWidth * devicePixelRatio; H = cv.height = innerHeight * devicePixelRatio; };
  resize(); addEventListener("resize", resize);
  const mk = (y) => ({ x: Math.random() * W, y: y ?? -20, r: (5 + Math.random() * 6) * devicePixelRatio, vx: (.2 + Math.random() * .4) * devicePixelRatio,
    vy: (.35 + Math.random() * .45) * devicePixelRatio, a: Math.random() * 6, va: (Math.random() - .5) * .02, ph: Math.random() * 6,
    c: ["#f2b3c2", "#f6c9d3", "#eca3b6"][Math.random() * 3 | 0], o: .35 + Math.random() * .35 });
  for (let i = 0; i < 9; i++) ps.push(mk(Math.random() * H));
  function petal(p) {
    ctx.save(); ctx.translate(p.x, p.y); ctx.rotate(p.a); ctx.globalAlpha = p.o; ctx.fillStyle = p.c;
    ctx.beginPath(); ctx.moveTo(0, -p.r);
    ctx.bezierCurveTo(p.r * .9, -p.r * .6, p.r * .7, p.r * .7, 0, p.r);
    ctx.bezierCurveTo(-p.r * .7, p.r * .7, -p.r * .9, -p.r * .6, 0, -p.r);
    ctx.fill(); ctx.restore();
  }
  // stop completely while the launcher is in the background (e.g. while playing)
  let raf = 0, last = 0;
  const awake = () => !document.hidden && document.hasFocus() && document.documentElement.dataset.theme === "sakura";
  const start = () => { if (!raf && awake()) raf = requestAnimationFrame(frame); };
  addEventListener("focus", start); document.addEventListener("visibilitychange", start);
  new MutationObserver(start).observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });
  function frame(t) {
    if (!awake()) { raf = 0; return; }
    raf = requestAnimationFrame(frame);
    if (t - last < 30) return; // ~33 fps is plenty for drifting petals
    last = t;
    ctx.clearRect(0, 0, W, H);
    for (const p of ps) {
      p.x += (p.vx + Math.sin(t / 1400 + p.ph) * .3) * 1.6; p.y += p.vy * 1.6; p.a += p.va * 1.6;
      if (p.y > H + 20 || p.x > W + 20) Object.assign(p, mk());
      petal(p);
    }
  }
  start();
  setTimeout(start, 500);
})();

/* ---------------------------------------------------------------- window chrome */
if (typeof window.elWindow === "function") {
  const root = document.documentElement;
  root.classList.add("frameless");
  const win = async op => { try { root.classList.toggle("maxed", !!(await window.elWindow(op))); } catch { } };
  win("state");
  const noDrag = "button, a, input, select, textarea, label, [data-inst], .seg, .search";
  document.addEventListener("mousedown", e => {
    if (e.button !== 0) return;
    const rz = e.target.closest("[data-rz]");
    if (rz) { e.preventDefault(); win(rz.dataset.rz); return; }
    if (e.target.closest("[data-drag]") && !e.target.closest(noDrag)) { e.preventDefault(); win("drag"); }
  });
  document.addEventListener("dblclick", e => {
    if (e.target.closest("[data-drag]") && !e.target.closest(noDrag)) win("max");
  });
  $$("[data-win]").forEach(b => b.onclick = () => win(b.dataset.win));
  addEventListener("resize", () => win("state"));
}

/* ---------------------------------------------------------------- boot */
const bootT0 = performance.now();
function hideSplash() {
  const sp = $("#splash");
  if (!sp || sp.classList.contains("done")) return;
  const wait = Math.max(0, 1050 - (performance.now() - bootT0)); // let the line finish its sweep
  setTimeout(() => {
    sp.classList.add("done");
    document.body.classList.add("enter");
    setTimeout(() => { sp.remove(); document.body.classList.remove("enter"); }, 1500);
  }, wait);
}
refresh().then(() => { connect(); hideSplash(); setTimeout(schedulePreloadSkin, 1600); }).catch(e => { hideSplash(); toast("无法连接本地服务：" + e.message, true); });
setInterval(() => { if (!document.hidden && !curTask) refresh().catch(() => {}); }, 15000);

/* ---------------------------------------------------------------- saves */
function fmtSize(n) { return n >= 1 << 30 ? (n / (1 << 30)).toFixed(1) + " GB" : n >= 1 << 20 ? (n / (1 << 20)).toFixed(1) + " MB" : Math.max(1, Math.round(n / 1024)) + " KB"; }
async function loadWorlds() {
  const el = $("#imList");
  const id = curInst.id;
  const ws = await api("worlds", { instance: id }).catch(() => []);
  if (segVal($("#imKind")) !== "world" || curInst.id !== id) return;
  if (!ws?.length) {
    el.innerHTML = `<p class="hint" style="text-align:center;padding:24px">还没有存档。进游戏创建一个世界后，这里会显示它的预览。</p>`;
    return;
  }
  el.innerHTML = `<div class="worlds">` + ws.map((w, k) => `<div class="world${w.cover ? " has-bg" : ""}" style="animation-delay:${k * 40}ms${w.cover ? `;--bg:url('${coverURL("world", id, w.folder, w.cover)}')` : ""}">
    <div class="w-icon">${w.hasIcon ? `<img src="/api/worldicon?t=${TOKEN}&inst=${encodeURIComponent(id)}&world=${encodeURIComponent(w.folder)}" alt="">` : `<img src="${esc(thumbFor(curInst))}" alt="">`}</div>
    <div class="w-meta"><b>${esc(w.name)}</b>
      <div class="w-tags">${w.error ? `<span class="tag red">${esc(w.error)}</span>` : ""}${w.mode ? `<span class="tag">${esc(w.mode)}</span>` : ""}${w.hardcore ? `<span class="tag red">极限</span>` : ""}${w.cheats ? `<span class="tag">作弊</span>` : ""}${w.version ? `<span class="tag">${esc(w.version)}</span>` : ""}</div>
      <small>${w.lastPlayed ? ago(w.lastPlayed) : ""}${w.day ? ` · 第 ${w.day} 天` : ""} · ${fmtSize(w.size)}${w.seed ? ` · 种子 <span class="sel-text">${esc(w.seed)}</span>` : ""}</small></div>
    <div class="w-acts">${S.config.hideMap ? "" : `<button class="btn ghost small" data-wmap="${esc(w.folder)}" data-name="${esc(w.name)}">地图</button>`}<button class="btn ghost small" data-wbg="${esc(w.folder)}" data-name="${esc(w.name)}">背景</button><button class="btn ghost small" data-wopen="${esc(w.folder)}">打开</button><button class="btn ghost small" data-wbak="${esc(w.folder)}" data-name="${esc(w.name)}">备份</button><button class="x" data-wdel="${esc(w.folder)}" title="删除">✕</button></div>
  </div>`).join("") + `</div>`;
  $$("[data-wopen]", el).forEach(b => b.onclick = () => call("openWorld", { instance: id, folder: b.dataset.wopen }));
  $$("[data-wmap]", el).forEach(b => b.onclick = () => openMap(id, b.dataset.wmap, b.dataset.name));
  $$("[data-wbg]", el).forEach(b => b.onclick = () => openCover({ kind: "world", inst: id, world: b.dataset.wbg, title: `存档背景 · ${b.dataset.name}` }));
  $$("[data-wbak]", el).forEach(b => b.onclick = () => call("backupWorld", { instance: id, folder: b.dataset.wbak, name: b.dataset.name }));
  $$("[data-wdel]", el).forEach(b => b.onclick = async () => {
    if (!confirmTwice(b, "删?")) return;
    await call("deleteWorld", { instance: id, folder: b.dataset.wdel }, "存档已删除");
    loadWorlds();
  });
}

/* ---------------------------------------------------------------- covers & backgrounds */
let cvTarget = null;
$("#imCover").onclick = () => openCover({ kind: "inst", inst: curInst.id, title: `实例封面 · ${curInst.name}` });
async function openCover(t) {
  cvTarget = t;
  $("#cvTitle").textContent = t.title;
  $("#coverModal").hidden = false;
  showCoverPreview();
  const shots = await api("screenshots", { instance: t.inst }).catch(() => []);
  $("#cvShotsHint").textContent = shots?.length ? `${shots.length} 张` : "";
  $("#cvShots").innerHTML = shots?.length ? shots.map(x => `<button data-shot="${esc(x.file)}"><img loading="lazy" src="/api/shot?t=${TOKEN}&inst=${encodeURIComponent(t.inst)}&file=${encodeURIComponent(x.file)}" alt=""></button>`).join("")
    : `<p class="hint">这个实例还没有截图。游戏里按 F2 截图，之后就能在这里选。</p>`;
  $$("[data-shot]").forEach(b => b.onclick = async () => {
    await call("coverFromShot", { kind: t.kind, instance: t.inst, world: t.world || "", file: b.dataset.shot });
    coverChanged();
  });
}
async function showCoverPreview() {
  const t = cvTarget, el = $("#cvPreview");
  let v = 0;
  if (t.kind === "inst") { await refresh(); v = (S.instances.find(i => i.id === t.inst) || {}).cover; }
  else { const ws = await api("worlds", { instance: t.inst }).catch(() => []); v = (ws.find(w => w.folder === t.world) || {}).cover; }
  el.style.backgroundImage = v ? `url('${coverURL(t.kind, t.inst, t.world, v)}')` : "";
  el.classList.toggle("empty", !v);
  $("#cvReset").disabled = !v;
}
async function coverChanged() {
  toast(cvTarget.kind === "inst" ? "封面已更新" : "存档背景已更新");
  await showCoverPreview();
  if (cvTarget.kind === "inst" && curInst) { $("#imThumb").src = thumbFor(S.instances.find(i => i.id === curInst.id) || curInst); }
  if (!$("#instModal").hidden && segVal($("#imKind")) === "world") loadWorlds();
  render();
}
async function uploadCover(file) {
  if (!file || !cvTarget) return;
  const t = cvTarget;
  const r = await fetch(`/api/coverupload?kind=${t.kind}&inst=${encodeURIComponent(t.inst)}&world=${encodeURIComponent(t.world || "")}`,
    { method: "POST", headers: { "X-Token": TOKEN }, body: file });
  const j = await r.json().catch(() => ({ error: "上传失败" }));
  if (j.error) { toast(j.error, true); return; }
  coverChanged();
}
$("#cvFile").onchange = e => { uploadCover(e.target.files[0]); e.target.value = ""; };
const drop = $("#cvDrop");
["dragenter", "dragover"].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.add("over"); }));
["dragleave", "drop"].forEach(ev => drop.addEventListener(ev, e => { e.preventDefault(); drop.classList.remove("over"); }));
drop.addEventListener("drop", e => uploadCover(e.dataTransfer.files[0]));
$("#cvReset").onclick = async () => {
  const t = cvTarget;
  await call("coverReset", { kind: t.kind, instance: t.inst, world: t.world || "" });
  toast("已恢复默认");
  await showCoverPreview();
  if (t.kind === "inst" && curInst) $("#imThumb").src = thumbFor(S.instances.find(i => i.id === curInst.id) || curInst);
  if (!$("#instModal").hidden && segVal($("#imKind")) === "world") loadWorlds();
  render();
};

/* ---------------------------------------------------------------- 一键优化 */
async function loadOptimize() {
  const st = await api("optimizeState").catch(() => ({}));
  $("#optWinOnly").hidden = !st.supported;
  $("#optSticky").checked = !st.sticky;
  $("#optGPU").checked = !!S.config.dedicatedGpu;
  const cur = (S.instances || []).find(i => i.id === S.config.selected);
  const ok = cur && cur.loader;
  $("#optMods").disabled = !ok;
  $("#optModsHint").textContent = !cur ? "先选择一个实例" : ok
    ? `为「${cur.name}」安装 Sodium、Lithium、FerriteCore、ModernFix 等，自动跳过不兼容的`
    : `「${cur.name}」是原版实例，需要 Fabric / Forge / NeoForge 才能装模组`;
}
$("#optSticky").onchange = async e => {
  try { await api("setSticky", { on: !e.target.checked }); toast(e.target.checked ? "已关闭粘滞键弹窗" : "已恢复粘滞键快捷键"); }
  catch (err) { toast(err.message, true); }
  loadOptimize();
};
$("#optGPU").onchange = async e => { S.config = await call("setGPU", { on: e.target.checked }, e.target.checked ? "Java 将使用独立显卡" : "已恢复默认显卡"); render(); };
$("#optMods").onclick = () => call("perfMods", { instance: S.config.selected });
$("#optAll").onclick = async () => {
  const b = $("#optAll"); b.disabled = true;
  try { const msg = await call("optimizeAll", { instance: S.config.selected }); toast(msg); await refresh(); loadOptimize(); }
  finally { b.disabled = false; }
};

$$("[data-theme-pick]").forEach(b => b.onclick = () => {
  document.documentElement.dataset.theme = b.dataset.themePick; // instant, then persist
  setCfg({ theme: b.dataset.themePick });
});

/* ---------------------------------------------------------------- world map */
const MAP = { inst: "", world: "", info: null, dim: "overworld", s: 1, ox: 0, oy: 0, tiles: new Map(), raf: 0 };
async function openMap(inst, world, name) {
  Object.assign(MAP, { inst, world, info: null });
  $("#mapTitle").textContent = name;
  $("#mapSub").textContent = "正在读取…";
  $("#mapModal").hidden = false;
  try { MAP.info = await api("mapInfo", { instance: inst, folder: world }); }
  catch (e) { toast(e.message, true); $("#mapModal").hidden = true; return; }
  const i = MAP.info;
  if (i.legacy) $("#mapSub").textContent = "1.13 以前的存档暂不支持地图";
  else $("#mapSub").textContent = (i.version ? i.version + " · " : "") + "拖动平移，滚轮缩放";
  $$("#mapDim button").forEach(b => b.disabled = !(i.dims || {})[b.dataset.v]);
  const dim = i.player && i.dims[i.player.dim] ? i.player.dim : (i.dims.overworld ? "overworld" : Object.keys(i.dims)[0] || "overworld");
  setMapDim(dim, true);
}
function setMapDim(dim, center) {
  MAP.dim = dim;
  setSeg($("#mapDim"), dim);
  MAP.tiles.forEach(t => t.remove()); MAP.tiles.clear();
  $("#mapLayer").innerHTML = "";
  const regs = (MAP.info?.dims || {})[dim] || [];
  $("#mapEmpty").hidden = regs.length > 0;
  MAP.regs = new Set(regs.map(r => r[0] + "," + r[1]));
  // markers live inside the layer and are counter-scaled
  const i = MAP.info || {};
  if (i.spawn && dim === "overworld") addMarker("spawn", i.spawn.x, i.spawn.z, "出生点");
  if (i.player && i.player.dim === dim) addMarker("me", i.player.x, i.player.z, "玩家");
  if (center) mapCenter();
  else mapApply();
}
function addMarker(kind, x, z, label) {
  const m = document.createElement("div");
  m.className = "map-mk mk-" + kind; m.dataset.x = x; m.dataset.z = z;
  m.innerHTML = kind === "me" ? `<canvas width="64" height="64"></canvas><span>${label}</span>` : `<i></i><span>${label}</span>`;
  $("#mapLayer").append(m);
  if (kind === "me" && typeof fetchSkin === "function") fetchSkin(S.config.account || "").then(info => drawHead(m.querySelector("canvas"), info)).catch(() => { });
}
function mapCenter() {
  const v = $("#mapView").getBoundingClientRect(), i = MAP.info || {};
  let x = 0, z = 0;
  const regs = ((i.dims || {})[MAP.dim]) || [];
  if (i.player && i.player.dim === MAP.dim) { x = i.player.x; z = i.player.z; }
  else if (i.spawn && MAP.dim === "overworld") { x = i.spawn.x; z = i.spawn.z; }
  else if (regs.length) { x = regs.reduce((a, r) => a + r[0], 0) / regs.length * 512 + 256; z = regs.reduce((a, r) => a + r[1], 0) / regs.length * 512 + 256; }
  MAP.s = Math.max(MAP.s, .5);
  MAP.ox = v.width / 2 - x * MAP.s; MAP.oy = v.height / 2 - z * MAP.s;
  mapApply();
}
function mapApply() {
  const L = $("#mapLayer");
  L.style.transform = `translate(${MAP.ox}px,${MAP.oy}px) scale(${MAP.s})`;
  $$(".map-mk", L).forEach(m => { m.style.transform = `translate(${m.dataset.x}px,${m.dataset.z}px) scale(${1 / MAP.s})`; });
  if (!MAP.raf) MAP.raf = requestAnimationFrame(() => { MAP.raf = 0; mapTiles(); });
}
function mapTiles() {
  const v = $("#mapView").getBoundingClientRect();
  const x0 = Math.floor((-MAP.ox) / MAP.s / 512) - 1, x1 = Math.floor((v.width - MAP.ox) / MAP.s / 512) + 1;
  const z0 = Math.floor((-MAP.oy) / MAP.s / 512) - 1, z1 = Math.floor((v.height - MAP.oy) / MAP.s / 512) + 1;
  if ((x1 - x0) * (z1 - z0) > 400) return; // zoomed far out: don't flood the renderer
  for (let rx = x0; rx <= x1; rx++) for (let rz = z0; rz <= z1; rz++) {
    const k = rx + "," + rz;
    if (!MAP.regs.has(k) || MAP.tiles.has(k)) continue;
    const t = document.createElement("img");
    t.className = "map-tile loading"; t.style.left = rx * 512 + "px"; t.style.top = rz * 512 + "px";
    t.onload = () => t.classList.remove("loading");
    t.onerror = () => t.classList.add("bad");
    t.src = `/api/maptile?t=${TOKEN}&inst=${encodeURIComponent(MAP.inst)}&world=${encodeURIComponent(MAP.world)}&dim=${MAP.dim}&rx=${rx}&rz=${rz}`;
    $("#mapLayer").prepend(t);
    MAP.tiles.set(k, t);
  }
}
function mapZoom(f, cx, cy) {
  const v = $("#mapView").getBoundingClientRect();
  if (cx == null) { cx = v.width / 2; cy = v.height / 2; }
  const s2 = Math.min(8, Math.max(.125, MAP.s * f));
  MAP.ox = cx - (cx - MAP.ox) * (s2 / MAP.s); MAP.oy = cy - (cy - MAP.oy) * (s2 / MAP.s); MAP.s = s2;
  mapApply();
}
(() => {
  const V = $("#mapView");
  let drag = null;
  V.addEventListener("pointerdown", e => { if (e.target.closest(".map-ctl")) return; drag = { x: e.clientX, y: e.clientY, ox: MAP.ox, oy: MAP.oy }; V.setPointerCapture(e.pointerId); V.classList.add("grab"); });
  V.addEventListener("pointermove", e => {
    const r = V.getBoundingClientRect();
    const bx = Math.floor((e.clientX - r.left - MAP.ox) / MAP.s), bz = Math.floor((e.clientY - r.top - MAP.oy) / MAP.s);
    $("#mapCoord").textContent = `X ${bx}   Z ${bz}`;
    if (!drag) return;
    MAP.ox = drag.ox + e.clientX - drag.x; MAP.oy = drag.oy + e.clientY - drag.y; mapApply();
  });
  const up = () => { drag = null; V.classList.remove("grab"); };
  V.addEventListener("pointerup", up); V.addEventListener("pointercancel", up);
  V.addEventListener("wheel", e => { e.preventDefault(); const r = V.getBoundingClientRect(); mapZoom(e.deltaY < 0 ? 1.25 : .8, e.clientX - r.left, e.clientY - r.top); }, { passive: false });
  $("#mapIn").onclick = () => mapZoom(1.5); $("#mapOut").onclick = () => mapZoom(1 / 1.5); $("#mapMe").onclick = mapCenter;
  $$("#mapDim button").forEach(b => b.onclick = () => setMapDim(b.dataset.v, true));
})();

/* ---------------------------------------------------------------- embedded Minecraft Wiki */
const WIKI = "https://zh.minecraft.wiki/";
const hasPanes = () => typeof window.elPane === "function";
let wikiUrl = WIKI;
function paneCall(name, op, arg = "") { if (hasPanes()) window.elPane(name, op, 0, 0, 0, 0, arg).catch(() => { }); }
// lay the native view exactly over its host box (physical pixels); hide it whenever
// its page is off-screen or any dialog/menu is open (a native view would cover them)
let paneRaf = 0, paneLast = "";
function syncWiki() {
  if (!hasPanes() || paneRaf) return;
  paneRaf = requestAnimationFrame(() => {
    paneRaf = 0;
    const on = $("#page-wiki").classList.contains("on") && !S?.config?.hideWiki;
    const covered = $$(".modal, .popover").some(m => !m.hidden) || !!$("#splash");
    if (!on || covered) { if (paneLast !== "hide") { paneLast = "hide"; paneCall("wiki", "hide"); } return; }
    const r = $("#wikiHost").getBoundingClientRect(), k = devicePixelRatio || 1;
    const rect = [r.left, r.top, r.width, r.height].map(v => Math.round(v * k));
    const sig = rect.join(",");
    if (sig === paneLast) return; // unchanged: don't poke the native window
    paneLast = sig;
    window.elPane("wiki", "show", ...rect, "").catch(() => { });
  });
}
window.onPaneLoc = (name, u, title) => {
  if (name !== "wiki") return;
  wikiUrl = u;
  $("#wikiTitle").textContent = title.replace(/ - Minecraft Wiki.*$/, "");
};
if (hasPanes()) {
  $("#wikiFallback").hidden = true;
  new ResizeObserver(syncWiki).observe($("#wikiHost"));
  addEventListener("resize", syncWiki);
  // only dialogs/menus opening or closing matter: watch those, not every DOM change
  const mo = new MutationObserver(syncWiki);
  $$(".modal, .popover, #page-wiki").forEach(el => mo.observe(el, { attributes: true, attributeFilter: ["hidden", "class"] }));
}
function openOutside(u) { call("open", { what: "url", id: u }); }
$$("[data-wk]").forEach(b => b.onclick = () => b.dataset.wk === "home" ? paneCall("wiki", "nav", WIKI) : paneCall("wiki", b.dataset.wk));
function wikiGo(u) { if (hasPanes() && !S?.config?.hideWiki) { go("wiki"); paneCall("wiki", "nav", u); } else openOutside(u); }
function wikiSearch(q) { wikiGo(WIKI + "?search=" + encodeURIComponent(q)); }
$("#wikiQ").onkeydown = e => {
  if (e.key !== "Enter") return;
  const q = e.target.value.trim(); if (q) wikiSearch(q);
};
$("#wikiOpen").onclick = $("#wikiOpen2").onclick = () => openOutside(hasPanes() ? wikiUrl : WIKI);
function renderWikiChips() {
  const cur = curInstance();
  const chips = [["合成", "合成"], ["附魔", "附魔"], ["酿造", "酿造"], ["生物", "生物"], ["方块", "方块"], ["红石电路", "红石电路"], ["进度", "进度"]];
  if (cur && /^\d/.test(cur.game)) chips.unshift(["Java版" + cur.game + " 更新内容", "Java版" + cur.game]);
  $("#wikiChips").innerHTML = chips.map(([l, p]) => `<button class="chip" data-wp="${esc(p)}">${esc(l)}</button>`).join("");
  $$("[data-wp]").forEach(b => b.onclick = () => wikiGo(WIKI + "w/" + encodeURIComponent(b.dataset.wp)));
}
// feature switches
$("#featMap").onchange = e => setCfg({ hideMap: !e.target.checked });
$("#featWiki").onchange = e => setCfg({ hideWiki: !e.target.checked });
$("#wikiClear").onclick = () => { paneCall("wiki", "clear"); toast(hasPanes() ? "网页缓存会在下次启动时清除" : "当前环境没有网页缓存"); };

/* ---------------------------------------------------------------- 快速搜索 Ctrl+K */
const PAGES = [["home", "首页", "shouye sy home"], ["skin", "角色", "juese js skin 皮肤 账户 pifu"], ["instances", "实例库", "shili slk instances 版本"],
  ["resources", "资源中心", "ziyuan zy mods 模组 光影 资源包 整合包"], ["wiki", "Minecraft Wiki", "wiki 百科 维基"],
  ["diagnose", "诊断中心", "zhenduan zd 崩溃 日志 log"], ["settings", "设置", "shezhi sz settings 内存 java 下载"]];
const ICON = {
  page: '<path d="M5 4h9l5 5v11H5z"/><path d="M14 4v5h5"/>', play: '<path d="M8 5.5v13l10.5-6.5z"/>', box: '<rect x="4" y="4" width="16" height="16" rx="3"/>',
  act: '<path d="M13 3 5 13.5h6L10 21l8-10.5h-6z"/>', search: '<circle cx="11" cy="11" r="6.5"/><path d="m16 16 4.5 4.5"/>', book: '<path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H11v16H5.5A1.5 1.5 0 0 1 4 18.5zM20 5.5A1.5 1.5 0 0 0 18.5 4H13v16h5.5a1.5 1.5 0 0 0 1.5-1.5z"/>',
  srv: '<rect x="4" y="4" width="16" height="7" rx="2"/><rect x="4" y="13" width="16" height="7" rx="2"/><path d="M8 7.5h.01M8 16.5h.01"/>', theme: '<circle cx="12" cy="12" r="8"/><path d="M12 4v16"/>'
};
function palItems(q) {
  const items = [];
  const cur = curInstance();
  for (const i of S?.instances || []) {
    items.push({ icon: "play", title: "启动 " + i.name, sub: `${i.game} ${LOADER[i.loader] ?? ""}`, keys: i.id + " qidong start launch", run: async () => {
      if (S.config.selected !== i.id) { await call("select", { id: i.id }); await refresh(); }
      go("home"); $("#playBtn").click();
    } });
    items.push({ icon: "box", title: "打开 " + i.name, sub: "存档、服务器、模组、资源包", keys: i.id + " dakai open", run: () => openInstance(i.id) });
  }
  for (const [p, t, k] of PAGES) items.push({ icon: "page", title: t, sub: "页面", keys: k, run: () => go(p) });
  const act = (title, sub, keys, run, icon = "act") => items.push({ icon, title, sub, keys, run });
  act("新建实例", "原版、Fabric、Forge、NeoForge…", "xinjian new create", openNew);
  act("导入整合包", ".mrpack 文件", "daoru import mrpack modpack", () => { go("instances"); $("#mrpackFile").click(); });
  if (cur?.loader) act("检查模组更新", "「" + cur.name + "」", "gengxin update mods jiancha", async () => { await openInstance(cur.id); setSeg($("#imKind"), "mod"); await loadContent(); checkUpdates("mod"); });
  if (cur) act("服务器列表", "「" + cur.name + "」的多人游戏服务器", "fuwuqi server duoren multiplayer", async () => { await openInstance(cur.id); setSeg($("#imKind"), "server"); loadContent(); }, "srv");
  act("一键优化", "粘滞键、显卡、性能模组", "youhua optimize", () => { go("settings"); setTimeout(() => $("#optAll").scrollIntoView({ behavior: "smooth", block: "center" }), 80); });
  for (const [v, n] of [["sakura", "樱花"], ["white", "纯白"], ["black", "纯黑"]]) if (curTheme() !== v)
    act("切换到" + n + "主题", "外观", "zhuti theme " + v + " " + n, () => { document.documentElement.dataset.theme = v; setCfg({ theme: v }); }, "theme");
  act("导出日志", "保存最近一次游戏日志", "rizhi log export", () => call("exportLog", {}, "日志已导出"));
  act("打开游戏目录", ".minecraft", "mulu folder open gamedir", () => call("open", { what: "gameDir" }));
  act("重新扫描 Java", "设置 · 内存与 Java", "java saomiao scan", () => { go("settings"); loadJava(true); });
  if (q) {
    if (/^[A-Za-z0-9.\-]+\.[A-Za-z]{2,}(:\d{1,5})?$|^\d{1,3}(\.\d{1,3}){3}(:\d{1,5})?$/.test(q) && cur)
      items.push({ icon: "srv", title: "加入服务器 " + q, sub: "用「" + cur.name + "」启动并直接进入", keys: q, always: true, run: () => joinServer(cur.id, q) });
    items.push({ icon: "search", title: `在资源中心搜索「${q}」`, sub: "Modrinth 模组、光影、资源包、整合包", keys: q, always: true, run: () => { go("resources"); $("#resQuery").value = q; search(true); } });
    if (!S?.config?.hideWiki) items.push({ icon: "book", title: `在 Wiki 搜索「${q}」`, sub: "zh.minecraft.wiki", keys: q, always: true, run: () => wikiSearch(q) });
  }
  if (!q) return items.filter(x => x.icon === "page" || /^启动/.test(x.title) && x.title === "启动 " + (cur?.name ?? "") || x.icon === "act").slice(0, 14);
  const ql = q.toLowerCase();
  const score = x => {
    if (x.always) return 0.1;
    const t = x.title.toLowerCase(), k = (x.keys || "").toLowerCase();
    if (t.startsWith(ql) || t.includes(" " + ql)) return 4;
    if (t.includes(ql)) return 3;
    if (k.split(" ").some(w => w.startsWith(ql))) return 2;
    if (k.includes(ql)) return 1.5;
    let j = 0; for (const ch of t) if (ch === ql[j]) j++;
    return j === ql.length ? 1 : 0;
  };
  return items.map(x => [score(x), x]).filter(([v]) => v > 0).sort((a, b) => b[0] - a[0]).map(([, x]) => x).slice(0, 12);
}
let palSel = 0, palList = [];
function renderPalette() {
  const q = $("#palQ").value.trim();
  palList = palItems(q);
  palSel = Math.min(palSel, Math.max(0, palList.length - 1));
  $("#palList").innerHTML = palList.map((x, k) => `<button class="pal-item${k === palSel ? " on" : ""}" data-pi="${k}">
    <svg viewBox="0 0 24 24">${ICON[x.icon] || ICON.act}</svg><span><b>${esc(x.title)}</b><small>${esc(x.sub || "")}</small></span></button>`).join("") ||
    `<p class="hint" style="text-align:center;padding:22px">没有找到「${esc(q)}」</p>`;
}
function openPalette() {
  if (!S) return;
  const m = $("#palette");
  if (!m.hidden) { closePalette(); return; }
  $$(".modal,.popover").forEach(x => x.hidden = true);
  m.hidden = false; $("#palQ").value = ""; palSel = 0; renderPalette();
  requestAnimationFrame(() => $("#palQ").focus());
}
function closePalette() { $("#palette").hidden = true; }
function runPal(k) { const x = palList[k]; if (!x) return; closePalette(); Promise.resolve().then(x.run).catch(e => toast(e.message, true)); }
$("#palQ").oninput = () => { palSel = 0; renderPalette(); };
$("#palQ").onkeydown = e => {
  if (e.key === "ArrowDown" || e.key === "ArrowUp") {
    e.preventDefault();
    palSel = (palSel + (e.key === "ArrowDown" ? 1 : -1) + palList.length) % Math.max(1, palList.length);
    $$(".pal-item").forEach((b, k) => b.classList.toggle("on", k === palSel));
    $(".pal-item.on")?.scrollIntoView({ block: "nearest" });
  } else if (e.key === "Enter") { e.preventDefault(); runPal(palSel); }
};
$("#palList").onclick = e => { const b = e.target.closest("[data-pi]"); if (b) runPal(+b.dataset.pi); };
$("#palList").onmousemove = e => {
  const b = e.target.closest("[data-pi]"); if (!b || +b.dataset.pi === palSel) return;
  palSel = +b.dataset.pi; $$(".pal-item").forEach((x, k) => x.classList.toggle("on", k === palSel));
};
$("#palBtn").onclick = openPalette;

/* ---------------------------------------------------------------- keyboard & browser behaviour */
// It's an app, not a web page: block the browser's reload / print / save / zoom /
// history shortcuts (a stray F5 used to reload the whole UI mid-download).
document.addEventListener("keydown", e => {
  const k = e.key.toLowerCase(), mod = e.ctrlKey || e.metaKey;
  if (mod && (k === "k" || k === "f")) { e.preventDefault(); openPalette(); return; }
  if (mod && !e.shiftKey && !e.altKey && /^[1-7]$/.test(k)) {
    e.preventDefault();
    const vis = PAGES.filter(([p]) => !(p === "wiki" && S?.config?.hideWiki));
    const p = vis[+k - 1]; if (p) { $$(".modal,.popover").forEach(x => x.hidden = true); go(p[0]); }
    return;
  }
  const block = k === "f5" || k === "f3" || k === "f7" || (k === "f12" && !window.__elDebug) ||
    (mod && ["r", "p", "s", "u", "j", "h", "g", "o", "n", "t", "w", "+", "-", "=", "0"].includes(k)) ||
    (mod && e.shiftKey && ["i", "c", "j", "delete"].includes(k) && !window.__elDebug) ||
    (e.altKey && (k === "arrowleft" || k === "arrowright" || k === "home")) || k === "browserback" || k === "browserforward";
  if (block) e.preventDefault();
}, true);
addEventListener("wheel", e => { if (e.ctrlKey) e.preventDefault(); }, { passive: false }); // no page zoom
addEventListener("mouseup", e => { if (e.button === 3 || e.button === 4) e.preventDefault(); }); // mouse back/forward
document.addEventListener("contextmenu", e => {
  const t = e.target;
  if (t.closest("input, textarea, [contenteditable=true], .sel-text, #logView") || String(getSelection())) return;
  e.preventDefault();
});
document.addEventListener("dragover", e => { if (!e.target.closest(".cv-drop")) e.preventDefault(); });
// a dropped file must never navigate the UI away; a dropped .mrpack is imported
document.addEventListener("drop", e => {
  if (e.target.closest(".cv-drop")) return;
  e.preventDefault();
  const f = e.dataTransfer?.files?.[0];
  if (f && /\.mrpack$/i.test(f.name)) importPack(f);
});
