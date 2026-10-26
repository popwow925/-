"use strict";
/* EririLily for Android — iOS-style UI over the launcher core's RPC. */
const TOKEN = location.hash.slice(1);
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const N = window.ELNative || null; // the Android shell (absent in a desktop browser preview)
const LOADER = { fabric: "Fabric", quilt: "Quilt", forge: "Forge", neoforge: "NeoForge", "": "原版" };

async function api(name, params = {}) {
  const r = await fetch("/api/" + name, { method: "POST", headers: { "X-Token": TOKEN, "Content-Type": "application/json" }, body: JSON.stringify(params) });
  const j = await r.json().catch(() => ({ error: "服务无响应" }));
  if (j.error) throw new Error(j.error);
  return j.result;
}
async function call(name, params, okMsg) {
  try { const r = await api(name, params); if (okMsg) toast(okMsg); return r; }
  catch (e) { toast(e.message, true); haptic("error"); throw e; }
}
function haptic(kind = "light") { try { N?.haptic(kind); } catch { } }
function stored(k, d = null) { try { const v = localStorage.getItem("el." + k); return v == null ? d : JSON.parse(v); } catch { return d; } }
function store(k, v) { try { localStorage.setItem("el." + k, JSON.stringify(v)); } catch { } }
function el(html) { const t = document.createElement("template"); t.innerHTML = html.trim(); return t.content.firstElementChild; }
const sleep = ms => new Promise(r => setTimeout(r, ms));
function fmtBytes(n) { if (!n) return "0 B"; const u = ["B", "KB", "MB", "GB"]; let i = 0; while (n >= 1024 && i < 3) { n /= 1024; i++; } return n.toFixed(i ? 1 : 0) + " " + u[i]; }
function ago(ts) {
  if (!ts) return "还没玩过";
  const d = Date.now() / 1000 - ts;
  if (d < 3600) return Math.max(1, Math.round(d / 60)) + " 分钟前";
  if (d < 86400) return Math.round(d / 3600) + " 小时前";
  return Math.round(d / 86400) + " 天前";
}
function hours(sec) { return sec < 3600 ? Math.max(1, Math.round(sec / 60)) + " 分钟" : (sec / 3600).toFixed(1) + " 小时"; }
function gb(mb) { return (mb / 1024).toFixed(mb >= 10240 ? 0 : 1) + " GB"; }
function nfmt(n) { return n >= 1e8 ? (n / 1e8).toFixed(1) + " 亿" : n >= 1e4 ? (n / 1e4).toFixed(1) + " 万" : String(n); }

/* ---------------------------------------------------------------- icons (SF Symbols-like) */
const P = {
  chev: '<path d="m9 5 7 7-7 7"/>', back: '<path d="m15 4-8 8 8 8"/>', plus: '<path d="M12 5v14M5 12h14"/>',
  play: '<path class="f" d="M7 4.5v15a1 1 0 0 0 1.5.86l12.4-7.5a1 1 0 0 0 0-1.72L8.5 3.64A1 1 0 0 0 7 4.5z" fill="currentColor" stroke="none"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/>',
  cube: '<path d="m12 3 8 4.5v9L12 21l-8-4.5v-9z"/><path d="m4 7.5 8 4.5 8-4.5M12 12v9"/>',
  puzzle: '<path d="M10 4a2 2 0 1 1 4 0v2h4v4h-2a2 2 0 1 0 0 4h2v4h-4v-2a2 2 0 1 0-4 0v2H6v-4h2a2 2 0 1 0 0-4H6V6h4z"/>',
  paint: '<path d="M19 3 9 13l2 2L21 5zM9 13c-3 0-4 2-4 4s-1 3-2 3c4 1 8 0 8-4"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 3 2.5 15 0 18M12 3c-2.5 3-2.5 15 0 18"/>',
  server: '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01"/>',
  photo: '<rect x="3" y="5" width="18" height="14" rx="3"/><circle cx="9" cy="10" r="1.6"/><path d="m21 16-5-5-9 8"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
  sliders: '<path d="M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0"/><circle cx="16" cy="6" r="2"/><circle cx="10" cy="12" r="2"/><circle cx="18" cy="18" r="2"/>',
  bolt: '<path d="M13 2 4 14h7l-1 8 9-12h-7z" fill="currentColor" stroke="none"/>',
  cpu: '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>',
  memory: '<rect x="3" y="7" width="18" height="10" rx="2"/><path d="M7 11v2M11 11v2M15 11v2M7 17v3M12 17v3M17 17v3"/>',
  gamepad: '<path d="M7 9h4M9 7v4M15 10h.01M17.5 12h.01"/><path d="M6.5 5h11A4.5 4.5 0 0 1 22 9.5c0 3-1 9.5-4 9.5-2 0-2.5-3-6-3s-4 3-6 3c-3 0-4-6.5-4-9.5A4.5 4.5 0 0 1 6.5 5z"/>',
  keyboard: '<rect x="2" y="6" width="20" height="12" rx="2"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
  trash: '<path d="M4 7h16M10 11v6M14 11v6M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2l1-12M9 7V4h6v3"/>',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7"/>',
  down: '<path d="M12 4v12M6 11l6 6 6-6M5 20h14"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>', x: '<path d="M6 6l12 12M18 6 6 18"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5h.01"/>',
  doc: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h6"/>',
  shirt: '<path d="M8 3 3 6l2 5 3-1v11h8V10l3 1 2-5-5-3a4 4 0 0 1-8 0z"/>',
  palette: '<path d="M12 3a9 9 0 0 0 0 18c1.5 0 2-1 2-2s-1-1.5-1-2.5 1-1.5 2-1.5h2a4 4 0 0 0 4-4c0-4.5-4-8-9-8z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10" cy="7" r="1"/><circle cx="15" cy="7.5" r="1"/>',
  wrench: '<path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.8-.6-.6-2.8z"/>',
  spark: '<path d="M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5 18 18M6 18l2.5-2.5M15.5 8.5 18 6"/>',
  wand: '<path d="m15 4 1 2 2 1-2 1-1 2-1-2-2-1 2-1zM5 19 15 9M19 13l.7 1.3L21 15l-1.3.7L19 17l-.7-1.3L17 15l1.3-.7z"/>',
  up: '<circle cx="12" cy="12" r="9"/><path d="M12 16V8M8.5 11.5 12 8l3.5 3.5"/>',
  link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
  share: '<path d="M12 3v12M8 7l4-4 4 4M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7"/>',
  pencil: '<path d="M4 20h4L19 9l-4-4L4 16zM14 6l4 4"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>',
  java: '<path d="M8 18c3 1.5 7 1 9-.5M7 15c4 1.5 8 1 10-.5M10 3c4 3-3 5 1 9M14 7c2 2-2 3 0 5M17 18.5c1.5-.5 2-1.5 1-2"/>',
  phone: '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M11 18h2"/>',
  thermo: '<path d="M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0z"/>',
  layers: '<path d="m12 3 9 5-9 5-9-5z"/><path d="m3 13 9 5 9-5"/>',
  hand: '<path d="M8 13V5a1.5 1.5 0 0 1 3 0v6M11 11V3.5a1.5 1.5 0 0 1 3 0V11M14 11V5a1.5 1.5 0 0 1 3 0v8c0 4-3 8-7 8-3 0-5-2-6.5-5L2 13a1.5 1.5 0 0 1 2.5-1.5L8 15"/>',
  box: '<path d="M21 8 12 3 3 8v8l9 5 9-5z"/><path d="m3 8 9 5 9-5M12 13v8"/>',
  stack: '<rect x="4" y="4" width="16" height="6" rx="2"/><rect x="4" y="14" width="16" height="6" rx="2"/>',
};
const ic = (name, cls = "") => `<svg viewBox="0 0 24 24" class="${cls}">${P[name] || ""}</svg>`;
const COL = { pink: "#ff2d55", red: "#ff3b30", orange: "#ff9500", yellow: "#ffcc00", green: "#34c759", teal: "#30b0c7", blue: "#007aff", indigo: "#5856d6", purple: "#af52de", gray: "#8e8e93", brown: "#a2845e" };

/* ---------------------------------------------------------------- theme & system */
let S = null; // core state
let A = null; // Android info
function applyTheme(theme) {
  theme = theme || "sakura";
  const sysDark = matchMedia("(prefers-color-scheme: dark)").matches;
  const mode = theme === "black" ? "dark" : theme === "white" ? "light" : sysDark ? "dark" : "light";
  document.documentElement.dataset.theme = theme;
  document.documentElement.dataset.mode = mode;
  try { N?.statusBar(mode === "light"); } catch { }
}
window.elSystemTheme = () => applyTheme(S?.config?.theme);
matchMedia("(prefers-color-scheme: dark)").addEventListener?.("change", window.elSystemTheme);
window.elInsets = (t, r, b, l) => {
  const s = document.documentElement.style;
  s.setProperty("--sat", t + "px"); s.setProperty("--sar", r + "px"); s.setProperty("--sab", b + "px"); s.setProperty("--sal", l + "px");
};
window.elToast = (m, err) => toast(m, err);
window.elResume = () => refresh();

/* ---------------------------------------------------------------- toasts */
function toast(msg, err = false) {
  const t = el(`<div class="toast${err ? " err" : ""}">${ic(err ? "info" : "check")}<span>${esc(msg)}</span></div>`);
  $("#toasts").append(t);
  while ($("#toasts").children.length > 3) $("#toasts").firstElementChild.remove();
  setTimeout(() => { t.classList.add("out"); setTimeout(() => t.remove(), 300); }, err ? 4200 : 2600);
}

/* ---------------------------------------------------------------- Dynamic Island */
const island = {
  el: $("#island"), timer: 0, label: "",
  start(label) {
    clearTimeout(this.timer);
    this.label = label;
    const e = this.el;
    e.classList.remove("hidden");
    $(".ic", e).className = "ic spin";
    $(".tx", e).textContent = label; $(".pc", e).textContent = "";
    $(".info", e).textContent = "准备中…"; $(".bar i", e).style.width = "0";
    try { N?.setTask(label, "准备中", -1); } catch { }
  },
  stage(s) { $(".info", this.el).textContent = s; $(".tx", this.el).textContent = this.label + (s ? " · " + s : ""); },
  progress(f, info, stage) {
    const pct = Math.round(f * 100);
    const ic = $(".ic", this.el); ic.className = "ic"; ic.style.setProperty("--p", pct);
    $(".pc", this.el).textContent = pct + "%";
    $(".bar i", this.el).style.width = pct + "%";
    $(".info", this.el).textContent = (stage ? stage + " · " : "") + info;
    try { N?.setTask(this.label, (stage || "") + " " + info, pct); } catch { }
  },
  finish(ok, msg) {
    const e = this.el;
    e.classList.remove("expanded");
    $(".ic", e).className = "ic " + (ok ? "ok" : "bad");
    $(".tx", e).textContent = msg || (ok ? "完成" : "失败"); $(".pc", e).textContent = "";
    try { N?.setTask("", "", -1); } catch { }
    this.timer = setTimeout(() => e.classList.add("hidden"), ok ? 1800 : 3500);
  },
};
island.el.onclick = e => {
  if (e.target.closest(".cancel")) { call("cancel"); return; }
  island.el.classList.toggle("expanded"); haptic();
};

