// glass-glsl.js: the shaders of SANKET's liquid glass. No libraries.
//
// One distance function describes everything made of glass at any moment: the scene's object, the drop it melts
// into, and the string of liquid it travels as. Three passes share it:
//   1. shadow: the glass's soft shadow on the paper, into a small texture;
//   2. coarse: at half resolution, where each ray first comes near the glass;
//   3. final: at the screen's full resolution, every ray finishes from there, so the glass is as sharp as the text.

export const VERT = `#version 300 es
void main() {
  vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}`;

export const COMMON = `#version 300 es
precision highp float;
precision highp int;

uniform float uTime;
uniform vec3 uCam;
uniform vec3 uFwd;
uniform vec3 uRight;
uniform vec3 uUp;
uniform float uTanHalf;
uniform float uAspect;
uniform vec2 uShift;

// Two shapes can be on at once: slot 0 is the one leaving, slot 1 the one forming. -1 is none.
uniform int uShape[2];
uniform mat4 uTo[2];      // world to the shape's own space
uniform vec4 uPar[2];     // each shape's own movement (see the shapes)
uniform float uForm[2];   // 1: the whole shape; 0: melted into its drop
uniform vec4 uSweep[2];   // the order it forms in: x lowest y, y 1 / height, z sweep width, w direction (+1 rises)
uniform vec4 uDrop[2];    // the drop it melts into: a capsule from xyz to uDrop2, radius w (world space)
uniform vec3 uDrop2[2];
uniform int uZero;        // always 0: loops that start from it aren't unrolled, which keeps the shaders small
uniform int uStreamN;     // the string of liquid between them: points xyz with radius w
uniform vec4 uStream[24];
uniform vec3 uStreamCLo[3];  // the string in three pieces, each with its own box, so far pieces cost nothing
uniform vec3 uStreamCHi[3];
uniform vec3 uStreamLo;
uniform vec3 uStreamHi;
uniform float uWobble;
uniform vec4 uTap;        // a tap's ripple: xyz where, w seconds since
uniform float uCols[24];  // the recorded ride: each column's height this frame
uniform float uColsTint[24];  // and its real height, for its colour
uniform vec4 uBarH;       // the four signal bars' heights this frame
uniform vec3 uLo;         // bounds of all the glass (world)
uniform vec3 uHi;
uniform int uShadowPass;   // 1 while drawing the shadow: the lift's rails and cables cast none
// The expensive, unmoving part of each slot's shape, measured once into a volume (when the device can).
uniform highp sampler3D uVol0;
uniform highp sampler3D uVol1;
uniform int uVolOn[2];
uniform vec3 uVolLo[2];
uniform vec3 uVolHi[2];
int gSlot = 0;

const vec3 LIGHT = vec3(-0.3939, 0.8371, 0.3802);
const vec3 FILL = vec3(0.7372, 0.4423, 0.5406);
const vec3 PAPER = vec3(0.896, 0.8714, 0.807);

// ---- distance functions ----

float sdBox(vec3 p, vec3 b) {
  vec3 q = abs(p) - b;
  return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
}
float sdRoundBox(vec3 p, vec3 b, float r) {
  vec3 q = abs(p) - b + r;
  return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0) - r;
}
float sdRoundRect(vec2 p, vec2 b, float r) {
  vec2 q = abs(p) - b + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float extrude(float d, float z, float h) {
  vec2 w = vec2(d, abs(z) - h);
  return min(max(w.x, w.y), 0.0) + length(max(w, 0.0));
}
float sdCapsule(vec3 p, vec3 a, vec3 b, float r) {
  vec3 pa = p - a, ba = b - a;
  float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-8), 0.0, 1.0);
  return length(pa - ba * h) - r;
}
float sdTorusZ(vec3 p, float R, float r) {
  return length(vec2(length(p.xy) - R, p.z)) - r;
}
float sdTorusY(vec3 p, float R, float r) {
  return length(vec2(length(p.xz) - R, p.y)) - r;
}
float sdCylZ(vec3 p, float r, float h) {
  vec2 d = abs(vec2(length(p.xy), p.z)) - vec2(r, h);
  return min(max(d.x, d.y), 0.0) + length(max(d, 0.0));
}
float sdRoundCone(vec3 p, float r1, float r2, float h) {
  float b = (r1 - r2) / h;
  float a = sqrt(1.0 - b * b);
  vec2 q = vec2(length(p.xz), p.y);
  float k = dot(q, vec2(-b, a));
  if (k < 0.0) return length(q) - r1;
  if (k > a * h) return length(q - vec2(0.0, h)) - r2;
  return dot(q, vec2(a, b)) - r1;
}
// A tapering tube piece between two balls (a, r1) and (b, r2).
float sdCone2(vec3 p, vec3 a, vec3 b, float r1, float r2) {
  vec3 ba = b - a;
  float l2 = max(dot(ba, ba), 1e-8);
  float rr = r1 - r2;
  float a2 = l2 - rr * rr;
  float il2 = 1.0 / l2;
  vec3 pa = p - a;
  float y = dot(pa, ba);
  float z = y - l2;
  vec3 xv = pa * l2 - ba * y;
  float x2 = dot(xv, xv);
  float y2 = y * y * l2;
  float z2 = z * z * l2;
  float k = sign(rr) * rr * rr * x2;
  if (sign(z) * a2 * z2 > k) return sqrt(x2 + z2) * il2 - r2;
  if (sign(y) * a2 * y2 < k) return sqrt(x2 + y2) * il2 - r1;
  return (sqrt(x2 * a2 * il2) + y * rr) * il2 - r1;
}
float smin(float a, float b, float k) {
  float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
  return mix(b, a, h) - k * h * (1.0 - h);
}
float smax(float a, float b, float k) { return -smin(-a, -b, k); }
vec2 rot(vec2 p, float a) { float c = cos(a), s = sin(a); return vec2(c * p.x - s * p.y, s * p.x + c * p.y); }
float ease(float x) { x = clamp(x, 0.0, 1.0); return x * x * (3.0 - 2.0 * x); }

// ---- the shapes, each in its own space, standing on the paper at y = 0 and facing +z ----

// ---- the unmoving parts of four shapes: measured once into volumes, or worked out here when that isn't possible ----

const float LW = 0.6;
const float LH = 1.72;
const float LD = 0.44;
// The lift car, in its own space: a glass block between a floor slab and a cornice, reeded sides, a folding gate,
// and the dial on its roof (its needle moves, so it isn't here).
float liftCar(vec3 c) {
  float car = sdRoundBox(c - vec3(0.0, 0.5 * LH, 0.0), vec3(LW - 0.03, 0.5 * LH - 0.06, LD - 0.03), 0.06);
  car = min(car, sdRoundBox(c - vec3(0.0, 0.055, 0.0), vec3(LW + 0.05, 0.055, LD + 0.05), 0.04));
  car = min(car, sdRoundBox(c - vec3(0.0, LH - 0.05, 0.0), vec3(LW + 0.07, 0.06, LD + 0.07), 0.045));
  // reeds down the sides
  if (abs(abs(c.x) - LW + 0.03) - 0.03 < car) {
    vec3 q = vec3(abs(c.x) - LW + 0.03, c.y, c.z - 0.12 * clamp(round(c.z / 0.12), -3.0, 3.0));
    car = smin(car, sdCapsule(q, vec3(0.0, 0.16, 0.0), vec3(0.0, LH - 0.16, 0.0), 0.026), 0.012);
  }
  // the folding gate on the front: diagonal bars crossing between upright stiles
  if (abs(c.z - LD + 0.03) - 0.04 < car) {
    vec2 g = c.xy - vec2(0.0, 0.14);
    float s = 0.26;
    float u = g.x + g.y * 0.62, v = g.x - g.y * 0.62;
    float lat = min(abs(fract(u / s + 0.5) - 0.5), abs(fract(v / s + 0.5) - 0.5)) * s * 0.85;
    float gate = length(vec2(lat, c.z - LD + 0.03)) - 0.02;
    gate = max(gate, sdRoundRect(c.xy - vec2(0.0, 0.5 * LH), vec2(LW - 0.1, 0.5 * LH - 0.17), 0.02));
    vec3 q = vec3(c.x - 0.26 * clamp(round(c.x / 0.26), -2.0, 2.0), c.y, c.z - LD + 0.03);
    gate = min(gate, sdCapsule(q, vec3(0.0, 0.16, 0.0), vec3(0.0, LH - 0.16, 0.0), 0.024));
    car = smin(car, gate, 0.012);
  }
  // the floor dial on the roof: a half disc, its ticks and the needle
  if (LH + 0.01 - c.y < car) {
    vec3 dc = c - vec3(0.0, LH + 0.01, 0.1);
    float disc = extrude(max(length(dc.xy) - 0.36, -dc.y), dc.z, 0.04) - 0.014;
    float ta = atan(dc.y, dc.x);
    float tk = clamp(round((ta - 0.39) / 0.59), 0.0, 4.0) * 0.59 + 0.39;
    float ticks = length(dc - vec3(cos(tk) * 0.285, sin(tk) * 0.285, 0.055)) - 0.026;
    car = min(car, smin(disc, ticks, 0.02));
  }
  return car;
}
// The scooter's body and box, in the body's own space (the wheels turn, so they aren't here).
float scooterBody(vec3 b) {
  float deck = sdRoundBox(b - vec3(0.05, 0.38, 0.0), vec3(0.42, 0.055, 0.2), 0.05);
  float rear = sdRoundBox(b - vec3(-0.66, 0.64, 0.0), vec3(0.42, 0.2, 0.21), 0.17);
  float seat = sdRoundBox(b - vec3(-0.55, 0.9, 0.0), vec3(0.36, 0.055, 0.17), 0.055);
  vec3 sh = b - vec3(0.5, 0.78, 0.0);
  sh.xy = rot(sh.xy, 0.25);
  float shield = sdRoundBox(sh, vec3(0.07, 0.38, 0.21), 0.07);
  float column = sdCapsule(b, vec3(0.64, 0.45, 0.0), vec3(0.5, 1.33, 0.0), 0.07);
  float bar = sdCapsule(b, vec3(0.5, 1.36, -0.33), vec3(0.5, 1.36, 0.33), 0.05);
  float lamp = length(b - vec3(0.6, 1.14, 0.0)) - 0.09;
  float box = sdRoundBox(b - vec3(-0.8, 1.3, 0.0), vec3(0.34, 0.3, 0.3), 0.07);
  float pipe = sdCapsule(b, vec3(-0.4, 0.25, 0.19), vec3(-1.06, 0.31, 0.19), 0.036);
  float body = smin(smin(deck, rear, 0.12), seat, 0.05);
  body = smin(body, smin(shield, min(column, lamp), 0.06), 0.1);
  body = smin(body, box, 0.05);
  body = smin(body, pipe, 0.04);
  return min(body, bar);
}
// The map pin alone: flat, like the icon (a ball would lens the whole room), with its hole.
float pinOnly(vec3 c) {
  c.z *= 2.2;
  float pin = smin(sdRoundCone(c - vec3(0.0, 0.17, 0.0), 0.02, 0.34, 1.25), length(c - vec3(0.0, 1.5, 0.0)) - 0.43, 0.08);
  pin = smax(pin, -sdCylZ(c - vec3(0.0, 1.5, 0.0), 0.15, 1.0), 0.03);
  return pin / 2.2;
}
// The padlock's body with its keyhole (the shackle moves, so it isn't here).
float lockBody(vec3 p) {
  float body = sdRoundBox(p - vec3(0.0, 0.62, 0.0), vec3(0.6, 0.5, 0.26), 0.13);
  float hole = min(sdCylZ(p - vec3(0.0, 0.7, 0.26), 0.085, 0.1), sdRoundBox(p - vec3(0.0, 0.53, 0.26), vec3(0.035, 0.12, 0.1), 0.02));
  return smax(body, -hole, 0.015);
}
float staticPart(int s, vec3 q) {
  if (s == 1) return liftCar(q);
  if (s == 2) return scooterBody(q);
  if (s == 5) return pinOnly(q);
  return lockBody(q);
}
// Outside its volume, the distance to the volume's box is a safe step (the part sits well inside it).
float baked(int s, vec3 q) {
  int k = gSlot;
  if (uVolOn[k] == 0) return staticPart(s, q);
  vec3 lo = uVolLo[k], hi = uVolHi[k];
  vec3 c = clamp(q, lo, hi);
  float outside = length(q - c);
  vec3 uvw = (c - lo) / (hi - lo);
  float inside = k == 0 ? textureLod(uVol0, uvw, 0.0).r : textureLod(uVol1, uvw, 0.0).r;
  return outside > 0.0 ? max(outside + 0.03, inside - outside) : inside;
}

// 0. Signal bars. Heights come from uBarH; P.x: bars still alive (4 all, 1 only the smallest), for colour.
float sdBars(vec3 p, vec4 P) {
  float d = 1e5;
  for (int i = 0; i < 4; i++) {
    float h = uBarH[i];
    if (h < 0.004) continue;
    float r = min(0.085, h * 0.5);
    d = min(d, sdRoundBox(p - vec3(-0.78 + float(i) * 0.52, h * 0.5, 0.0), vec3(0.19, h * 0.5, 0.19), r));
  }
  return d;
}

// 1. An old lift car, in solid glass: a folding scissor gate across its front, reeded sides, a cornice, a floor dial
// with its needle on the roof, and the cables and rails it rides on. P.x: how high the car is, P.y and P.w: the
// needle's direction (cos, sin), P.z: rails and cables (0..1).
float sdLift(vec3 p, vec4 P) {
  float d = 1e5;
  vec3 c = p - vec3(0.0, P.x, 0.0);
  if (P.z > 0.01 && uShadowPass == 0) {
    // two guide rails, fixed to the building, and three cables that go up with the car
    d = sdRoundBox(vec3(abs(p.x) - (LW + 0.2), p.y - 7.0, p.z), vec3(0.028 * P.z, 7.0, 0.05 * P.z), 0.022 * P.z);
    vec3 k = vec3(c.x - 0.1 * clamp(round(c.x / 0.1), -1.0, 1.0), c.y - LH - 6.0, c.z + 0.04);
    d = min(d, sdCapsule(k, vec3(0.0, -6.0, 0.0), vec3(0.0, 6.0, 0.0), 0.011 * P.z));
  }
  float bound = sdBox(c - vec3(0.0, 0.5 * LH + 0.2, 0.0), vec3(LW + 0.1, 0.5 * LH + 0.34, LD + 0.1));
  if (bound > 0.12) return min(d, bound);
  float car = baked(1, c);
  // the needle, which turns as the car climbs
  if (LH + 0.01 - c.y < car) {
    vec3 dc = c - vec3(0.0, LH + 0.01, 0.1);
    car = smin(car, sdCapsule(dc, vec3(0.0, 0.03, 0.065), vec3(0.0, 0.03, 0.065) + vec3(P.y, P.w, 0.0) * 0.28, 0.018), 0.02);
  }
  return min(d, car);
}

// 2. A delivery scooter, side on, facing +x, with its box. P.x: how far the wheels have turned (rad),
// P.y: the body's bounce, P.z: lean (rad).
float sdWheel(vec3 w, float turn) {
  float tyre = sdTorusZ(w, 0.205, 0.085);
  float rim = sdTorusZ(w, 0.128, 0.02);
  float d = min(tyre, rim);
  if (max(length(w.xy) - 0.146, abs(w.z) - 0.05) >= d) return d;
  vec2 r = rot(w.xy, turn);
  float sector = 1.2566371;
  float an = atan(r.y, r.x);
  an = mod(an + 0.5 * sector, sector) - 0.5 * sector;
  vec2 sp = length(r) * vec2(cos(an), sin(an));
  float spokes = length(vec3(sp.x - clamp(sp.x, 0.04, 0.13), sp.y, w.z)) - 0.016;
  float hub = length(w) - 0.05;
  return min(d, min(spokes, hub));
}
float sdScooter(vec3 p, vec4 P) {
  vec3 w = vec3(p.x - (p.x >= 0.0 ? 0.82 : -0.82), p.y - 0.3, p.z);
  float wheels = sdWheel(w, P.x);
  vec3 b = p - vec3(0.0, P.y, 0.0);
  b.xy = rot(b.xy - vec2(-0.82, 0.3), P.z) + vec2(-0.82, 0.3);
  float bound = sdBox(b - vec3(-0.05, 0.95, 0.0), vec3(1.1, 0.75, 0.45));
  if (bound > 0.12) return min(wheels, bound);
  return min(baked(2, b), wheels);
}
`;

