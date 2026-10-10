// story.js: what the glass does at every point of the story. No WebGL here: it turns the scroll position and the
// clock into the shapes, the liquid between them, the smoke and the camera's framing, and glass.js draws that.
//
// The story is one piece of liquid glass that becomes eight things in turn:
//   0 signal bars, dying    1 an old cage lift, rising    2 a delivery scooter, riding    3 the ride, as a graph
//   4 a phone, downloading  5 a pin on a map              6 a padlock                     7 the bars again, full
// Position P runs from 0 to 8: scene k owns [k, k + 1). It rests for the first part (its words are on screen and
// scrolling moves it: the lift rises, the scooter rides), then changes into scene k + 1 in one of four ways:
// a string of liquid that flies across the screen, a fall, a slide or a drop.

export const SCENE_COUNT = 8;
const BARS = 0, LIFT = 1, SCOOTER = 2, GRAPH = 3, PHONE = 4, PIN = 5, LOCK = 6;

// How each tint absorbs red, green and blue (the shader has the same numbers).
const CLEAR = [0.30, 0.19, 0.15];
const GREEN = [1.55, 0.26, 1.05];
const AMBER = [0.16, 0.62, 2.3];
const ORANGE = [0.1, 1.15, 2.45];
const RED = [0.1, 2.1, 1.95];

// The recorded ride the app's demo replays: mean signal (dBm) in 4 s steps from 0 to 96 s; null = no signal.
const RIDE_4S = [-89.0, -87.5, -87.8, -89.0, -86.0, -90.5, -91.0, -91.0, -88.8, -90.0, -90.2, -92.8, -92.2, -89.5, -92.0,
  -108.5, -115.5, -117.0, -119.8, -125.7, null, null, null, null];
// The page shows it in 8 s steps (two readings per column), so twelve chunky columns can stand well apart.
const RIDE = Array.from({ length: RIDE_4S.length / 2 }, (_, i) => {
  const a = RIDE_4S[2 * i], b = RIDE_4S[2 * i + 1];
  return a === null ? b : b === null ? a : (a + b) / 2;
});
export const COLS = RIDE.map((dbm) => (dbm === null ? 0.07 : 0.1 + 1.55 * Math.min(1, Math.max(0, (dbm + 128) / 40))));
const SP = 0.3;
const X0 = -0.5 * SP * (COLS.length - 1);
const WARN_AT = 7.5;            // SANKET warned at 63 s: its post stands in the gap at 64 s, in column units
const GONE_COL = 9;             // 72-80 s: the signal was gone at 79 s

const LIFT_H = 1.72;
const LIFT_RISE = 2.3;     // how far the car climbs
const RIDE_X = 1.25;       // how far the scooter rides
const WHEEL_R = 0.29;

/** The scenes: shape, how it turns to face you, its pitch, how much of its scroll it rests, and its framing. */
const SCENES = [
  { shape: BARS, yaw: -0.5, rest: 0.46, view: { c: [0, 0.95, 0], w: 2.3, h: 2.05 } },
  { shape: LIFT, yaw: -0.4, rest: 0.66, view: { c: [0, 1.12, 0], w: 2.0, h: 2.7 } },
  { shape: SCOOTER, yaw: 0.32, rest: 0.62, view: { c: [0, 0.88, 0], w: 2.8, h: 1.95 } },
  { shape: GRAPH, yaw: 0.2, rest: 0.56, view: { c: [0, 1.02, 0], w: 3.6, h: 2.15 } },
  { shape: PHONE, yaw: 0.34, pitch: -0.12, rest: 0.56, view: { c: [0, 1.05, 0], w: 1.75, h: 2.35 } },
  { shape: PIN, yaw: 0.3, rest: 0.56, view: { c: [0, 0.95, 0], w: 2.3, h: 2.1 } },
  { shape: LOCK, yaw: -0.36, rest: 0.56, view: { c: [0, 0.9, 0], w: 1.75, h: 1.95 } },
  { shape: BARS, yaw: -0.5, rest: 1, view: { c: [0, 0.95, 0], w: 2.3, h: 2.05 } },
];
export const REST = SCENES.map((s) => s.rest);

/**
 * How each scene changes into the next. Times are shares of the change (0..1):
 *   melt: the old shape melts into its drop      form: the new shape grows out of its drop
 *   head/tail: when the string's front and back travel the path (stream), or when the drop moves (others)
 * Paths are in the old object's space, x stretched to the screen's shape (see setAspect).
 */