/* ---------------------------------------------------------------- navigation stacks */
const tabs = {};
let curTab = "home";
class Stack {
  constructor(name, root) { this.name = name; this.el = $("#tab-" + name); this.pages = []; this.push(root, false); }
  get top() { return this.pages[this.pages.length - 1]; }
  push(def, animate = true) {
    const prev = this.top;
    const p = makePage(def, this, prev);
    this.el.append(p.el);
    this.pages.push(p);
    if (prev) prev.el.classList.remove("top");
    p.el.classList.add("top");
    if (animate) {
      p.el.classList.add("enter");
      requestAnimationFrame(() => requestAnimationFrame(() => { p.el.classList.remove("enter"); prev?.el.classList.add("under"); }));
      haptic("select");
    } else prev?.el.classList.add("under");
    $("#tabbar").classList.toggle("away", !!def.noTab);
    return p;
  }
  pop(animate = true) {
    if (this.pages.length < 2) return false;
    const p = this.pages.pop();
    const prev = this.top;
    p.def.onClose?.(p);
    prev.el.classList.remove("under"); prev.el.classList.add("top");
    if (animate) { p.el.classList.add("leave"); setTimeout(() => p.el.remove(), 480); } else p.el.remove();
    $("#tabbar").classList.toggle("away", !!prev.def.noTab);
    prev.refresh?.();
    return true;
  }
  popToRoot() { while (this.pages.length > 1) this.pop(this.pages.length === 2); }
}

function makePage(def, stack, prev) {
  const p = { def, stack };
  p.el = el(`<div class="page${def.noTab ? " no-tab" : ""}">
    <header class="nav${def.large === false && !def.cover ? " always" : ""}${def.cover ? " over-cover" : ""}">
      ${prev ? `<button class="back">${ic("back")}<span>${esc(prev.def.short || prev.def.title || "返回")}</span></button>` : ""}
      <div class="title">${esc(def.title || "")}</div><div class="right"></div></header>
    <div class="scroll">${def.large === false ? "" : `<div class="large"><div>${esc(def.title || "")}</div><div class="lx"></div></div>`}<div class="body"></div></div></div>`);
  p.nav = $(".nav", p.el); p.scroll = $(".scroll", p.el); p.body = $(".body", p.el);
  p.right = $(".right", p.nav); p.lx = $(".lx", p.el);
  $(".back", p.nav)?.addEventListener("click", () => stack.pop());
  const th = def.cover ? 150 : def.large === false ? 4 : 38;
  p.scroll.addEventListener("scroll", () => p.nav.classList.toggle("solid", p.scroll.scrollTop > th), { passive: true });
  p.refresh = async () => { try { await def.render(p); } catch (e) { console.error(e); } };
  swipeBack(p, stack);
  p.refresh();
  return p;
}

// iOS edge swipe to go back
function swipeBack(p, stack) {
  let x0 = 0, dx = 0, on = false, t0 = 0;
  p.el.addEventListener("touchstart", e => {
    const t = e.touches[0];
    if (stack.pages.length < 2 || stack.top !== p || t.clientX > 28) return;
    on = true; x0 = t.clientX; dx = 0; t0 = Date.now();
    p.el.classList.add("dragging");
  }, { passive: true });
  p.el.addEventListener("touchmove", e => {
    if (!on) return;
    dx = Math.max(0, e.touches[0].clientX - x0);
    p.el.style.transform = `translateX(${dx}px)`;
    const prev = stack.pages[stack.pages.length - 2];
    prev.el.style.transition = "none";
    prev.el.style.transform = `translateX(${-30 + dx / innerWidth * 30}%)`;
  }, { passive: true });
  const end = () => {
    if (!on) return;
    on = false;
    p.el.classList.remove("dragging");
    const prev = stack.pages[stack.pages.length - 2];
    prev.el.style.transition = ""; prev.el.style.transform = "";
    p.el.style.transform = "";
    const fast = dx / Math.max(1, Date.now() - t0) > .5;
    if (dx > innerWidth * .35 || (fast && dx > 40)) stack.pop();
  };
  p.el.addEventListener("touchend", end);
  p.el.addEventListener("touchcancel", end);
}

function switchTab(name) {
  if (name === curTab) { tabs[name].pages.length > 1 ? tabs[name].popToRoot() : tabs[name].top.scroll.scrollTo({ top: 0, behavior: "smooth" }); return; }
  curTab = name;
  haptic("select");
  $$(".tab").forEach(t => t.classList.toggle("on", t.id === "tab-" + name));
  $$("#tabbar button").forEach(b => b.classList.toggle("on", b.dataset.tab === name));
  $("#tabbar").classList.toggle("away", !!tabs[name].top.def.noTab);
  tabs[name].top.refresh();
}
$$("#tabbar button").forEach(b => b.onclick = () => switchTab(b.dataset.tab));
const push = def => tabs[curTab].push(def);

/* ---------------------------------------------------------------- list builders */
function section(title, rows, foot, big) {
  const s = el(`<div class="section">${title ? `<h3 class="${big ? "big" : ""}">${title}</h3>` : ""}<div class="list"></div>${foot ? `<div class="foot">${foot}</div>` : ""}</div>`);
  const l = $(".list", s);
  for (const r of rows.flat()) if (r) l.append(r);
  return s;
}
// row({ icon, color, img, title, sub, val, chev, onClick, right, cls, danger })
function row(o) {
  const tag = o.onClick ? "button" : "div";
  const r = el(`<${tag} class="cell${o.onClick ? "" : " static"}${o.icon || o.img || o.avatar ? " icon-inset" : ""}${o.tall ? " tall" : ""}${o.cls ? " " + o.cls : ""}">
    ${o.icon ? `<span class="ico" style="background:${COL[o.color] || o.color || "var(--tint)"}">${ic(o.icon)}</span>` : ""}
    ${o.img ? `<img class="thumb${o.px ? " px" : ""}" src="${esc(o.img)}" alt="" loading="lazy" onerror="this.style.visibility='hidden'">` : ""}
    ${o.avatar ? `<span class="avatar" data-head="${esc(o.avatar)}"></span>` : ""}
    ${o.title != null ? `<span class="main"><b class="${o.strong ? "strong" : ""}">${o.titleHTML || esc(o.title)}</b>${o.sub ? `<small class="${o.wrap ? "wrap" : ""}">${o.subHTML ? o.sub : esc(o.sub)}</small>` : ""}</span>` : ""}
    ${o.val != null ? `<span class="val">${esc(o.val)}</span>` : ""}
    ${o.chev ? ic("chev", "chev") : ""}${o.checked ? ic("check", "check") : ""}</${tag}>`);
  if (o.right) r.append(typeof o.right === "string" ? el(o.right) : o.right);
  if (o.onClick) r.addEventListener("click", e => { if (!e.target.closest(".switch")) o.onClick(e, r); });
  if (o.onLong) longPress(r, o.onLong);
  if (o.danger) r.classList.add("danger");
  return r;
}
function sw(on, change) {
  const s = el(`<label class="switch"><input type="checkbox"${on ? " checked" : ""}><i></i></label>`);
  $("input", s).onchange = e => { haptic("select"); change(e.target.checked, e.target); };
  return s;
}
const toggleRow = (o, on, change) => row({ ...o, right: sw(on, change) });
function slider(o) {
  const w = el(`<div class="slider">${o.name ? `<span class="name">${esc(o.name)}</span>` : ""}<input type="range" min="${o.min}" max="${o.max}" step="${o.step || 1}" value="${o.value}"><span class="lab"></span></div>`);
  const inp = $("input", w), lab = $(".lab", w);
  const paint = () => { const v = +inp.value; lab.textContent = o.fmt ? o.fmt(v) : v; inp.style.setProperty("--p", ((v - o.min) / (o.max - o.min) * 100) + "%"); };
  let last = +inp.value;
  inp.oninput = () => { paint(); const v = +inp.value; if (v !== last) { last = v; haptic("select"); o.onInput?.(v); } };
  inp.onchange = () => o.onChange?.(+inp.value);
  paint();
  return w;
}
function seg(options, value, change) {
  const s = el(`<div class="seg"><i class="ind"></i>${options.map(([v, t]) => `<button data-v="${esc(v)}">${esc(t)}</button>`).join("")}</div>`);
  const set = v => {
    const bs = $$("button", s), i = Math.max(0, bs.findIndex(b => b.dataset.v === String(v)));
    bs.forEach((b, j) => b.classList.toggle("on", j === i));
    const ind = $(".ind", s);
    ind.style.width = `calc((100% - 4px) / ${bs.length})`;
    ind.style.transform = `translateX(${i * 100}%)`;
  };
  $$("button", s).forEach(b => b.onclick = () => { set(b.dataset.v); haptic("select"); change(b.dataset.v); });
  set(value);
  s.set = set;
  return s;
}
function longPress(node, fn) {
  let t = 0, x = 0, y = 0;
  node.addEventListener("touchstart", e => { x = e.touches[0].clientX; y = e.touches[0].clientY; t = setTimeout(() => { t = 0; haptic("heavy"); fn(x, y); }, 480); }, { passive: true });
  const cancel = () => { if (t) clearTimeout(t); t = 0; };
  node.addEventListener("touchmove", e => { if (Math.hypot(e.touches[0].clientX - x, e.touches[0].clientY - y) > 10) cancel(); }, { passive: true });
  node.addEventListener("touchend", e => { if (!t && fn) e.preventDefault(); cancel(); });
  node.addEventListener("contextmenu", e => { e.preventDefault(); fn(e.clientX, e.clientY); });
}
const loading = () => el(`<div class="center-load"><span class="spinner"></span></div>`);
const empty = (icon, title, text) => el(`<div class="empty">${ic(icon)}<b>${esc(title)}</b>${esc(text || "")}</div>`);

/* ---------------------------------------------------------------- overlays */
const overlays = []; // for the back button
function dim(onClick) {
  const d = el(`<div class="dim"></div>`);
  document.body.append(d);
  requestAnimationFrame(() => d.classList.add("on"));
  d.onclick = onClick;
  return d;
}
function sheet(o) {
  const d = dim(() => close());
  const s = el(`<div class="sheet d-${o.detent || "large"}"><div class="grab"></div><div class="shead">
    ${o.left ? `<button class="navbtn lbtn">${esc(o.left)}</button>` : ""}<h2>${esc(o.title || "")}</h2>
    ${o.right ? `<button class="navbtn strong rbtn">${esc(o.right)}</button>` : ""}</div><div class="sbody"></div></div>`);
  document.body.append(s);
  const api = { el: s, body: $(".sbody", s), close, closed: false };
  function close() {
    if (api.closed) return; api.closed = true;
    s.classList.remove("on"); d.classList.remove("on");
    setTimeout(() => { s.remove(); d.remove(); }, 450);
    const i = overlays.indexOf(api); if (i >= 0) overlays.splice(i, 1);
    o.onClose?.();
  }
  $(".lbtn", s)?.addEventListener("click", () => (o.onLeft || close)(api));
  $(".rbtn", s)?.addEventListener("click", () => o.onRight?.(api));
  // drag down to dismiss
  let y0 = 0, dy = 0, drag = false;
  const head = [$(".grab", s), $(".shead", s)];
  head.forEach(h => h.addEventListener("touchstart", e => { drag = true; y0 = e.touches[0].clientY; dy = 0; s.classList.add("dragging"); }, { passive: true }));
  s.addEventListener("touchmove", e => { if (!drag) return; dy = Math.max(0, e.touches[0].clientY - y0); s.style.transform = `translateY(${dy}px)`; }, { passive: true });
  s.addEventListener("touchend", () => { if (!drag) return; drag = false; s.classList.remove("dragging"); s.style.transform = ""; if (dy > 120) close(); });
  overlays.push(api);
  requestAnimationFrame(() => requestAnimationFrame(() => s.classList.add("on")));
  o.render?.(api.body, api);
  return api;
}
function alertBox(o) {
  return new Promise(res => {
    const w = el(`<div class="alert-wrap"><div class="alert"><div class="at">${o.title ? `<h4>${esc(o.title)}</h4>` : ""}${o.message ? `<p>${esc(o.message)}</p>` : ""}
      ${o.input != null ? `<input value="${esc(o.input)}" placeholder="${esc(o.placeholder || "")}">` : ""}</div>
      <div class="ab${(o.buttons || []).length > 2 ? " col" : ""}"></div></div></div>`);
    const btns = o.buttons || [{ text: "好", value: true, bold: true }];
    const api = { close: () => done(undefined) };
    function done(v) { w.classList.remove("on"); setTimeout(() => w.remove(), 200); const i = overlays.indexOf(api); if (i >= 0) overlays.splice(i, 1); res(v); }
    for (const b of btns) {
      const bt = el(`<button class="${b.bold ? "bold" : ""} ${b.red ? "red" : ""}">${esc(b.text)}</button>`);
      bt.onclick = () => done(o.input != null && b.value === true ? $("input", w).value : b.value);
      $(".ab", w).append(bt);
    }
    document.body.append(w); overlays.push(api);
    requestAnimationFrame(() => w.classList.add("on"));
    if (o.input != null) setTimeout(() => $("input", w).focus(), 250);
    haptic(o.red ? "error" : "light");
  });
}
const confirmBox = (title, message, ok = "确定", red = false) =>
  alertBox({ title, message, buttons: [{ text: "取消", value: false }, { text: ok, value: true, bold: !red, red }] });
