// Replays data/golden/sanket_golden.json through the JavaScript port and compares every tick
// with the Python reference: alarms and measurement counts must match exactly, filter states to 1e-6.
// Usage: node web/verify_golden.js [path/to/golden.json] [path/to/z_table.json]
"use strict";
const fs = require("fs");
const path = require("path");
const { SanketDetector, invNormal } = require("./sanket.js");

const goldenPath = process.argv[2] || path.join(__dirname, "..", "data", "golden", "sanket_golden.json");
const g = JSON.parse(fs.readFileSync(goldenPath, "utf8"));
let ticks = 0, alarms = 0, bad = 0;
const close = (a, b) => Math.abs(a - b) <= 1e-6 * Math.max(1, Math.abs(b));
for (const tr of g.traces) {
  const det = new SanketDetector(g.params);
  for (const [t, r, q, nb, alarm, n, level, rate, p00, p01, p11] of tr.ticks) {
    const a = det.step(t, r, q, nb);
    ticks++; if (a) alarms++;
    const k = det.kf;
    if (a !== alarm || det.nMeas !== n || !close(k.level, level) || !close(k.rate, rate) ||
        !close(k.p00, p00) || !close(k.p01, p01) || !close(k.p11, p11)) {
      if (bad < 5) console.error(`mismatch in ${tr.name} at t=${t}: js alarm=${a} n=${det.nMeas} level=${k.level} vs py ${alarm} ${n} ${level}`);
      bad++;
    }
  }
}
if (process.argv[3]) {
  // entries: [p, z_python, relative tolerance]; tolerance 0 means bit-identical
  for (const [p, z, tol] of JSON.parse(fs.readFileSync(process.argv[3], "utf8"))) {
    const j = invNormal(p);
    const ok = tol === 0 ? j === z : Math.abs(j - z) <= tol * Math.max(1, Math.abs(z));
    if (!ok) { console.error(`invNormal(${p}) = ${j} but Python gives ${z}`); bad++; }
  }
}
console.log(`${g.traces.length} traces, ${ticks} ticks, ${alarms} alarms, ${bad} mismatches`);
process.exit(bad ? 1 : 0);