const CHANGES = [
  { type: 'stream', melt: [0, 0.34], head: [0.04, 0.62], tail: [0.24, 0.84], form: [0.6, 1], rHead: 0.2, rTail: 0.05,
    path: [[0, 0.55, 0], [-0.8, 1.8, 0.5], [-2.3, 2.35, 0.4], [-3.5, 1.7, 0.1], [-3.0, 0.7, 0.5], [-1.5, 0.55, 0.6], [-0.45, 1.15, 0.3], [0, 0.95, 0]] },
  { type: 'fall', melt: [0, 0.34], head: [0.22, 0.62], form: [0.52, 1] },
  { type: 'slide', melt: [0, 0.4], head: [0.28, 0.7], form: [0.5, 1] },
  { type: 'drop', melt: [0, 0.4], head: [0.3, 0.68], form: [0.55, 1] },
  { type: 'stream', melt: [0, 0.34], head: [0.04, 0.62], tail: [0.24, 0.84], form: [0.58, 1], rHead: 0.17, rTail: 0.045,
    path: [[0, 1.05, 0], [-0.6, 2.2, 0.3], [-2.1, 2.5, 0.2], [-3.1, 1.8, 0.3], [-2.4, 1.0, 0.6], [-1.0, 1.4, 0.5], [0, 0.7, 0]] },
  { type: 'drop', melt: [0, 0.4], head: [0.3, 0.68], form: [0.55, 1] },
  { type: 'drop', melt: [0, 0.4], head: [0.3, 0.7], form: [0.52, 1] },
];

const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
const lerp = (a, b, t) => a + (b - a) * t;
const smooth = (x) => { const t = clamp(x, 0, 1); return t * t * (3 - 2 * t); };
const smoother = (x) => { const t = clamp(x, 0, 1); return t * t * t * (t * (t * 6 - 15) + 10); };
const span = (x, r) => clamp((x - r[0]) / (r[1] - r[0]), 0, 1);
const mix3 = (a, b, t) => [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)];
const add3 = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];
const sub3 = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const len3 = (a) => Math.hypot(a[0], a[1], a[2]);

/** A pose: where an object stands and how it is turned. World = pos + Ry(yaw) Rx(pitch) object. */
function pose(pos, yaw, pitch = 0) {
  const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
  // rows of R = Ry * Rx
  const R = [[cy, sy * sp, sy * cp], [0, cp, -sp], [-sy, cy * sp, cy * cp]];
  const toWorld = (q) => [
    pos[0] + R[0][0] * q[0] + R[0][1] * q[1] + R[0][2] * q[2],
    pos[1] + R[1][0] * q[0] + R[1][1] * q[1] + R[1][2] * q[2],
    pos[2] + R[2][0] * q[0] + R[2][1] * q[1] + R[2][2] * q[2],
  ];
  // world to object, column-major: q = R^T (p - pos)
  const t = [-(R[0][0] * pos[0] + R[1][0] * pos[1] + R[2][0] * pos[2]),
    -(R[0][1] * pos[0] + R[1][1] * pos[1] + R[2][1] * pos[2]),
    -(R[0][2] * pos[0] + R[1][2] * pos[1] + R[2][2] * pos[2])];
  const toObj = new Float32Array([
    R[0][0], R[0][1], R[0][2], 0,
    R[1][0], R[1][1], R[1][2], 0,
    R[2][0], R[2][1], R[2][2], 0,
    t[0], t[1], t[2], 1,
  ]);
  return { pos, yaw, pitch, toWorld, toObj };
}