const promptBox = (title, message, value = "", placeholder = "") =>
  alertBox({ title, message, input: value, placeholder, buttons: [{ text: "取消", value: null }, { text: "好", value: true, bold: true }] });
function actionSheet(o) {
  return new Promise(res => {
    const w = el(`<div class="asheet-wrap"><div class="asheet"><div class="grp">${o.title || o.message ? `<div class="hd">${o.title ? `<b>${esc(o.title)}</b>` : ""}${esc(o.message || "")}</div>` : ""}</div>
      <div class="grp"><button class="bold cancel">取消</button></div></div></div>`);
    const api = { close: () => done(null) };
    function done(v) { w.classList.remove("on"); setTimeout(() => w.remove(), 400); const i = overlays.indexOf(api); if (i >= 0) overlays.splice(i, 1); res(v); }
    for (const a of o.actions) {
      const b = el(`<button class="${a.red ? "red" : ""}">${esc(a.text)}</button>`);
      b.onclick = () => done(a.value ?? a.text);
      $(".grp", w).append(b);
    }
    $(".cancel", w).onclick = () => done(null);
    w.onclick = e => { if (e.target === w) done(null); };
    document.body.append(w); overlays.push(api);
    requestAnimationFrame(() => w.classList.add("on"));
    haptic();
  });
}
function contextMenu(x, y, items) {
  const w = el(`<div class="ctx-wrap"><div class="ctx"></div></div>`);
  const api = { close };
  function close() { w.classList.remove("on"); setTimeout(() => w.remove(), 250); const i = overlays.indexOf(api); if (i >= 0) overlays.splice(i, 1); }
  for (const it of items) {
    const b = el(`<button class="${it.red ? "red" : ""}"><span>${esc(it.text)}</span>${ic(it.icon || "chev")}</button>`);
    b.onclick = () => { close(); it.fn(); };
    $(".ctx", w).append(b);
  }
  w.onclick = e => { if (e.target === w) close(); };
  document.body.append(w); overlays.push(api);
  const m = $(".ctx", w);
  m.style.left = Math.min(x, innerWidth - 260) + "px";
  m.style.top = Math.min(y, innerHeight - items.length * 46 - 30) + "px";
  requestAnimationFrame(() => w.classList.add("on"));
}
window.elBack = () => {
  const o = overlays[overlays.length - 1];
  if (o) { o.close(); return true; }
  if (tabs[curTab].pop()) return true;
  if (curTab !== "home") { switchTab("home"); return true; }
  return false;
};

