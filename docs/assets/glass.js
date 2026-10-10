// glass.js: SANKET's own WebGL2 engine for one piece of liquid glass on warm paper. No libraries.
//
// story.js says what the glass is doing; this draws it, in three passes (see glass-glsl.js): the shadow, a coarse
// pass that finds where the glass is, and the full-resolution pass, so the glass is as sharp as the screen.
// It times the graphics chip before the first frame and picks the richest settings it can hold at 60 frames a
// second; if frames still run slow it steps down once more, never mid-way back up.

import { VERT, COMMON, SHAPES, RAYS, LIGHTING, SHADOW_MAIN, NEAR_MAIN, PAPERLIB, PAPER_HEAD, PAPER_MAIN, MASK, GLASS_MAIN, BAKE_MAIN }
  from './glass-glsl.js';
import { createStory, SCENE_COUNT, REST, COLS } from './story.js';

export { SCENE_COUNT, REST };

const FOV = 30 * Math.PI / 180;
const TAN_HALF = Math.tan(FOV / 2);
const ELEVATION = 11 * Math.PI / 180;
const LIGHT = [-0.3939, 0.8371, 0.3802];

/**
 * Settings from richest to plainest. Sharpness goes last: the first three draw at the screen's full resolution
 * (up to 2 device pixels per CSS pixel) and only drop the coloured edges and some steps; the last is the fallback.
 * dpr: most device pixels per CSS pixel; budget: most pixels; near: the coarse pass's divisor.
 */
const LEVELS = [
  { dpr: 2, budget: 6.2e6, near: 3, steps: 120, refine: 32, inner: 20, disp: 1, shadow: 384, shadowSteps: 32 },
  { dpr: 2, budget: 6.2e6, near: 3, steps: 104, refine: 28, inner: 16, disp: 0, shadow: 384, shadowSteps: 28 },
  { dpr: 2, budget: 6.2e6, near: 3, steps: 88, refine: 24, inner: 12, disp: 0, shadow: 256, shadowSteps: 20 },
  { dpr: 1.5, budget: 3.4e6, near: 3, steps: 80, refine: 22, inner: 12, disp: 0, shadow: 256, shadowSteps: 18 },
];

/** The unmoving parts measured into volumes, in each part's own space: shape id -> box and grid step. */
const VOLUMES = {
  1: { lo: [-0.74, -0.05, -0.58], hi: [0.74, 2.16, 0.58], step: 0.0135 },   // the lift car
  2: { lo: [-1.22, 0.15, -0.45], hi: [0.82, 1.72, 0.45], step: 0.0135 },   // the scooter's body
  5: { lo: [-0.5, 0.07, -0.27], hi: [0.5, 2.0, 0.27], step: 0.012 },       // the map pin
  6: { lo: [-0.68, 0.04, -0.34], hi: [0.68, 1.2, 0.34], step: 0.012 },     // the padlock's body
};

const SCENE_UNIFORMS = ['uTime', 'uCam', 'uFwd', 'uRight', 'uUp', 'uTanHalf', 'uAspect', 'uShift', 'uShape', 'uTo',
  'uPar', 'uForm', 'uSweep', 'uDrop', 'uDrop2', 'uZero', 'uStreamN', 'uStream', 'uStreamLo', 'uStreamHi', 'uStreamCLo', 'uStreamCHi', 'uWobble', 'uTap', 'uCols', 'uColsTint', 'uBarH', 'uLo', 'uHi',
  'uShadowPass', 'uVolOn', 'uVolLo', 'uVolHi'];
const PAPER_UNIFORMS = ['uShadow', 'uShadowRect', 'uShadeTint', 'uShadeAmt', 'uSmokeN', 'uSmoke', 'uSmokeA', 'uRes'];
const PASS_UNIFORMS = {
  shadow: ['uShadowRect', 'uShadowRes', 'uShadowSteps'],
  near: ['uNearRes', 'uPixNear', 'uSteps'],
  paper: ['uCam', 'uFwd', 'uRight', 'uUp', 'uTanHalf', 'uAspect', 'uShift', ...PAPER_UNIFORMS],
  mask: ['uNear', 'uNearRes', 'uRes'],
  bake: ['uBakeShape', 'uBakeLo', 'uBakeHi', 'uBakeRes', 'uBakeZ'],
  glass: [...PAPER_UNIFORMS, 'uTintS', 'uInner', 'uDisp', 'uNear', 'uNearRes', 'uPix', 'uRefine', 'uDebug'],
};