/** Each shape's box, the drop it melts into, and the heights it forms over, in its own space. */
function shapeInfo(shape, par) {
  switch (shape) {
    case BARS: return { lo: [-1.0, 0, -0.24], hi: [1.0, 1.95, 0.24], drop: [[0, 0.5, 0], [0, 0.5, 0], 0.5], y0: 0, h: 1.9 };
    case LIFT: {
      const y = par[0];
      return { lo: [-0.88, 0, -0.56], hi: [0.88, y + LIFT_H + 0.44, 0.58], drop: [[0, y + 0.95, 0], [0, y + 0.95, 0], 0.62],
        y0: y, h: LIFT_H + 0.4, casterLo: [-0.72, y, -0.56], casterHi: [0.72, y + LIFT_H + 0.44, 0.58],
        shaftHi: y + LIFT_H + 12 };
    }
    case SCOOTER: return { lo: [-1.2, 0, -0.5], hi: [1.12, 1.78, 0.5], drop: [[-0.5, 0.45, 0], [0.5, 0.45, 0], 0.4], y0: 0, h: 1.65 };
    case GRAPH: return { lo: [-1.78, 0, -0.22], hi: [1.78, 2.3, 0.58], drop: [[-1.5, 0.22, 0], [1.5, 0.22, 0], 0.22], y0: 0, h: 1.8 };
    case PHONE: return { lo: [-0.55, 0, -0.14], hi: [0.55, 2.1, 0.14], drop: [[0, 0.9, 0], [0, 1.2, 0], 0.48], y0: 0, h: 2.06 };
    case PIN: return { lo: [-1.02, 0, -0.62], hi: [1.02, 1.98 + (1 - par[0]) * 1.5, 0.78], drop: [[-0.3, 0.35, 0.05], [0.3, 0.35, 0.05], 0.4], y0: 0, h: 1.95 };
    default: return { lo: [-0.75, 0, -0.42], hi: [0.75, 1.82 + 0.3 * par[0], 0.42], drop: [[0, 0.75, 0], [0, 0.75, 0], 0.55], y0: 0, h: 1.8 };
  }
}

/** The main tint of a shape, for its liquid and its shadow. */
function shapeTint(shape, par) {
  if (shape === BARS) {
    const f = clamp((par[0] - 0.6) / 3.4, 0, 1);
    return f > 0.66 ? mix3(AMBER, GREEN, (f - 0.66) / 0.34) : f > 0.4 ? mix3(ORANGE, AMBER, (f - 0.4) / 0.26) : mix3(RED, ORANGE, f / 0.4);
  }
  if (shape === GRAPH) return mix3(GREEN, AMBER, 0.45);
  if (shape === PIN) return mix3(RED, CLEAR, 0.4);
  if (shape === LOCK) return GREEN.map((v) => v * 0.8);
  if (shape === SCOOTER) return mix3(CLEAR, ORANGE, 0.25);
  return CLEAR;
}

/** A path through way points (centripetal Catmull-Rom), sampled by distance along it. */
function makePath(points) {
  const P = [points[0], ...points, points[points.length - 1]];
  const dense = [];
  for (let i = 1; i < P.length - 2; i++) {
    const p0 = P[i - 1], p1 = P[i], p2 = P[i + 1], p3 = P[i + 2];
    for (let k = 0; k < 24; k++) {
      const t = k / 24;
      const t2 = t * t, t3 = t2 * t;
      dense.push([0, 1, 2].map((c) => 0.5 * ((2 * p1[c]) + (-p0[c] + p2[c]) * t + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * t2 +
        (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * t3)));
    }
  }
  dense.push(points[points.length - 1]);
  const acc = [0];
  for (let i = 1; i < dense.length; i++) acc.push(acc[i - 1] + len3(sub3(dense[i], dense[i - 1])));
  const total = acc[acc.length - 1] || 1;
  return {
    total,
    at(s) {
      const d = clamp(s, 0, 1) * total;
      let lo = 0, hi = acc.length - 1;
      while (hi - lo > 1) { const m = (lo + hi) >> 1; if (acc[m] < d) lo = m; else hi = m; }
      const f = (d - acc[lo]) / Math.max(1e-6, acc[hi] - acc[lo]);
      return mix3(dense[lo], dense[hi], f);
    },
  };
}

const sstep = (e0, e1, x) => smooth((x - e0) / (e1 - e0));

/** What the shader needs for a shape: its four numbers, plus the bars' or the ride's heights this frame. */
function shaderPar(o, time, st) {
  const P = o.par;
  if (o.shape === BARS) {
    st.barH = [0, 1, 2, 3].map((i) => {
      const on = smooth(P[0] - i);
      let h = lerp(0.17, 0.55 + i * 0.42, on) * smooth(P[1] * 1.75 - i * 0.25);
      h += P[2] * 0.06 * Math.sin(time * 2.4 - i * 1.15) * on;
      return Math.max(h, 0);
    });
    return P;
  }
  if (o.shape === LIFT) {
    const ang = lerp(2.75, 0.39, P[1]);
    return [P[0], Math.cos(ang), P[2], Math.sin(ang)];
  }
  if (o.shape === GRAPH) {
    const n = COLS.length, reveal = P[0], scan = P[1] * (n - 1), bead = P[2];
    const cols = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      let h = COLS[i] * sstep(i / n - 0.02, i / n + 0.07, reveal * 1.1);
      h *= 1 + 0.06 * bead * Math.exp(-(i - scan) * (i - scan) * 0.5);
      cols[i] = Math.max(0.07, h);
    }
    st.cols = cols;
    const warn = sstep(WARN_AT / n + 0.04, WARN_AT / n + 0.14, reveal * 1.1);
    const i0 = Math.floor(scan);
    const hb = lerp(cols[i0], cols[Math.min(i0 + 1, n - 1)], smooth(scan - i0));
    return [warn, X0 + scan * SP, hb + 0.1, 0.065 * bead];
  }
  return P;
}

