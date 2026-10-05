/* SANKET detector — JavaScript port of sanket/core.py (SanketDetector + ThresholdDetector).
 * Same floating-point operations in the same order as the Python reference; verified against
 * data/golden/sanket_golden.json by web/verify_golden.js. Works in the browser and in Node. */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.Sanket = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";
  const RSRP_MIN = -140, RSRP_MAX = -43, RSRQ_MIN = -34, RSRQ_MAX = 3;
  const UNAVAILABLE = 0, STALE = 1, NEW = 2, FIRST = 3;

  const finite = (x) => typeof x === "number" && Number.isFinite(x);
  const validRsrp = (x) => finite(x) && x >= RSRP_MIN && x <= RSRP_MAX;
  const validRsrq = (x) => finite(x) && x >= RSRQ_MIN && x <= RSRQ_MAX;

  // Wichura AS241, exactly as CPython's statistics.NormalDist.inv_cdf
  function invNormal(p) {
    if (!(p > 0 && p < 1)) throw new RangeError("probability must be in (0, 1)");
    const q = p - 0.5;
    let r, num, den, x;
    if (Math.abs(q) <= 0.425) {
      r = 0.180625 - q * q;
      num = (((((((2.5090809287301226727e+3 * r + 3.3430575583588128105e+4) * r + 6.7265770927008700853e+4) * r +
        4.5921953931549871457e+4) * r + 1.3731693765509461125e+4) * r + 1.9715909503065514427e+3) * r +
        1.3314166789178437745e+2) * r + 3.3871328727963666080e+0) * q;
      den = (((((((5.2264952788528545610e+3 * r + 2.8729085735721942674e+4) * r + 3.9307895800092710610e+4) * r +
        2.1213794301586595867e+4) * r + 5.3941960214247511077e+3) * r + 6.8718700749205790830e+2) * r +
        4.2313330701600911252e+1) * r + 1.0);
      x = num / den;
      return 0.0 + (x * 1.0);
    }
    r = q <= 0.0 ? p : 1.0 - p;
    r = Math.sqrt(-Math.log(r));
    if (r <= 5.0) {
      r = r - 1.6;
      num = (((((((7.74545014278341407640e-4 * r + 2.27238449892691845833e-2) * r + 2.41780725177450611770e-1) * r +
        1.27045825245236838258e+0) * r + 3.64784832476320460504e+0) * r + 5.76949722146069140550e+0) * r +
        4.63033784615654529590e+0) * r + 1.42343711074968357734e+0);
      den = (((((((1.05075007164441684324e-9 * r + 5.47593808499534494600e-4) * r + 1.51986665636164571966e-2) * r +
        1.48103976427480074590e-1) * r + 6.89767334985100004550e-1) * r + 1.67638483018380384940e+0) * r +
        2.05319162663775882187e+0) * r + 1.0);
    } else {
      r = r - 5.0;
      num = (((((((2.01033439929228813265e-7 * r + 2.71155556874348757815e-5) * r + 1.24266094738807843860e-3) * r +
        2.65321895265761230930e-2) * r + 2.96560571828504891230e-1) * r + 1.78482653991729133580e+0) * r +
        5.46378491116411436990e+0) * r + 6.65790464350110377720e+0);
      den = (((((((2.04426310338993978564e-15 * r + 1.42151175831644588870e-7) * r + 1.84631831751005468180e-5) * r +
        7.86869131145613259100e-4) * r + 1.48753612908506148525e-2) * r + 1.36929880922735805310e-1) * r +
        5.99832206555887937690e-1) * r + 1.0);
    }
    x = num / den;
    if (q < 0.0) x = -x;
    return 0.0 + (x * 1.0);
  }

  function normalCdf(z) { // display only (risk %), not used in decisions
    const t = 1 / (1 + 0.2316419 * Math.abs(z));
    const d = 0.3989422804014327 * Math.exp(-z * z / 2);
    const p = d * t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
    return z > 0 ? 1 - p : p;
  }

  class StreamAdapter {
    constructor(heartbeat = 4.0, dedupe = true, resetOnCellChange = false, grace = 3.0) {
      if (!(heartbeat > 0) || !(grace >= 0)) throw new RangeError("heartbeat must be > 0, grace >= 0");
      this.heartbeat = heartbeat; this.dedupe = dedupe; this.resetOnCellChange = resetOnCellChange; this.grace = grace;
      this.reset();
    }
    reset() { this.last = null; this.tLast = null; this.cell = null; this.tBad = null; }
    push(t, rsrp, rsrq = null, cell = null) {
      if (!validRsrp(rsrp)) {
        if (this.last !== null) {
          if (this.tBad === null) this.tBad = t;
          if (t - this.tBad < this.grace) return STALE;
        }
        this.reset();
        return UNAVAILABLE;
      }
      this.tBad = null;
      const q = validRsrq(rsrq) ? rsrq : null;
      let status;
      if (this.last === null) status = FIRST;
      else if (this.resetOnCellChange && cell !== null && this.cell !== null && cell !== this.cell) status = FIRST;
      else if (this.dedupe && this.last[0] === rsrp && this.last[1] === q && (t - this.tLast) < this.heartbeat) return STALE;
      else status = NEW;
      this.last = [rsrp, q]; this.tLast = t;
      if (cell !== null) this.cell = cell;
      return status;
    }
  }

  class LocalLinearTrend {
    constructor(measSd = 2.0, q = 0.2, rate0Sd = 1.0, gate = 3.5) {
      if (!(measSd > 0) || !(q > 0) || !(rate0Sd > 0)) throw new RangeError("measSd, q, rate0Sd must be > 0");
      this.measVar = measSd * measSd; this.q = q; this.rate0Var = rate0Sd * rate0Sd; this.gate = gate ? gate : 0.0;
      this.reset();
    }
    reset() { this.level = 0.0; this.rate = 0.0; this.p00 = 0.0; this.p01 = 0.0; this.p11 = 0.0; this.t = null; this.n = 0; }
    get ready() { return this.n > 0; }
    init(t, z) { this.level = z; this.rate = 0.0; this.p00 = this.measVar; this.p01 = 0.0; this.p11 = this.rate0Var; this.t = t; this.n = 1; }
    predictTo(t) {
      if (this.t === null) return;
      const dt = t - this.t;
      if (dt <= 0.0) return;
      const q = this.q, p00 = this.p00, p01 = this.p01, p11 = this.p11;
      this.level = this.level + this.rate * dt;
      this.p00 = p00 + 2.0 * dt * p01 + dt * dt * p11 + q * dt * dt * dt / 3.0;
      this.p01 = p01 + dt * p11 + q * dt * dt / 2.0;
      this.p11 = p11 + q * dt;
      this.t = t;
    }
    update(t, z) {
      if (this.n === 0) { this.init(t, z); return; }
      this.predictTo(t);
      let r = this.measVar;
      let s = this.p00 + r;
      const y = z - this.level;
      if (this.gate > 0.0) {
        const nis = y * y / s;
        const g2 = this.gate * this.gate;
        if (nis > g2) { r = r * nis / g2; s = this.p00 + r; }
      }
      const k0 = this.p00 / s, k1 = this.p01 / s;
      const p00 = this.p00, p01 = this.p01, p11 = this.p11;
      this.level = this.level + k0 * y;
      this.rate = this.rate + k1 * y;
      this.p11 = p11 - k1 * p01;
      this.p01 = (1.0 - k0) * p01;
      this.p00 = (1.0 - k0) * p00;
      this.n += 1;
    }
  }

  function forecast(level, rate, p00, p01, p11, q, h) {
    const mean = level + rate * h;
    const v = p00 + 2.0 * h * p01 + h * h * p11 + q * h * h * h / 3.0;
    return [mean, Math.sqrt(v > 0.0 ? v : 0.0)];
  }

  class Detector {
    constructor(o) {
      const persistence = o.persistence ?? 2, warmup = o.warmup ?? 3, cooldown = o.cooldown_s ?? 60.0;
      if (persistence < 1 || warmup < 1 || cooldown < 0) throw new RangeError("bad persistence / warmup / cooldown");
      this.persistence = persistence; this.cooldown = cooldown; this.warmup = warmup;
      this.adapter = new StreamAdapter(o.heartbeat_s ?? 4.0, o.dedupe ?? true, o.reset_on_cell_change ?? false, o.grace_s ?? 3.0);
    }
    reset() { this.adapter.reset(); this.nMeas = 0; this.run = 0; this.lastAlarm = null; this.lastCondition = false; this._resetSignal(); }
    step(t, rsrp, rsrq = null, nbr = null, cell = null) {
      const status = this.adapter.push(t, rsrp, rsrq, cell);
      if (status === UNAVAILABLE) {
        if (this.nMeas) this._resetSignal();
        this.nMeas = 0; this.run = 0; this.lastCondition = false;
        return false;
      }
      if (status === FIRST) { this._resetSignal(); this.nMeas = 0; }
      if (status === FIRST || status === NEW) {
        this._onMeasurement(t, rsrp, validRsrq(rsrq) ? rsrq : null);
        this.nMeas += 1;
      }
      this._onTick(t, nbr);
      const cond = this.nMeas >= this.warmup && this._condition(t);
      this.lastCondition = cond;
      this.run = cond ? this.run + 1 : 0;
      if (this.run >= this.persistence && (this.lastAlarm === null || t - this.lastAlarm >= this.cooldown)) {
        this.lastAlarm = t;
        return true;
      }
      return false;
    }
  }

  class ThresholdDetector extends Detector {
    constructor(o = {}) { super({ warmup: 1, ...o }); this.theta = o.theta ?? -110.0; this.reset(); }
    _resetSignal() { this.z = NaN; }
    _onMeasurement(t, rsrp) { this.z = rsrp; }
    _onTick() {}
    _condition() { return this.z <= this.theta; }
  }

  class SanketDetector extends Detector {
    constructor(o = {}) {
      super(o);
      const mode = o.rsrq_mode ?? "off";
      if (!["off", "confirm", "dual"].includes(mode)) throw new RangeError("rsrq_mode must be off, confirm or dual");
      this.measSd = o.meas_sd ?? 2.0; this.q = o.q ?? 0.2; this.gate = o.gate ?? 3.5;
      this.theta = o.theta ?? -124.0; this.horizon = o.horizon ?? 15.0; this.pThr = o.p_thr ?? 0.5;
      this.zThr = invNormal(this.pThr); this.minRate = o.min_rate ?? 0.3;
      this.rsrqMode = mode; this.thetaQ = o.theta_q ?? -18.0; this.confirmEps = o.confirm_eps ?? 0.3; this.minRateQ = o.min_rate_q ?? 0.2;
      this.nbrVeto = o.nbr_veto ?? false; this.nbrDelta = o.nbr_delta ?? 6.0; this.nbrDiff = o.nbr_diff ?? 1.0; this.nbrMaxAge = o.nbr_max_age ?? 6.0;
      this.kf = new LocalLinearTrend(this.measSd, this.q, 1.0, this.gate);
      this.kq = new LocalLinearTrend(o.rsrq_meas_sd ?? 1.5, o.rsrq_q ?? 0.1, 0.5, this.gate);
      this.kn = new LocalLinearTrend(this.measSd, this.q, 1.0, this.gate);
      this.nbrAdapter = new StreamAdapter(o.heartbeat_s ?? 4.0, o.dedupe ?? true, false, o.grace_s ?? 3.0);
      this.reset();
    }
    _resetSignal() {
      this.kf.reset(); this.kq.reset(); this.kn.reset(); this.nbrAdapter.reset(); this.nbrT = null; this.tNow = null;
    }
    _onMeasurement(t, rsrp, rsrq) { this.kf.update(t, rsrp); if (rsrq !== null) this.kq.update(t, rsrq); }
    _onTick(t, nbr) {
      this.tNow = t;
      this.kf.predictTo(t);
      if (this.kq.ready) this.kq.predictTo(t);
      const status = this.nbrAdapter.push(t, nbr);
      if (status === UNAVAILABLE) { this.kn.reset(); this.nbrT = null; }
      else {
        if (status === FIRST) this.kn.reset();
        if (status === FIRST || status === NEW) { this.kn.update(t, nbr); this.nbrT = t; }
        this.kn.predictTo(t);
      }
    }
    _condition(t) {
      const kf = this.kf, h = this.horizon;
      const [mean, sd] = forecast(kf.level, kf.rate, kf.p00, kf.p01, kf.p11, kf.q, h);
      let main = (this.theta - mean) >= this.zThr * sd && kf.rate <= -this.minRate;
      if (this.rsrqMode !== "off") {
        const kq = this.kq;
        if (this.rsrqMode === "confirm") {
          if (kq.n >= 2 && kq.rate > this.confirmEps) main = false;
        } else if (kq.n >= 2) {
          const [qm, qs] = forecast(kq.level, kq.rate, kq.p00, kq.p01, kq.p11, kq.q, h);
          if ((this.thetaQ - qm) >= this.zThr * qs && kq.rate <= -this.minRateQ) main = true;
        }
      }
      if (main && this.nbrVeto && this.kn.n >= 2 && this.nbrT !== null && (t - this.nbrT) <= this.nbrMaxAge) {
        if (this.kn.level >= kf.level - this.nbrDelta && (this.kn.rate - kf.rate) >= this.nbrDiff) main = false;
      }
      return main;
    }
    risk() {
      const kf = this.kf;
      if (!kf.ready) return 0.0;
      const [mean, sd] = forecast(kf.level, kf.rate, kf.p00, kf.p01, kf.p11, kf.q, this.horizon);
      if (sd <= 0) return mean <= this.theta ? 1.0 : 0.0;
      return normalCdf((this.theta - mean) / sd);
    }
    forecastBand(h) {
      const kf = this.kf;
      return forecast(kf.level, kf.rate, kf.p00, kf.p01, kf.p11, kf.q, h);
    }
  }

  return { SanketDetector, ThresholdDetector, LocalLinearTrend, StreamAdapter, forecast, invNormal, validRsrp, validRsrq };
});
