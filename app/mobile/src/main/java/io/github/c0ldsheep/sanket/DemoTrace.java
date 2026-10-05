package io.github.c0ldsheep.sanket;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The test trip from the study (Fig. 2: a scooter going down a basement ramp), replayed through the
 * same pipeline as live readings so the app can be shown anywhere. The screen labels it as a demo.
 */
final class DemoTrace {
    /** The demo's sample download: an offline map kept on the project's public download page. */
    static final String FILE_URL = "https://github.com/c0ldsheep/SANKET-app/releases/download/demo/SANKET-demo-map.png";

    static final class Row {
        final double t;
        final double rsrp;
        final double rsrq;
        final double nbr;

        Row(double t, double rsrp, double rsrq, double nbr) {
            this.t = t;
            this.rsrp = rsrp;
            this.rsrq = rsrq;
            this.nbr = nbr;
        }
    }

    private final List<Row> rows;
    private int next;

    private DemoTrace(List<Row> rows) { this.rows = rows; }

    static DemoTrace load(Context ctx) {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open("demo_trace.csv"), StandardCharsets.UTF_8))) {
            String line = in.readLine();
            while ((line = in.readLine()) != null) {
                String[] f = line.split(",", -1);
                if (f.length >= 4) rows.add(new Row(number(f[0]), number(f[1]), number(f[2]), number(f[3])));
            }
        } catch (IOException | NumberFormatException e) {
            Log.e("SanketDemo", "Demo trip unreadable", e);
            rows.clear();
        }
        return new DemoTrace(rows);
    }

    /** The next second of the trip, or null at the end. */
    Row next() { return next < rows.size() ? rows.get(next++) : null; }

    private static double number(String s) { return s.isEmpty() ? Double.NaN : Double.parseDouble(s); }
}
