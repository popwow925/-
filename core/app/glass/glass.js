"use strict";
/* EririLily — frosted-glass UI over the launcher core's RPC. Written for EririLily; shares nothing with other launchers. */
const TOKEN = location.hash.slice(1);
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const N = window.ELNative || null;
const LOADER = { fabric: "Fabric", quilt: "Quilt", forge: "Forge", neoforge: "NeoForge", "": "原版" };
const KIND = { mod: "模组", resourcepack: "资源包", shader: "光影", modpack: "整合包" };

async function api(name, params = {}) {
  const r = await fetch("/api/" + name, { method: "POST", headers: { "X-Token": TOKEN, "Content-Type": "application/json" }, body: JSON.stringify(params) });
  const j = await r.json().catch(() => ({ error: "服务无响应" }));
  if (j.error) throw new Error(j.error);
  return j.result;
}
async function call(name, params, ok) {
  try { const r = await api(name, params); if (ok) toast(ok); return r; }
  catch (e) { toast(e.message, true); haptic("error"); throw e; }
}
const haptic = (k = "light") => { try { N?.haptic(k); } catch { } };
const stored = (k, d) => { try { const v = localStorage.getItem("el." + k); return v == null ? d : JSON.parse(v); } catch { return d; } };
const store = (k, v) => { try { localStorage.setItem("el." + k, JSON.stringify(v)); } catch { } };
const el = html => { const t = document.createElement("template"); t.innerHTML = html.trim(); return t.content.firstElementChild; };
const fmtBytes = n => { if (!n) return "0 B"; const u = ["B", "KB", "MB", "GB"]; let i = 0; while (n >= 1024 && i < 3) { n /= 1024; i++; } return n.toFixed(i ? 1 : 0) + " " + u[i]; };
const ago = ts => { if (!ts) return "还没玩过"; const d = Date.now() / 1000 - ts; return d < 3600 ? Math.max(1, Math.round(d / 60)) + " 分钟前" : d < 86400 ? Math.round(d / 3600) + " 小时前" : Math.round(d / 86400) + " 天前"; };
const hours = s => s < 3600 ? Math.max(1, Math.round(s / 60)) + " 分钟" : (s / 3600).toFixed(1) + " 小时";
const gb = mb => (mb / 1024).toFixed(mb >= 10240 ? 0 : 1) + " GB";
const nfmt = n => { const t = v => String(+v.toFixed(1)); return n >= 1e8 ? t(n / 1e8) + " 亿" : n >= 1e4 ? t(n / 1e4) + " 万" : String(n); };

/* ---------------------------------------------------------------- icons */
const P = {
  home: '<path d="M4 11 12 4l8 7v8a1 1 0 0 1-1 1h-4v-5H9v5H5a1 1 0 0 1-1-1z"/>',
  chev: '<path d="m9 5 7 7-7 7"/>', back: '<path d="m15 4-8 8 8 8"/>', plus: '<path d="M12 5v14M5 12h14"/>',
  play: '<path d="M8 5.2v13.6a1 1 0 0 0 1.5.86l11-6.8a1 1 0 0 0 0-1.72l-11-6.8A1 1 0 0 0 8 5.2z" fill="currentColor" stroke="none"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>', x: '<path d="M6 6l12 12M18 6 6 18"/>', info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5h.01"/>',
  cube: '<path d="m12 3 8 4.5v9L12 21l-8-4.5v-9z"/><path d="m4 7.5 8 4.5 8-4.5M12 12v9"/>',
  puzzle: '<path d="M10 4a2 2 0 1 1 4 0v2h4v4h-2a2 2 0 1 0 0 4h2v4h-4v-2a2 2 0 1 0-4 0v2H6v-4h2a2 2 0 1 0 0-4H6V6h4z"/>',
  paint: '<path d="M12 3a9 9 0 0 0 0 18c1.5 0 2-1 2-2s-1-1.5-1-2.5 1-1.5 2-1.5h2a4 4 0 0 0 4-4c0-4.5-4-8-9-8z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10" cy="7" r="1"/><circle cx="15" cy="7.5" r="1"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  wand: '<path d="m15 4 1 2 2 1-2 1-1 2-1-2-2-1 2-1zM5 19 15 9M19 13l.7 1.3L21 15l-1.3.7L19 17l-.7-1.3L17 15l1.3-.7z"/>',
  layers: '<path d="m12 3 9 5-9 5-9-5z"/><path d="m3 13 9 5 9-5"/>', chip: '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>',
  mem: '<rect x="3" y="7" width="18" height="10" rx="2"/><path d="M7 11v2M11 11v2M15 11v2M7 17v3M12 17v3M17 17v3"/>',
  pad: '<path d="M7 9h4M9 7v4M15 10h.01M17.5 12h.01"/><path d="M6.5 5h11A4.5 4.5 0 0 1 22 9.5c0 3-1 9.5-4 9.5-2 0-2.5-3-6-3s-4 3-6 3c-3 0-4-6.5-4-9.5A4.5 4.5 0 0 1 6.5 5z"/>',
  user: '<circle cx="12" cy="8.5" r="3.8"/><path d="M4.5 20.5c.8-4 4-5.8 7.5-5.8s6.7 1.8 7.5 5.8"/>', trash: '<path d="M4 7h16M10 11v6M14 11v6M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2l1-12M9 7V4h6v3"/>',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  down: '<path d="M12 4v12M6 11l6 6 6-6M5 20h14"/>', up: '<circle cx="12" cy="12" r="9"/><path d="M12 16V8M8.5 11.5 12 8l3.5 3.5"/>',
  search: '<circle cx="11" cy="11" r="6.5"/><path d="m20 20-4.2-4.2"/>', doc: '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5M9 13h6M9 17h6"/>',
  bolt: '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>', chart: '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>', java: '<path d="M8 18c3 1.5 7 1 9-.5M7 15c4 1.5 8 1 10-.5M10 3c4 3-3 5 1 9M14 7c2 2-2 3 0 5"/>',
  link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>', copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2"/>',
  share: '<path d="M12 3v12M8 7l4-4 4 4M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-7"/>', pencil: '<path d="M4 20h4L19 9l-4-4L4 16zM14 6l4 4"/>',
  shield: '<path d="M12 3 5 6v6c0 4.500 3 8 7 9 4-1 7-4.500 7-9V6z"/><path d="m9 12 2 2 4-4"/>', globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.500 3 2.500 15 0 18M12 3c-2.500 3-2.500 15 0 18"/>',
  phone: '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M11 18h2"/>', drop: '<path d="M12 3c4 5 6 8 6 11a6 6 0 0 1-12 0c0-3 2-6 6-11z"/>', stop: '<rect x="6.500" y="6.500" width="11" height="11" rx="2.500" fill="currentColor" stroke="none"/>',
};
const ic = (n, c = "") => `<svg viewBox="0 0 24 24" class="${c}">${P[n] || ""}</svg>`;
const COL = { pink: ["#ff5c8a", "#ff9bb5"], red: ["#ff453a", "#ff8a7a"], orange: ["#ff9500", "#ffc04d"], green: ["#2fbf71", "#7be0b0"], teal: ["#18b6c4", "#6fdce6"], blue: ["#3d7bff", "#78a8ff"], indigo: ["#6a5cf0", "#9a8dff"], purple: ["#b05ae8", "#d794ff"], gray: ["#7d8594", "#aab0bd"] };

/* ---------------------------------------------------------------- theme & local look settings */
let S = null, A = null;
function applyTheme(theme) {
  theme = theme || "sakura";
  const dark = matchMedia("(prefers-color-scheme: dark)").matches;
  const eff = theme === "auto" ? (dark ? "black" : "sakura") : theme;
  const r = document.documentElement;
  r.dataset.theme = eff; r.dataset.mode = eff === "black" ? "dark" : "light";
  try { N?.statusBar(r.dataset.mode === "light"); } catch { }
}
function applyLook() {
  const r = document.documentElement;
  r.style.setProperty("--blur", stored("blur", 26) + "px");
  r.dataset.quiet = stored("quiet", false) ? "1" : "0";
  r.dataset.flat = stored("flat", false) ? "1" : "0";
}
window.elSystemTheme = () => applyTheme(S?.config?.theme);
matchMedia("(prefers-color-scheme: dark)").addEventListener?.("change", () => window.elSystemTheme());
window.elInsets = (t, r, b, l) => { const s = document.documentElement.style; s.setProperty("--sat", t + "px"); s.setProperty("--sar", r + "px"); s.setProperty("--sab", b + "px"); s.setProperty("--sal", l + "px"); };
window.elToast = (m, e) => toast(m, e);
window.elResume = () => refresh();