/** One scene at rest. r: how far through its rest (0..1), local: seconds since it came on. */
function rest(k, r, time, local, intro) {
  const S = SCENES[k];
  let pos = [0, 0, 0];
  let par = [4, 1, 0, 0];
  let view = S.view;
  switch (S.shape) {
    case BARS:
      if (k === 0) {
        if (intro === null) par = [1, 1, 0, 0];
        else {
          const t = time - intro;
          par = [4 - 3 * smoother((t - 2.15) / 1.5), smooth((t - 0.5) / 0.95), 0, 0];
        }
        const dying = (4 - par[0]) / 3;
        view = { c: [0, lerp(0.95, 0.62, dying), 0], w: lerp(2.3, 2.1, dying), h: lerp(2.05, 1.45, dying) };
      } else {
        par = [4, 1, 1, 0];
      }
      break;
    case LIFT: {
      const rise = smoother((r - 0.06) / 0.82);
      const y = LIFT_RISE * rise + 0.01 * Math.sin(time * 2.3) * Math.sin(Math.PI * rise);
      par = [y, rise + 0.008 * Math.sin(time * 9.1), 1, 0];
      view = { c: [0, S.view.c[1] + 0.66 * y, 0], w: S.view.w, h: S.view.h };
      break;
    }
    case SCOOTER: {
      const dist = RIDE_X * smoother((r - 0.03) / 0.88);
      const fwd = [Math.cos(S.yaw), 0, -Math.sin(S.yaw)];
      pos = [fwd[0] * dist, 0, fwd[2] * dist];
      par = [dist / WHEEL_R, 0.004 * Math.sin(time * 39) + 0.003 * Math.sin(time * 23), 0, 0];
      view = { c: [pos[0] * 0.9, S.view.c[1], pos[2] * 0.9], w: S.view.w, h: S.view.h };
      break;
    }
    case GRAPH:
      par = [1, (time / 4.8) % 1, 1, 0];
      break;
    case PHONE: {
      // the download: in, a wait while the signal is gone, on again from where it stopped, done, again
      const c = local % 8.2;
      let p, wait = 0, done = 0;
      if (c < 2.6) p = 0.62 * smooth(c / 2.6);
      else if (c < 4.2) { p = 0.62; wait = smooth((c - 2.6) / 0.25) * (1 - smooth((c - 3.95) / 0.25)); }
      else if (c < 5.8) p = 0.62 + 0.38 * smooth((c - 4.2) / 1.6);
      else if (c < 7.7) { p = 1; done = 1; }
      else { p = 1 - smooth((c - 7.7) / 0.5); }
      par = [p, wait, 0, done];
      break;
    }
    case PIN:
      par = [1 - 0.04 * (0.5 + 0.5 * Math.sin(time * 1.9)), (local % 2.6) / 2.6, 0, 0];
      break;
    default: {
      const shut = smoother((r - 0.04) / 0.3);
      par = [(1 - shut) + 0.05 * Math.sin(Math.PI * clamp((r - 0.3) / 0.12, 0, 1)), 0, 0, 0];
    }
  }
  return { shape: S.shape, pose: pose(pos, S.yaw, S.pitch || 0), par, view };
}