const IDENTITY = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
const ZERO4 = [0, 0, 0, 0];
const slot = {
  shape: new Int32Array(2), to: new Float32Array(32), par: new Float32Array(8), form: new Float32Array(2),
  sweep: new Float32Array(8), drop: new Float32Array(8), drop2: new Float32Array(6),
  volOn: new Int32Array(2), volLo: new Float32Array(6), volHi: new Float32Array(6),
};
const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };

/**
 * Creates the engine on a canvas. Returns null without WebGL2. Options:
 *   still: draw only when asked (renderAt), for posters
 *   host: the element that takes pointer input (default: the canvas)
 *   level: force a settings level (0..3), for tests; scale: force device pixels per CSS pixel, for tests
 *   debug: 1 shows how hard each pixel worked, 2 which pixels ran the glass pass; timing: GPU time per pass
 *   afterFrame(info): called after each frame with { P, speed, state }
 */
export function createGlass(canvas, options = {}) {
  const gl = canvas.getContext('webgl2', {
    alpha: true, antialias: false, depth: false, stencil: true, premultipliedAlpha: true,
    powerPreference: 'high-performance', preserveDrawingBuffer: !!options.still,
  });
  if (!gl) return null;

  const story = createStory();
  const parallel = gl.getExtension('KHR_parallel_shader_compile');

  function program(name, fragment) {
    const vs = gl.createShader(gl.VERTEX_SHADER);
    gl.shaderSource(vs, VERT);
    gl.compileShader(vs);
    const fs = gl.createShader(gl.FRAGMENT_SHADER);
    gl.shaderSource(fs, fragment);
    gl.compileShader(fs);
    const p = gl.createProgram();
    gl.attachShader(p, vs);
    gl.attachShader(p, fs);
    gl.linkProgram(p);
    return { name, p, fs, u: null, scene: name === 'shadow' || name === 'near' || name === 'glass' || name === 'bake' };
  }
  const passes = {
    shadow: program('shadow', COMMON + SHAPES + RAYS + SHADOW_MAIN),
    near: program('near', COMMON + SHAPES + RAYS + NEAR_MAIN),
    paper: program('paper', PAPER_HEAD + PAPERLIB + PAPER_MAIN),
    mask: program('mask', MASK),
    glass: program('glass', COMMON + SHAPES + RAYS + PAPERLIB + LIGHTING + GLASS_MAIN),
    bake: program('bake', COMMON + SHAPES + BAKE_MAIN),
  };

  function target(w, h, filter, count = 1) {
    const fb = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    const texs = [];
    for (let i = 0; i < count; i++) {
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, tex, 0);
      texs.push(tex);
    }
    if (count > 1) gl.drawBuffers(texs.map((_, i) => gl.COLOR_ATTACHMENT0 + i));
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    return { tex: texs[0], tex2: texs[1], texs, fb, w, h };
  }
  function drop(t) {
    if (!t) return;
    for (const tex of t.texs) gl.deleteTexture(tex);
    gl.deleteFramebuffer(t.fb);
  }

  const vao = gl.createVertexArray();
  let shadowT = null, nearT = null;
  let level = clamp(options.level ?? 0, 0, LEVELS.length - 1);
  const forcedLevel = options.level !== undefined;
  const forcedScale = options.scale;

  let width = 1, height = 1, cssW = 1, cssH = 1, scale = 1, layout = null, cam = null;
  let ready = false, failed = false, onReady = null, running = false, raf = 0, last = 0;
  const readyPromise = new Promise((resolve) => { onReady = resolve; });

  // the story position: the page sets where it should be; the glass follows on a spring
  let goal = 0, P = 0, vel = 0, first = true;
  let orbitX = 0, orbitY = 0, pointerX = 0, pointerY = 0;
  let tap = [0, 0, 0, -1e9], state = null;
  let slow = 0, frames = 0;

  function link(pass) {
    const { p, fs } = pass;
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) {
      console.error('SANKET glass: shader failed', gl.getShaderInfoLog(fs) || gl.getProgramInfoLog(p));
      return false;
    }
    pass.u = {};
    if (pass.scene) for (const name of SCENE_UNIFORMS) pass.u[name] = gl.getUniformLocation(p, name);
    for (const name of PASS_UNIFORMS[pass.name]) pass.u[name] = gl.getUniformLocation(p, name);
    gl.useProgram(p);
    const v0 = gl.getUniformLocation(p, 'uVol0'), v1 = gl.getUniformLocation(p, 'uVol1');
    if (v0) gl.uniform1i(v0, 3);
    if (v1) gl.uniform1i(v1, 4);
    if (pass.u.uCols) gl.uniform1fv(pass.u.uCols, new Float32Array(COLS));
    if (pass.u.uColsTint) gl.uniform1fv(pass.u.uColsTint, new Float32Array(COLS));
    return true;
  }

  function finish() {
    if (!Object.values(passes).every(link)) {
      failed = true;
      onReady(false);
      return;
    }
    bake();
    resize();
    if (!forcedLevel && !options.still) calibrate();
    ready = true;
    onReady(true);
    if (running) last = 0;
  }
  if (parallel) {
    const all = Object.values(passes);
    const poll = () => (all.every((x) => gl.getProgramParameter(x.p, parallel.COMPLETION_STATUS_KHR)) ? finish() : setTimeout(poll, 30));
    poll();
  } else {
    setTimeout(finish, 0);
  }

  // Measures the four unmoving parts into volumes once, so the glass doesn't work them out thousands of times a frame.
  // Needs float render targets; without them the shapes are simply worked out in full.
  const volumes = {};
  function bake() {
    if (options.noBake || !gl.getExtension('EXT_color_buffer_float')) return;
    const pass = passes.bake;
    gl.useProgram(pass.p);
    gl.bindVertexArray(vao);
    const fb = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    for (const key of Object.keys(VOLUMES)) {
      const v = VOLUMES[key];
      const n = [0, 1, 2].map((i) => Math.ceil((v.hi[i] - v.lo[i]) / v.step));
      const hi = [0, 1, 2].map((i) => v.lo[i] + n[i] * v.step);
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_3D, tex);
      gl.texStorage3D(gl.TEXTURE_3D, 1, gl.R16F, n[0], n[1], n[2]);
      gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_3D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      for (const w of [gl.TEXTURE_WRAP_S, gl.TEXTURE_WRAP_T, gl.TEXTURE_WRAP_R]) gl.texParameteri(gl.TEXTURE_3D, w, gl.CLAMP_TO_EDGE);
      gl.bindTexture(gl.TEXTURE_3D, null);
      gl.viewport(0, 0, n[0], n[1]);
      gl.uniform1i(pass.u.uBakeShape, +key);
      gl.uniform3fv(pass.u.uBakeLo, v.lo);
      gl.uniform3fv(pass.u.uBakeHi, hi);
      gl.uniform2f(pass.u.uBakeRes, n[0], n[1]);
      for (let z = 0; z < n[2]; z++) {
        gl.framebufferTextureLayer(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, tex, 0, z);
        gl.uniform1f(pass.u.uBakeZ, (z + 0.5) / n[2]);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
      }
      volumes[key] = { tex, lo: v.lo, hi };
    }
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.deleteFramebuffer(fb);
  }

  function resize() {
    const r = canvas.getBoundingClientRect();
    cssW = Math.max(1, r.width);
    cssH = Math.max(1, r.height);
    const L = LEVELS[level];
    scale = forcedScale || Math.min(devicePixelRatio || 1, L.dpr, Math.sqrt(L.budget / (cssW * cssH)));
    width = Math.max(1, Math.round(cssW * scale));
    height = Math.max(1, Math.round(cssH * scale));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    const nw = Math.max(1, Math.ceil(width / L.near)), nh = Math.max(1, Math.ceil(height / L.near));
    if (!nearT || nearT.w !== nw || nearT.h !== nh) { drop(nearT); nearT = target(nw, nh, gl.NEAREST); }
    if (!shadowT || shadowT.w !== L.shadow) { drop(shadowT); shadowT = target(L.shadow, L.shadow, gl.LINEAR); }
    const aspect = cssW / cssH;
    // Wide screens keep the glass on the right, beside the words; tall ones keep it in the top half.
    layout = options.layout || (aspect >= 1.05 ? { sx: 0.44, sy: -0.03, w: 0.46, h: 0.6 }
      : aspect >= 0.8 ? { sx: 0.3, sy: 0.12, w: 0.6, h: 0.5 }
        : { sx: 0.0, sy: 0.32, w: 0.86, h: 0.36 });
    story.setAspect(aspect);
    if (ready && state) draw(state);
  }

  function camera(view) {
    const aspect = cssW / cssH;
    const dH = (view.h / 2) / (TAN_HALF * layout.h);
    const dW = (view.w / 2) / (TAN_HALF * aspect * layout.w);
    const dist = Math.max(dH, dW, 4.2);
    const az = orbitX * 0.13, el = ELEVATION - orbitY * 0.045;
    const t = view.c;
    const pos = [t[0] + dist * Math.cos(el) * Math.sin(az), t[1] + dist * Math.sin(el), t[2] + dist * Math.cos(el) * Math.cos(az)];
    const f = norm(sub(t, pos));
    const right = norm(cross(f, [0, 1, 0]));
    const up = cross(right, f);
    return { pos, f, right, up, aspect };
  }

  /** Where a world point lands, in normalised device coordinates (with the layout's shift), or null if behind. */
  function ndcOf(p) {
    const v = sub(p, cam.pos);
    const z = dot(v, cam.f);
    if (z <= 0.05) return null;
    return [dot(v, cam.right) / (z * TAN_HALF * cam.aspect) + layout.sx, dot(v, cam.up) / (z * TAN_HALF) + layout.sy];
  }

  /** The part of the screen the glass can reach this frame (NDC), from the corners of its bounds. */
  function screenRect(lo, hi) {
    let x0 = 1, y0 = 1, x1 = -1, y1 = -1;
    for (let i = 0; i < 8; i++) {
      const q = ndcOf([i & 1 ? hi[0] : lo[0], i & 2 ? hi[1] : lo[1], i & 4 ? hi[2] : lo[2]]);
      if (!q) return [-1, -1, 1, 1];
      x0 = Math.min(x0, q[0]); y0 = Math.min(y0, q[1]); x1 = Math.max(x1, q[0]); y1 = Math.max(y1, q[1]);
    }
    const mx = 6 / width, my = 6 / height;
    return [clamp(x0 - mx, -1, 1), clamp(y0 - my, -1, 1), clamp(x1 + mx, -1, 1), clamp(y1 + my, -1, 1)];
  }

  /** The paper under the glass that its shadow can fall on: the casters' box pushed down along the light. */
  function shadowRect(lo, hi) {
    let x0 = Infinity, z0 = Infinity, x1 = -Infinity, z1 = -Infinity;
    for (let i = 0; i < 8; i++) {
      const c = [i & 1 ? hi[0] : lo[0], i & 2 ? hi[1] : lo[1], i & 4 ? hi[2] : lo[2]];
      const y = Math.max(0, c[1]);
      for (const f of [[c[0], c[2]], [c[0] - LIGHT[0] * y / LIGHT[1], c[2] - LIGHT[2] * y / LIGHT[1]]]) {
        x0 = Math.min(x0, f[0]); z0 = Math.min(z0, f[1]); x1 = Math.max(x1, f[0]); z1 = Math.max(z1, f[1]);
      }
    }
    const pad = 1.0;
    x0 -= pad; z0 -= pad; x1 += pad; z1 += pad;
    // keep it square-ish so a texel covers about the same paper both ways
    const w = x1 - x0, d = z1 - z0, s = Math.max(w, d);
    return [x0 - (s - w) / 2, z0 - (s - d) / 2, 1 / s, 1 / s];
  }

  function setScene(pass, st, time, shadowPass) {
    const u = pass.u;
    gl.uniform1f(u.uTime, time);
    gl.uniform3fv(u.uCam, cam.pos);
    gl.uniform3fv(u.uFwd, cam.f);
    gl.uniform3fv(u.uRight, cam.right);
    gl.uniform3fv(u.uUp, cam.up);
    gl.uniform1f(u.uTanHalf, TAN_HALF);
    gl.uniform1f(u.uAspect, cam.aspect);
    gl.uniform2f(u.uShift, layout.sx, layout.sy);
    const slots = [st.A, st.B];
    for (let k = 0; k < 2; k++) {
      const o = slots[k];
      slot.shape[k] = o ? o.shape : -1;
      slot.to.set(o ? o.pose.toObj : IDENTITY, k * 16);
      slot.par.set(o ? (o.spar || o.par) : ZERO4, k * 4);
      slot.form[k] = o ? o.form : 0;
      slot.sweep.set(o ? o.sweep : ZERO4, k * 4);
      slot.drop.set(o ? [o.drop[0][0], o.drop[0][1], o.drop[0][2], o.drop[2]] : ZERO4, k * 4);
      slot.drop2.set(o ? o.drop[1] : [0, 0, 0], k * 3);
    }
    for (let k = 0; k < 2; k++) {
      const vol = slots[k] && volumes[slots[k].shape];
      slot.volOn[k] = vol ? 1 : 0;
      slot.volLo.set(vol ? vol.lo : [0, 0, 0], k * 3);
      slot.volHi.set(vol ? vol.hi : [1, 1, 1], k * 3);
    }
    gl.uniform1iv(u.uVolOn, slot.volOn);
    gl.uniform3fv(u.uVolLo, slot.volLo);
    gl.uniform3fv(u.uVolHi, slot.volHi);
    gl.uniform1iv(u.uShape, slot.shape);
    gl.uniformMatrix4fv(u.uTo, false, slot.to);
    gl.uniform4fv(u.uPar, slot.par);
    gl.uniform1fv(u.uForm, slot.form);
    gl.uniform4fv(u.uSweep, slot.sweep);
    gl.uniform4fv(u.uDrop, slot.drop);
    gl.uniform3fv(u.uDrop2, slot.drop2);
    gl.uniform1i(u.uZero, 0);
    gl.uniform1i(u.uStreamN, st.streamN);
    if (st.streamN) {
      gl.uniform4fv(u.uStream, st.stream);
      gl.uniform3fv(u.uStreamLo, st.streamLo);
      gl.uniform3fv(u.uStreamHi, st.streamHi);
      gl.uniform3fv(u.uStreamCLo, st.streamCLo);
      gl.uniform3fv(u.uStreamCHi, st.streamCHi);
    }
    if (st.barH && u.uBarH) gl.uniform4fv(u.uBarH, st.barH);
    if (st.cols && u.uCols) gl.uniform1fv(u.uCols, st.cols);
    gl.uniform1f(u.uWobble, st.wobble);
    gl.uniform4f(u.uTap, tap[0], tap[1], tap[2], time - tap[3]);
    gl.uniform3f(u.uLo, st.lo[0] - 0.08, st.lo[1] - 0.08, st.lo[2] - 0.08);
    gl.uniform3f(u.uHi, st.hi[0] + 0.08, st.hi[1] + 0.08, st.hi[2] + 0.08);
    gl.uniform1i(u.uShadowPass, shadowPass ? 1 : 0);
  }

  // Test-only: per-pass GPU times, when options.timing is set.
  const timer = options.timing ? gl.getExtension('EXT_disjoint_timer_query_webgl2') : null;
  const pending = [], spent = { shadow: [], near: [], final: [] };
  function begin(name) {
    if (!timer) return null;
    const q = gl.createQuery();
    gl.beginQuery(timer.TIME_ELAPSED_EXT, q);
    pending.push({ q, name });
    return q;
  }
  function end(q) { if (q) gl.endQuery(timer.TIME_ELAPSED_EXT); }
  function collect() {
    for (let i = pending.length - 1; i >= 0; i--) {
      const { q, name } = pending[i];
      if (gl.getQueryParameter(q, gl.QUERY_RESULT_AVAILABLE)) {
        if (!gl.getParameter(timer.GPU_DISJOINT_EXT)) spent[name].push(gl.getQueryParameter(q, gl.QUERY_RESULT) / 1e6);
        gl.deleteQuery(q);
        pending.splice(i, 1);
      }
    }
    return spent;
  }

  let clock = 0;
  function draw(st) {
    const L = LEVELS[level];
    cam = camera(st.view);
    const time = clock;
    const rect = screenRect([st.lo[0] - 0.1, st.lo[1] - 0.1, st.lo[2] - 0.1], [st.hi[0] + 0.1, st.hi[1] + 0.1, st.hi[2] + 0.1]);
    const sr = shadowRect(st.cLo, st.cHi);
    gl.bindVertexArray(vao);
    // the slots' measured volumes, for every pass
    for (let k = 0; k < 2; k++) {
      const o = k === 0 ? st.A : st.B;
      const vol = o && volumes[o.shape];
      gl.activeTexture(gl.TEXTURE3 + k);
      gl.bindTexture(gl.TEXTURE_3D, vol ? vol.tex : null);
    }
    gl.activeTexture(gl.TEXTURE0);

    // 1. the shadow
    let pass = passes.shadow;
    {
    const q1 = begin('shadow');
    gl.bindFramebuffer(gl.FRAMEBUFFER, shadowT.fb);
    gl.viewport(0, 0, shadowT.w, shadowT.h);
    gl.useProgram(pass.p);
    setScene(pass, st, time, true);
    gl.uniform4fv(pass.u.uShadowRect, sr);
    gl.uniform2f(pass.u.uShadowRes, shadowT.w, shadowT.h);
    gl.uniform1i(pass.u.uShadowSteps, L.shadowSteps);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    end(q1);
    }

    // 2. where the glass is, coarsely, only inside its part of the screen
    const tint = st.tintS;
    const shadeTint = [Math.exp(-tint[0] * 0.55), Math.exp(-tint[1] * 0.55), Math.exp(-tint[2] * 0.55)];
    pass = passes.near;
    gl.bindFramebuffer(gl.FRAMEBUFFER, nearT.fb);
    gl.viewport(0, 0, nearT.w, nearT.h);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    const sx0 = Math.floor((rect[0] + 1) / 2 * nearT.w) - 1, sy0 = Math.floor((rect[1] + 1) / 2 * nearT.h) - 1;
    const sx1 = Math.ceil((rect[2] + 1) / 2 * nearT.w) + 1, sy1 = Math.ceil((rect[3] + 1) / 2 * nearT.h) + 1;
    gl.enable(gl.SCISSOR_TEST);
    gl.scissor(Math.max(0, sx0), Math.max(0, sy0), Math.max(0, sx1 - sx0), Math.max(0, sy1 - sy0));
    gl.useProgram(pass.p);
    setScene(pass, st, time, false);
    gl.uniform2f(pass.u.uNearRes, nearT.w, nearT.h);
    gl.uniform1f(pass.u.uPixNear, 2 * TAN_HALF / nearT.h);
    gl.uniform1i(pass.u.uSteps, L.steps);
    const q2 = begin('near');
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    end(q2);
    gl.disable(gl.SCISSOR_TEST);

    // 3. the paper, where it's marked: a small shader
    const paperUniforms = (u) => {
      gl.uniform1i(u.uShadow, 0);
      gl.uniform4fv(u.uShadowRect, sr);
      gl.uniform3fv(u.uShadeTint, shadeTint);
      gl.uniform1f(u.uShadeAmt, st.shade);
      gl.uniform1i(u.uSmokeN, st.smokeN);
      if (st.smokeN) {
        gl.uniform4fv(u.uSmoke, st.smoke);
        gl.uniform1fv(u.uSmokeA, st.smokeAlpha);
      }
      gl.uniform2f(u.uRes, width, height);
    };
    pass = passes.paper;
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, width, height);
    gl.clearColor(0, 0, 0, 0);
    gl.clearStencil(0);
    gl.clear(gl.COLOR_BUFFER_BIT | gl.STENCIL_BUFFER_BIT);
    // only where the paper is marked: under the shadow, the smoke and the glass
    const pr = rect.slice();
    const grow = (q) => { if (!q) return; pr[0] = Math.min(pr[0], q[0]); pr[1] = Math.min(pr[1], q[1]); pr[2] = Math.max(pr[2], q[0]); pr[3] = Math.max(pr[3], q[1]); };
    const sx = 1 / sr[2], sz = 1 / sr[3];
    for (const f of [[sr[0], 0, sr[1]], [sr[0] + sx, 0, sr[1]], [sr[0], 0, sr[1] + sz], [sr[0] + sx, 0, sr[1] + sz]]) grow(ndcOf(f));
    for (let i = 0; i < st.smokeN; i++) {
      const c = st.smoke.subarray(i * 4, i * 4 + 4);
      for (const o of [[-1, -1, 0], [1, 1, 0], [-1, 1, 0], [1, -1, 0]]) grow(ndcOf([c[0] + o[0] * c[3], c[1] + o[1] * c[3], c[2]]));
    }
    const px0 = Math.max(0, Math.floor((Math.max(-1, pr[0]) + 1) / 2 * width) - 2), py0 = Math.max(0, Math.floor((Math.max(-1, pr[1]) + 1) / 2 * height) - 2);
    const px1 = Math.min(width, Math.ceil((Math.min(1, pr[2]) + 1) / 2 * width) + 2), py1 = Math.min(height, Math.ceil((Math.min(1, pr[3]) + 1) / 2 * height) + 2);
    gl.enable(gl.SCISSOR_TEST);
    gl.scissor(px0, py0, Math.max(0, px1 - px0), Math.max(0, py1 - py0));
    gl.useProgram(pass.p);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, shadowT.tex);
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_2D, nearT.tex);
    let u = pass.u;
    gl.uniform3fv(u.uCam, cam.pos);
    gl.uniform3fv(u.uFwd, cam.f);
    gl.uniform3fv(u.uRight, cam.right);
    gl.uniform3fv(u.uUp, cam.up);
    gl.uniform1f(u.uTanHalf, TAN_HALF);
    gl.uniform1f(u.uAspect, cam.aspect);
    gl.uniform2f(u.uShift, layout.sx, layout.sy);
    paperUniforms(u);
    const q3 = begin('final');
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    // 4. mark the pixels near glass, inside the glass's part of the screen
    const fx0 = Math.max(0, Math.floor((rect[0] + 1) / 2 * width) - 2), fy0 = Math.max(0, Math.floor((rect[1] + 1) / 2 * height) - 2);
    const fx1 = Math.min(width, Math.ceil((rect[2] + 1) / 2 * width) + 2), fy1 = Math.min(height, Math.ceil((rect[3] + 1) / 2 * height) + 2);
    gl.scissor(fx0, fy0, Math.max(0, fx1 - fx0), Math.max(0, fy1 - fy0));
    gl.enable(gl.STENCIL_TEST);
    gl.stencilFunc(gl.ALWAYS, 1, 0xff);
    gl.stencilOp(gl.KEEP, gl.KEEP, gl.REPLACE);
    gl.colorMask(false, false, false, false);
    pass = passes.mask;
    gl.useProgram(pass.p);
    gl.uniform1i(pass.u.uNear, 1);
    gl.uniform2f(pass.u.uNearRes, nearT.w, nearT.h);
    gl.uniform2f(pass.u.uRes, width, height);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.colorMask(true, true, true, true);

    // 5. the glass, sharp, only on the marked pixels, laid over the paper
    gl.stencilFunc(gl.EQUAL, 1, 0xff);
    gl.stencilOp(gl.KEEP, gl.KEEP, gl.KEEP);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    pass = passes.glass;
    gl.useProgram(pass.p);
    setScene(pass, st, time, false);
    u = pass.u;
    paperUniforms(u);
    gl.uniform1i(u.uNear, 1);
    gl.uniform3fv(u.uTintS, tint);
    gl.uniform1i(u.uInner, L.inner);
    gl.uniform1i(u.uDisp, L.disp);
    gl.uniform2f(u.uNearRes, nearT.w, nearT.h);
    gl.uniform1f(u.uPix, 2 * TAN_HALF / height);
    gl.uniform1i(u.uRefine, L.refine);
    gl.uniform1i(u.uDebug, options.debug || 0);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    end(q3);
    gl.disable(gl.BLEND);
    gl.disable(gl.STENCIL_TEST);
    gl.disable(gl.SCISSOR_TEST);
  }

  /** Times the chip on a busy moment (the string mid-flight) and starts at the richest level it can hold. */
  function calibrate() {
    const px = new Uint8Array(4);
    const probe = story.frame(0.72, 3, 0, 0, true);
    const timeOne = () => {
      const t0 = performance.now();
      draw(probe);
      gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
      return performance.now() - t0;
    };
    try {
      timeOne();
      const ms = Math.min(timeOne(), timeOne());
      // cost grows with the pixels drawn; aim for 11 ms, leaving room for everything else in a 16.7 ms frame
      let pick = 0;
      const pixels = (l) => { const L = LEVELS[l]; const s = Math.min(devicePixelRatio || 1, L.dpr, Math.sqrt(L.budget / (cssW * cssH))); return s * s; };
      const work = [1, 0.8, 0.66, 0.62];
      while (pick < LEVELS.length - 1 && ms * (pixels(pick) / pixels(0)) * work[pick] > 11) pick++;
      if (pick !== level) { level = pick; resize(); }
    } catch (e) {
      /* keep the default */
    }
  }

  function frame(now) {
    raf = requestAnimationFrame(frame);
    if (!ready) return;
    const dt = last ? Math.min(0.1, (now - last) / 1000) : 1 / 60;
    last = now;
    clock = now / 1000;
    if (first) { P = goal; vel = 0; first = false; story.startIntro(clock); }
    // the glass follows the scroll on a spring: quick, but never a jump
    let rem = dt;
    while (rem > 1e-6) {
      const h = Math.min(rem, 1 / 120);
      const w = 11;
      vel += (w * w * (goal - P) - 2 * w * vel) * h;
      P += vel * h;
      rem -= h;
    }
    orbitX += (pointerX - orbitX) * (1 - Math.exp(-dt / 0.35));
    orbitY += (pointerY - orbitY) * (1 - Math.exp(-dt / 0.35));
    state = story.frame(P, clock, dt, vel);
    draw(state);
    if (options.afterFrame) options.afterFrame({ P, speed: vel, state });
    adapt(dt);
  }

  // If frames still run slow after the start, step down once at a time (never back up while the page is open).
  function adapt(dt) {
    if (forcedLevel || forcedScale) return;
    frames++;
    if (frames < 40) return;
    if (dt > 0.024) slow++;
    if (frames % 90 === 0) {
      if (slow > 30 && level < LEVELS.length - 1) { level++; resize(); }
      slow = 0;
    }
  }

  // Touchable: the view leans toward the pointer, and a tap sends a ripple through the glass.
  const host = options.host || canvas;
  host.addEventListener('pointermove', (e) => {
    if (e.pointerType !== 'mouse') return;
    const r = canvas.getBoundingClientRect();
    pointerX = clamp(((e.clientX - r.left) / r.width) * 2 - 1, -1, 1);
    pointerY = clamp(((e.clientY - r.top) / r.height) * 2 - 1, -1, 1);
  }, { passive: true });
  host.addEventListener('pointerleave', () => { pointerX = 0; pointerY = 0; }, { passive: true });
  host.addEventListener('pointerdown', (e) => {
    if (!cam || !state || (e.target.closest && e.target.closest('a, button, summary'))) return;
    const r = canvas.getBoundingClientRect();
    const nx = ((e.clientX - r.left) / r.width) * 2 - 1 - layout.sx;
    const ny = 1 - ((e.clientY - r.top) / r.height) * 2 - layout.sy;
    const rd = norm([0, 1, 2].map((c) => cam.f[c] + nx * cam.aspect * TAN_HALF * cam.right[c] + ny * TAN_HALF * cam.up[c]));
    const c = state.view.c;
    const t = dot(sub(c, cam.pos), cam.f) / Math.max(1e-3, dot(rd, cam.f));
    tap = [cam.pos[0] + rd[0] * t, cam.pos[1] + rd[1] * t, cam.pos[2] + rd[2] * t, clock];
  }, { passive: true });

  canvas.addEventListener('webglcontextlost', (e) => {
    e.preventDefault();
    stop();
    if (options.onLost) options.onLost();
  }, false);

  function start() {
    if (running || failed) return;
    running = true;
    last = 0;
    raf = requestAnimationFrame(frame);
  }
  function stop() {
    running = false;
    cancelAnimationFrame(raf);
  }

  /** Screen position (CSS pixels from the canvas's top left) of a world point. */
  function project(p) {
    if (!cam || !p) return null;
    const q = ndcOf(p);
    if (!q) return null;
    return { x: (q[0] + 1) / 2 * cssW, y: (1 - q[1]) / 2 * cssH };
  }

  return {
    ready: readyPromise,
    /** Where the scroll is in the story (0..SCENE_COUNT). */
    setTarget(p) { goal = clamp(p, 0, SCENE_COUNT - 0.0001); },
    get position() { return P; },
    get state() { return state; },
    get level() { return level; },
    resize,
    start,
    stop,
    /** For posters and tests: draw story position p at clock t (seconds), at once. */
    renderAt(p, t = 0) {
      if (!ready) return;
      clock = t;
      state = story.frame(p, t, 0, 0, true);
      draw(state);
    },
    project,
    timings: () => (timer ? collect() : null),
  };
}