/* ---------------------------------------------------------------- toast & island */
function toast(msg, err = false) {
  const t = el(`<div class="toast${err ? " err" : ""}">${ic(err ? "info" : "check")}<span>${esc(msg)}</span></div>`);
  $("#toasts").append(t);
  setTimeout(() => { t.classList.add("out"); setTimeout(() => t.remove(), 320); }, err ? 4200 : 2400);
}
const island = (() => {
  const n = $("#island"), b = $("b", n), sm = $("small", n), bar = $("u", n); let t = 0;
  const show = () => { clearTimeout(t); n.classList.remove("hidden"); requestAnimationFrame(() => n.classList.add("on")); };
  const hide = d => { clearTimeout(t); t = setTimeout(() => { n.classList.remove("on"); }, d); };
  $(".x", n).onclick = () => call("cancel", {}).catch(() => { });
  return {
    start(label) { n.classList.remove("done", "fail"); b.textContent = label; sm.textContent = "准备中…"; bar.style.width = "0"; show(); try { N?.setTask(label, "", 0); } catch { } },
    stage(d) { sm.textContent = typeof d === "string" ? d : d?.label || d?.stage || sm.textContent; },
    progress(f, text, stage) { bar.style.width = Math.round(f * 100) + "%"; sm.textContent = (stage ? stage + " · " : "") + text; try { N?.setTask(b.textContent, text, Math.round(f * 100)); } catch { } },
    finish(ok, text) { n.classList.add(ok ? "done" : "fail"); b.textContent = text; sm.textContent = ""; bar.style.width = ok ? "100%" : "0"; try { N?.setTask("", "", 0); } catch { } hide(1800); },
  };
})();

/* ---------------------------------------------------------------- building blocks */
function row(o) {
  const c = COL[o.color] || COL.pink;
  const n = el(`<${o.onClick || o.onLong ? "button" : "div"} class="cell${o.onClick ? " tap" : ""}${o.red ? " red" : ""}${o.center ? " center" : ""}">
    ${o.icon ? `<span class="ic" style="--c1:${c[0]};--c2:${c[1]}">${ic(o.icon)}</span>` : ""}
    ${o.img ? `<img class="th" src="${esc(o.img)}" alt="" loading="lazy" onerror="this.style.visibility='hidden'">` : ""}${o.inst ? pic(o.inst, "th") : ""}
    ${o.head ? `<span class="avatar" data-head="${esc(o.head)}"></span>` : ""}
    ${o.title != null && !o.center ? `<span class="m"><b>${esc(o.title)}</b>${o.sub ? `<small class="${o.wrap ? "wrap" : ""}">${esc(o.sub)}</small>` : ""}</span>` : `<span>${esc(o.title || "")}</span>`}
    ${o.val != null ? `<span class="v">${esc(o.val)}</span>` : ""}
    ${o.checked ? ic("check", "tick") : ""}${o.chev ? ic("chev", "chev") : ""}</${o.onClick || o.onLong ? "button" : "div"}>`);
  if (o.right) n.append(o.right);
  if (o.onClick) n.onclick = e => { haptic("light"); o.onClick(e); };
  if (o.onLong) longPress(n, o.onLong);
  return n;
}
const grp = rows => { const g = el(`<div class="grp"></div>`); g.append(...rows.filter(Boolean)); return g; };
const h2 = (t, link, fn) => { const n = el(`<div class="h2"><span>${esc(t)}</span>${link ? `<a>${esc(link)}</a>` : ""}</div>`); if (link) $("a", n).onclick = fn; return n; };
const foot = t => el(`<div class="foot">${esc(t)}</div>`);
const section = (title, rows, note) => { const f = document.createDocumentFragment(); if (title) f.append(h2(title)); f.append(grp(rows)); if (note) f.append(foot(note)); return f; };
function sw(on, change) {
  const n = el(`<label class="sw"><input type="checkbox" ${on ? "checked" : ""}><i></i></label>`);
  $("input", n).onchange = e => { haptic("select"); change(e.target.checked, e.target); };
  return n;
}
const toggleRow = (o, on, change) => row({ ...o, right: sw(on, change) });
function slider(o) {
  const n = el(`<div class="cell col"><div style="display:flex;justify-content:space-between;font-weight:600"><span>${esc(o.name)}</span><span class="val" style="color:var(--tx2);font-weight:500"></span></div><input class="range" type="range" min="${o.min}" max="${o.max}" step="${o.step || 1}" value="${o.value}"></div>`);
  const i = $("input", n), v = $(".val", n);
  const sync = () => { v.textContent = o.fmt(+i.value); i.style.setProperty("--p", ((i.value - o.min) / (o.max - o.min) * 100) + "%"); };
  sync(); i.oninput = sync; i.onchange = () => { haptic("select"); o.onChange(+i.value); };
  return n;
}
function seg(options, value, change, scroll) {
  const n = el(`<div class="seg${scroll ? " scrollx" : ""}">${options.map(([v, t]) => `<button data-v="${esc(v)}" class="${v === value ? "on" : ""}">${esc(t)}</button>`).join("")}</div>`);
  const set = v => $$("button", n).forEach(b => b.classList.toggle("on", b.dataset.v === v));
  n.onclick = e => { const b = e.target.closest("button"); if (!b) return; set(b.dataset.v); haptic("select"); change(b.dataset.v); };
  n.set = set; return n;
}
function longPress(n, fn) {
  let t = 0, x = 0, y = 0, fired = false;
  n.addEventListener("touchstart", e => { fired = false; x = e.touches[0].clientX; y = e.touches[0].clientY; t = setTimeout(() => { fired = true; haptic("heavy"); fn(x, y); }, 520); }, { passive: true });
  const stop = () => clearTimeout(t);
  n.addEventListener("touchmove", e => { if (Math.abs(e.touches[0].clientX - x) + Math.abs(e.touches[0].clientY - y) > 10) stop(); }, { passive: true });
  n.addEventListener("touchend", stop); n.addEventListener("touchcancel", stop);
  n.addEventListener("contextmenu", e => { e.preventDefault(); fn(e.clientX, e.clientY); });
  n.addEventListener("click", e => { if (fired) { e.stopImmediatePropagation(); e.preventDefault(); fired = false; } }, true);
}
const loading = () => el(`<div class="spin"></div>`);
const empty = (icon, title, text) => el(`<div class="empty">${ic(icon)}<b>${esc(title)}</b>${esc(text || "")}</div>`);

/* ---------------------------------------------------------------- sheets & dialogs */
const openSheets = [];
function sheet(o) {
  const scrim = el(`<div class="scrim"></div>`);
  const s = el(`<div class="sheet${o.dialog ? " dialog" : ""}"><div class="grab"></div><header><button class="l">${esc(o.left || "")}</button><h3>${esc(o.title || "")}</h3><button class="r">${esc(o.right || "")}</button></header><div class="body"></div></div>`);
  if (o.dialog) { $(".grab", s).remove(); $("header", s).remove(); }
  document.body.append(scrim, s);
  const api2 = {
    body: $(".body", s), el: s,
    close() { const k = openSheets.indexOf(api2); if (k >= 0) openSheets.splice(k, 1); scrim.classList.remove("on"); s.classList.remove("on"); setTimeout(() => { scrim.remove(); s.remove(); o.onClose?.(); }, 420); },
  };
  if (!o.dialog) {
    $(".l", s).onclick = () => o.onLeft ? o.onLeft(api2) : api2.close();
    $(".r", s).onclick = () => o.onRight ? o.onRight(api2) : api2.close();
    if (!o.left) $(".l", s).style.visibility = "hidden";
    // drag the handle or header down to dismiss
    let y0 = 0, dy = 0, drag = false;
    const hd = $("header", s), gr = $(".grab", s);
    for (const h of [hd, gr]) {
      h.addEventListener("touchstart", e => { y0 = e.touches[0].clientY; dy = 0; drag = true; s.style.transition = "none"; }, { passive: true });
      h.addEventListener("touchmove", e => { if (!drag) return; dy = Math.max(0, e.touches[0].clientY - y0); s.style.transform = `translate3d(0,${dy}px,0)`; }, { passive: true });
      h.addEventListener("touchend", () => { if (!drag) return; drag = false; s.style.transition = ""; s.style.transform = ""; if (dy > 110) api2.close(); });
    }
  }
  openSheets.push(api2);
  scrim.onclick = () => (o.dialog ? null : api2.close());
  o.render?.(api2.body, api2);
  requestAnimationFrame(() => requestAnimationFrame(() => { scrim.classList.add("on"); s.classList.add("on"); }));
  return api2;
}
function dialog({ title, message, input, value = "", placeholder = "", ok = "确定", cancel = "取消", red = false }) {
  return new Promise(res => {
    let done = false;
    const s = sheet({
      dialog: true, onClose: () => { if (!done) res(null); },
      render(b) {
        b.append(el(`<div class="dlg"><h3>${esc(title)}</h3>${message ? `<p>${esc(message)}</p>` : ""}${input ? `<input class="field" value="${esc(value)}" placeholder="${esc(placeholder)}">` : ""}</div>`));
        const act = el(`<div class="dlg-act">${cancel ? `<button class="dim">${esc(cancel)}</button>` : ""}<button class="${red ? "red" : ""}">${esc(ok)}</button></div>`);
        const [c, k] = cancel ? $$("button", act) : [null, $("button", act)];
        c && (c.onclick = () => { done = true; s.close(); res(null); });
        k.onclick = () => { done = true; const v = input ? $("input", b).value.trim() : true; s.close(); res(v || (input ? null : true)); };
        b.append(act);
        if (input) setTimeout(() => $("input", b)?.focus(), 380);
      },
    });
  });
}
const confirmBox = (title, message, ok = "确定", red = false) => dialog({ title, message, ok, red });
const promptBox = (title, message, value = "", placeholder = "") => dialog({ title, message, input: true, value, placeholder });
function menu(title, items) {
  return new Promise(res => {
    let picked = false;
    const s = sheet({ title, right: "取消", onClose: () => { if (!picked) res(null); }, render(b, sh) {
      b.append(grp(items.map(i => row({ icon: i.icon, color: i.color, title: i.text, sub: i.sub, red: i.red, onClick: () => { picked = true; sh.close(); res(i.value ?? i.text); } }))));
    } });
  });
}