/** The world-space drop a shape melts into, its box, and its forming heights. */
function worldBits(o) {
  const info = shapeInfo(o.shape, o.par);
  const a = o.pose.toWorld(info.drop[0]), b = o.pose.toWorld(info.drop[1]);
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  const clo = info.casterLo || info.lo, chi = info.casterHi || info.hi;
  const cLo = [Infinity, Infinity, Infinity], cHi = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < 8; i++) {
    const q = [i & 1 ? info.hi[0] : info.lo[0], i & 2 ? info.hi[1] : info.lo[1], i & 4 ? info.hi[2] : info.lo[2]];
    const w = o.pose.toWorld(q);
    for (let c = 0; c < 3; c++) { lo[c] = Math.min(lo[c], w[c]); hi[c] = Math.max(hi[c], w[c]); }
    const qc = [i & 1 ? chi[0] : clo[0], i & 2 ? chi[1] : clo[1], i & 4 ? chi[2] : clo[2]];
    const wc = o.pose.toWorld(qc);
    for (let c = 0; c < 3; c++) { cLo[c] = Math.min(cLo[c], wc[c]); cHi[c] = Math.max(cHi[c], wc[c]); }
  }
  if (info.shaftHi) hi[1] = Math.max(hi[1], info.shaftHi);
  return { drop: [a, b, info.drop[2]], lo, hi, cLo, cHi, sweep: [info.y0, 1 / info.h] };
}