export const SHAPES = `
// 3. The recorded ride: one chunky column per 8 s, as tall as the signal was (uCols), standing well apart so each
// reads on its own, and a post where SANKET warned (at 63 s).
// P.x: the post (0..1 grown), P.y and P.z: the reading bead's x and height, P.w: its size.
const float GN = 12.0;
const float SP = 0.3;
const float WARN_AT = 7.5;      // in the gap between 56-64 s and 64-72 s
float sdGraph(vec3 p, vec4 P) {
  float x0 = -0.5 * SP * (GN - 1.0);
  float fi = clamp(floor((p.x - x0) / SP + 0.5), 0.0, GN - 1.0);
  float d = 1e5;
  for (int k = -1; k <= 1; k++) {
    float i = clamp(fi + float(k), 0.0, GN - 1.0);
    float h = uCols[int(i)];
    d = min(d, sdRoundBox(p - vec3(x0 + i * SP, h * 0.5, 0.0), vec3(0.1, h * 0.5, 0.13), min(0.065, h * 0.5)));
  }
  if (P.x > 0.001) {
    float top = 0.25 + 1.75 * P.x;
    vec3 base = vec3(x0 + WARN_AT * SP, 0.0, 0.0);
    d = min(d, sdCapsule(p, base, base + vec3(0.0, top, 0.0), 0.03));
    d = min(d, length(p - base - vec3(0.0, top, 0.0)) - 0.11 * P.x);
  }
  if (P.w > 0.002) d = min(d, length(p - vec3(P.y, P.z, 0.0)) - P.w);
  return d;
}

// 4. A phone standing on its edge. Its screen (drawn in the shading) shows a download.
const vec2 SCREEN_H = vec2(0.43, 0.94);
const float PHONE_Y = 1.03;
float sdPhone(vec3 p, vec4 P) {
  vec3 c = p - vec3(0.0, PHONE_Y, 0.0);
  float body = extrude(sdRoundRect(c.xy, vec2(0.48, 1.0), 0.15), c.z, 0.045) - 0.028;
  float screen = extrude(sdRoundRect(c.xy, SCREEN_H, 0.11), c.z - 0.075, 0.012);
  body = smax(body, -screen, 0.01);
  float cam = extrude(sdRoundRect(c.xy - vec2(-0.24, 0.74), vec2(0.15), 0.07), c.z + 0.075, 0.02) - 0.01;
  return smin(body, cam, 0.02);
}

// 5. A map pin on a folded paper map. P.x: the pin has landed (0 high .. 1 down), P.y: a ripple spreading from it.
float sdPinMap(vec3 p, vec4 P) {
  float d = 1e5;
  for (int i = 0; i < 3; i++) {
    vec3 q = p - vec3(-0.64 + float(i) * 0.64, 0.11, 0.08);
    q.xy = rot(q.xy, i == 1 ? 0.17 : -0.17);
    d = smin(d, sdRoundBox(q, vec3(0.33, 0.03, 0.66), 0.025), 0.03);
  }
  if (P.y > 0.002 && P.y < 0.998) {
    float r = 0.16 + 0.85 * P.y;
    d = min(d, sdTorusY(p - vec3(0.1, 0.2, 0.08), r, 0.026 * (1.0 - P.y)));
  }
  return min(d, baked(5, p - vec3(0.1, (1.0 - P.x) * 1.5, 0.08)));
}

// 6. A padlock with a keyhole. P.x: shackle open (0 shut .. 1 open).
float sdLock(vec3 p, vec4 P) {
  float body = baked(6, p);
  float up = 0.3 * P.x;
  vec3 s = p - vec3(0.0, 1.2 + up, 0.0);
  float ring = max(sdTorusZ(s, 0.38, 0.095), -s.y);
  float legs = min(sdCapsule(p, vec3(-0.38, 1.2 + up, 0.0), vec3(-0.38, 0.95, 0.0), 0.095),
                   sdCapsule(p, vec3(0.38, 1.2 + up, 0.0), vec3(0.38, 0.95 + up * 0.9, 0.0), 0.095));
  return smin(body, min(ring, legs), 0.03);
}

float shapeD(int s, vec3 q, vec4 P) {
  if (s == 0) return sdBars(q, P);
  if (s == 1) return sdLift(q, P);
  if (s == 2) return sdScooter(q, P);
  if (s == 3) return sdGraph(q, P);
  if (s == 4) return sdPhone(q, P);
  if (s == 5) return sdPinMap(q, P);
  return sdLock(q, P);
}

// A shape on its way into or out of its drop. The drop is a capsule (a ball when both ends meet); the shape forms
// in order along its height, so it fills like a mould, or drains from the top.
float formed(int k, vec3 p) {
  vec4 drop = uDrop[k];
  float dd = drop.w > 0.002 ? sdCapsule(p, drop.xyz, uDrop2[k], drop.w) : 1e5;
  float form = uForm[k];
  if (form <= 0.001) return dd;
  vec3 q = (uTo[k] * vec4(p, 1.0)).xyz;
  gSlot = k;
  float ds = shapeD(uShape[k], q, uPar[k]);
  if (form >= 0.999) return ds;
  vec4 sw = uSweep[k];
  float h = clamp((q.y - sw.x) * sw.y, 0.0, 1.0);
  if (sw.w < 0.0) h = 1.0 - h;
  float m = ease(form * (1.0 + sw.z) - sw.z * h);
  return mix(dd, ds, m);
}

float streamD(vec3 p) {
  if (uStreamN < 1) return 1e5;
  vec3 c = 0.5 * (uStreamLo + uStreamHi);
  float bound = sdBox(p - c, 0.5 * (uStreamHi - uStreamLo));
  if (bound > 0.3) return bound;
  if (uStreamN == 1) return length(p - uStream[0].xyz) - uStream[0].w;
  float d = 1e5;
  for (int c = uZero; c < 3; c++) {
    float b = sdBox(p - 0.5 * (uStreamCLo[c] + uStreamCHi[c]), 0.5 * (uStreamCHi[c] - uStreamCLo[c]));
    if (b > d) continue;
    for (int j = 0; j < 8; j++) {
      int i = c * 8 + j;
      if (i + 1 >= uStreamN) break;
      d = min(d, sdCone2(p, uStream[i].xyz, uStream[i + 1].xyz, uStream[i].w, uStream[i + 1].w));
    }
  }
  return d;
}

void parts(vec3 p, out float a, out float b, out float s) {
  float r[2];
  r[0] = 1e5;
  r[1] = 1e5;
  for (int k = uZero; k < 2; k++) {
    if (uShape[k] >= 0) r[k] = formed(k, p);
  }
  a = r[0];
  b = r[1];
  s = streamD(p);
}

int gEvals = 0;   // how many times the glass was measured for this pixel (the debug heat view)
float map(vec3 p) {
  gEvals++;
  float a, b, s;
  parts(p, a, b, s);
  float d = smin(smin(a, b, 0.24), s, 0.2);
  // the liquid ripples while it moves, and where it was touched
  if (uWobble > 0.001) {
    d += uWobble * 0.03 * sin(6.3 * p.x + 2.1 * uTime) * sin(5.7 * p.y - 1.7 * uTime) * sin(6.9 * p.z + 1.3 * uTime);
  }
  if (uTap.w >= 0.0 && uTap.w < 2.4) {
    float r = length(p - uTap.xyz);
    d += 0.022 * exp(-2.4 * uTap.w) * exp(-1.8 * r) * sin(17.0 * r - 13.0 * uTap.w);
  }
  return d;
}

vec3 normalAt(vec3 p, float e) {
  vec3 n = vec3(0.0);
  for (int i = uZero; i < 4; i++) {
    vec3 k = 2.0 * vec3(float(((i + 3) >> 1) & 1), float((i >> 1) & 1), float(i & 1)) - 1.0;
    n += k * map(p + e * k);
  }
  return normalize(n);
}

// ---- colour ----

// How the glass is tinted: how strongly it absorbs red, green and blue per unit of thickness.
const vec3 CLEAR = vec3(0.30, 0.19, 0.15);
const vec3 GREEN = vec3(1.55, 0.26, 1.05);
const vec3 AMBER = vec3(0.16, 0.62, 2.3);
const vec3 ORANGE = vec3(0.1, 1.15, 2.45);
const vec3 RED = vec3(0.1, 2.1, 1.95);

vec3 signalTint(float f) {
  f = clamp(f, 0.0, 1.0);
  if (f > 0.66) return mix(AMBER, GREEN, (f - 0.66) / 0.34);
  if (f > 0.4) return mix(ORANGE, AMBER, (f - 0.4) / 0.26);
  return mix(RED, ORANGE, f / 0.4);
}

vec3 tintOf(int s, vec3 q, vec4 P) {
  if (s == 0) {
    float i = clamp(floor((q.x + 0.78) / 0.52 + 0.5), 0.0, 3.0);
    return mix(CLEAR, signalTint((P.x - 0.6) / 3.4), ease(P.x - i));
  }
  if (s == 1) return q.y > P.x + LH + 0.02 && abs(q.x) < 0.4 ? AMBER : CLEAR;
  if (s == 2) {
    vec3 b = q - vec3(0.0, P.y, 0.0);
    return (b.y > 0.98 && b.x < -0.44) ? ORANGE : CLEAR;
  }
  if (s == 3) {
    float x0 = -0.5 * SP * (GN - 1.0);
    float dx = abs(q.x - (x0 + WARN_AT * SP));
    if (dx < 0.045 || (q.y > 1.85 && dx < 0.13)) return AMBER;   // the post and its bead only
    float i = clamp(floor((q.x - x0) / SP + 0.5), 0.0, GN - 1.0);
    return 0.62 * signalTint((uColsTint[int(i)] - 0.1) / 1.5);
  }
  if (s == 4) return CLEAR * 1.2;
  if (s == 5) return q.y > 0.3 ? RED : CLEAR * 0.7;
  return GREEN * 0.8;
}

// Light of its own: the dying bar's red, the lift's needle, the scooter's lamp, the reading bead, the keyhole.
vec3 glowOf(int s, vec3 q, vec4 P) {
  if (s == 0) {
    float dying = 1.0 - smoothstep(1.2, 1.9, P.x);
    if (dying <= 0.0 || q.x > -0.5) return vec3(0.0);
    float f = 0.55 + 0.45 * sin(uTime * 7.3) * sin(uTime * 2.9 + 1.0);
    return vec3(1.0, 0.16, 0.08) * 0.16 * dying * f;
  }
  if (s == 1) {
    vec3 dc = q - vec3(0.0, P.x + LH + 0.01, 0.1);
    float tip = length(dc - vec3(P.y * 0.28, 0.03 + P.w * 0.28, 0.065));
    return vec3(1.0, 0.62, 0.18) * 0.35 * exp(-tip * tip * 120.0);
  }
  if (s == 2) {
    vec3 b = q - vec3(0.0, P.y, 0.0);
    float l = length(b - vec3(0.62, 1.14, 0.0));
    return vec3(1.0, 0.93, 0.78) * 0.5 * exp(-l * l * 160.0);
  }
  if (s == 3) {
    vec3 g = vec3(0.0);
    if (P.w > 0.002) {
      float b = length(q - vec3(P.y, P.z, 0.0));
      g += vec3(0.75, 1.0, 0.8) * 0.45 * exp(-b * b * 90.0);
    }
    float dx = abs(q.x - (-0.5 * SP * (GN - 1.0) + WARN_AT * SP));
    if (dx < 0.045 || (q.y > 1.85 && dx < 0.13)) g += vec3(1.0, 0.65, 0.15) * 0.12 * (0.6 + 0.4 * sin(uTime * 3.2));
    return g;
  }
  if (s == 6) {
    float k = length(q.xy - vec2(0.0, 0.66));
    return vec3(0.3, 1.0, 0.55) * 0.22 * exp(-k * k * 60.0) * step(0.15, q.z);
  }
  return vec3(0.0);
}
`;