/* ---------------------------------------------------------------- pages & router */
const stage = $("#stage"), dock = $("#dock");
const tabNames = ["home", "library", "discover", "me"];
const roots = {}, stack = [];
let curTab = "home";

function makePage(def, isRoot) {
  const n = el(`<section class="page${isRoot ? "" : " sub enter"}"><header class="top"><div class="lead"></div><h1></h1><div class="act" style="display:flex;gap:8px"></div></header><div class="scroll"><div class="body"></div></div></section>`);
  const p = { def, el: n, top: $(".top", n), scroll: $(".scroll", n), body: $(".body", n), act: $(".act", n), lead: $(".lead", n), h1: $("h1", n), root: isRoot };
  p.setTitle = t => { p.h1.textContent = t; };
  p.refresh = () => Promise.resolve(def.render?.(p));
  p.setTitle(def.title || "");
  if (!isRoot) {
    const b = el(`<button class="iconbtn glass">${ic("back")}</button>`);
    b.onclick = () => pop(); p.lead.append(b);
  } else p.lead.remove();
  if (def.brand) { p.h1.remove(); const br = el(`<div class="brand"><img src="logo.png" alt=""><div><b>樱梨</b><small>ERIRILILY</small></div></div>`); p.top.insertBefore(br, p.act); }
  p.scroll.addEventListener("scroll", () => p.top.classList.toggle("solid", p.scroll.scrollTop > 12), { passive: true });
  return p;
}
function current() { return stack.length ? stack[stack.length - 1] : roots[curTab]; }
function push(def) {
  const below = current();
  const p = makePage(def, false);
  stage.append(p.el); stack.push(p);
  p.refresh();
  below.el.classList.add("under");
  requestAnimationFrame(() => requestAnimationFrame(() => p.el.classList.remove("enter")));
  dock.classList.add("gone");
  haptic("light");
  return p;
}
function pop() {
  const p = stack.pop(); if (!p) return false;
  p.def.onClose?.();
  p.el.classList.add("enter");
  const below = current();
  below.el.classList.remove("under");
  if (!stack.length) dock.classList.remove("gone");
  setTimeout(() => p.el.remove(), 480);
  if (below.def.live) below.refresh();
  return true;
}
window.elBack = () => { if (openSheets.length) { openSheets[openSheets.length - 1].close(); return true; } if (pop()) return true; if (curTab !== "home") { switchTab("home"); return true; } return false; };
function switchTab(name) {
  if (name === curTab && !stack.length) { roots[name].scroll.scrollTo({ top: 0, behavior: "smooth" }); return; }
  while (stack.length) { const p = stack.pop(); p.el.remove(); }
  dock.classList.remove("gone");
  for (const t of tabNames) { roots[t].el.classList.remove("under"); roots[t].el.classList.toggle("hide", t !== name); }
  curTab = name; haptic("select");
  $$("#dock button").forEach(b => b.classList.toggle("on", b.dataset.tab === name));
  $("#dock .pill").style.transform = `translateX(${tabNames.indexOf(name) * 100}%)`;
  roots[name].refresh();
}
$$("#dock button").forEach(b => b.onclick = () => switchTab(b.dataset.tab));
// swipe in from the left edge to go back
(() => {
  let x0 = 0, y0 = 0, on = false, top = null, below = null;
  stage.addEventListener("touchstart", e => { if (!stack.length || e.touches[0].clientX > 26) return; x0 = e.touches[0].clientX; y0 = e.touches[0].clientY; on = true; top = current(); below = stack.length > 1 ? stack[stack.length - 2] : roots[curTab]; top.el.style.transition = "none"; }, { passive: true });
  stage.addEventListener("touchmove", e => { if (!on) return; const dx = Math.max(0, e.touches[0].clientX - x0); top.el.style.transform = `translate3d(${dx}px,0,0)`; }, { passive: true });
  stage.addEventListener("touchend", e => { if (!on) return; on = false; const dx = e.changedTouches[0].clientX - x0; top.el.style.transition = ""; top.el.style.transform = ""; if (dx > 90) pop(); });
})();

/* ---------------------------------------------------------------- images & data */
const proxied = u => /^https:\/\/[^/]*modrinth\.com\//.test(u || "") ? `/api/img?t=${TOKEN}&u=${encodeURIComponent(u)}` : u;
const coverURL = (kind, inst, world, v) => `/api/cover?t=${TOKEN}&kind=${kind}&inst=${encodeURIComponent(inst)}${world ? "&world=" + encodeURIComponent(world) : ""}&v=${v}`;
const ART = [["#ff9ab8", "#ffc59a"], ["#9cc2ff", "#c9a8ff"], ["#8fe0c4", "#9fcfff"], ["#ffd27a", "#ff9a8a"], ["#c9a6ff", "#ff9ac8"], ["#86d4ff", "#9af0cf"], ["#ffa3a3", "#ffd9a3"], ["#b4e08a", "#86e0c8"]];
function artStyle(i) {
  let h = 7; for (const ch of (i?.id || "x")) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  const [a, b] = ART[h % ART.length];
  return `background:radial-gradient(circle at ${20 + h % 60}% ${10 + (h >> 4) % 40}%,rgba(255,255,255,.38),transparent 52%),linear-gradient(${(h >> 3) % 360}deg,${a},${b})`;
}
function thumbURL(i) { if (i?.cover) return coverURL("inst", i.id, "", i.cover); if (i?.icon) return proxied(i.icon); return ""; }
/** an instance picture: its own cover when it has one, otherwise generated art */
function pic(i, cls) { const u = thumbURL(i); return u ? `<img class="${cls}" src="${esc(u)}" alt="" loading="lazy">` : `<span class="${cls} art" style="${artStyle(i)}"></span>`; }
const skinCache = {};
async function paintHeads(root = document) {
  for (const n of $$("[data-head]", root)) {
    const id = n.dataset.head;
    try {
      if (!skinCache[id]) {
        const r = await fetch(`/api/skin?t=${TOKEN}&id=${encodeURIComponent(id)}`);
        const url = URL.createObjectURL(await r.blob());
        skinCache[id] = await new Promise((ok, no) => { const i = new Image(); i.onload = () => ok(i); i.onerror = no; i.src = url; });
      }
      const img = skinCache[id], k = img.width / 64, cv = document.createElement("canvas"); cv.width = cv.height = 64;
      const c = cv.getContext("2d"); c.imageSmoothingEnabled = false;
      c.drawImage(img, 8 * k, 8 * k, 8 * k, 8 * k, 4, 4, 56, 56); c.drawImage(img, 40 * k, 8 * k, 8 * k, 8 * k, 0, 0, 64, 64);
      n.replaceChildren(cv);
    } catch { n.innerHTML = ic("user"); n.style.display = "grid"; n.style.placeItems = "center"; }
  }
}
const curInst = () => (S?.instances || []).find(i => i.id === S?.config?.selected);
const instById = id => (S?.instances || []).find(i => i.id === id);
const metaLine = i => ["Minecraft " + i.game, LOADER[i.loader] ?? i.loader, i.mods ? i.mods + " 个模组" : ""].filter(Boolean).join(" · ");
async function setConfig(patch) { try { S.config = await api("setConfig", patch); return S.config; } catch (e) { toast(e.message, true); } }
const loadAndroid = async force => { try { A = await api("androidInfo", { refresh: !!force }); } catch { A = null; } return A; };
let refreshing = null;
function refresh() {
  if (refreshing) return refreshing;
  refreshing = (async () => {
    try {
      S = await api("state"); applyTheme(S.config.theme);
      if (S.android && !A) await loadAndroid();
      for (const p of [...Object.values(roots), ...stack]) if (p.def.live && !p.el.classList.contains("hide")) p.refresh();
      syncPlay();
    } catch (e) { console.error(e); }
    refreshing = null;
  })();
  return refreshing;
}
const openURL = u => { try { N.openUrl(u); } catch { window.open(u, "_blank"); } };

