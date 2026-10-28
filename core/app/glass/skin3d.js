"use strict";
/* A dependency-free 3D Minecraft skin viewer built from CSS 3D transforms. */

class SkinViewer {
  constructor(stage) {
    this.stage = stage;
    this.s = 9;               // screen pixels per skin pixel
    this.yaw = -28; this.pitch = -8;
    this.anim = "idle";
    this.root = document.createElement("div");
    this.root.className = "mc-root";
    stage.append(this.root);
    this.drag();
    this.spin = true;
    this.active = false;
    // only animate while the 角色 page is on screen: the rest of the time the
    // model costs nothing (no rAF loop, CSS animations paused)
    const tick = () => {
      if (!this.active) { this.raf = 0; return; }
      if (this.spin && !this.dragging) this.yaw += .12;
      this.apply();
      this.raf = requestAnimationFrame(tick);
    };
    this.tick = tick;
  }
  isBuilt(info) { return this.key === info.url + "|" + info.slim; }
  setActive(on) {
    this.active = on;
    this.root.classList.toggle("paused", !on);
    if (on && !this.raf) this.raf = requestAnimationFrame(this.tick);
  }
  apply() { this.root.style.transform = `translate(-50%,-50%) rotateX(${this.pitch}deg) rotateY(${this.yaw}deg)`; }
  drag() {
    let x0, y0, a0, p0;
    this.stage.addEventListener("pointerdown", e => {
      this.dragging = true; x0 = e.clientX; y0 = e.clientY; a0 = this.yaw; p0 = this.pitch;
      this.stage.setPointerCapture(e.pointerId);
    });
    this.stage.addEventListener("pointermove", e => {
      if (!this.dragging) return;
      this.yaw = a0 + (e.clientX - x0) * .6;
      this.pitch = Math.max(-40, Math.min(30, p0 - (e.clientY - y0) * .3));
    });
    const up = () => { if (this.dragging) { this.dragging = false; this.spin = false; clearTimeout(this.t); this.t = setTimeout(() => this.spin = true, 4000); } };
    this.stage.addEventListener("pointerup", up);
    this.stage.addEventListener("pointercancel", up);
  }
  setAnim(a) { this.anim = a; this.root.dataset.anim = a; }

