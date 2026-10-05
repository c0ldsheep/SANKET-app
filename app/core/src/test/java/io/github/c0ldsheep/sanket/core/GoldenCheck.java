package io.github.c0ldsheep.sanket.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays sanket_golden.json (produced by the Python reference) through the Java
 * port. Alarms and measurement counts must match exactly; filter states to 1e-6 (the golden
 * file stores 6 decimals). Usage: java -cp out io.github.c0ldsheep.sanket.core.GoldenCheck path/to/sanket_golden.json
 */
public final class GoldenCheck {
    public static void main(String[] args) throws Exception {
        int[] r = run(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        System.out.println(r[0] + " traces, " + r[1] + " ticks, " + r[2] + " alarms, " + r[3] + " mismatches");
        System.exit(r[3] == 0 ? 0 : 1);
    }

    /** Replays the golden file and returns {traces, ticks, alarms, mismatches}. */
    static int[] run(String text) {
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) new Json(text).value();
        @SuppressWarnings("unchecked")
        Map<String, Object> pj = (Map<String, Object>) g.get("params");
        SanketDetector.Params base = SanketDetector.Params.pythonDefaults();
        for (Map.Entry<String, Object> e : pj.entrySet()) apply(base, e.getKey(), e.getValue());
        int ticks = 0, alarms = 0, bad = 0, traces = 0;
        for (Object o : (List<?>) g.get("traces")) {
            traces++;
            Map<?, ?> tr = (Map<?, ?>) o;
            SanketDetector d = new SanketDetector(base);
            for (Object row : (List<?>) tr.get("ticks")) {
                List<?> v = (List<?>) row;
                boolean a = d.step(num(v.get(0)), num(v.get(1)), num(v.get(2)), num(v.get(3)));
                ticks++;
                if (a) alarms++;
                double[] cov = d.covariance();
                boolean ok = a == (Boolean) v.get(4) && d.measurements() == (int) num(v.get(5))
                        && close(d.level(), num(v.get(6))) && close(d.rate(), num(v.get(7)))
                        && close(cov[0], num(v.get(8))) && close(cov[1], num(v.get(9))) && close(cov[2], num(v.get(10)));
                if (!ok) {
                    if (bad < 5) System.err.println("mismatch in " + tr.get("name") + " at t=" + v.get(0));
                    bad++;
                }
            }
        }
        return new int[] {traces, ticks, alarms, bad};
    }

    static boolean close(double a, double b) { return Math.abs(a - b) <= 1e-6 * Math.max(1.0, Math.abs(b)); }

    static double num(Object o) { return o == null ? Double.NaN : ((Number) o).doubleValue(); }

    static void apply(SanketDetector.Params p, String k, Object v) {
        switch (k) {
            case "meas_sd": p.measSd = num(v); break;
            case "q": p.q = num(v); break;
            case "gate": p.gate = num(v); break;
            case "theta": p.theta = num(v); break;
            case "horizon": p.horizon = num(v); break;
            case "p_thr": p.pThr = num(v); break;
            case "min_rate": p.minRate = num(v); break;
            case "rsrq_mode": p.rsrqMode = (String) v; break;
            case "theta_q": p.thetaQ = num(v); break;
            case "confirm_eps": p.confirmEps = num(v); break;
            case "min_rate_q": p.minRateQ = num(v); break;
            case "nbr_veto": p.nbrVeto = (Boolean) v; break;
            case "nbr_delta": p.nbrDelta = num(v); break;
            case "nbr_diff": p.nbrDiff = num(v); break;
            case "nbr_max_age": p.nbrMaxAge = num(v); break;
            case "persistence": p.persistence = (int) num(v); break;
            case "warmup": p.warmup = (int) num(v); break;
            case "cooldown_s": p.cooldownS = num(v); break;
            case "heartbeat_s": p.heartbeatS = num(v); break;
            case "grace_s": p.graceS = num(v); break;
            case "dedupe": p.dedupe = (Boolean) v; break;
            default: throw new IllegalArgumentException("unknown parameter " + k);
        }
    }

    /** Minimal JSON reader for the golden file (objects, arrays, numbers, strings, true/false/null). */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) { this.s = s; }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int j = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(j, i));
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = str();
                ws();
                i++;                       // ':'
                m.put(k, value());
                ws();
                if (s.charAt(i++) == '}') return m;   // else ','
            }
        }

        private List<Object> arr() {
            List<Object> a = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return a; }
            while (true) {
                a.add(value());
                ws();
                if (s.charAt(i++) == ']') return a;   // else ','
            }
        }

        private String str() {
            StringBuilder b = new StringBuilder();
            i++;                           // opening quote
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char e = s.charAt(i++);
                    if (e == 'u') { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    else b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                } else b.append(c);
            }
            i++;
            return b.toString();
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    }
}