/* ---------------------------------------------------------------- events & launch */
let launching = false;
function syncPlay(stageText, f) {
  for (const b of $$(".playbtn")) {
    const label = $("span", b), fill = $(".fill", b);
    b.classList.toggle("running", !!S?.running && !launching);
    if (launching) { label.textContent = stageText || label.textContent || "正在启动…"; if (f != null) fill.style.width = f * 100 + "%"; }
    else { fill.style.width = "0"; label.textContent = S?.running ? "游戏运行中 · 回到游戏" : "开始游戏"; }
  }
}
function connect() {
  const es = new EventSource("/api/events?t=" + TOKEN);
  es.onmessage = ev => {
    const { type, data } = JSON.parse(ev.data);
    switch (type) {
      case "task":
        if (data.state === "start") { island.start(data.label); launching = data.label.startsWith("启动"); syncPlay("准备启动"); }
        else {
          if (data.state === "error") { island.finish(false, "失败"); toast(data.error, true); haptic("error"); }
          else if (data.state === "cancelled") island.finish(false, "已取消");
          else island.finish(true, launching ? "游戏已启动" : "完成");
          if (data.state !== "done" || !launching) launching = false;
          refresh();
        }
        break;
      case "stage": island.stage(data); syncPlay(typeof data === "string" ? data : data?.label); break;
      case "progress": { const f = data.total ? data.done / data.total : 0; island.progress(f, `${data.done}/${data.total} · ${data.speed ? fmtBytes(data.speed) + "/s" : fmtBytes(data.bytes)}`, data.stage); syncPlay(data.stage, f); break; }
      case "started": launching = true; refresh(); break;
      case "ready": launching = false; haptic("success"); toast(`游戏已启动，用时 ${(data.ms / 1000).toFixed(1)} 秒`); refresh(); break;
      case "logs": logLines.push(...data); if (logLines.length > 4000) logLines.splice(0, logLines.length - 4000); logSink?.(data); break;
      case "exit":
        launching = false;
        if (data.diagnosis) { haptic("error"); diagnosisSheet(data.diagnosis); }
        else if (data.played > 5) toast(`游戏已退出，本次游玩 ${hours(data.played)}`);
        refresh(); break;
      case "installed": haptic("success"); toast("安装完成：" + data); refresh(); break;
      case "fixed": toast(data); refresh(); loadAndroid(true); break;
      case "updated": toast(`已更新 ${data.n} 项`); refresh(); break;
      case "account": msSheet?.close(); if (data.error) toast(data.error, true); else { haptic("success"); toast("已登录 " + data.ok); } refresh(); break;
    }
  };
  es.onerror = () => { es.close(); setTimeout(connect, 1500); };
}
const logLines = []; let logSink = null, msSheet = null;
async function launch(id, server) {
  if (!S.accounts?.length) { toast("先添加一个账户", true); switchTab("me"); return; }
  if (S.running) { if (N?.openGame) N.openGame(); else if (await confirmBox("游戏正在运行", "要结束游戏进程吗？未保存的进度可能丢失。", "结束游戏", true)) call("kill", {}, "已结束游戏"); return; }
  if (launching) return;
  haptic("medium"); launching = true; syncPlay("准备启动");
  try { await api("launch", { id: id || S.config.selected, server }); }
  catch (e) { launching = false; syncPlay(); toast(e.message, true); haptic("error"); }
}
function diagnosisSheet(d) {
  sheet({ title: "游戏异常退出", right: "完成", render(b, s) {
    b.append(grp([row({ icon: "info", color: "red", title: d.title, sub: d.detail, wrap: true })]));
    if (d.evidence) b.append(foot("日志：" + d.evidence));
    const acts = [];
    if (d.fix) acts.push(row({ title: d.fixLabel || "一键修复", center: true, onClick: async () => { s.close(); await call("fix", { action: d.fix, arg: d.fixArg || "", instance: S.config.selected }); } }));
    acts.push(row({ title: "查看日志", center: true, onClick: () => { s.close(); push(logsPage()); } }));
    if (d.report) acts.push(row({ title: "打开崩溃报告", center: true, onClick: () => call("open", { what: "report" }) }));
    b.append(el(`<div style="height:12px"></div>`), grp(acts));
  } });
}

/* ================================================================ 首页 */
function homePage() {
  return {
    brand: true, live: true,
    render(p) {
      if (!S) return;
      const acc = (S.accounts || []).find(a => a.id === S.config.account);
      const av = el(`<button class="iconbtn avatar glass" ${acc ? `data-head="${esc(acc.id)}"` : ""}>${acc ? "" : ic("user")}</button>`);
      av.onclick = () => accountsSheet();
      p.act.replaceChildren(av); paintHeads(p.act);
      const i = curInst(), b = document.createDocumentFragment();
      const hero = el(`<div class="hero${i ? "" : " blank"}">${i ? pic(i, "bgimg") : `<img class="mark" src="logo.png" alt="">`}
        <button class="sw-btn">${i ? "切换实例" : "新建实例"}${ic("chev")}</button>
        <div class="meta">${i ? `<span class="chip">${esc(i.game)}</span><span class="chip">${esc(LOADER[i.loader] ?? i.loader)}</span>${i.mods ? `<span class="chip">${i.mods} 模组</span>` : ""}` : `<span class="chip">欢迎</span>`}</div>
        <h2>${esc(i ? i.name : "开始你的冒险")}</h2>
        <p>${i ? esc(ago(i.lastPlayed)) + (i.playTime ? " · 共玩 " + hours(i.playTime) : "") : "新建一个实例，就能在手机上玩 Java 版"}</p>
        <button class="btn playbtn block"><i class="fill"></i>${ic("play")}<span>开始游戏</span></button></div>`);
      $(".sw-btn", hero).onclick = () => i ? pickInstanceSheet() : newInstanceSheet();
      $(".playbtn", hero).onclick = () => i ? launch() : newInstanceSheet();
      b.append(hero);

      const tier = A ? ["", "入门机", "中端机", "旗舰机"][A.tier] || "—" : "—";
      const rs = A?.renderers || [], rcur = S.config.renderer || "auto";
      const rname = rcur === "auto" ? "自动" : rs.find(r => r.id === rcur)?.name || rcur;
      const mem = S.config.memoryMB || (i?.loader ? S.autoMemModded : S.autoMem);
      const fps = S.config.fpsLimit;
      const tiles = el(`<div class="tiles"></div>`);
      const t1 = el(`<button class="tile accent"><div class="t">${ic("wand")}一键优化</div><div class="big">${tier}</div><div class="sub">按机型调校画质与帧率</div></button>`);
      t1.onclick = () => optimizeSheet(S.config.selected);
      const t2 = el(`<button class="tile"><div class="t">${ic("layers")}渲染器</div><div class="big" style="font-size:20px">${esc(rname)}</div><div class="sub">倍率 ${S.config.renderScale || 100}% · ${fps ? fps + " FPS" : "不限帧率"}</div></button>`);
      t2.onclick = () => push(perfPage());
      const t3 = el(`<button class="tile"><div class="t">${ic("mem")}内存</div><div class="big">${gb(mem)}</div><div class="sub">本机可用 ${gb(S.memFree)} / ${gb(S.memTotal)}</div></button>`);
      t3.onclick = () => push(javaPage());
      const t4 = el(`<button class="tile"><div class="t">${ic("pad")}键位</div><div class="big" style="font-size:20px">${esc(S.config.controlsLayout === "default" || !S.config.controlsLayout ? "默认布局" : S.config.controlsLayout)}</div><div class="sub">点按编辑虚拟按键</div></button>`);
      t4.onclick = () => { try { N.editControls(S.config.controlsLayout || "default"); } catch { push(controlsPage()); } };
      tiles.append(t1, t2, t3, t4); b.append(tiles);

      if (S.diagnosis) { const w = el(`<button class="warn glass">${ic("info")}<span><b>${esc(S.diagnosis.title)}</b><small>${esc(S.diagnosis.detail)}</small></span></button>`); w.onclick = () => diagnosisSheet(S.diagnosis); b.append(w); }
      const others = (S.instances || []).filter(x => x.id !== S.config.selected).slice(0, 8);
      if (others.length) {
        b.append(h2("最近游玩", "全部", () => switchTab("library")));
        const hr = el(`<div class="hrow"></div>`);
        for (const x of others) {
          const c = el(`<button class="card glass">${pic(x, "ph")}<b>${esc(x.name)}</b><small>${esc(LOADER[x.loader] ?? x.loader)} ${esc(x.game)} · ${esc(ago(x.lastPlayed))}</small></button>`);
          c.onclick = async () => { await call("select", { id: x.id }); haptic("select"); await refresh(); p.scroll.scrollTo({ top: 0, behavior: "smooth" }); };
          longPress(c, () => instanceMenu(x));
          hr.append(c);
        }
        b.append(hr);
      }
      p.body.replaceChildren(b); syncPlay();
    },
  };
}
function pickInstanceSheet() {
  sheet({ title: "选择实例", right: "完成", render(b, s) {
    b.append(grp((S.instances || []).map(x => row({ inst: x, title: x.name, sub: metaLine(x), checked: x.id === S.config.selected, onClick: async () => { await call("select", { id: x.id }); s.close(); refresh(); } }))));
    b.append(el(`<div style="height:12px"></div>`), grp([row({ icon: "plus", color: "green", title: "新建实例", chev: true, onClick: () => { s.close(); newInstanceSheet(); } })]));
  } });
}
async function instanceMenu(x) {
  const v = await menu(x.name, [{ text: "开始游戏", icon: "play", value: "play" }, { text: "设为当前", icon: "check", color: "green", value: "sel" }, { text: "详情", icon: "info", color: "blue", value: "info" }, { text: "一键优化", icon: "wand", color: "purple", value: "opt" }, { text: "删除", icon: "trash", color: "red", red: true, value: "del" }]);
  if (v === "play") { await call("select", { id: x.id }); launch(x.id); }
  if (v === "sel") { await call("select", { id: x.id }); refresh(); }
  if (v === "info") push(instancePage(x.id));
  if (v === "opt") optimizeSheet(x.id);
  if (v === "del") deleteInstance(x);
}
async function deleteInstance(x) {
  if (!await confirmBox(`删除「${x.name}」？`, "实例的存档、模组和设置都会被删除，无法恢复。", "删除", true)) return;
  try { await api("deleteInstance", { id: x.id }); toast("已删除"); } catch (e) { toast(e.message, true); }
  while (stack.length) pop();
  refresh();
}