  // url: object URL of the skin png; legacy: 64x32 skin; slim: Alex arms
  load(url, legacy, slim, im) {
    const key = url + "|" + slim;
    if (this.key === key) return; // same skin: keep the built model
    this.key = key;
    this.img = im;
    const s = this.s, root = this.root;
    const frag = document.createDocumentFragment();
    root.dataset.anim = this.anim;
    root.style.width = 16 * s + "px"; root.style.height = 32 * s + "px";
    // Each face gets its own tiny texture cut from the skin with the shading
    // baked in (no CSS filters: those made every face an expensive layer), drawn
    // slightly oversized (O px per side) so neighbouring faces overlap: no seams.
    // Fully transparent faces (empty overlay layers) are skipped altogether.
    const img = this.img, k = img.width / 64, O = .6;
    const tex = {};
    const crop = (u, v, fw, fh, shade) => {
      const key = [u, v, fw, fh, shade].join(",");
      if (key in tex) return tex[key];
      const c = document.createElement("canvas");
      c.width = fw * k; c.height = fh * k;
      const g = c.getContext("2d", { willReadFrequently: true });
      g.drawImage(img, u * k, v * k, fw * k, fh * k, 0, 0, fw * k, fh * k);
      const px = g.getImageData(0, 0, c.width, c.height).data;
      let any = false;
      for (let i = 3; i < px.length; i += 4) if (px[i]) { any = true; break; }
      if (!any) return (tex[key] = null);
      if (shade !== 1) {
        g.globalCompositeOperation = "source-atop";
        g.fillStyle = shade < 1 ? `rgba(0,0,0,${1 - shade})` : `rgba(255,255,255,${shade - 1})`;
        g.fillRect(0, 0, c.width, c.height);
      }
      return (tex[key] = c.toDataURL());
    };
    const face = (cub, u, v, fw, fh, w, h, tf, shade) => {
      const t = crop(u, v, fw, fh, shade);
      if (!t) return;
      const f = document.createElement("i");
      f.style.cssText = `width:${fw * s + 2 * O}px;height:${fh * s + 2 * O}px;left:${(w - fw) * s / 2 - O}px;top:${(h - fh) * s / 2 - O}px;` +
        `background-image:url(${t});background-size:100% 100%;transform:${tf}`;
      cub.append(f);
    };
    // a textured box: UV origin (u,v), size w*h*d in skin pixels
    const cuboid = (u, v, w, h, d, extra = "") => {
      const c = document.createElement("div");
      c.className = "mc-cub";
      c.style.cssText = `width:${w * s}px;height:${h * s}px;${extra}`;
      const z = n => `translateZ(${n * s / 2}px)`;
      face(c, u + d, v + d, w, h, w, h, z(d), 1);                                   // front
      face(c, u + 2 * d + w, v + d, w, h, w, h, `rotateY(180deg) ${z(d)}`, .72);       // back
      face(c, u, v + d, d, h, w, h, `rotateY(-90deg) ${z(w)}`, .86);                  // right (player's)
      face(c, u + d + w, v + d, d, h, w, h, `rotateY(90deg) ${z(w)}`, .86);           // left
      face(c, u + d, v, w, d, w, h, `rotateX(90deg) ${z(h)}`, 1.08);                  // top
      face(c, u + d + w, v, w, d, w, h, `rotateX(-90deg) scaleY(-1) ${z(h)}`, .6);    // bottom
      return c;
    };
    const part = (name, x, y, w, h, inner, outer, mirror) => {
      const p = document.createElement("div");
      p.className = "mc-part " + name;
      p.style.cssText = `left:${x * s}px;top:${y * s}px;width:${w * s}px;height:${h * s}px`;
      const wrap = document.createElement("div");
      wrap.className = "mc-wrap";
      if (mirror) wrap.style.transform = "scaleX(-1)";
      wrap.append(inner);
      if (outer) wrap.append(outer);
      p.append(wrap);
      frag.append(p);
    };
    const inflate = (w, h, d, k) => `transform:scale3d(${(w + k) / w},${(h + k) / h},${(d + k) / d})`;
    const aw = slim ? 3 : 4;
    part("head", 4, 0, 8, 8, cuboid(0, 0, 8, 8, 8), cuboid(32, 0, 8, 8, 8, inflate(8, 8, 8, 1)));
    part("body", 4, 8, 8, 12, cuboid(16, 16, 8, 12, 4), legacy ? null : cuboid(16, 32, 8, 12, 4, inflate(8, 12, 4, .5)));
    part("arm-r", 4 - aw, 8, aw, 12, cuboid(40, 16, aw, 12, 4), legacy ? null : cuboid(40, 32, aw, 12, 4, inflate(aw, 12, 4, .5)));
    if (legacy) part("arm-l", 12, 8, aw, 12, cuboid(40, 16, aw, 12, 4), null, true);
    else part("arm-l", 12, 8, aw, 12, cuboid(32, 48, aw, 12, 4), cuboid(48, 48, aw, 12, 4, inflate(aw, 12, 4, .5)));
    part("leg-r", 4, 20, 4, 12, cuboid(0, 16, 4, 12, 4), legacy ? null : cuboid(0, 32, 4, 12, 4, inflate(4, 12, 4, .5)));
    if (legacy) part("leg-l", 8, 20, 4, 12, cuboid(0, 16, 4, 12, 4), null, true);
    else part("leg-l", 8, 20, 4, 12, cuboid(16, 48, 4, 12, 4), cuboid(0, 48, 4, 12, 4, inflate(4, 12, 4, .5)));
    root.replaceChildren(frag);
    this.apply();
  }
}

/* ---------------------------------------------------------------- skin data */
const skinCache = {}; // account id -> {url, slim, legacy, source}
async function fetchSkin(id, force) {
  if (skinCache[id] && !force) return skinCache[id];
  const r = await fetch(`/api/skin?t=${TOKEN}&id=${encodeURIComponent(id || "")}&_=${Date.now()}`);
  const blob = await r.blob();
  const url = URL.createObjectURL(blob);
  const img = await new Promise((ok, no) => { const i = new Image(); i.onload = () => ok(i); i.onerror = no; i.src = url; });
  const info = { url, img, slim: r.headers.get("X-Skin-Model") === "slim", legacy: img.height * 2 === img.width, source: r.headers.get("X-Skin-Source") };
  if (skinCache[id]) URL.revokeObjectURL(skinCache[id].url);
  skinCache[id] = info;
  return info;
}