export const RAYS = `
vec2 pack16(float t) {
  float v = floor(clamp(t / 64.0, 0.0, 1.0) * 65535.0);
  return vec2(floor(v / 256.0), mod(v, 256.0)) / 255.0;
}
float unpack16(vec2 v) { return (floor(v.x * 255.0 + 0.5) * 256.0 + floor(v.y * 255.0 + 0.5)) / 65535.0 * 64.0; }
vec3 rayDir(vec2 ndc) {
  vec2 v = ndc - uShift;
  return normalize(uFwd + v.x * uAspect * uTanHalf * uRight + v.y * uTanHalf * uUp);
}

bool boxRange(vec3 ro, vec3 rd, vec3 lo, vec3 hi, out float t0, out float t1) {
  vec3 inv = 1.0 / (rd + vec3(equal(rd, vec3(0.0))) * 1e-7);
  vec3 a = (lo - ro) * inv, b = (hi - ro) * inv;
  vec3 mn = min(a, b), mx = max(a, b);
  t0 = max(max(max(mn.x, mn.y), mn.z), 0.0);
  t1 = min(min(mx.x, mx.y), mx.z);
  return t1 > t0;
}
`;

export const LIGHTING = `
uniform vec3 uTintS;        // the travelling liquid's tint
uniform int uInner;
uniform int uDisp;

const float IOR = 1.47;

// A rounded-rectangle light in direction c, as seen in direction d (1 inside, 0 outside).
float softbox(vec3 d, vec3 c, vec2 h, float soft) {
  float z = dot(d, c);
  if (z <= 0.05) return 0.0;
  vec3 r = normalize(cross(vec3(0.0, 1.0, 0.0), c));
  vec3 u = cross(c, r);
  vec2 q = vec2(dot(d, r), dot(d, u)) / z;
  return 1.0 - smoothstep(-soft, soft, sdRoundRect(q, h, min(h.x, h.y) * 0.7));
}

// The studio the glass reflects: warm paper all round, a big soft box above left, a strip on the right, a line of
// light behind, and dark cards at the sides that give glass its dark edges, as in product photographs.
vec3 studio(vec3 d) {
  vec3 c = mix(PAPER * 0.74, PAPER * 1.04, smoothstep(-0.3, 0.35, d.y));
  // dark cards at both sides and a dark flag overhead
  float flag = smoothstep(0.2, 0.9, abs(d.x)) * (1.0 - smoothstep(0.2, 0.9, abs(d.y)));
  c *= 1.0 - 0.72 * flag;
  c *= 1.0 - 0.55 * smoothstep(0.82, 0.98, d.y) * (1.0 - softbox(d, LIGHT, vec2(0.5, 0.34), 0.1));
  c *= 1.0 - 0.3 * smoothstep(0.4, 1.0, -d.z) * (1.0 - smoothstep(0.1, 0.7, d.y));
  c += vec3(1.0, 0.97, 0.92) * 6.0 * softbox(d, LIGHT, vec2(0.42, 0.27), 0.05);
  c += vec3(0.95, 0.97, 1.0) * 3.0 * softbox(d, FILL, vec2(0.07, 0.45), 0.03);
  c += vec3(1.0, 0.98, 0.95) * 2.0 * softbox(d, vec3(0.1414, 0.3298, -0.9334), vec2(0.7, 0.045), 0.025);
  return c;
}

vec3 behind(vec3 o, vec3 d) {
  if (d.y < -0.002) return paper(o + d * (-o.y / d.y));
  return studio(d);
}

float seg2(vec2 p, vec2 a, vec2 b) {
  vec2 pa = p - a, ba = b - a;
  return length(pa - ba * clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0));
}

// The phone's screen: a ring fills as the file comes in; when the signal drops it turns amber and waits, then
// carries on, and a tick shows when it's done. P.x progress, P.y waiting (0/1), P.w done (0..1).
vec3 phoneScreen(vec2 s, vec4 P) {
  float aa = 0.007;
  vec3 col = vec3(0.012, 0.016, 0.022);
  vec3 acc = mix(vec3(0.2, 0.85, 0.45), vec3(1.0, 0.62, 0.12), P.y);
  vec2 rc = s - vec2(0.0, 0.16);
  float ring = abs(length(rc) - 0.25) - 0.026;
  float a = atan(rc.x, rc.y);
  float frac = (a < 0.0 ? a + 6.2831853 : a) / 6.2831853;
  float on = 1.0 - smoothstep(P.x - 0.004, P.x + 0.004, frac);
  col = mix(col, mix(vec3(0.07, 0.08, 0.1), acc, on), 1.0 - smoothstep(-aa, aa, ring));
  float arrow = min(seg2(s, vec2(0.0, 0.27), vec2(0.0, 0.06)),
                    min(seg2(s, vec2(-0.075, 0.135), vec2(0.0, 0.06)), seg2(s, vec2(0.075, 0.135), vec2(0.0, 0.06)))) - 0.02;
  float tick = min(seg2(s, vec2(-0.09, 0.16), vec2(-0.025, 0.095)), seg2(s, vec2(-0.025, 0.095), vec2(0.1, 0.23))) - 0.022;
  float icon = mix(arrow, tick, step(0.5, P.w));
  col = mix(col, acc, 1.0 - smoothstep(-aa, aa, icon));
  float lines = min(sdRoundRect(s - vec2(0.0, -0.3), vec2(0.25, 0.018), 0.018), sdRoundRect(s - vec2(-0.08, -0.4), vec2(0.17, 0.018), 0.018));
  col = mix(col, vec3(0.16, 0.17, 0.2), 1.0 - smoothstep(-aa, aa, lines));
  float track = sdRoundRect(s - vec2(0.0, -0.58), vec2(0.3, 0.014), 0.014);
  col = mix(col, vec3(0.08, 0.09, 0.11), 1.0 - smoothstep(-aa, aa, track));
  float fill = max(track, s.x - (-0.3 + 0.6 * P.x));
  col = mix(col, acc, 1.0 - smoothstep(-aa, aa, fill));
  for (int i = 0; i < 4; i++) {
    float fi = float(i);
    float h = 0.016 + fi * 0.012;
    float bar = sdRoundRect(s - vec2(0.2 + fi * 0.04, 0.8 + h), vec2(0.012, h), 0.006);
    float lit = P.y > 0.5 ? step(fi, 0.5) : 1.0;
    col = mix(col, mix(vec3(0.15, 0.16, 0.18), P.y > 0.5 ? vec3(1.0, 0.35, 0.2) : vec3(0.85), lit), 1.0 - smoothstep(-aa, aa, bar));
  }
  return col;
}

// How much of a pixel is the phone's lit screen, and its colour.
float screenOf(int k, vec3 p, vec3 n, out vec3 col) {
  col = vec3(0.0);
  if (uShape[k] != 4 || uForm[k] < 0.98) return 0.0;
  vec3 q = (uTo[k] * vec4(p, 1.0)).xyz;
  vec3 nq = mat3(uTo[k]) * n;
  vec2 s = q.xy - vec2(0.0, PHONE_Y);
  if (nq.z < 0.5 || q.z < 0.0) return 0.0;
  float e = sdRoundRect(s, SCREEN_H - 0.01, 0.1);
  if (e > 0.0) return 0.0;
  col = phoneScreen(s, uPar[k]);
  return 1.0 - smoothstep(-0.012, 0.0, e);
}

// Which glass is at p: the shape leaving (x), the shape forming (y) or the liquid between; its tint and own light.
vec2 owners(vec3 p, out vec3 absorb, out vec3 glow) {
  float wa = 1.0, wb = 0.0, ws = 0.0;
  if (uShape[1] >= 0 || uStreamN > 0 || uForm[0] < 0.999) {
    float a, b, s;
    parts(p, a, b, s);
    wa = exp(-max(a, 0.0) * 40.0); wb = exp(-max(b, 0.0) * 40.0); ws = exp(-max(s, 0.0) * 40.0);
    float sum = wa + wb + ws + 1e-6;
    wa /= sum; wb /= sum; ws /= sum;
  }
  absorb = ws * uTintS;
  glow = vec3(0.0);
  float w2[2];
  w2[0] = wa;
  w2[1] = wb;
  for (int k = uZero; k < 2; k++) {
    if (uShape[k] < 0 || w2[k] <= 0.001) continue;
    vec3 q = (uTo[k] * vec4(p, 1.0)).xyz;
    absorb += w2[k] * mix(uTintS, tintOf(uShape[k], q, uPar[k]), uForm[k]);
    glow += w2[k] * uForm[k] * glowOf(uShape[k], q, uPar[k]);
  }
  return vec2(wa, wb);
}

// What's seen through the glass entering at p: into it, across it and out again, tinted by what it crossed.
vec3 transmit(vec3 p, vec3 rd, vec3 n, vec3 absorb) {
  vec3 t1 = refract(rd, n, 1.0 / IOR);
  vec3 x = p - n * 0.004;
  float travel = 0.0;
  for (int i = 0; i < 64; i++) {
    if (i >= uInner) break;
    float d = -map(x);
    if (d < 0.0015) break;
    float st = max(d, 0.035 + 0.06 * travel);
    x += t1 * st;
    travel += st;
    if (travel > 3.2) break;
  }
  vec3 n2 = normalAt(x, 0.002);
  vec3 o = x + n2 * 0.004;
  vec3 inner = studio(reflect(t1, -n2)) * 0.62;
  vec3 eG = refract(t1, -n2, IOR);
  vec3 trans;
  if (uDisp > 0) {
    vec3 eR = refract(t1, -n2, IOR - 0.02);
    vec3 eB = refract(t1, -n2, IOR + 0.03);
    trans.r = dot(eR, eR) > 0.0 ? behind(o, eR).r : inner.r;
    trans.g = dot(eG, eG) > 0.0 ? behind(o, eG).g : inner.g;
    trans.b = dot(eB, eB) > 0.0 ? behind(o, eB).b : inner.b;
  } else {
    trans = dot(eG, eG) > 0.0 ? behind(o, eG) : inner;
  }
  return trans * exp(-absorb * travel);
}

// The surface over what's seen through it: reflections, the phone's lit screen, and light of its own.
vec3 surface(vec3 p, vec3 rd, vec3 n, vec3 trans, vec2 w, vec3 glow) {
  float cosi = clamp(dot(-rd, n), 0.0, 1.0);
  float fres = 0.04 + 0.96 * pow(1.0 - cosi, 5.0);
  vec3 refl = studio(reflect(rd, n));
  vec3 sc = vec3(0.0);
  float scr = 0.0;
  for (int k = uZero; k < 2; k++) {
    vec3 c2;
    float m = screenOf(k, p, n, c2) * (k == 0 ? w.x : w.y);
    if (m > scr) { scr = m; sc = c2; }
  }
  trans = mix(trans, sc, scr);
  return mix(trans, refl, fres) + glow;
}

// Half-resolution results are kept as sRGB of half their value, so 8 bits are enough.
vec3 encodeT(vec3 c) { return toSrgb(c * 0.5); }
vec3 decodeT(vec3 e) { return 2.0 * mix(e / 12.92, pow((e + 0.055) / 1.055, vec3(2.4)), step(0.04045, e)); }

`;