/* ================================================================ 实例 */
function libraryPage() {
  return {
    title: "实例", live: true,
    render(p) {
      if (!S) return;
      const add = el(`<button class="iconbtn glass">${ic("plus")}</button>`); add.onclick = () => newInstanceSheet();
      p.act.replaceChildren(add);
      const list = S.instances || [], b = document.createDocumentFragment();
      if (!list.length) b.append(empty("cube", "还没有实例", "点右上角 + 新建，或导入 .mrpack 整合包"));
      else b.append(grp(list.map(x => row({ inst: x, title: x.name + (x.id === S.config.selected ? " · 当前" : ""), sub: metaLine(x) + " · " + ago(x.lastPlayed), chev: true, onClick: () => push(instancePage(x.id)), onLong: () => instanceMenu(x) }))));
      const imp = el(`<label class="cell tap"><span class="ic" style="--c1:${COL.purple[0]};--c2:${COL.purple[1]}">${ic("down")}</span><span class="m"><b>导入整合包</b><small>Modrinth .mrpack 文件</small></span><input type="file" accept=".mrpack,application/zip,*/*" hidden></label>`);
      $("input", imp).onchange = e => importPack(e.target.files[0], e.target);
      b.append(el(`<div style="height:14px"></div>`), grp([row({ icon: "plus", color: "green", title: "新建实例", chev: true, onClick: () => newInstanceSheet() }), imp]));
      p.body.replaceChildren(b);
    },
  };
}
async function importPack(f, input) {
  if (!f) return; input.value = "";
  const r = await fetch(`/api/upload?name=${encodeURIComponent(f.name)}`, { method: "POST", headers: { "X-Token": TOKEN }, body: f });
  const j = await r.json().catch(() => ({ error: "导入失败" }));
  if (j.error) toast(j.error, true); else toast("正在导入 " + f.name);
}
function newInstanceSheet() {
  let manifest = null, kind = "release", game = "", loader = "", loaderVer = "", name = "", q = "";
  sheet({
    title: "新建实例", left: "取消", right: "安装",
    async onRight(sh) {
      if (!game) { toast("请选择游戏版本", true); return; }
      try { await api("createInstance", { game, loader, loaderVersion: loaderVer, name }); sh.close(); haptic("success"); } catch (e) { toast(e.message, true); }
    },
    render(b) {
      const nm = el(`<input class="field" placeholder="名称（默认用版本号）">`); nm.oninput = () => name = nm.value;
      const lver = el(`<div></div>`), vers = el(`<div></div>`), gsel = el(`<span></span>`);
      const head = el(`<div class="h2"><span>游戏版本 </span></div>`); $("span", head).append(gsel);
      const sq = el(`<div class="search" style="margin-top:10px">${ic("search")}<input class="field" placeholder="搜索版本"></div>`); $("input", sq).oninput = e => { q = e.target.value.trim(); renderVers(); };
      b.append(nm, h2("加载器"), seg([["", "原版"], ["fabric", "Fabric"], ["forge", "Forge"], ["neoforge", "NeoForge"], ["quilt", "Quilt"]], "", v => { loader = v; loaderVer = ""; loadLoader(); }, true), lver, head,
        seg([["release", "正式版"], ["snapshot", "快照"], ["old", "远古"]], "release", v => { kind = v; renderVers(); }), sq, vers);
      vers.append(loading());
      api("versions").then(m => { manifest = m; renderVers(); }).catch(e => vers.replaceChildren(empty("info", "获取版本失败", e.message)));
      function renderVers() {
        if (!manifest) return;
        let list = manifest.versions.filter(v => kind === "release" ? v.type === "release" : kind === "snapshot" ? v.type === "snapshot" : v.type.startsWith("old"));
        if (q) list = list.filter(v => v.id.includes(q));
        const old = v => /^1\.(\d+)/.test(v.id) && +v.id.match(/^1\.(\d+)/)[1] <= 12;
        vers.replaceChildren(grp(list.slice(0, 120).map(v => row({ title: v.id, sub: (v.releaseTime || "").slice(0, 10) + (old(v) ? " · 安卓版暂不支持 1.12.2 及以下" : ""), checked: v.id === game, onClick: () => { game = v.id; gsel.textContent = "· " + v.id; loadLoader(); renderVers(); } }))));
      }
      async function loadLoader() {
        lver.replaceChildren(); if (!loader || !game) return;
        lver.append(loading());
        try {
          const vs = await api("loaderVersions", { loader, game });
          if (!vs?.length) { lver.replaceChildren(el(`<div class="foot">${LOADER[loader]} 暂不支持 ${esc(game)}，换一个版本或加载器。</div>`)); return; }
          loaderVer = (vs.find(v => v.stable) || vs[0]).version;
          const pick = row({ title: LOADER[loader] + " 版本", val: loaderVer, chev: true, onClick: () => sheet({ title: LOADER[loader] + " 版本", right: "完成", render(bb, ss) { bb.append(grp(vs.slice(0, 80).map(v => row({ title: v.version, sub: v.stable ? "稳定版" : "", checked: v.version === loaderVer, onClick: () => { loaderVer = v.version; $(".v", pick).textContent = loaderVer; ss.close(); } })))); } }) });
          lver.replaceChildren(el(`<div style="height:10px"></div>`), grp([pick]));
        } catch (e) { lver.replaceChildren(el(`<div class="foot">获取加载器版本失败：${esc(e.message)}</div>`)); }
      }
    },
  });
}
function instancePage(id) {
  return {
    title: instById(id)?.name || "", live: true,
    async render(p) {
      const x = instById(id);
      if (!x) { p.body.replaceChildren(empty("cube", "实例不存在")); return; }
      p.setTitle(""); const b = document.createDocumentFragment();
      b.append(el(`<div class="cover">${pic(x, "cv")}</div>`));
      const head = el(`<div class="dhead"><h1>${esc(x.name)}</h1><p>${esc(metaLine(x))}${x.loaderVersion ? " " + esc(x.loaderVersion) : ""} · ${esc(ago(x.lastPlayed))}</p><div class="row"><button class="btn playbtn"><i class="fill"></i>${ic("play")}<span>开始游戏</span></button><button class="btn ghost" style="width:56px;padding:0">${ic("wand")}</button></div></div>`);
      $(".playbtn", head).onclick = async () => { await call("select", { id }); launch(id); };
      $(".ghost", head).onclick = () => optimizeSheet(id);
      b.append(head, h2("内容"), grp([
        row({ icon: "puzzle", color: "indigo", title: "模组", val: x.mods ? String(x.mods) : "", chev: true, onClick: () => push(contentPage(id, "mod")) }),
        row({ icon: "paint", color: "orange", title: "资源包", chev: true, onClick: () => push(contentPage(id, "resourcepack")) }),
        row({ icon: "sun", color: "teal", title: "光影", chev: true, onClick: () => push(contentPage(id, "shader")) }),
      ]), h2("管理"), grp([
        row({ icon: "pencil", color: "blue", title: "重命名", chev: true, onClick: async () => { const n = await promptBox("重命名", "", x.name); if (n) { await call("renameInstance", { id, name: n }); await refresh(); } } }),
        row({ icon: "folder", color: "gray", title: "打开实例文件夹", chev: true, onClick: () => call("open", { what: "instance", id }) }),
        row({ icon: "trash", color: "red", title: "删除实例", red: true, onClick: () => deleteInstance(x) }),
      ]));
      p.body.replaceChildren(b); syncPlay();
    },
  };
}
function contentPage(id, kind) {
  return {
    title: KIND[kind],
    async render(p) {
      const upd = el(`<button class="iconbtn glass">${ic("up")}</button>`); upd.onclick = () => checkUpdates(id, kind);
      const add = el(`<button class="iconbtn glass">${ic("plus")}</button>`); add.onclick = () => { while (stack.length) pop(); discover.kind = kind; discover.items = []; discover.offset = 0; switchTab("discover"); };
      p.act.replaceChildren(upd, add);
      if (!p.body.children.length) p.body.append(loading());
      const info = await api("contentInfo", { instance: id, kind }).catch(() => null);
      const items = info?.items || await api("content", { instance: id, kind }).catch(() => []);
      if (!items?.length) { p.body.replaceChildren(empty(kind === "mod" ? "puzzle" : kind === "shader" ? "sun" : "paint", `没有${KIND[kind]}`, "去「发现」里找找吧")); return; }
      p.body.replaceChildren(h2(`${items.length} 项${info?.offline ? " · 离线" : ""}`), grp(items.map(c => row({
        img: c.icon ? proxied(c.icon) : "", title: c.title || c.name || c.file, sub: [c.version, fmtBytes(c.size), c.enabled ? "" : "已停用"].filter(Boolean).join(" · "),
        right: sw(c.enabled, async (on, inp) => { try { await api("toggleContent", { instance: id, kind, file: c.file }); } catch (e) { inp.checked = !on; toast(e.message, true); } }),
        onLong: async () => {
          const v = await menu(c.title || c.file, [...(c.slug ? [{ text: "在 Modrinth 查看", icon: "link", color: "blue", value: "web" }] : []), { text: "删除", icon: "trash", color: "red", red: true, value: "del" }]);
          if (v === "web") openURL("https://modrinth.com/project/" + c.slug);
          if (v === "del" && await confirmBox("删除 " + (c.title || c.file) + "？", "", "删除", true)) { await call("deleteContent", { instance: id, kind, file: c.file }, "已删除"); p.refresh(); }
        },
      }))), foot("长按某项可删除；右上角检查更新。"));
    },
  };
}
async function checkUpdates(id, kind) {
  island.start("检查更新");
  try {
    const ups = await api("checkUpdates", { instance: id, kind });
    island.finish(true, ups.length ? `${ups.length} 项可更新` : "都是最新的");
    if (!ups.length) return;
    sheet({ title: "可用更新", right: "全部更新", async onRight(s) { s.close(); await call("applyUpdates", { instance: id, kind }); },
      render(b) { b.append(grp(ups.map(u => row({ img: u.icon ? proxied(u.icon) : "", title: u.title, sub: `${u.from} → ${u.to}` })))); } });
  } catch (e) { island.finish(false, "检查失败"); toast(e.message, true); }
}
function optimizeSheet(inst) {
  let level = "balanced", mods = true;
  sheet({
    title: "一键优化", left: "取消", right: "优化",
    async onRight(s) {
      try {
        const r = await api("superOptimize", { instance: inst || "", level, mods });
        s.close(); haptic("success"); await refresh();
        sheet({ title: "优化完成", right: "好", render(b) { b.append(grp(r.changes.map(c => row({ icon: "check", color: "green", title: c, wrap: true })))); } });
      } catch (e) { toast(e.message, true); }
    },
    render(b) {
      const d = A?.device || {};
      const planBox = el(`<div></div>`);
      b.append(grp([row({ icon: "wand", color: "pink", title: (d.brand || "") + " " + (d.model || "本机"), sub: [d.soc, d.glRenderer, d.ramMB ? gb(d.ramMB) + " 内存" : "", d.refresh ? Math.round(d.refresh) + " Hz" : ""].filter(Boolean).join(" · "), wrap: true })]),
        el(`<div style="height:12px"></div>`), seg([["balanced", "均衡"], ["extreme", "极致帧率"], ["quality", "画质优先"]], level, v => { level = v; plan(); }), planBox,
        el(`<div style="height:12px"></div>`), grp([toggleRow({ title: "安装手机适用的性能模组", sub: "Lithium、FerriteCore、ModernFix 等（需要加载器）" }, mods, v => mods = v)]),
        foot("会调整渲染倍率、帧率上限、游戏视距与画质选项、内存分配、持续性能模式等。之后都可以在「我的 › 性能」里单独修改。"));
      async function plan() {
        planBox.replaceChildren(loading());
        try {
          const pl = await api("optimizePlan", { instance: inst || "", level });
          planBox.replaceChildren(h2(`预计效果 · ${pl.tierName}`), grp([
            row({ icon: "layers", color: "indigo", title: "渲染倍率", val: pl.renderScale + "%" }), row({ icon: "chart", color: "green", title: "帧率上限", val: pl.fpsLimit + " FPS" }),
            row({ icon: "globe", color: "teal", title: "视距 / 模拟距离", val: `${pl.options.renderDistance} / ${pl.options.simulationDistance}` }), row({ icon: "sun", color: "orange", title: "画质", val: pl.options.graphicsMode === "1" ? "精美" : "流畅" })]));
        } catch (e) { planBox.replaceChildren(foot("无法计算方案：" + e.message)); }
      }
      plan();
    },
  });
}