export function createStory() {
  let xStretch = 1, tall = false, paths = [];
  let intro = null;
  let lastScene = -1, entered = 0;
  const smoke = [];
  let emitWait = 0, lastExhaust = null;
  const streamBuf = new Float32Array(24 * 4);
  const smokeBuf = new Float32Array(12 * 4);
  const smokeAlpha = new Float32Array(12);

  function setAspect(aspect) {
    xStretch = clamp(aspect / 1.75, 0.3, 1);
    tall = aspect < 0.8;
    paths = CHANGES.map((c) => (c.path ? c.path.map((p) => [p[0] * xStretch, p[1] * (tall ? 1.18 : 1), p[2]]) : null));
  }
  setAspect(1.6);

  /** Everything glass.js needs to draw position P at time (seconds). dt: seconds since the last frame. */
  function frame(P, time, dt = 0, speed = 0, still = false) {
    const p = clamp(P, 0, SCENE_COUNT - 0.0001);
    const k = Math.floor(p);
    const u = p - k;
    const S = SCENES[k];
    const atRest = k === SCENE_COUNT - 1 || u < S.rest;
    const restScene = atRest ? k : -1;
    if (restScene !== lastScene) { lastScene = restScene; entered = time; }
    const local = time - entered;

    const st = {
      A: null, B: null, streamN: 0, stream: streamBuf, streamLo: [0, 0, 0], streamHi: [0, 0, 0], tintS: CLEAR,
      wobble: Math.min(0.5, Math.abs(speed) * 0.7), view: null, lo: null, hi: null, cLo: null, cHi: null,
      smokeN: 0, smoke: smokeBuf, smokeAlpha, scene: k, settled: false, labels: null, shade: 1,
    };
    let scooter = null;

    if (atRest) {
      const o = rest(k, clamp(u / S.rest, 0, 1), time, local, intro);
      const wb = worldBits(o);
      st.A = { ...o, form: 1, drop: [[0, 0, 0], [0, 0, 0], 0], sweep: [wb.sweep[0], wb.sweep[1], 0.6, 1] };
      st.view = o.view;
      st.lo = wb.lo; st.hi = wb.hi; st.cLo = wb.cLo; st.cHi = wb.cHi;
      st.tintS = shapeTint(o.shape, o.par);
      st.settled = true;
      if (o.shape === SCOOTER) scooter = o;
      if (o.shape === GRAPH) st.labels = labelPoints(o);
      if (k === 0 && intro !== null) introDrop(st, time - intro, wb);
    } else {
      change(st, k, (u - S.rest) / (1 - S.rest), time, local);
      if (st.A && st.A.shape === SCOOTER && st.A.form > 0.85) scooter = st.A;
    }
    if (!still) updateSmoke(st, scooter, time, dt);
    for (const o of [st.A, st.B]) if (o) o.spar = shaderPar(o, time, st);
    return st;
  }

  // The first moment: a drop falls onto the paper and the bars grow out of it.
  function introDrop(st, t, wb) {
    if (t > 1.5) return;
    const land = 0.62;
    let y, r = 0.2, tail = 0;
    if (t < land) {
      const f = t / land;
      y = 4.2 - (4.2 - 0.42) * f * f;
      tail = 0.35 * f;
    } else {
      y = 0.42;
      r = 0.2 * (1 - smooth((t - land) / 0.85)) + 0.001;
    }
    const head = [0, y, 0], back = [0, y + tail, 0];
    setStream(st, [head, back], [r, r * 0.55]);
    st.wobble = Math.max(st.wobble, 0.8 * Math.exp(-3 * Math.max(0, t - land)) * (t > land ? 1 : 0));
    st.lo = st.lo.map((v, i) => Math.min(v, back[i] - 0.3, head[i] - 0.3));
    st.hi = st.hi.map((v, i) => Math.max(v, back[i] + 0.3, head[i] + 0.3));
    st.cLo = st.lo; st.cHi = st.hi;
  }

  function setStream(st, pts, radii) {
    const n = Math.min(24, pts.length);
    const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    const cLo = [], cHi = [];
    for (let i = 0; i < n; i++) {
      streamBuf.set([pts[i][0], pts[i][1], pts[i][2], radii[i]], i * 4);
      for (let c = 0; c < 3; c++) { lo[c] = Math.min(lo[c], pts[i][c] - radii[i]); hi[c] = Math.max(hi[c], pts[i][c] + radii[i]); }
    }
    // three pieces of 8 segments, each boxed (a piece's box includes the point that starts the next piece)
    for (let k = 0; k < 3; k++) {
      const l = [Infinity, Infinity, Infinity], h = [-Infinity, -Infinity, -Infinity];
      for (let i = k * 8; i <= Math.min(n - 1, k * 8 + 8); i++) {
        for (let c = 0; c < 3; c++) { l[c] = Math.min(l[c], pts[i][c] - radii[i]); h[c] = Math.max(h[c], pts[i][c] + radii[i]); }
      }
      if (!isFinite(l[0])) { l.fill(1e4); h.fill(1e4 + 1); }
      cLo.push(...l.map((v) => v - 0.02)); cHi.push(...h.map((v) => v + 0.02));
    }
    st.streamN = n;
    st.streamLo = lo.map((v) => v - 0.02);
    st.streamHi = hi.map((v) => v + 0.02);
    st.streamCLo = cLo;
    st.streamCHi = cHi;
  }

  let restLocal = 0;
  const pathCache = new Map();
  function pathFor(k, from, to) {
    const key = `${k}|${xStretch}|${from.map((v) => v.toFixed(3))}|${to.map((v) => v.toFixed(3))}`;
    let path = pathCache.get(key);
    if (!path) {
      if (pathCache.size > 16) pathCache.clear();
      path = makePath([from, ...paths[k].slice(1, -1), to]);
      pathCache.set(key, path);
    }
    return path;
  }

  /** Scene k changing into k + 1; tau runs 0..1 over the change. */
  function change(st, k, tau, time) {
    const C = CHANGES[k];
    const a = rest(k, 1, time, restLocal, intro);
    const b = rest(k + 1, 0, time, 0, null);
    if (C.type === 'fall') a.par[2] = 1 - smooth(tau / 0.3);          // the rails and cables go first
    if (b.shape === GRAPH) b.par = [smooth(span(tau, [C.form[0] - 0.05, 1])), 0, 0, 0];
    const wa = worldBits(a), wb = worldBits(b);
    const centre = (d) => mix3(d[0], d[1], 0.5);
    const melt = smoother(span(tau, C.melt));
    const formB = smoother(span(tau, C.form));
    const flying = Math.sin(Math.PI * clamp(tau, 0, 1));
    st.tintS = mix3(mix3(shapeTint(a.shape, a.par), shapeTint(b.shape, b.par), smooth(span(tau, [0.3, 0.75]))), CLEAR, 0.92 * flying);

    let dropA, dropB;
    if (C.type === 'stream') {
      const rA = Math.min(wa.drop[2], 0.24) * (0.5 + 0.5 * smooth(tau / 0.12)) * (1 - smooth(span(tau, [0.14, 0.3])));
      const rB = wb.drop[2] * smooth(span(tau, [C.head[1] - 0.06, C.form[0] + 0.1])) * (1 - 0.35 * smooth(span(tau, [C.form[0] + 0.15, 1])));
      dropA = [wa.drop[0], wa.drop[1], rA];
      dropB = [wb.drop[0], wb.drop[1], rB];
      const sh = smoother(span(tau, C.head)), stl = smoother(span(tau, C.tail));
      if (sh > 0.002 && stl < 0.998) {
        const path = pathFor(k, centre(wa.drop), centre(wb.drop));
        const pts = [], radii = [];
        const n = 24;
        for (let i = 0; i < n; i++) {
          const f = i / (n - 1);                     // 0 at the tail, 1 at the head
          const q = path.at(lerp(stl, sh, f));
          const w = Math.sin(time * 5.2 - f * 7.5) * 0.06 * Math.sin(Math.PI * f) * (1 - stl);
          pts.push([q[0], q[1] + w, q[2] + 0.5 * w]);
          const fat = Math.max(smooth(1 - tau / 0.3) * (1 - f) * 0.12, 0);   // thicker where it leaves the old shape
          radii.push((lerp(C.rTail, C.rHead, Math.pow(f, 0.75)) + fat) * (1 + 0.08 * Math.sin(time * 6.0 - f * 7.0)));
        }
        setStream(st, pts, radii);
      }
      st.wobble = Math.max(st.wobble, 0.85 * Math.sin(Math.PI * tau));
    } else {
      // one drop: the old shape melts into it, it moves, and the new shape grows out of it
      const m = smoother(span(tau, C.head));
      const da = wa.drop, db = wb.drop;
      let d0, d1, r;
      if (C.type === 'fall') {
        const g = span(tau, C.head) ** 2;
        const ca = centre(da), cb = centre(db);
        const land = span(tau, [C.head[1], C.head[1] + 0.08]);
        const c = [lerp(ca[0], cb[0], g), lerp(ca[1], 0.42, g), lerp(ca[2], cb[2], g)];
        const stretch = 0.45 * g * (1 - land);
        const rFall = lerp(da[2], 0.34, smooth(g * 3));
        d0 = mix3(c, db[0], land);
        d1 = mix3([c[0], c[1] + stretch, c[2]], db[1], land);
        r = lerp(rFall, db[2] * 1.12, land) * (1 - 0.1 * smooth(span(tau, [C.head[1] + 0.08, 1])));
        if (tau > C.head[1]) st.wobble = Math.max(st.wobble, 1.1 * Math.exp(-7 * (tau - C.head[1])));
      } else {
        const hop = C.type === 'drop' ? 0.42 * Math.sin(Math.PI * m) : 0.05 * Math.sin(Math.PI * m);
        d0 = mix3(da[0], db[0], m); d0[1] += hop;
        d1 = mix3(da[1], db[1], m); d1[1] += hop;
        r = lerp(da[2], db[2], m) * (1 + 0.1 * Math.sin(Math.PI * m));
      }
      const grow = 0.4 + 0.6 * smooth(tau / 0.2);
      dropA = dropB = [d0, d1, r * grow];
      st.wobble = Math.max(st.wobble, 0.7 * Math.sin(Math.PI * tau));
    }

    const formA = 1 - melt;
    const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
    const cLo = [Infinity, Infinity, Infinity], cHi = [-Infinity, -Infinity, -Infinity];
    const grow3 = (l, h, x, y) => { for (let c = 0; c < 3; c++) { l[c] = Math.min(l[c], x[c]); h[c] = Math.max(h[c], y[c]); } };
    const addDrop = (d) => {
      if (d[2] < 0.002) return;
      for (const e of [d[0], d[1]]) {
        grow3(lo, hi, e.map((v) => v - d[2]), e.map((v) => v + d[2]));
        grow3(cLo, cHi, e.map((v) => v - d[2]), e.map((v) => v + d[2]));
      }
    };
    if (formA > 0.001 || dropA[2] > 0.02) {
      st.A = { ...a, form: formA, drop: [dropA[0], dropA[1], formA < 0.999 ? Math.max(dropA[2], 0.03) : dropA[2]],
        sweep: [wa.sweep[0], wa.sweep[1], 0.7, 1] };
      if (formA > 0.001) { grow3(lo, hi, wa.lo, wa.hi); grow3(cLo, cHi, wa.cLo, wa.cHi); }
      addDrop(st.A.drop);
    }
    if (formB > 0.001 || dropB[2] > 0.02) {
      st.B = { ...b, form: formB, drop: [dropB[0], dropB[1], formB > 0.001 ? Math.max(dropB[2], 0.03) : dropB[2]],
        sweep: [wb.sweep[0], wb.sweep[1], 0.7, 1] };
      if (formB > 0.001) { grow3(lo, hi, wb.lo, wb.hi); grow3(cLo, cHi, wb.cLo, wb.cHi); }
      addDrop(st.B.drop);
    }
    if (st.streamN) { grow3(lo, hi, st.streamLo, st.streamHi); grow3(cLo, cHi, st.streamLo, st.streamHi); }
    if (!isFinite(lo[0])) { grow3(lo, hi, wb.lo, wb.hi); grow3(cLo, cHi, wb.cLo, wb.cHi); }
    st.lo = lo; st.hi = hi; st.cLo = cLo; st.cHi = cHi;

    // the camera follows, and steps back a little while the string flies
    const v = smoother(span(tau, [0.12, 0.88]));
    const fly = C.type === 'stream' ? Math.sin(Math.PI * smooth(span(tau, [0.0, 0.9]))) : 0;
    const wide = 1 + 0.55 * fly;
    const c = mix3(a.view.c, b.view.c, v);
    c[1] += 0.45 * fly;
    st.view = { c, w: lerp(a.view.w, b.view.w, v) * wide, h: lerp(a.view.h, b.view.h, v) * wide };
    st.shade = 1;
  }

  function labelPoints(o) {
    return {
      warn: o.pose.toWorld([X0 + WARN_AT * SP, 2.11, 0]),
      gone: o.pose.toWorld([X0 + GONE_COL * SP, COLS[GONE_COL] + 0.12, 0]),
    };
  }

  // Exhaust smoke: soft puffs that leave the scooter's pipe, drift back and up, swell and fade. Faster riding,
  // more puffs; standing still, the engine idles out one now and then.
  function updateSmoke(st, sc, time, dt) {
    dt = Math.min(dt, 0.05);
    for (const s of smoke) {
      s.age += dt;
      for (let c = 0; c < 3; c++) s.p[c] += s.v[c] * dt;
      const drag = Math.exp(-dt * 0.9);
      s.v[0] *= drag; s.v[2] *= drag; s.v[1] = s.v[1] * drag + 0.04 * dt;
    }
    while (smoke.length && smoke[0].age >= smoke[0].life) smoke.shift();
    for (let i = smoke.length - 1; i >= 0; i--) if (smoke[i].age >= smoke[i].life) smoke.splice(i, 1);
    if (sc) {
      const ex = sc.pose.toWorld([-1.12, 0.31 + sc.par[1], 0.19]);
      const speed = lastExhaust && dt > 0 ? Math.min(4, len3(sub3(ex, lastExhaust)) / dt) : 0;
      lastExhaust = ex;
      emitWait -= dt;
      if (emitWait <= 0) {
        emitWait = speed > 0.05 ? Math.max(0.06, 0.2 - speed * 0.08) : 0.42 + Math.random() * 0.3;
        const back = sub3(sc.pose.toWorld([-1, 0, 0]), sc.pose.toWorld([0, 0, 0]));
        const kick = 0.22 + speed * 0.28;
        smoke.push({
          p: ex.slice(),
          v: [back[0] * kick + (Math.random() - 0.5) * 0.08, 0.16 + Math.random() * 0.12, back[2] * kick + (Math.random() - 0.5) * 0.08],
          age: 0, life: 1.5 + Math.random() * 0.9 + speed * 0.25, r0: 0.05, r1: 0.3 + Math.random() * 0.12 + Math.min(0.14, speed * 0.06),
        });
        if (smoke.length > 12) smoke.shift();
      }
    } else {
      lastExhaust = null;
    }
    let n = 0;
    for (const s of smoke) {
      const a = s.age / s.life;
      const r = lerp(s.r0, s.r1, 1 - (1 - a) * (1 - a));
      smokeBuf.set([s.p[0], s.p[1], s.p[2], r], n * 4);
      smokeAlpha[n] = Math.pow(1 - a, 1.2) * smooth(a / 0.06);
      n++;
    }
    st.smokeN = n;
  }

  return {
    setAspect,
    /** Starts the opening: a drop falls and the bars grow, then die one by one. */
    startIntro(time) { intro = time; },
    frame(P, time, dt, speed, still) {
      const st = frame(P, time, dt, speed, still);
      if (st.settled) restLocal = time - entered;
      return st;
    },
  };
}