// Pass 1: the glass's shadow on the paper, seen from the light.
export const SHADOW_MAIN = `
uniform vec4 uShadowRect;
uniform vec2 uShadowRes;
uniform int uShadowSteps;
out vec4 outColor;
void main() {
  vec2 st = gl_FragCoord.xy / uShadowRes;
  vec2 xz = uShadowRect.xy + st / uShadowRect.zw;
  vec3 f = vec3(xz.x, 0.002, xz.y);
  float res = 1.0;
  float t0, t1;
  if (boxRange(f, LIGHT, uLo, uHi, t0, t1)) {
    float t = max(t0, 0.03);
    for (int i = 0; i < 48; i++) {
      if (i >= uShadowSteps) break;
      float h = map(f + LIGHT * t);
      res = min(res, 16.0 * h / t);
      t += clamp(h, 0.025, 0.22);
      if (res < -0.1 || t > t1) break;
    }
  }
  res = clamp(res, 0.0, 1.0);
  res = res * res * (3.0 - 2.0 * res);
  float ao = clamp(map(f + vec3(0.0, 0.08, 0.0)) / 0.08, 0.0, 1.0);
  outColor = vec4(res, ao, 0.0, 1.0);
}`;

// Pass 2: at a third of the resolution, how far along each ray the glass first comes near (two bytes).
export const NEAR_MAIN = `
uniform vec2 uNearRes;
uniform float uPixNear;
uniform int uSteps;
out vec4 outColor;
void main() {
  outColor = vec4(0.0);
  vec3 rd = rayDir(gl_FragCoord.xy / uNearRes * 2.0 - 1.0);
  float t0, t1;
  if (!boxRange(uCam, rd, uLo, uHi, t0, t1)) return;
  float t = t0;
  for (int i = 0; i < 160; i++) {
    if (i >= uSteps) break;
    float d = map(uCam + rd * t);
    float cone = uPixNear * t * 1.6;
    if (d < cone) {
      outColor = vec4(pack16(max(t0, t - 2.0 * cone)), 1.0, 1.0);
      return;
    }
    t += d * 0.85;
    if (t > t1) return;
  }
}`;