/* ================================================================ 发现 */
const discover = { kind: "mod", q: "", offset: 0, items: [], total: 0, onlyFit: true };
function discoverPage() {
  return {
    title: "发现",
    async render(p) {
      if (!p.built) {
        p.built = true;
        const sb = el(`<div class="search">${ic("search")}<input class="field" placeholder="搜索 Modrinth" enterkeyhint="search"></div>`);
        let t = 0; $("input", sb).oninput = e => { clearTimeout(t); t = setTimeout(() => { discover.q = e.target.value.trim(); discover.offset = 0; discover.items = []; load(); }, 350); };
        p.seg = seg([["mod", "模组"], ["modpack", "整合包"], ["resourcepack", "资源包"], ["shader", "光影"]], discover.kind, v => { discover.kind = v; discover.offset = 0; discover.items = []; render(); load(); });
        p.fit = el(`<div style="margin-top:12px"></div>`); p.list = el(`<div></div>`);
        p.body.append(sb, p.seg, p.fit, p.list);
        p.scroll.addEventListener("scroll", () => { if (p.scroll.scrollTop + p.scroll.clientHeight > p.scroll.scrollHeight - 400 && !p.more && discover.items.length && discover.items.length < discover.total) { p.more = true; discover.offset += 20; load(true); } }, { passive: true });
      }
      p.seg.set(discover.kind); render();
      if (!discover.items.length) load();
      function render() {
        const cur = curInst();
        p.fit.replaceChildren(cur && discover.kind !== "modpack" ? grp([toggleRow({ title: `只看适合「${cur.name}」的`, sub: `Minecraft ${cur.game}${cur.loader ? " · " + LOADER[cur.loader] : ""}` }, discover.onlyFit, v => { discover.onlyFit = v; discover.offset = 0; discover.items = []; load(); })]) : "");
      }
      async function load(append) {
        if (!append) p.list.replaceChildren(loading());
        const c = curInst(), fit = discover.onlyFit && c && discover.kind !== "modpack";
        try {
          const r = await api("search", { query: discover.q, kind: discover.kind, game: fit ? c.game : "", loader: fit && discover.kind === "mod" ? c.loader : "", offset: discover.offset });
          discover.total = r.total_hits; discover.items = append ? discover.items.concat(r.hits) : r.hits;
          p.list.replaceChildren(discover.items.length ? h2(`${nfmt(discover.total)} 个结果`) : "", discover.items.length ? grp(discover.items.map(x => row({ img: proxied(x.icon_url), title: x.title, sub: `${x.author} · ${nfmt(x.downloads)} 下载 · ${x.description}`, chev: true, onClick: () => projectSheet(x) }))) : empty("search", "没有结果", "换个关键词试试"));
        } catch (e) { p.list.replaceChildren(empty("info", "搜索失败", e.message)); }
        p.more = false;
      }
    },
  };
}
function projectSheet(x) {
  sheet({
    title: x.title, right: "安装",
    async onRight(s) {
      s.close();
      if (x.project_type === "modpack") await call("installPack", { project: x.project_id, title: x.title });
      else { if (!S.config.selected) { toast("请先新建实例", true); return; } await call("installProject", { instance: S.config.selected, project: x.project_id, title: x.title }); }
    },
    render(b) {
      b.append(grp([row({ img: proxied(x.icon_url), title: x.title, sub: `${x.author} · ${nfmt(x.downloads)} 次下载` }), row({ title: "", sub: x.description, wrap: true })]),
        foot((x.display_categories || []).join(" · ") + (x.project_type === "modpack" ? "" : ` · 将安装到「${curInst()?.name || ""}」，前置依赖会自动安装`)),
        el(`<div style="height:12px"></div>`), grp([row({ icon: "link", color: "green", title: "在 Modrinth 查看", chev: true, onClick: () => openURL("https://modrinth.com/project/" + x.slug) })]));
    },
  });
}