/* ---------------------------------------------------------------- images */
function proxied(u) { return /^https:\/\/[^/]*modrinth\.com\//.test(u || "") ? `/api/img?t=${TOKEN}&u=${encodeURIComponent(u)}` : u; }
function coverURL(kind, inst, world, v) { return `/api/cover?t=${TOKEN}&kind=${kind}&inst=${encodeURIComponent(inst)}${world ? "&world=" + encodeURIComponent(world) : ""}&v=${v}`; }
function thumbFor(inst) {
  if (!inst) return "hero.jpg";
  if (inst.cover) return coverURL("inst", inst.id, "", inst.cover);
  if (inst.icon) return proxied(inst.icon);
  let h = 0; for (const ch of inst.id) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return `thumb${h % 4}.jpg`;
}
function drawHead(canvas, info) {
  const k = info.img.width / 64, c = canvas.getContext("2d");
  canvas.width = canvas.height = 64;
  c.imageSmoothingEnabled = false;
  c.drawImage(info.img, 8 * k, 8 * k, 8 * k, 8 * k, 4, 4, 56, 56);
  c.drawImage(info.img, 40 * k, 8 * k, 8 * k, 8 * k, 0, 0, 64, 64);
}
async function paintHeads(root = document) {
  for (const n of $$("[data-head]", root)) {
    try {
      const info = await fetchSkin(n.dataset.head);
      if (n.dataset.drawn === info.url) continue;
      const cv = document.createElement("canvas"); drawHead(cv, info); n.replaceChildren(cv); n.dataset.drawn = info.url;
    } catch { }
  }
}
const curInst = () => (S?.instances || []).find(i => i.id === S?.config?.selected);
const instById = id => (S?.instances || []).find(i => i.id === id);
const metaLine = i => ["Minecraft " + i.game, LOADER[i.loader] ?? i.loader, i.mods ? i.mods + " 个模组" : ""].filter(Boolean).join(" · ");

/* ---------------------------------------------------------------- state & events */
let refreshing = null;
async function refresh() {
  if (refreshing) return refreshing;
  refreshing = (async () => {
    try {
      S = await api("state");
      applyTheme(S.config.theme);
      if (S.android && !A) await loadAndroid();
      for (const t of Object.values(tabs)) for (const p of t.pages) if (p.def.live) p.refresh();
    } catch (e) { console.error(e); }
    refreshing = null;
  })();
  return refreshing;
}
async function loadAndroid(force) {
  try { A = await api("androidInfo", { refresh: !!force }); } catch { A = null; }
  return A;
}

let launching = false;
function connect() {
  const es = new EventSource("/api/events?t=" + TOKEN);
  es.onmessage = ev => {
    const { type, data } = JSON.parse(ev.data);
    switch (type) {
      case "task":
        if (data.state === "start") { island.start(data.label); launching = data.label.startsWith("启动"); playUI(); }
        else {
          if (data.state === "error") { island.finish(false, "失败"); toast(data.error, true); haptic("error"); }
          else if (data.state === "cancelled") island.finish(false, "已取消");
          else island.finish(true, launching ? "游戏已启动" : "完成");
          if (data.state !== "done" || !launching) launching = false;
          refresh(); playUI();
        }
        break;
      case "stage": island.stage(data); playUI(data); break;
      case "progress": {
        const f = data.total ? data.done / data.total : 0;
        island.progress(f, `${data.done}/${data.total} · ${data.speed ? fmtBytes(data.speed) + "/s" : fmtBytes(data.bytes)}`, data.stage);
        playUI(data.stage, f);
        break;
      }
      case "started": launching = true; refresh(); break;
      case "ready": launching = false; haptic("success"); toast(`游戏已启动，用时 ${(data.ms / 1000).toFixed(1)} 秒`); refresh(); break;
      case "logs": logLines.push(...data); if (logLines.length > 4000) logLines.splice(0, logLines.length - 4000); logSink?.(data); break;
      case "exit":
        launching = false;
        if (data.diagnosis) { haptic("error"); showDiagnosis(data.diagnosis); }
        else if (data.played > 5) toast(`游戏已退出，本次游玩 ${hours(data.played)}`);
        refresh();
        break;
      case "installed": haptic("success"); toast("安装完成：" + data); refresh(); break;
      case "fixed": toast(data); refresh(); loadAndroid(true); break;
      case "updated": toast(`已更新 ${data.n} 项`); refresh(); break;
      case "account":
        msSheet?.close();
        if (data.error) toast(data.error, true); else { haptic("success"); toast("已登录 " + data.ok); }
        refresh();
        break;
    }
  };
  es.onerror = () => { es.close(); setTimeout(connect, 1500); };
}
const logLines = [];
let logSink = null;

/* ---------------------------------------------------------------- launch */
function playUI(stage, f) {
  for (const b of $$(".play")) {
    const fill = $(".fillbar", b), label = $("span:last-child", b);
    b.classList.toggle("running", !!S?.running && !launching);
    if (launching) { label.textContent = stage || "正在启动…"; fill.style.width = f != null ? (f * 100) + "%" : fill.style.width; }
    else if (S?.running) { label.textContent = "游戏运行中 · 回到游戏"; fill.style.width = "0"; }
    else { label.textContent = "开始游戏"; fill.style.width = "0"; }
  }
}
async function launch(id, server) {
  if (!S.accounts?.length) { toast("先添加一个账户", true); switchTab("me"); return; }
  if (S.running) { gameRunningSheet(); return; }
  if (launching) return;
  haptic("medium");
  launching = true; playUI("准备启动");
  try { await api("launch", { id: id || S.config.selected, server }); }
  catch (e) { launching = false; playUI(); toast(e.message, true); haptic("error"); }
}
function gameRunningSheet() {
  actionSheet({ title: "游戏正在运行", message: "要结束游戏进程吗？未保存的进度可能丢失。", actions: [{ text: "结束游戏", red: true, value: "kill" }] })
    .then(v => { if (v === "kill") call("kill", {}, "已结束游戏"); });
}

function showDiagnosis(d) {
  sheet({
    title: "游戏异常退出", detent: "medium", right: "完成", onRight: s => s.close(),
    render(b, s) {
      b.append(section("", [row({ icon: "info", color: "red", title: d.title, sub: d.detail, wrap: true })], d.evidence ? "日志：" + esc(d.evidence) : ""));
      const acts = [];
      if (d.fix) acts.push(row({ title: d.fixLabel || "一键修复", cls: "action center", onClick: async () => { s.close(); await call("fix", { action: d.fix, arg: d.fixArg || "", instance: S.config.selected }); } }));
      acts.push(row({ title: "查看日志", cls: "action center", onClick: () => { s.close(); switchTab("me"); push(logsPage()); } }));
      if (d.report) acts.push(row({ title: "打开崩溃报告", cls: "action center", onClick: () => call("open", { what: "report" }) }));
      b.append(section("", acts));
    },
  });
}

/* ================================================================ 首页 */
function homePage() {
  return {
    title: "樱梨", live: true,
    async render(p) {
      if (!S) return;
      const acc = (S.accounts || []).find(a => a.id === S.config.account);
      p.lx.innerHTML = "";
      const av = el(`<button class="avatar-btn" ${acc ? `data-head="${esc(acc.id)}"` : ""}>${acc ? "" : ic("user")}</button>`);
      av.onclick = () => accountsSheet();
      p.lx.append(av);
      paintHeads(p.lx);
      const i = curInst();
      const b = document.createDocumentFragment();
      const hero = el(`<div class="hero"><img src="${esc(i ? thumbFor(i) : "hero.jpg")}" alt="">
        <button class="switcher">${i ? "切换实例" : "新建实例"} ${ic("chev")}</button>
        <div class="info"><small>${i ? esc(LOADER[i.loader] ?? i.loader) + " · " + esc(i.game) : "欢迎"}</small>
        <h2>${esc(i ? i.name : "开始你的冒险")}</h2>
        <p>${i ? esc(ago(i.lastPlayed)) + (i.playTime ? " · 共玩 " + hours(i.playTime) : "") : "新建一个实例，就能在手机上玩 Java 版"}</p></div>
        <button class="play"><i class="fillbar"></i>${ic("play")}<span>开始游戏</span></button></div>`);
      $(".switcher", hero).onclick = () => i ? pickInstanceSheet() : newInstanceSheet();
      $(".play", hero).onclick = () => i ? (S.running ? gameRunningSheet() : launch()) : newInstanceSheet();
      b.append(hero);

      // widgets
      const w = el(`<div class="widgets"></div>`);
      const tier = A ? ["", "入门机", "中端机", "旗舰机"][A.tier] || "—" : "—";
      const opt = el(`<button class="widget grad"><div class="wt">${ic("wand")}一键超级优化</div><div class="big">${tier}</div><div class="sub">按机型调校画质与帧率</div></button>`);
      opt.onclick = () => optimizeSheet(S.config.selected);
      const fps = S.config.fpsLimit || (A?.device?.refresh ? Math.round(A.device.refresh) : 60);
      const perf = el(`<button class="widget"><div class="wt">${ic("chart")}帧率与渲染</div>
        <div class="big">${S.config.fpsLimit ? fps : "不限"}<small>FPS</small></div><div class="sub">渲染倍率 ${S.config.renderScale || 100}%${S.config.dynamicRes ? " · 动态" : ""}</div></button>`);
      perf.onclick = () => { switchTab("me"); push(perfPage()); };
      const memAlloc = S.config.memoryMB || (i?.loader ? S.autoMemModded : S.autoMem);
      const memPct = S.memTotal ? Math.round((S.memTotal - S.memFree) / S.memTotal * 100) : 0;
      const mem = el(`<button class="widget"><div class="wt">${ic("memory")}内存</div>
        <div class="ring" style="--v:${memPct}"></div><div class="big" style="font-size:22px">${gb(memAlloc)}<small>给游戏</small></div>
        <div class="sub">可用 ${gb(S.memFree)} / ${gb(S.memTotal)}</div></button>`);
      mem.onclick = () => { switchTab("me"); push(javaPage()); };
      const ctl = el(`<button class="widget"><div class="wt">${ic("gamepad")}键位</div><div class="big" style="font-size:22px">${esc(S.config.controlsLayout || "default")}</div>
        <div class="sub">点按编辑虚拟按键</div></button>`);
      ctl.onclick = () => { try { N.editControls(S.config.controlsLayout || "default"); } catch { toast("请在安卓设备上编辑键位", true); } };
      w.append(opt, perf, mem, ctl);
      b.append(w);

      if (S.diagnosis) {
        b.append(section("上次游戏", [row({ icon: "info", color: "red", title: S.diagnosis.title, sub: S.diagnosis.detail, chev: true, cls: "diag", onClick: () => showDiagnosis(S.diagnosis) })]));
      }
      // recent instances
      const others = (S.instances || []).filter(x => x.id !== S.config.selected).slice(0, 8);
      if (others.length) {
        const sec = el(`<div><div class="section" style="margin-bottom:10px"><h3 class="big">最近游玩 <a>全部</a></h3></div><div class="hscroll"></div></div>`);
        $("a", sec).onclick = () => switchTab("library");
        for (const x of others) {
          const c = el(`<button class="card"><img src="${esc(thumbFor(x))}" alt="" loading="lazy"><b>${esc(x.name)}</b><small>${esc(LOADER[x.loader] ?? x.loader)} ${esc(x.game)} · ${esc(ago(x.lastPlayed))}</small></button>`);
          c.onclick = async () => { await call("select", { id: x.id }); haptic("select"); await refresh(); p.scroll.scrollTo({ top: 0, behavior: "smooth" }); };
          longPress(c, (cx, cy) => instanceMenu(x, cx, cy));
          $(".hscroll", sec).append(c);
        }
        b.append(sec);
      }
      // quick servers
      if (i) {
        const srv = await api("servers", { instance: i.id }).catch(() => []);
        if (srv?.length) {
          b.append(section("快速加入服务器", srv.slice(0, 4).map(s => row({
            img: s.icon ? "data:image/png;base64," + s.icon : "", px: true, title: s.name || s.ip, sub: s.ip, chev: true,
            onClick: () => launch(i.id, s.ip),
          }))));
        }
      }
      b.append(section("", [row({ icon: "plus", color: "green", title: "新建实例", chev: true, onClick: () => newInstanceSheet() }),
        row({ icon: "search", color: "blue", title: "发现模组与整合包", chev: true, onClick: () => switchTab("discover") })]));
      p.body.replaceChildren(b);
      playUI();
    },
  };
}

function pickInstanceSheet() {
  sheet({
    title: "选择实例", detent: "medium", right: "完成", onRight: s => s.close(),
    render(b, s) {
      b.append(section("", (S.instances || []).map(x => row({
        img: thumbFor(x), title: x.name, sub: metaLine(x), tall: true, checked: x.id === S.config.selected,
        onClick: async () => { await call("select", { id: x.id }); haptic("select"); s.close(); refresh(); },
      }))));
    },
  });
}

function instanceMenu(x, cx, cy) {
  contextMenu(cx, cy, [
    { text: "开始游戏", icon: "play", fn: async () => { await call("select", { id: x.id }); launch(x.id); } },
    { text: "设为当前", icon: "check", fn: async () => { await call("select", { id: x.id }); refresh(); } },
    { text: "详情", icon: "info", fn: () => { switchTab("library"); push(instancePage(x.id)); } },
    { text: "一键超级优化", icon: "wand", fn: () => optimizeSheet(x.id) },
    { text: "删除", icon: "trash", red: true, fn: () => deleteInstance(x) },
  ]);
}
async function deleteInstance(x) {
  if (!await confirmBox(`删除「${x.name}」？`, "实例的存档、模组和设置都会被删除，无法恢复。", "删除", true)) return;
  try { await api("deleteInstance", { id: x.id }); toast("已删除"); } catch (e) { toast(e.message, true); }
  await refresh();
  tabs.library.popToRoot();
}

/* ================================================================ 实例 */
function libraryPage() {
  return {
    title: "实例", live: true,
    render(p) {
      if (!S) return;
      p.right.innerHTML = "";
      const add = el(`<button class="navbtn">${ic("plus")}</button>`);
      add.onclick = () => newInstanceSheet();
      p.right.append(add);
      const list = S.instances || [];
      const b = document.createDocumentFragment();
      if (!list.length) b.append(empty("cube", "还没有实例", "点右上角 + 新建，或导入 .mrpack 整合包"));
      else b.append(section(`${list.length} 个实例`, list.map(x => row({
        img: thumbFor(x), title: x.name, sub: metaLine(x) + " · " + ago(x.lastPlayed), tall: true, chev: true, strong: x.id === S.config.selected,
        titleHTML: esc(x.name) + (x.id === S.config.selected ? ' <span class="pill tint">当前</span>' : ""),
        onClick: () => push(instancePage(x.id)), onLong: (cx, cy) => instanceMenu(x, cx, cy),
      }))));
      const imp = el(`<label class="cell" style="cursor:pointer"><span class="ico" style="background:${COL.purple}">${ic("down")}</span><span class="main"><b>导入整合包</b><small>Modrinth .mrpack 文件</small></span><input type="file" accept=".mrpack,application/zip,*/*" hidden></label>`);
      $("input", imp).onchange = e => importPack(e.target.files[0], e.target);
      b.append(section("", [row({ icon: "plus", color: "green", title: "新建实例", chev: true, onClick: () => newInstanceSheet() }), imp]));
      p.body.replaceChildren(b);
    },
  };
}
async function importPack(f, input) {
  if (!f) return;
  input.value = "";
  const r = await fetch(`/api/upload?name=${encodeURIComponent(f.name)}`, { method: "POST", headers: { "X-Token": TOKEN }, body: f });
  const j = await r.json().catch(() => ({ error: "导入失败" }));
  if (j.error) toast(j.error, true); else toast("正在导入 " + f.name);
}

function newInstanceSheet() {
  let manifest = null, kind = "release", game = "", loader = "", loaderVer = "", name = "", q = "";
  const s = sheet({
    title: "新建实例", left: "取消", right: "安装",
    async onRight(sh) {
      if (!game) { toast("请选择游戏版本", true); return; }
      try { await api("createInstance", { game, loader, loaderVersion: loaderVer, name }); sh.close(); haptic("success"); }
      catch (e) { toast(e.message, true); }
    },
    render(b) {
      const top = el(`<div></div>`);
      const nameSec = section("", [row({ title: "名称", right: (() => { const i = el(`<input class="field" placeholder="默认用版本号">`); i.oninput = () => name = i.value; return i; })() })]);
      const loaderSeg = seg([["", "原版"], ["fabric", "Fabric"], ["forge", "Forge"], ["neoforge", "NeoForge"], ["quilt", "Quilt"]], "", v => { loader = v; loaderVer = ""; loadLoader(); });
      const lsw = el(`<div class="seg-wrap"></div>`); lsw.append(loaderSeg);
      const lverSec = el(`<div></div>`);
      const verHead = el(`<div class="seg-wrap"></div>`);
      verHead.append(seg([["release", "正式版"], ["snapshot", "快照"], ["old", "远古"]], "release", v => { kind = v; renderVers(); }));
      const searchBox = el(`<div class="search">${ic("search")}<input placeholder="搜索版本"></div>`);
      $("input", searchBox).oninput = e => { q = e.target.value.trim(); renderVers(); };
      const vers = el(`<div></div>`);
      top.append(nameSec, el(`<div class="section" style="margin-bottom:8px"><h3>加载器</h3></div>`), lsw, lverSec, el(`<div class="section" style="margin-bottom:8px"><h3>游戏版本 <span class="gsel"></span></h3></div>`), verHead, searchBox, vers);
      b.append(top);
      vers.append(loading());
      api("versions").then(m => { manifest = m; renderVers(); }).catch(e => vers.replaceChildren(empty("info", "获取版本失败", e.message)));
      function renderVers() {
        if (!manifest) return;
        let list = manifest.versions.filter(v => kind === "release" ? v.type === "release" : kind === "snapshot" ? v.type === "snapshot" : v.type.startsWith("old"));
        if (q) list = list.filter(v => v.id.includes(q));
        const old = v => /^1\.(\d+)/.test(v.id) && +v.id.match(/^1\.(\d+)/)[1] <= 12;
        vers.replaceChildren(section("", list.slice(0, 120).map(v => row({
          title: v.id, sub: (v.releaseTime || "").slice(0, 10) + (old(v) ? " · 安卓版暂不支持 1.12.2 及以下" : ""), checked: v.id === game,
          onClick: () => { game = v.id; haptic("select"); $(".gsel", top).textContent = "· " + v.id; loadLoader(); renderVers(); },
        }))));
      }
      async function loadLoader() {
        lverSec.replaceChildren();
        if (!loader || !game) return;
        lverSec.append(loading());
        try {
          const vs = await api("loaderVersions", { loader, game });
          if (!vs?.length) { lverSec.replaceChildren(section("", [row({ title: `${LOADER[loader]} 暂不支持 ${game}`, sub: "换一个版本或加载器" })])); return; }
          loaderVer = (vs.find(v => v.stable) || vs[0]).version;
          const pick = row({ title: `${LOADER[loader]} 版本`, val: loaderVer, chev: true, onClick: () => sheet({
            title: LOADER[loader] + " 版本", detent: "medium",
            render(bb, ss) { bb.append(section("", vs.slice(0, 80).map(v => row({ title: v.version, sub: v.stable ? "稳定版" : "", checked: v.version === loaderVer, onClick: () => { loaderVer = v.version; $(".val", pick).textContent = loaderVer; ss.close(); } })))); },
          }) });
          lverSec.replaceChildren(section("", [pick]));
        } catch (e) { lverSec.replaceChildren(section("", [row({ title: "获取加载器版本失败", sub: e.message })])); }
      }
    },
  });
  return s;
}

function instancePage(id) {
  return {
    title: instById(id)?.name || id, short: "实例", cover: true, large: false, live: true,
    async render(p) {
      const x = instById(id);
      if (!x) { p.body.replaceChildren(empty("cube", "实例不存在")); return; }
      p.nav.querySelector(".title").textContent = x.name;
      const b = document.createDocumentFragment();
      b.append(el(`<div class="cover"><img src="${esc(thumbFor(x))}" alt=""></div>`));
      const head = el(`<div class="detail-head"><h1>${esc(x.name)}</h1><p>${esc(metaLine(x))}${x.loaderVersion ? " " + esc(x.loaderVersion) : ""} · ${esc(ago(x.lastPlayed))}</p><div class="row"></div></div>`);
      const play = el(`<button class="btn">${ic("play")}开始游戏</button>`);
      play.onclick = async () => { await call("select", { id }); launch(id); };
      const more = el(`<button class="btn gray" style="width:56px;flex:none">${ic("wand")}</button>`);
      more.onclick = () => optimizeSheet(id);
      $(".row", head).append(play, more);
      b.append(head);
      b.append(section("内容", [
        row({ icon: "puzzle", color: "indigo", title: "模组", val: x.mods ? String(x.mods) : "", chev: true, onClick: () => push(contentPage(id, "mod")) }),
        row({ icon: "paint", color: "orange", title: "资源包", chev: true, onClick: () => push(contentPage(id, "resourcepack")) }),
        row({ icon: "sun", color: "yellow", title: "光影包", chev: true, onClick: () => push(contentPage(id, "shader")) }),
      ]));
      b.append(section("世界", [
        row({ icon: "globe", color: "green", title: "存档", chev: true, onClick: () => push(worldsPage(id)) }),
        row({ icon: "server", color: "blue", title: "服务器", chev: true, onClick: () => push(serversPage(id)) }),
        row({ icon: "photo", color: "pink", title: "截图与封面", chev: true, onClick: () => push(shotsPage(id)) }),
      ]));
      b.append(section("设置", [
        row({ icon: "sliders", color: "gray", title: "游戏选项", sub: "视距、帧率、界面大小…", chev: true, onClick: () => push(optionsPage(id)) }),
        row({ icon: "wand", color: "pink", title: "一键超级优化", chev: true, onClick: () => optimizeSheet(id) }),
      ]));
      b.append(section("管理", [
        row({ icon: "pencil", color: "teal", title: "重命名", chev: true, onClick: async () => { const n = await promptBox("重命名实例", "", x.name); if (n) { await call("renameInstance", { id, name: n }, "已重命名"); refresh(); } } }),
        row({ icon: "folder", color: "blue", title: "打开实例文件夹", chev: true, onClick: () => call("open", { what: "instance", id }) }),
        row({ icon: "refresh", color: "green", title: "校验并补全文件", chev: true, onClick: () => call("fix", { action: "verify", arg: "", instance: id }) }),
        row({ icon: "java", color: "orange", title: "Java 版本", val: x.java ? "Java " + x.java : "自动", chev: true, onClick: () => javaPickSheet(id) }),
      ]));
      b.append(section("", [row({ title: "删除实例", danger: true, onClick: () => deleteInstance(x) })]));
      p.body.replaceChildren(b);
    },
  };
}
function javaPickSheet(id) {
  actionSheet({ title: "为这个实例指定 Java", message: "安卓上可用 8 / 17 / 21", actions: [8, 17, 21].map(n => ({ text: "Java " + n, value: n })) })
    .then(n => { if (n) call("fix", { action: "java", arg: String(n), instance: id }); });
}

const KIND = { mod: "模组", resourcepack: "资源包", shader: "光影包" };
function contentPage(id, kind) {
  return {
    title: KIND[kind], short: "返回",
    async render(p) {
      p.right.innerHTML = "";
      const upd = el(`<button class="navbtn">${ic("up")}</button>`);
      upd.onclick = () => checkUpdates(id, kind, p);
      const add = el(`<button class="navbtn">${ic("plus")}</button>`);
      add.onclick = () => { switchTab("discover"); discoverKind(kind === "shader" ? "shader" : kind); };
      p.right.append(upd, add);
      if (!p.body.children.length) p.body.append(loading());
      const info = await api("contentInfo", { instance: id, kind }).catch(() => null);
      const items = info?.items || await api("content", { instance: id, kind }).catch(() => []);
      if (!items?.length) { p.body.replaceChildren(empty(kind === "mod" ? "puzzle" : kind === "shader" ? "sun" : "paint", `没有${KIND[kind]}`, "去「发现」里找找吧")); return; }
      p.body.replaceChildren(section(`${items.length} 项${info?.offline ? " · 离线" : ""}`, items.map(c => row({
        img: c.icon ? proxied(c.icon) : "", title: c.title || c.name || c.file, sub: [c.version, fmtBytes(c.size), c.enabled ? "" : "已停用"].filter(Boolean).join(" · "),
        right: sw(c.enabled, async (on, inp) => { try { await api("toggleContent", { instance: id, kind, file: c.file }); } catch (e) { inp.checked = !on; toast(e.message, true); } }),
        onLong: (cx, cy) => contextMenu(cx, cy, [
          ...(c.slug ? [{ text: "在 Modrinth 查看", icon: "link", fn: () => openURL("https://modrinth.com/project/" + c.slug) }] : []),
          { text: "删除", icon: "trash", red: true, fn: async () => { if (await confirmBox("删除 " + (c.title || c.file) + "？", "", "删除", true)) { await call("deleteContent", { instance: id, kind, file: c.file }, "已删除"); p.refresh(); } } },
        ]),
      }))), el(`<div class="section"><div class="foot">长按某项可删除；右上角检查更新。</div></div>`));
    },
  };
}
async function checkUpdates(id, kind, p) {
  island.start("检查更新");
  try {
    const ups = await api("checkUpdates", { instance: id, kind });
    island.finish(true, ups.length ? `${ups.length} 项可更新` : "都是最新的");
    if (!ups.length) return;
    sheet({
      title: "可用更新", detent: "medium", right: "全部更新",
      async onRight(s) { s.close(); await call("applyUpdates", { instance: id, kind }); },
      render(b) { b.append(section("", ups.map(u => row({ img: u.icon ? proxied(u.icon) : "", title: u.title, sub: `${u.from} → ${u.to}` })))); },
    });
  } catch (e) { island.finish(false, "检查失败"); toast(e.message, true); }
}
function openURL(u) { try { N.openUrl(u); } catch { window.open(u, "_blank"); } }

function worldsPage(id) {
  return {
    title: "存档", short: "返回",
    async render(p) {
      const ws = await api("worlds", { instance: id }).catch(() => []);
      if (!ws?.length) { p.body.replaceChildren(empty("globe", "还没有存档", "进入游戏创建一个世界吧")); return; }
      p.body.replaceChildren(section(`${ws.length} 个世界`, ws.map(w => row({
        img: w.hasIcon ? `/api/worldicon?t=${TOKEN}&inst=${encodeURIComponent(id)}&world=${encodeURIComponent(w.folder)}` : thumbFor(instById(id)),
        px: true, tall: true, title: w.name, sub: [w.mode + (w.hardcore ? "·极限" : ""), w.version, "第 " + w.day + " 天", ago(w.lastPlayed), fmtBytes(w.size)].filter(Boolean).join(" · "),
        onClick: async () => {
          const v = await actionSheet({ title: w.name, actions: [{ text: "备份存档", value: "bak" }, { text: "打开文件夹", value: "open" }, { text: "删除存档", value: "del", red: true }] });
          if (v === "bak") call("backupWorld", { instance: id, folder: w.folder, name: w.name });
          if (v === "open") call("openWorld", { instance: id, folder: w.folder });
          if (v === "del" && await confirmBox(`删除「${w.name}」？`, "存档会被永久删除，建议先备份。", "删除", true)) { await call("deleteWorld", { instance: id, folder: w.folder }, "存档已删除"); p.refresh(); }
        },
      }))));
    },
  };
}

function motdHTML(s) {
  const C = { 0: "#000", 1: "#00a", 2: "#0a0", 3: "#0aa", 4: "#a00", 5: "#a0a", 6: "#fa0", 7: "#aaa", 8: "#555", 9: "#55f", a: "#5f5", b: "#5ff", c: "#f55", d: "#f5f", e: "#ff5", f: "var(--label)" };
  let out = "", open = 0;
  const parts = String(s || "").split("§");
  out += esc(parts[0]);
  for (const part of parts.slice(1)) {
    const k = part[0]?.toLowerCase(), rest = esc(part.slice(1));
    if (C[k]) { out += "</span>".repeat(open); open = 1; out += `<span style="color:${C[k]}">${rest}`; }
    else if (k === "r") { out += "</span>".repeat(open); open = 0; out += rest; }
    else out += rest;
  }
  return out + "</span>".repeat(open);
}
function serversPage(id) {
  return {
    title: "服务器", short: "返回",
    async render(p) {
      p.right.innerHTML = "";
      const add = el(`<button class="navbtn">${ic("plus")}</button>`);
      add.onclick = async () => {
        const ip = await promptBox("添加服务器", "地址，例如 mc.example.com", "", "服务器地址");
        if (!ip) return;
        const name = await promptBox("服务器名称", "", ip.split(":")[0]);
        try { await api("addServer", { instance: id, name: name || ip, ip: ip.trim() }); p.refresh(); } catch (e) { toast(e.message, true); }
      };
      p.right.append(add);
      const list = await api("servers", { instance: id }).catch(e => { toast(e.message, true); return []; });
      if (!list.length) { p.body.replaceChildren(empty("server", "没有服务器", "点右上角 + 添加")); return; }
      const rows = list.map(sv => {
        const r = row({
          img: sv.icon ? "data:image/png;base64," + sv.icon : "", px: true, tall: true, title: sv.name || sv.ip, sub: sv.ip, val: "…",
          onClick: async () => {
            const v = await actionSheet({ title: sv.name, message: sv.ip, actions: [{ text: "加入服务器", value: "join" }, { text: "复制地址", value: "copy" }, { text: "删除", value: "del", red: true }] });
            if (v === "join") { await call("select", { id }); launch(id, sv.ip); }
            if (v === "copy") { try { N.copy(sv.ip); toast("已复制"); } catch { navigator.clipboard?.writeText(sv.ip); } }
            if (v === "del") { await call("removeServer", { instance: id, ip: sv.ip }); p.refresh(); }
          },
        });
        api("pingServer", { addr: sv.ip }).then(st => {
          $(".val", r).innerHTML = st.online ? `<span class="pill ${st.ping < 120 ? "green" : st.ping < 260 ? "orange" : "red"}">${st.ping} ms</span>` : `<span class="pill red">离线</span>`;
          if (st.online) $("small", r).innerHTML = `${st.players}/${st.max} 人 · <span class="motd">${motdHTML(st.motd.split("\n")[0])}</span>`;
          if (st.favicon && !sv.icon) $(".thumb", r).src = st.favicon;
        }).catch(() => { });
        return r;
      });
      p.body.replaceChildren(section(`${list.length} 个服务器`, rows, "点按加入，游戏会直接连接服务器。"));
    },
  };
}

function shotsPage(id) {
  return {
    title: "截图", short: "返回",
    async render(p) {
      const shots = await api("screenshots", { instance: id }).catch(() => []);
      const b = document.createDocumentFragment();
      const x = instById(id);
      b.append(section("封面", [row({ img: thumbFor(x), title: x?.cover ? "自定义封面" : "默认封面", sub: "点按下方截图设为实例封面" }),
        ...(x?.cover ? [row({ title: "恢复默认封面", cls: "action", onClick: async () => { await call("coverReset", { kind: "inst", instance: id, world: "" }); await refresh(); p.refresh(); } })] : [])]));
      if (!shots?.length) b.append(empty("photo", "没有截图", "游戏中按「截图」（F2）即可保存"));
      else {
        const g = el(`<div class="grid3"></div>`);
        for (const s of shots) {
          const im = el(`<img loading="lazy" src="/api/shot?t=${TOKEN}&inst=${encodeURIComponent(id)}&file=${encodeURIComponent(s.file)}" alt="">`);
          im.onclick = async () => { if (await confirmBox("设为封面？", s.file)) { await call("coverFromShot", { kind: "inst", instance: id, world: "", file: s.file }, "封面已更新"); await refresh(); p.refresh(); } };
          g.append(im);
        }
        b.append(el(`<div class="section" style="margin-bottom:8px"><h3>${shots.length} 张截图</h3></div>`), g);
      }
      p.body.replaceChildren(b);
    },
  };
}

function optionsPage(id) {
  return {
    title: "游戏选项", short: "返回",
    async render(p) {
      const o = await api("gameOptions", { instance: id }).catch(() => ({}));
      const patch = {};
      const save = async () => { if (!Object.keys(patch).length) return; try { await api("setGameOptions", { instance: id, options: patch }); toast("已保存"); } catch (e) { toast(e.message, true); } };
      p.def.onClose = save;
      const num = (k, d) => o[k] != null ? +o[k] : d;
      const sl = (name, k, min, max, step, d, fmt) => slider({ name, min, max, step, value: num(k, d), fmt, onChange: v => { patch[k] = String(v); } });
      const b = document.createDocumentFragment();
      b.append(section("画面", [
        sl("视距", "renderDistance", 2, 32, 1, 8, v => v + " 区块"), sl("模拟距离", "simulationDistance", 5, 32, 1, 8, v => v + " 区块"),
        sl("最大帧率", "maxFps", 10, 260, 10, 120, v => v >= 260 ? "无限" : v), sl("界面大小", "guiScale", 0, 6, 1, 0, v => v ? v + "×" : "自动"),
        sl("视场角", "fov", -1, 1, .05, 0, v => Math.round(70 + v * 40) + "°"),
      ]));
      const bool = (title, k, d) => toggleRow({ title }, o[k] != null ? o[k] === "true" : d, v => patch[k] = String(v));
      b.append(section("", [
        toggleRow({ title: "精美画质" }, o.graphicsMode ? o.graphicsMode !== "0" : o.fancyGraphics === "true", v => { patch.graphicsMode = v ? "1" : "0"; patch.fancyGraphics = String(v); }),
        bool("实体阴影", "entityShadows", true), bool("平滑光照", "ao", true), bool("视角摇晃", "bobView", true), bool("垂直同步", "enableVsync", false),
      ]));
      b.append(section("操作", [bool("自动跳跃", "autoJump", false), bool("潜行切换", "toggleCrouch", false), bool("疾跑切换", "toggleSprint", false)],
        "修改会在退出本页时写入 options.txt，下次进入游戏生效。"));
      p.body.replaceChildren(b);
    },
  };
}

/* ---------------------------------------------------------------- one-tap optimiser */
function optimizeSheet(inst) {
  let level = "balanced", mods = true;
  sheet({
    title: "一键超级优化", detent: "large", left: "取消", right: "优化",
    async onRight(s) {
      try {
        const r = await api("superOptimize", { instance: inst || "", level, mods });
        s.close(); haptic("success");
        await refresh();
        sheet({ title: "优化完成", detent: "medium", right: "好", onRight: x => x.close(), render(b) { b.append(section("已应用", r.changes.map(c => row({ icon: "check", color: "green", title: c, wrap: true })))); } });
      } catch (e) { toast(e.message, true); }
    },
    async render(b) {
      const dev = A?.device || {};
      const head = el(`<div class="section"><div class="list"><div class="cell static tall"><span class="ico" style="background:linear-gradient(140deg,var(--tint),var(--tint2));width:46px;height:46px;border-radius:12px;font-size:26px">${ic("wand")}</span>
        <span class="main"><b class="strong">${esc((dev.brand || "") + " " + (dev.model || "本机"))}</b><small class="wrap">${esc([dev.soc, dev.glRenderer, dev.ramMB ? gb(dev.ramMB) + " 内存" : "", dev.refresh ? Math.round(dev.refresh) + " Hz" : ""].filter(Boolean).join(" · "))}</small></span></div></div></div>`);
      b.append(head);
      const sw2 = el(`<div class="seg-wrap"></div>`);
      sw2.append(seg([["balanced", "均衡"], ["extreme", "极致帧率"], ["quality", "画质优先"]], level, v => { level = v; plan(); }));
      b.append(sw2);
      const planBox = el(`<div></div>`);
      b.append(planBox);
      b.append(section("", [toggleRow({ title: "安装手机适用的性能模组", sub: "Lithium、FerriteCore、ModernFix 等（需要加载器）" }, mods, v => mods = v)],
        "优化会调整：渲染倍率、帧率上限、游戏视距与画质选项、内存分配、持续性能模式、渲染线程大核调度与 ADPF 性能提示。之后都可以在「我的 › 性能」里单独修改。"));
      async function plan() {
        planBox.replaceChildren(loading());
        try {
          const pl = await api("optimizePlan", { instance: inst || "", level });
          planBox.replaceChildren(section(`预计效果 · ${pl.tierName}`, [
            row({ icon: "layers", color: "indigo", title: "渲染倍率", val: pl.renderScale + "%" }),
            row({ icon: "chart", color: "green", title: "帧率上限", val: pl.fpsLimit + " FPS" }),
            row({ icon: "globe", color: "teal", title: "视距 / 模拟距离", val: `${pl.options.renderDistance} / ${pl.options.simulationDistance}` }),
            row({ icon: "sun", color: "orange", title: "画质", val: pl.options.graphicsMode === "1" ? "精美" : "流畅" }),
            ...(pl.renderMods ? [row({ icon: "puzzle", color: "purple", title: "额外安装 Sodium 渲染优化", sub: "极致模式 · 中高端机" })] : []),
          ]));
        } catch (e) { planBox.replaceChildren(section("", [row({ title: "无法计算方案", sub: e.message })])); }
      }
      plan();
    },
  });
}

/* ================================================================ 发现 (Modrinth) */
let discover = { kind: "mod", q: "", offset: 0, items: [], total: 0, onlyFit: true };
function discoverKind(k) { discover.kind = k; discover.offset = 0; discover.items = []; tabs.discover.popToRoot(); tabs.discover.top.refresh(); }
function discoverPage() {
  return {
    title: "发现",
    async render(p) {
      if (!p.built) {
        p.built = true;
        const sb = el(`<div class="search">${ic("search")}<input placeholder="搜索 Modrinth" enterkeyhint="search"></div>`);
        let t = 0;
        $("input", sb).oninput = e => { clearTimeout(t); t = setTimeout(() => { discover.q = e.target.value.trim(); discover.offset = 0; discover.items = []; load(); }, 350); };
        const sg = el(`<div class="seg-wrap"></div>`);
        p.seg = seg([["mod", "模组"], ["modpack", "整合包"], ["resourcepack", "资源包"], ["shader", "光影"]], discover.kind, v => { discover.kind = v; discover.offset = 0; discover.items = []; load(); });
        sg.append(p.seg);
        p.fit = el(`<div></div>`);
        p.list = el(`<div></div>`);
        p.more = el(`<div class="section"><button class="btn gray">加载更多</button></div>`);
        $("button", p.more).onclick = () => { discover.offset += 20; load(true); };
        p.body.append(sb, sg, p.fit, p.list, p.more);
        p.scroll.addEventListener("scroll", () => { if (p.scroll.scrollTop + p.scroll.clientHeight > p.scroll.scrollHeight - 300 && !p.loadingMore && discover.items.length < discover.total) { p.loadingMore = true; discover.offset += 20; load(true); } }, { passive: true });
      }
      p.seg.set(discover.kind);
      const cur = curInst();
      p.fit.replaceChildren(cur && discover.kind !== "modpack" ? section("", [toggleRow({ title: `只看适合「${cur.name}」的`, sub: `Minecraft ${cur.game}${cur.loader ? " · " + LOADER[cur.loader] : ""}` }, discover.onlyFit, v => { discover.onlyFit = v; discover.offset = 0; discover.items = []; load(); })]) : "");
      if (!discover.items.length) load();
      async function load(append) {
        if (!append) p.list.replaceChildren(loading());
        const c = curInst();
        const fit = discover.onlyFit && c && discover.kind !== "modpack";
        try {
          const r = await api("search", { query: discover.q, kind: discover.kind, game: fit ? c.game : "", loader: fit && discover.kind === "mod" ? c.loader : "", offset: discover.offset });
          discover.total = r.total_hits;
          discover.items = append ? discover.items.concat(r.hits) : r.hits;
          const rows = discover.items.map(x => row({
            img: proxied(x.icon_url), title: x.title, sub: `${x.author} · ${nfmt(x.downloads)} 次下载 · ${x.description}`, tall: true, chev: true,
            onClick: () => projectSheet(x),
          }));
          p.list.replaceChildren(rows.length ? section(`${nfmt(discover.total)} 个结果`, rows) : empty("search", "没有结果", "换个关键词试试"));
          p.more.hidden = discover.items.length >= discover.total;
        } catch (e) { p.list.replaceChildren(empty("info", "搜索失败", e.message)); }
        p.loadingMore = false;
      }
    },
  };
}
function projectSheet(x) {
  sheet({
    title: x.title, detent: "medium", right: "安装",
    async onRight(s) {
      s.close();
      if (x.project_type === "modpack") await call("installPack", { project: x.project_id, title: x.title });
      else {
        if (!S.config.selected) { toast("请先新建实例", true); return; }
        await call("installProject", { instance: S.config.selected, project: x.project_id, title: x.title });
      }
    },
    render(b) {
      b.append(section("", [row({ img: proxied(x.icon_url), title: x.title, sub: `${x.author} · ${nfmt(x.downloads)} 次下载`, tall: true, strong: true })]));
      b.append(el(`<div class="section"><div class="list"><div class="cell static"><span class="main"><small class="wrap" style="font-size:15px;color:var(--label)">${esc(x.description)}</small></span></div></div>
        <div class="foot">${esc((x.display_categories || []).join(" · "))}${x.project_type === "modpack" ? "" : " · 将安装到「" + esc(curInst()?.name || "") + "」，前置依赖会自动安装"}</div></div>`));
      b.append(section("", [row({ icon: "link", color: "green", title: "在 Modrinth 查看", chev: true, onClick: () => openURL("https://modrinth.com/project/" + x.slug) })]));
    },
  });
}

/* ================================================================ 我的 */
function mePage() {
  return {
    title: "我的", live: true,
    async render(p) {
      if (!S) return;
      const acc = (S.accounts || []).find(a => a.id === S.config.account);
      const b = document.createDocumentFragment();
      b.append(section("", [row({ avatar: acc?.id || "", title: acc ? acc.name : "添加账户", sub: acc ? (acc.type === "msa" ? "微软正版账户" : "离线账户") + " · 皮肤与账户" : "离线或微软正版登录", tall: true, chev: true, strong: true, onClick: () => accountsSheet() })]));
      b.append(section("", [row({ icon: "shirt", color: "pink", title: "皮肤", chev: true, onClick: () => push(skinPage()) })]));
      b.append(section("性能与操控", [
        row({ icon: "wand", color: "pink", title: "一键超级优化", chev: true, onClick: () => optimizeSheet(S.config.selected) }),
        row({ icon: "bolt", color: "orange", title: "性能", val: `${S.config.renderScale || 100}% · ${S.config.fpsLimit || "不限"}`, chev: true, onClick: () => push(perfPage()) }),
        row({ icon: "gamepad", color: "indigo", title: "操控与键位", chev: true, onClick: () => push(controlsPage()) }),
      ]));
      b.append(section("游戏", [
        row({ icon: "java", color: "red", title: "Java 与内存", chev: true, onClick: () => push(javaPage()) }),
        row({ icon: "down", color: "blue", title: "下载", val: { auto: "自动", official: "官方", bmclapi: "BMCLAPI" }[S.config.source] || "自动", chev: true, onClick: () => push(downloadPage()) }),
        row({ icon: "folder", color: "teal", title: "游戏文件", sub: "在系统「文件」应用中管理", chev: true, onClick: () => call("open", { what: "gameDir" }) }),
      ]));
      const themes = [["sakura", "樱花"], ["white", "纯白"], ["black", "纯黑"], ["auto", "跟随系统"]];
      const ts = el(`<div class="cell static" style="padding:10px 16px"></div>`);
      ts.append(seg(themes, S.config.theme || "sakura", async v => { applyTheme(v); await setConfig({ theme: v }); }));
      b.append(section("外观", [ts]));
      b.append(section("通用", [
        toggleRow({ icon: "globe", color: "blue", title: "新实例默认简体中文" }, S.config.zhCN, v => setConfig({ zhCN: v })),
        toggleRow({ icon: "stack", color: "gray", title: "版本隔离", sub: "每个实例独立的存档与模组" }, S.config.isolation, v => setConfig({ isolation: v })),
      ]));
      b.append(section("", [
        row({ icon: "doc", color: "gray", title: "游戏日志", chev: true, onClick: () => push(logsPage()) }),
        row({ icon: "info", color: "blue", title: "关于樱梨启动器", val: "v" + S.version, chev: true, onClick: () => push(aboutPage()) }),
      ]));
      p.body.replaceChildren(b);
      paintHeads(p.body);
    },
  };
}
async function setConfig(patch) {
  try { const c = await api("setConfig", patch); S.config = c; return c; } catch (e) { toast(e.message, true); }
}

let msSheet = null;
function accountsSheet() {
  sheet({
    title: "账户", detent: "large", right: "完成", onRight: s => s.close(),
    render(b, s) {
      const draw = () => {
        b.replaceChildren();
        const list = S.accounts || [];
        if (list.length) b.append(section("我的账户", list.map(a => row({
          avatar: a.id, title: a.name, sub: a.type === "msa" ? "微软正版" : "离线", checked: a.id === S.config.account,
          onClick: async () => { await call("selectAccount", { id: a.id }); await refresh(); draw(); },
          onLong: async () => { if (await confirmBox(`移除 ${a.name}？`, "", "移除", true)) { await call("removeAccount", { id: a.id }); await refresh(); draw(); } },
        })), "长按账户可移除。"));
        b.append(section("添加", [
          row({ icon: "user", color: "gray", title: "离线账户", sub: "输入任意名字，单人游戏和离线服务器可用", chev: true, onClick: async () => {
            const n = await promptBox("离线账户", "3-16 位字母、数字或下划线", "", "玩家名");
            if (n) { await call("addOffline", { name: n }, "已添加 " + n); await refresh(); draw(); }
          } }),
          row({ icon: "shield", color: "green", title: "微软正版登录", sub: S.msBuiltin ? "已内置登录" : "需要在下方填写 Azure 应用 ID", chev: true, onClick: () => msLogin() }),
        ]));
        if (!S.msBuiltin) {
          const inp = el(`<input class="field left" placeholder="Azure 客户端 ID（可选）" value="${esc(S.config.msClientId || "")}">`);
          inp.onchange = () => setConfig({ msClientId: inp.value.trim() });
          b.append(section("微软登录设置", [row({ title: "", right: inp })], "正版登录需要一个已获批 Minecraft API 权限的 Azure 应用 ID。"));
        }
        paintHeads(b);
      };
      draw();
    },
  });
}
async function msLogin() {
  try {
    const r = await api("msLogin");
    msSheet = sheet({
      title: "微软登录", detent: "medium", left: "取消", onLeft: s => { call("msCancel"); s.close(); },
      onClose: () => { msSheet = null; },
      render(b) {
        b.append(el(`<div class="section"><div class="list"><div class="code">${esc(r.code)}</div></div><div class="foot">在打开的网页中输入这个代码并登录微软账户，完成后这里会自动继续。</div></div>`));
        b.append(section("", [
          row({ icon: "copy", color: "blue", title: "复制代码", onClick: () => { try { N.copy(r.code); } catch { navigator.clipboard?.writeText(r.code); } toast("已复制"); } }),
          row({ icon: "link", color: "green", title: "打开登录网页", onClick: () => openURL(r.url) }),
        ]));
      },
    });
  } catch (e) { toast(e.message, true); }
}

function skinPage() {
  let viewer = null;
  return {
    title: "皮肤", short: "我的", noTab: true,
    onClose: () => viewer?.setActive(false),
    async render(p) {
      const acc = (S.accounts || []).find(a => a.id === S.config.account);
      const b = document.createDocumentFragment();
      const stage = el(`<div class="stage3d"></div>`);
      b.append(stage);
      const animSeg = el(`<div class="seg-wrap"></div>`);
      animSeg.append(seg([["idle", "站立"], ["walk", "行走"], ["run", "奔跑"]], "idle", v => viewer?.setAnim(v)));
      b.append(animSeg);
      if (!acc) { b.append(empty("user", "还没有账户", "先添加一个账户")); p.body.replaceChildren(b); return; }
      const info = await fetchSkin(acc.id, true);
      const model = el(`<div class="cell static" style="padding:10px 16px"></div>`);
      model.append(seg([["classic", "经典（Steve）"], ["slim", "纤细（Alex）"]], info.slim ? "slim" : "classic", async v => { await call("skinModel", { id: acc.id, model: v }); p.refresh(); }));
      const up = el(`<label class="cell icon-inset" style="cursor:pointer"><span class="ico" style="background:${COL.pink}">${ic("down")}</span><span class="main"><b>上传皮肤图片</b><small>64×64 PNG${acc.type === "msa" ? " · 将同步到正版" : ""}</small></span><input type="file" accept="image/png" hidden></label>`);
      $("input", up).onchange = async e => {
        const f = e.target.files[0]; if (!f) return; e.target.value = "";
        const m = info.slim ? "slim" : "classic";
        const r = await fetch(`/api/skinupload?id=${encodeURIComponent(acc.id)}&model=${m}&official=${acc.type === "msa" ? 1 : 0}&name=${encodeURIComponent(f.name)}`, { method: "POST", headers: { "X-Token": TOKEN }, body: f });
        const j = await r.json().catch(() => ({ error: "上传失败" }));
        if (j.error) toast(j.error, true); else { toast("皮肤已更换"); haptic("success"); p.refresh(); }
      };
      b.append(section(`${esc(acc.name)} · ${{ custom: "自定义皮肤", official: "正版皮肤", default: "默认皮肤" }[info.source] || ""}`, [model, up,
        row({ icon: "search", color: "blue", title: "导入正版玩家的皮肤", chev: true, onClick: async () => { const n = await promptBox("导入皮肤", "输入正版玩家名", "", "玩家名"); if (n) { await call("skinImport", { id: acc.id, player: n }, `已导入 ${n} 的皮肤`); p.refresh(); } } }),
        row({ icon: "refresh", color: "gray", title: "恢复默认", chev: true, onClick: async () => { await call("skinReset", { id: acc.id }, "已恢复"); p.refresh(); } }),
      ], acc.type === "msa" ? "上传会直接修改你的正版皮肤，所有服务器都能看到。" : "离线账户的皮肤会写入游戏资源；装有 CustomSkinLoader 时游戏内可见。"));
      p.body.replaceChildren(b);
      viewer = new SkinViewer(stage);
      viewer.s = 8;
      viewer.load(info.url, info.legacy, info.slim, info.img);
      viewer.setActive(true);
      paintHeads();
    },
  };
}

function perfPage() {
  return {
    title: "性能", short: "我的", live: false,
    async render(p) {
      const c = S.config;
      if (S.android && !A) await loadAndroid();
      const b = document.createDocumentFragment();
      const opt = el(`<div class="section"><button class="btn">${ic("wand")}一键超级优化</button></div>`);
      $("button", opt).onclick = () => optimizeSheet(S.config.selected);
      b.append(opt);
      const fpsVals = [0, 30, 45, 60, 90, 120, 144];
      const refreshHz = Math.round(A?.device?.refresh || 60);
      b.append(section("渲染", [
        slider({ name: "渲染倍率", min: 25, max: 100, step: 5, value: c.renderScale || 100, fmt: v => v + "%" + (A?.device?.screenH ? ` · ${Math.round(A.device.screenH * v / 100)}p` : ""), onChange: v => setConfig({ renderScale: v }) }),
        slider({ name: "帧率上限", min: 0, max: fpsVals.length - 1, value: Math.max(0, fpsVals.indexOf(c.fpsLimit || 0)), fmt: i => fpsVals[i] ? fpsVals[i] + " FPS" : "不限", onChange: i => setConfig({ fpsLimit: fpsVals[i] }) }),
        toggleRow({ title: "动态分辨率", sub: "掉帧时自动降低渲染倍率，流畅时再恢复" }, c.dynamicRes, v => setConfig({ dynamicRes: v })),
        toggleRow({ title: "垂直同步", sub: `与屏幕刷新率（${refreshHz} Hz）同步，消除画面撕裂` }, c.vsync, v => setConfig({ vsync: v })),
      ], "渲染倍率越低越流畅：画面以较低分辨率绘制，由屏幕硬件放大，几乎没有额外开销。"));
      const huds = [["off", "关闭"], ["fps", "简洁"], ["full", "完整"]];
      const hud = el(`<div class="cell static" style="padding:10px 16px;gap:12px"><span style="flex:none">帧率显示</span></div>`);
      const hs = seg(huds, c.perfHud || "fps", v => setConfig({ perfHud: v })); hs.style.flex = "1";
      hud.append(hs);
      b.append(section("游戏内显示", [hud], "完整模式显示帧率、帧时间曲线、渲染分辨率、内存、电池温度和温控状态。游戏中点屏幕顶部的胶囊可打开控制中心随时调整。"));
      // renderer
      const rs = A?.renderers || [];
      const cur = c.renderer || "auto";
      const curName = cur === "auto" ? "自动" : (rs.find(r => r.id === cur)?.name || cur);
      b.append(section("渲染器", [
        row({ icon: "layers", color: "indigo", title: "渲染器", val: curName, chev: true, onClick: () => rendererSheet(p) }),
      ], "自动：1.17 及以上用 MobileGlues（OpenGL 4），1.16.5 及以下用 GL4ES。"));
      b.append(section("系统调度", [
        toggleRow({ title: "持续性能模式", sub: "避免长时间游玩后过热降频造成的掉帧" }, c.sustainedPerf, v => setConfig({ sustainedPerf: v })),
        toggleRow({ title: "渲染线程大核调度", sub: "把游戏渲染线程固定到性能核心" }, c.bigCores, v => setConfig({ bigCores: v })),
        toggleRow({ title: "ADPF 性能提示", sub: "告诉系统每帧的目标耗时，按需提频（Android 13+）" }, c.perfHint, v => setConfig({ perfHint: v })),
      ]));
      if (A?.device) {
        const d = A.device;
        b.append(section("本机", [
          row({ icon: "phone", color: "gray", title: (d.brand || "") + " " + (d.model || ""), sub: `Android ${d.release} · ${d.soc || ""}` }),
          row({ icon: "cpu", color: "blue", title: "处理器", val: `${d.cores} 核 · ${d.bigCores} 大核 · ${(d.maxFreqMHz / 1000).toFixed(2)} GHz` }),
          row({ icon: "layers", color: "purple", title: "图形", sub: d.glRenderer + (d.vulkan ? " · Vulkan" : "") }),
          row({ icon: "memory", color: "green", title: "内存", val: gb(d.ramMB) }),
          row({ icon: "chart", color: "orange", title: "屏幕", val: `${d.screenW}×${d.screenH} · ${Math.round(d.refresh)} Hz` }),
          row({ icon: "spark", color: "pink", title: "性能档位", val: ["", "入门", "中端", "旗舰"][A.tier] || "—" }),
        ]));
      }
      p.body.replaceChildren(b);
    },
  };
}
function rendererSheet(page) {
  sheet({
    title: "渲染器", detent: "medium", right: "完成", onRight: s => s.close(),
    render(b, s) {
      const cur = S.config.renderer || "auto";
      const pick = async id => { await setConfig({ renderer: id }); haptic("select"); s.close(); page.refresh(); };
      const rows = [row({ title: "自动", sub: "按游戏版本选择", checked: cur === "auto", onClick: () => pick("auto") })];
      for (const r of A?.renderers || []) rows.push(row({ title: r.name, sub: r.desc + (r.plugin ? " · 插件" : ""), checked: cur === r.id, onClick: () => pick(r.id),
        onLong: r.plugin ? async () => { if (await confirmBox("删除渲染器 " + r.name + "？", "", "删除", true)) { await call("removeRenderer", { id: r.id }); await loadAndroid(true); s.close(); page.refresh(); } } : null }));
      b.append(section("", rows));
      const imp = el(`<label class="cell" style="cursor:pointer"><span class="main"><b style="color:var(--tint)">导入渲染器插件…</b><small>.so 文件，或含 renderer.json 的 .zip</small></span><input type="file" hidden></label>`);
      $("input", imp).onchange = async e => {
        const f = e.target.files[0]; if (!f) return; e.target.value = "";
        const r = await fetch(`/api/import?kind=renderer&name=${encodeURIComponent(f.name)}`, { method: "POST", headers: { "X-Token": TOKEN }, body: f });
        const j = await r.json().catch(() => ({ error: "导入失败" }));
        if (j.error) toast(j.error, true); else { await loadAndroid(true); s.close(); page.refresh(); }
      };
      b.append(section("", [imp]));
    },
  });
}

function controlsPage() {
  return {
    title: "操控与键位", short: "我的",
    async render(p) {
      if (S.android && !A) await loadAndroid();
      const c = S.config;
      const layouts = A?.layouts || ["default"];
      const b = document.createDocumentFragment();
      b.append(section("键位布局", layouts.map(n => row({
        icon: "gamepad", color: "indigo", title: n === "default" ? "默认布局" : n, checked: (c.controlsLayout || "default") === n,
        onClick: async () => {
          const v = await actionSheet({ title: n === "default" ? "默认布局" : n, actions: [{ text: "使用这个布局", value: "use" }, { text: "编辑", value: "edit" }, ...(n !== "default" ? [{ text: "删除", value: "del", red: true }] : [])] });
          if (v === "use") { await setConfig({ controlsLayout: n }); p.refresh(); }
          if (v === "edit") { try { N.editControls(n); } catch { toast("请在安卓设备上编辑", true); } }
          if (v === "del") { await call("deleteLayout", { name: n }); await loadAndroid(true); await refresh(); p.refresh(); }
        },
      })), "编辑器里可拖动按键、调整大小与形状、绑定组合键、设为开关或仅在游戏中显示，并能另存为新布局。"));
      b.append(section("触控", [
        slider({ name: "视角灵敏度", min: 25, max: 300, step: 5, value: c.mouseSpeed || 100, fmt: v => v + "%", onChange: v => setConfig({ mouseSpeed: v }) }),
        slider({ name: "按键不透明度", min: 10, max: 100, step: 5, value: c.controlsOpacity || 70, fmt: v => v + "%", onChange: v => setConfig({ controlsOpacity: v }) }),
        toggleRow({ title: "按键震动反馈" }, c.haptics, v => setConfig({ haptics: v })),
      ], "滑动屏幕转动视角；轻点放置/使用；长按挖掘/攻击；点物品栏切换物品；摇杆推过外圈自动疾跑。菜单中手指即鼠标，双指轻点为右键。"));
      b.append(section("体感", [
        toggleRow({ title: "陀螺仪瞄准", sub: "转动手机来微调视角" }, c.gyroAim, v => { setConfig({ gyroAim: v }); }),
        slider({ name: "陀螺仪灵敏度", min: 25, max: 300, step: 5, value: c.gyroSens || 100, fmt: v => v + "%", onChange: v => setConfig({ gyroSens: v }) }),
      ]));
      b.append(section("外设", [
        row({ icon: "keyboard", color: "gray", title: "键盘与鼠标", sub: "插上即用：鼠标自动锁定为视角控制", wrap: true }),
        row({ icon: "gamepad", color: "green", title: "手柄", sub: "左摇杆移动 · 右摇杆视角 · RT 攻击 · LT 使用 · A 跳 · B 潜行 · X 背包 · LB/RB 切换物品", wrap: true }),
      ]));
      p.body.replaceChildren(b);
    },
  };
}

function javaPage() {
  return {
    title: "Java 与内存", short: "我的",
    async render(p) {
      const c = S.config;
      const b = document.createDocumentFragment();
      if (S.android) {
        await loadAndroid(true);
        const rts = A?.runtimes || [];
        const rows = rts.map(j => row({ icon: "java", color: "orange", title: "Java " + j.major, sub: `${j.version}${j.vendor ? " · " + j.vendor : ""}`,
          onLong: async () => { if (await confirmBox(`删除 Java ${j.major}？`, "", "删除", true)) { await call("removeJre", { home: j.home }); p.refresh(); } } }));
        const missing = (A?.bundled || []).filter(n => !rts.some(j => j.major === n));
        for (const n of missing) rows.push(row({ icon: "down", color: "blue", title: "安装 Java " + n, sub: "已内置在安装包中", chev: true, onClick: () => call("installJre", { major: n }) }));
        const imp = el(`<label class="cell icon-inset" style="cursor:pointer"><span class="ico" style="background:${COL.gray}">${ic("folder")}</span><span class="main"><b>导入运行时…</b><small>安卓版 JRE 的 .tar.xz / .zip</small></span><input type="file" hidden></label>`);
        $("input", imp).onchange = async e => {
          const f = e.target.files[0]; if (!f) return; e.target.value = "";
          const r = await fetch(`/api/import?kind=jre&name=${encodeURIComponent(f.name)}`, { method: "POST", headers: { "X-Token": TOKEN }, body: f });
          const j = await r.json().catch(() => ({ error: "导入失败" }));
          if (j.error) toast(j.error, true);
        };
        rows.push(imp);
        b.append(section("Java 运行时", rows, "1.20.5 及以上需要 Java 21，1.17–1.20.4 用 Java 17，更早的版本用 Java 8 或 17。启动时会自动选择。长按可删除。"));
        const mirror = el(`<input class="field left" placeholder="https://…/jre{major}-{abi}.tar.xz" value="${esc(c.runtimeMirror || "")}">`);
        mirror.onchange = () => setConfig({ runtimeMirror: mirror.value.trim() });
        b.append(section("运行时下载地址", [row({ title: "", right: mirror })], "可选。缺少运行时时从这里下载，{major} 和 {abi} 会被替换。"));
      } else {
        const js = await api("javaList", { refresh: false }).catch(() => []);
        b.append(section("已找到的 Java", js.map(j => row({ title: "Java " + j.major, sub: j.path }))));
      }
      const auto = c.memoryMB === 0;
      const memRows = [toggleRow({ title: "自动分配内存", sub: `原版 ${gb(S.autoMem)} · 模组 ${gb(S.autoMemModded)}` }, auto, async v => { await setConfig({ memoryMB: v ? 0 : S.autoMemModded }); p.refresh(); })];
      if (!auto) memRows.push(slider({ name: "游戏内存", min: 1024, max: Math.max(2048, (S.memTotal || 4096) - 1536), step: 256, value: c.memoryMB, fmt: v => gb(v), onChange: v => setConfig({ memoryMB: v }) }));
      b.append(section("内存", memRows, `本机共 ${gb(S.memTotal)}。给游戏太多内存会让系统杀掉后台，甚至让游戏闪退。`));
      const args = el(`<textarea class="field" placeholder="例如 -XX:+UseZGC" spellcheck="false">${esc(c.jvmArgs || "")}</textarea>`);
      args.onchange = () => setConfig({ jvmArgs: args.value.trim() });
      b.append(section("额外 JVM 参数", [row({ title: "", right: args })]));
      p.body.replaceChildren(b);
    },
  };
}

function downloadPage() {
  return {
    title: "下载", short: "我的",
    render(p) {
      const c = S.config;
      const b = document.createDocumentFragment();
      const srcs = [["auto", "自动选择", "测速后自动用最快的源" + (S.autoSource ? `（当前：${S.autoSource}）` : "")], ["bmclapi", "BMCLAPI 镜像", "国内推荐"], ["official", "官方源", "海外推荐"]];
      b.append(section("下载源", srcs.map(([v, t, sub]) => row({ title: t, sub, checked: (c.source || "auto") === v, onClick: async () => { await setConfig({ source: v }); p.refresh(); } }))));
      b.append(section("并发", [slider({ name: "下载线程", min: 4, max: 128, step: 4, value: c.threads || 32, fmt: v => v, onChange: v => setConfig({ threads: v }) })], "手机网络建议 16–48。"));
      p.body.replaceChildren(b);
    },
  };
}

function logsPage() {
  return {
    title: "游戏日志", short: "返回", noTab: true, large: false,
    async render(p) {
      p.right.innerHTML = "";
      const share = el(`<button class="navbtn">${ic("share")}</button>`);
      share.onclick = async () => {
        try { const path = await api("exportLog"); N ? N.exportFile(path, path.split("/").pop()) : toast("已导出到 " + path); }
        catch (e) { toast(e.message, true); }
      };
      p.right.append(share);
      const lines = (await api("logs").catch(() => [])) || [];
      const box = el(`<div class="logbox"></div>`);
      const add = ls => {
        const frag = document.createDocumentFragment();
        for (const l of ls) {
          const d = document.createElement("div");
          d.textContent = l;
          if (/\bWARN\b|\[warn/i.test(l)) d.className = "w"; else if (/ERROR|Exception|FATAL/.test(l)) d.className = "e"; else if (l.startsWith("[EririLily]")) d.className = "s";
          frag.append(d);
        }
        const atEnd = p.scroll.scrollTop + p.scroll.clientHeight > p.scroll.scrollHeight - 80;
        box.append(frag);
        while (box.childElementCount > 3000) box.firstElementChild.remove();
        if (atEnd) p.scroll.scrollTop = p.scroll.scrollHeight;
      };
      p.body.replaceChildren(lines.length ? box : empty("doc", "还没有日志", "启动一次游戏后这里会显示输出"));
      add(lines);
      p.scroll.scrollTop = p.scroll.scrollHeight;
      logSink = ls => { if (!box.isConnected) { logSink = null; return; } if (!box.parentNode) p.body.replaceChildren(box); add(ls); };
    },
  };
}

function aboutPage() {
  return {
    title: "关于", short: "我的",
    render(p) {
      const b = document.createDocumentFragment();
      b.append(el(`<div class="empty" style="padding-top:8px"><img src="logo.png" alt="" style="width:96px;height:96px;border-radius:22px;display:block;margin:0 auto 12px;box-shadow:var(--shadow)"><b>樱梨启动器</b>v${esc(S.version)}${N ? " · Android " + esc(N.appVersion?.() || "") : ""}<br>D0cK × Claude</div>`));
      b.append(section("开源组件", [
        row({ title: "OpenJDK", sub: "GPLv2 + Classpath Exception · 安卓移植来自 PojavLauncherTeam" }),
        row({ title: "LWJGL 3", sub: "BSD-3-Clause" }),
        row({ title: "GL4ES", sub: "MIT · ptitSeb" }),
        row({ title: "MobileGlues", sub: "LGPL-2.1 · MobileGL-Dev" }),
        row({ title: "OpenAL Soft", sub: "LGPL-2.0" }),
      ]));
      b.append(section("", [row({ title: "非 Mojang 或 Microsoft 官方产品", sub: "Minecraft 是 Mojang Studios 的商标", wrap: true })]));
      p.body.replaceChildren(b);
    },
  };
}

/* ---------------------------------------------------------------- boot */
(async function boot() {
  applyTheme(document.documentElement.dataset.theme || "sakura");
  if (!TOKEN) { document.body.textContent = "缺少访问令牌"; return; }
  await refresh();
  tabs.home = new Stack("home", homePage());
  tabs.library = new Stack("library", libraryPage());
  tabs.discover = new Stack("discover", discoverPage());
  tabs.me = new Stack("me", mePage());
  connect();
  const bootEl = $("#boot");
  bootEl.style.opacity = "0";
  setTimeout(() => bootEl.remove(), 450);
  // keep the memory widget and running state fresh
  setInterval(() => { if (document.visibilityState === "visible" && !refreshing) refresh(); }, 15000);
})();