// The paper and what's drawn on it: the glass's shadow, and the scooter's smoke. Shared by the paper and glass passes.
export const PAPERLIB = `
uniform sampler2D uShadow;
uniform vec4 uShadowRect;   // x0, z0, 1 / width, 1 / depth: the paper the shadow texture covers
uniform vec3 uShadeTint;    // the light that gets through the glass, for the bright middle of its shadow
uniform float uShadeAmt;    // 0 when there is no glass to cast a shadow
uniform int uSmokeN;
uniform vec4 uSmoke[12];
uniform float uSmokeA[12];

vec4 shadowAt(vec2 xz) {
  vec2 st = (xz - uShadowRect.xy) * uShadowRect.zw;
  if (uShadeAmt <= 0.0 || st.x < 0.0 || st.y < 0.0 || st.x > 1.0 || st.y > 1.0) return vec4(1.0, 1.0, 0.0, 0.0);
  vec4 s = texture(uShadow, st);
  float e = min(min(st.x, 1.0 - st.x), min(st.y, 1.0 - st.y));
  return mix(vec4(1.0, 1.0, 0.0, 0.0), s, smoothstep(0.0, 0.14, e));
}

// The paper: the glass's shadow is light, tinted, rimmed, and brighter in its middle where the glass focuses light.
vec3 paper(vec3 f) {
  vec4 s = shadowAt(f.xz);
  float dark = smoothstep(0.04, 1.0, 1.0 - s.r) * uShadeAmt;
  float rim = dark * (1.0 - dark) * 4.0;
  float core = smoothstep(0.75, 1.0, dark);
  vec3 c = PAPER * (1.0 - dark * 0.3 * (vec3(1.0) - 0.4 * (vec3(1.0) - uShadeTint)) - 0.05 * rim);
  c += core * 0.12 * uShadeTint * PAPER;
  c *= 1.0 - 0.2 * smoothstep(0.05, 1.0, 1.0 - s.g) * uShadeAmt;
  return c;
}

// Exhaust smoke: soft puffs, lit from the light's side, in front of anything farther than tMax.
vec3 smoke(vec3 col, vec3 rd, float tMax) {
  for (int i = 0; i < 12; i++) {
    if (i >= uSmokeN) break;
    vec3 oc = uSmoke[i].xyz - uCam;
    float b = dot(oc, rd);
    float r = uSmoke[i].w;
    float h2 = dot(oc, oc) - b * b;
    if (b <= 0.0 || h2 >= r * r || b - r > tMax) continue;
    float k = 1.0 - h2 / (r * r);
    vec3 nrm = normalize(uCam + rd * b - uSmoke[i].xyz);
    float lit = 0.5 + 0.5 * dot(nrm, LIGHT);
    col = mix(col, mix(vec3(0.5, 0.49, 0.47), vec3(0.95, 0.94, 0.92), lit), clamp(k * uSmokeA[i] * 0.85, 0.0, 1.0));
  }
  return col;
}

// Paper stays exactly paper; only highlights above it are rolled off.
vec3 tone(vec3 c) {
  vec3 k = vec3(0.9);
  return mix(c, k + (1.0 - k) * (1.0 - exp(-(c - k) / (1.0 - k))), step(k, c));
}
vec3 toSrgb(vec3 c) {
  c = clamp(c, 0.0, 1.0);
  return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}
`;