/* ================================================================ 我的 */
function mePage() {
  return {
    title: "我的", live: true,
    render(p) {
      if (!S) return;
      const acc = (S.accounts || []).find(a => a.id === S.config.account), b = document.createDocumentFragment();
      b.append(grp([row({ head: acc?.id, icon: acc ? "" : "user", title: acc ? acc.name : "添加账户", sub: acc ? (acc.type === "msa" ? "微软正版账户" : "离线账户") : "离线或微软正版登录", chev: true, onClick: () => accountsSheet() })]));

      // appearance
      const mk = (id, name, a, bb, g) => { const c = el(`<button class="th-card glass ${(S.config.theme || "sakura") === id ? "on" : ""}" data-t="${id}"><div class="sw-prev" style="background:${g};--a:${a};--b:${bb}"><u style="--g:${id === "black" ? "rgba(255,255,255,.14)" : "rgba(255,255,255,.7)"}"></u></div>${name}</button>`); c.onclick = async () => { applyTheme(id); haptic("select"); $$(".th-card", b2).forEach(n => n.classList.toggle("on", n === c)); await setConfig({ theme: id }); }; return c; };
      const b2 = el(`<div class="themes"></div>`);
      b2.append(mk("sakura", "樱花", "#ff9fbd", "#ffd3a8", "linear-gradient(160deg,#fdeaf0,#f7e6dd)"), mk("white", "纯白", "#cfe0ff", "#e4d6ff", "linear-gradient(160deg,#f6f8fc,#eef1f8)"), mk("black", "纯黑", "#7a2a52", "#23336f", "#050407"));
      b.append(h2("外观"), b2, el(`<div style="height:12px"></div>`), grp([
        row({ icon: "sun", color: "gray", title: "跟随系统深浅色", right: sw(S.config.theme === "auto", async v => { const t = v ? "auto" : (document.documentElement.dataset.theme || "sakura"); applyTheme(t); await setConfig({ theme: t }); refresh(); }) }),
        slider({ name: "玻璃模糊", min: 8, max: 48, step: 2, value: stored("blur", 26), fmt: v => v + "px", onChange: v => { store("blur", v); applyLook(); } }),
        toggleRow({ icon: "drop", color: "teal", title: "背景流动动画", sub: "关闭可省电" }, !stored("quiet", false), v => { store("quiet", !v); applyLook(); }),
        toggleRow({ icon: "layers", color: "gray", title: "低性能模式", sub: "关闭毛玻璃模糊，旧手机更流畅" }, stored("flat", false), v => { store("flat", v); applyLook(); }),
      ]));
      b.append(h2("性能与操控"), grp([
        row({ icon: "wand", color: "pink", title: "一键优化", chev: true, onClick: () => optimizeSheet(S.config.selected) }),
        row({ icon: "bolt", color: "orange", title: "性能与渲染器", val: `${S.config.renderScale || 100}% · ${S.config.fpsLimit || "不限"}`, chev: true, onClick: () => push(perfPage()) }),
        row({ icon: "pad", color: "indigo", title: "操控与键位", chev: true, onClick: () => push(controlsPage()) }),
      ]), h2("游戏"), grp([
        row({ icon: "java", color: "red", title: "Java 与内存", chev: true, onClick: () => push(javaPage()) }),
        row({ icon: "down", color: "blue", title: "下载", val: { auto: "自动", official: "官方", bmclapi: "BMCLAPI" }[S.config.source] || "自动", chev: true, onClick: () => push(downloadPage()) }),
        row({ icon: "folder", color: "teal", title: "游戏文件", sub: "在系统「文件」应用中管理", chev: true, onClick: () => call("open", { what: "gameDir" }) }),
        toggleRow({ icon: "globe", color: "blue", title: "新实例默认简体中文" }, S.config.zhCN, v => setConfig({ zhCN: v })),
        toggleRow({ icon: "cube", color: "gray", title: "版本隔离", sub: "每个实例独立的存档与模组" }, S.config.isolation, v => setConfig({ isolation: v })),
      ]), el(`<div style="height:14px"></div>`), grp([
        row({ icon: "doc", color: "gray", title: "游戏日志", chev: true, onClick: () => push(logsPage()) }),
        row({ icon: "info", color: "blue", title: "关于樱梨", val: "v" + S.version, chev: true, onClick: () => push(aboutPage()) }),
      ]));
      p.body.replaceChildren(b); paintHeads(p.body);
    },
  };
}
function accountsSheet() {
  sheet({ title: "账户", right: "完成", render(b) {
    const draw = () => {
      b.replaceChildren(); const list = S.accounts || [];
      if (list.length) b.append(h2("我的账户"), grp(list.map(a => row({ head: a.id, title: a.name, sub: a.type === "msa" ? "微软正版" : "离线", checked: a.id === S.config.account,
        onClick: async () => { await call("selectAccount", { id: a.id }); await refresh(); draw(); },
        onLong: async () => { if (await confirmBox(`移除 ${a.name}？`, "", "移除", true)) { await call("removeAccount", { id: a.id }); await refresh(); draw(); } } }))), foot("长按账户可移除。"));
      b.append(h2("添加"), grp([
        row({ icon: "user", color: "gray", title: "离线账户", sub: "输入任意名字，单人游戏和离线服务器可用", chev: true, onClick: async () => { const n = await promptBox("离线账户", "3-16 位字母、数字或下划线", "", "玩家名"); if (n) { await call("addOffline", { name: n }, "已添加 " + n); await refresh(); draw(); } } }),
        row({ icon: "shield", color: "green", title: "微软正版登录", sub: S.msBuiltin ? "已内置登录" : "需要先填写 Azure 应用 ID", chev: true, onClick: () => msLogin() })]));
      if (!S.msBuiltin) { const inp = el(`<input class="field" placeholder="Azure 客户端 ID（可选）" value="${esc(S.config.msClientId || "")}">`); inp.onchange = () => setConfig({ msClientId: inp.value.trim() }); b.append(el(`<div style="height:12px"></div>`), inp, foot("正版登录需要一个已获批 Minecraft API 权限的 Azure 应用 ID。")); }
      paintHeads(b);
    };
    draw();
  } });
}
async function msLogin() {
  try {
    const r = await api("msLogin");
    msSheet = sheet({ title: "微软登录", left: "取消", onLeft: s => { call("msCancel"); s.close(); }, right: "", onClose: () => { msSheet = null; }, render(b) {
      b.append(el(`<div class="code">${esc(r.code)}</div>`), foot("在打开的网页中输入这个代码并登录微软账户，完成后这里会自动继续。"), el(`<div style="height:12px"></div>`), grp([
        row({ icon: "copy", color: "blue", title: "复制代码", onClick: () => { try { N.copy(r.code); } catch { navigator.clipboard?.writeText(r.code); } toast("已复制"); } }),
        row({ icon: "link", color: "green", title: "打开登录网页", onClick: () => openURL(r.url) })]));
    } });
  } catch (e) { toast(e.message, true); }
}
function perfPage() {
  return {
    title: "性能与渲染器",
    async render(p) {
      const c = S.config; if (S.android && !A) await loadAndroid();
      const fpsVals = [0, 30, 45, 60, 90, 120, 144], hz = Math.round(A?.device?.refresh || 60), b = document.createDocumentFragment();
      const opt = el(`<button class="btn block">${ic("wand")}一键优化</button>`); opt.onclick = () => optimizeSheet(S.config.selected);
      b.append(opt, h2("渲染"), grp([
        slider({ name: "渲染倍率", min: 25, max: 100, step: 5, value: c.renderScale || 100, fmt: v => v + "%" + (A?.device?.screenH ? ` · ${Math.round(A.device.screenH * v / 100)}p` : ""), onChange: v => setConfig({ renderScale: v }) }),
        slider({ name: "帧率上限", min: 0, max: fpsVals.length - 1, value: Math.max(0, fpsVals.indexOf(c.fpsLimit || 0)), fmt: i => fpsVals[i] ? fpsVals[i] + " FPS" : "不限", onChange: i => setConfig({ fpsLimit: fpsVals[i] }) }),
        toggleRow({ title: "垂直同步", sub: `与屏幕刷新率（${hz} Hz）同步` }, c.vsync, v => setConfig({ vsync: v })),
      ]), foot("渲染倍率越低越流畅：画面以较低分辨率绘制，几乎没有额外开销。"));
      const rs = A?.renderers || [], cur = c.renderer || "auto";
      b.append(h2("渲染器"), grp([row({ icon: "layers", color: "indigo", title: "渲染器", val: cur === "auto" ? "自动" : rs.find(r => r.id === cur)?.name || cur, chev: true, onClick: () => rendererSheet(p) })]), foot("自动：按游戏版本和显卡选择最合适的渲染器。"));
      b.append(h2("游戏内显示"), seg([["off", "关闭"], ["fps", "简洁"], ["full", "完整"]], c.perfHud || "fps", v => setConfig({ perfHud: v })), el(`<div style="height:12px"></div>`),
        grp([toggleRow({ icon: "pad", color: "pink", title: "按键显示", sub: "WASD、鼠标键与每秒点击数；无背景，游戏中可拖动、点按折叠" }, c.keyOverlay !== false, v => setConfig({ keyOverlay: v }))]));
      if (A?.device) { const d = A.device; b.append(h2("本机"), grp([
        row({ icon: "phone", color: "gray", title: (d.brand || "") + " " + (d.model || ""), sub: `Android ${d.release} · ${d.soc || ""}` }),
        row({ icon: "chip", color: "blue", title: "处理器", val: `${d.cores} 核 · ${(d.maxFreqMHz / 1000).toFixed(2)} GHz` }),
        row({ icon: "layers", color: "purple", title: "图形", sub: d.glRenderer + (d.vulkan ? " · Vulkan" : ""), wrap: true }),
        row({ icon: "mem", color: "green", title: "内存", val: gb(d.ramMB) }), row({ icon: "chart", color: "orange", title: "屏幕", val: `${d.screenW}×${d.screenH} · ${Math.round(d.refresh)} Hz` })])); }
      p.body.replaceChildren(b);
    },
  };
}
function rendererSheet(page) {
  sheet({ title: "渲染器", right: "完成", render(b, s) {
    const cur = S.config.renderer || "auto", pick = async id => { await setConfig({ renderer: id }); haptic("select"); s.close(); page.refresh(); };
    b.append(grp([row({ title: "自动", sub: "按游戏版本选择", checked: cur === "auto", onClick: () => pick("auto") }), ...(A?.renderers || []).map(r => row({ title: r.name, sub: r.desc + (r.plugin ? " · 插件" : ""), checked: cur === r.id, onClick: () => pick(r.id) }))]));
  } });
}
function controlsPage() {
  return {
    title: "操控与键位",
    async render(p) {
      if (S.android && !A) await loadAndroid();
      const c = S.config, layouts = A?.layouts || ["default"], b = document.createDocumentFragment();
      b.append(h2("键位布局"), grp(layouts.map(n => row({ icon: "pad", color: "indigo", title: n === "default" ? "默认布局" : n, checked: (c.controlsLayout || "default") === n, onClick: async () => {
        const v = await menu(n === "default" ? "默认布局" : n, [{ text: "使用这个布局", icon: "check", color: "green", value: "use" }, { text: "编辑", icon: "pencil", color: "blue", value: "edit" }, ...(n !== "default" ? [{ text: "删除", icon: "trash", color: "red", red: true, value: "del" }] : [])]);
        if (v === "use") { await setConfig({ controlsLayout: n }); p.refresh(); }
        if (v === "edit") { try { N.editControls(n); } catch { toast("请在安卓设备上编辑", true); } }
        if (v === "del") { await call("deleteLayout", { name: n }); await loadAndroid(true); await refresh(); p.refresh(); } } }))),
        foot("编辑器里可拖动按键、调整大小、绑定组合键，并能另存为新布局。"));
      b.append(h2("触控"), grp([
        slider({ name: "视角灵敏度", min: 25, max: 300, step: 5, value: c.mouseSpeed || 100, fmt: v => v + "%", onChange: v => setConfig({ mouseSpeed: v }) }),
        slider({ name: "按键不透明度", min: 10, max: 100, step: 5, value: c.controlsOpacity || 70, fmt: v => v + "%", onChange: v => setConfig({ controlsOpacity: v }) }),
        toggleRow({ title: "按键震动反馈" }, c.haptics, v => setConfig({ haptics: v }))]),
        h2("体感"), grp([toggleRow({ title: "陀螺仪瞄准", sub: "转动手机来微调视角" }, c.gyroAim, v => setConfig({ gyroAim: v })), slider({ name: "陀螺仪灵敏度", min: 25, max: 300, step: 5, value: c.gyroSens || 100, fmt: v => v + "%", onChange: v => setConfig({ gyroSens: v }) })]));
      p.body.replaceChildren(b);
    },
  };
}
function javaPage() {
  return {
    title: "Java 与内存",
    async render(p) {
      const c = S.config, b = document.createDocumentFragment();
      if (S.android) {
        await loadAndroid(true);
        const rts = A?.runtimes || [], rows = rts.map(j => row({ icon: "java", color: "orange", title: "Java " + j.major, sub: `${j.version}${j.vendor ? " · " + j.vendor : ""}` }));
        for (const n of (A?.bundled || []).filter(n => !rts.some(j => j.major === n))) rows.push(row({ icon: "down", color: "blue", title: "安装 Java " + n, sub: "已内置在安装包中", chev: true, onClick: () => call("installJre", { major: n }) }));
        b.append(h2("Java 运行时"), grp(rows.length ? rows : [row({ title: "运行时会在首次启动时自动准备" })]), foot("1.20.5 及以上需要 Java 21，1.17–1.20.4 用 Java 17，更早的版本用 Java 8。启动时会自动选择。"));
      }
      const auto = c.memoryMB === 0, mem = [toggleRow({ title: "自动分配内存", sub: `原版 ${gb(S.autoMem)} · 模组 ${gb(S.autoMemModded)}` }, auto, async v => { await setConfig({ memoryMB: v ? 0 : S.autoMemModded }); p.refresh(); })];
      if (!auto) mem.push(slider({ name: "游戏内存", min: 1024, max: Math.max(2048, (S.memTotal || 4096) - 1536), step: 256, value: c.memoryMB, fmt: gb, onChange: v => setConfig({ memoryMB: v }) }));
      b.append(h2("内存"), grp(mem), foot(`本机共 ${gb(S.memTotal)}。给游戏太多内存会让系统杀掉后台，甚至让游戏闪退。`));
      const args = el(`<textarea class="field" placeholder="额外 JVM 参数，例如 -XX:+UseZGC" spellcheck="false">${esc(c.jvmArgs || "")}</textarea>`); args.onchange = () => setConfig({ jvmArgs: args.value.trim() });
      b.append(h2("高级"), args);
      p.body.replaceChildren(b);
    },
  };
}
function downloadPage() {
  return {
    title: "下载",
    render(p) {
      const c = S.config, srcs = [["auto", "自动选择", "测速后自动用最快的源" + (S.autoSource ? `（当前：${S.autoSource}）` : "")], ["bmclapi", "BMCLAPI 镜像", "国内推荐"], ["official", "官方源", "海外推荐"]];
      p.body.replaceChildren(h2("下载源"), grp(srcs.map(([v, t, sub]) => row({ title: t, sub, checked: (c.source || "auto") === v, onClick: async () => { await setConfig({ source: v }); p.refresh(); } }))),
        h2("并发"), grp([slider({ name: "下载线程", min: 4, max: 128, step: 4, value: c.threads || 32, fmt: v => v, onChange: v => setConfig({ threads: v }) })]), foot("手机网络建议 16–48。"));
    },
  };
}
function logsPage() {
  return {
    title: "游戏日志",
    async render(p) {
      const share = el(`<button class="iconbtn glass">${ic("share")}</button>`);
      share.onclick = async () => { try { const path = await api("exportLog"); N ? N.exportFile(path, path.split("/").pop()) : toast("已导出到 " + path); } catch (e) { toast(e.message, true); } };
      p.act.replaceChildren(share);
      const lines = (await api("logs").catch(() => [])) || [], box = el(`<div class="logbox"></div>`);
      const add = ls => {
        const f = document.createDocumentFragment();
        for (const l of ls) { const d = document.createElement("div"); d.textContent = l; if (/\bWARN\b|\[warn/i.test(l)) d.className = "w"; else if (/ERROR|Exception|FATAL/.test(l)) d.className = "e"; else if (l.startsWith("[EririLily]")) d.className = "s"; f.append(d); }
        const end = p.scroll.scrollTop + p.scroll.clientHeight > p.scroll.scrollHeight - 80;
        box.append(f); while (box.childElementCount > 3000) box.firstElementChild.remove();
        if (end) p.scroll.scrollTop = p.scroll.scrollHeight;
      };
      p.body.replaceChildren(lines.length ? box : empty("doc", "还没有日志", "启动一次游戏后这里会显示输出")); add(lines); p.scroll.scrollTop = p.scroll.scrollHeight;
      logSink = ls => { if (!box.isConnected) { logSink = null; return; } add(ls); };
    },
  };
}
function aboutPage() {
  return {
    title: "关于",
    render(p) {
      p.body.replaceChildren(el(`<div class="about"><img src="logo.png" alt=""><b>樱梨启动器</b><small>v${esc(S.version)}${N ? " · Android " + esc(N.appVersion?.() || "") : ""} · D0cK × Claude</small></div>`),
        h2("游戏运行层"), grp([row({ title: "Zalith Launcher 2 运行层", sub: "GPLv3 · 提供 Java 运行与渲染器（Krypton Wrapper、GL4ES、Kopper Zink、VirGL 等），其余部分为樱梨自研", wrap: true })]),
        h2("开源组件"), grp([row({ title: "OpenJDK", sub: "GPLv2 + Classpath Exception" }), row({ title: "LWJGL 3", sub: "BSD-3-Clause" }), row({ title: "OpenAL Soft", sub: "LGPL-2.0" }), row({ title: "GL4ES", sub: "MIT" })]),
        foot("非 Mojang 或 Microsoft 官方产品。Minecraft 是 Mojang Studios 的商标。"));
    },
  };
}

/* ---------------------------------------------------------------- boot */
(async function boot() {
  applyLook(); applyTheme("sakura");
  if (!TOKEN) { document.body.textContent = "缺少访问令牌"; return; }
  await refresh();
  const defs = { home: homePage(), library: libraryPage(), discover: discoverPage(), me: mePage() };
  for (const t of tabNames) { roots[t] = makePage(defs[t], true); roots[t].el.classList.toggle("hide", t !== "home"); stage.append(roots[t].el); }
  await Promise.all(tabNames.map(t => roots[t].refresh()));
  connect();
  setTimeout(() => $("#boot").classList.add("gone"), 150);
  setInterval(() => { if (document.visibilityState === "visible" && !refreshing) refresh(); }, 15000);
})();