// The paper pass's own small header, so plain paper is drawn by a small, fast shader.
export const PAPER_HEAD = `#version 300 es
precision highp float;
precision highp int;
uniform vec3 uCam;
uniform vec3 uFwd;
uniform vec3 uRight;
uniform vec3 uUp;
uniform float uTanHalf;
uniform float uAspect;
uniform vec2 uShift;
const vec3 LIGHT = vec3(-0.3939, 0.8371, 0.3802);
const vec3 PAPER = vec3(0.896, 0.8714, 0.807);
vec3 rayDir(vec2 ndc) {
  vec2 v = ndc - uShift;
  return normalize(uFwd + v.x * uAspect * uTanHalf * uRight + v.y * uTanHalf * uUp);
}
`;

// Pass 3: the paper, everywhere.
export const PAPER_MAIN = `
uniform vec2 uRes;
out vec4 outColor;
void main() {
  vec3 rd = rayDir(gl_FragCoord.xy / uRes * 2.0 - 1.0);
  vec3 col = rd.y < -0.0005 ? paper(uCam + rd * (-uCam.y / rd.y)) : PAPER;
  col = smoke(col, rd, 1e9);
  // The page's own paper shows through where nothing marks it, so the canvas has no edge anywhere.
  float a = smoothstep(0.002, 0.01, length(col - PAPER));
  outColor = vec4(toSrgb(tone(col)) * a, a);
}`;

// Pass 4: marks (in the stencil) the pixels the coarse pass found glass near, so the glass pass runs only there.
export const MASK = `#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uNear;
uniform vec2 uNearRes;
uniform vec2 uRes;
out vec4 outColor;
void main() {
  ivec2 c = ivec2(floor(gl_FragCoord.xy * uNearRes / uRes));
  ivec2 hi = ivec2(uNearRes) - 1;
  float near = 0.0;
  for (int j = -1; j <= 1; j++) {
    for (int i = -1; i <= 1; i++) near = max(near, texelFetch(uNear, clamp(c + ivec2(i, j), ivec2(0), hi), 0).b);
  }
  if (near < 0.5) discard;
  outColor = vec4(0.0);
}`;

// Pass 5: the glass, at full resolution, on the marked pixels. Each ray finishes from where the coarse pass found
// glass near it; its edge is softened over one pixel, and it's laid over the paper by how much of the pixel it covers.
export const GLASS_MAIN = `
uniform vec2 uRes;
uniform sampler2D uNear;
uniform vec2 uNearRes;
uniform float uPix;
uniform int uRefine;
uniform int uDebug;
out vec4 outColor;

void main() {
  if (uDebug == 2) { outColor = vec4(1.0, 0.0, 1.0, 1.0); return; }
  vec2 ndc = gl_FragCoord.xy / uRes * 2.0 - 1.0;
  vec3 rd = rayDir(ndc);
  ivec2 hi = ivec2(uNearRes) - 1;
  ivec2 c = ivec2(floor(gl_FragCoord.xy * uNearRes / uRes));
  float ts = 1e9;
  for (int j = -1; j <= 1; j++) {
    for (int i = -1; i <= 1; i++) {
      vec4 v = texelFetch(uNear, clamp(c + ivec2(i, j), ivec2(0), hi), 0);
      if (v.b > 0.5) ts = min(ts, unpack16(v.rg));
    }
  }
  float t0, t1;
  if (ts > 1e8 || !boxRange(uCam, rd, uLo, uHi, t0, t1)) discard;
  float t = max(ts, t0);
  float best = 1e9, tb = t;
  bool hit = false;
  for (int i = 0; i < 96; i++) {
    if (i >= uRefine) break;
    float d = map(uCam + rd * t);
    float fp = uPix * t;
    if (d < 0.25 * fp) { hit = true; tb = t; break; }
    if (d / fp < best) { best = d / fp; tb = t; }
    t += d * 0.8;
    if (t > t1) break;
  }
  float cover = hit ? 1.0 : 1.0 - smoothstep(0.25, 1.0, best);
  if (cover <= 0.0) discard;
  vec3 p = uCam + rd * tb;
  vec3 n = normalAt(p, max(0.0008, 0.5 * uPix * tb));
  vec3 absorb, glow;
  vec2 w = owners(p, absorb, glow);
  vec3 g = surface(p, rd, n, transmit(p, rd, n, absorb), w, glow);
  g = smoke(g, rd, tb);
  outColor = vec4(toSrgb(tone(g)) * cover, cover);
  if (uDebug == 1) {
    float h = float(gEvals) / 160.0;
    outColor = vec4(clamp(vec3(h * 3.0, h * 3.0 - 1.0, h * 3.0 - 2.0), 0.0, 1.0), 1.0);
  }
}`;

// Measuring a volume: one slice at a time, the unmoving part of a shape at every point of a grid.
export const BAKE_MAIN = `
uniform int uBakeShape;
uniform vec3 uBakeLo;
uniform vec3 uBakeHi;
uniform vec2 uBakeRes;
uniform float uBakeZ;
out vec4 outColor;
void main() {
  vec3 q = mix(uBakeLo, uBakeHi, vec3(gl_FragCoord.xy / uBakeRes, uBakeZ));
  outColor = vec4(staticPart(uBakeShape, q), 0.0, 0.0, 1.0);
}`;
