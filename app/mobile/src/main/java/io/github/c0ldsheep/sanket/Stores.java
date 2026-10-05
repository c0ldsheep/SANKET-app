package io.github.c0ldsheep.sanket;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import io.github.c0ldsheep.sanket.core.DeviceProfile;
import io.github.c0ldsheep.sanket.core.SafetyLog;
import io.github.c0ldsheep.sanket.core.ZoneMemory;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Saves and loads everything SANKET keeps, as encrypted JSON in app-private storage. */
final class Stores {
    static final String PREFS = "sanket";
    private static final String TAG = "SanketStores";
    private static final String ZONES = "places.bin";
    private static final String LOG = "safety-log.bin";
    private static final String TRANSFERS = "downloads.bin";
    private static final String CARRIERS = "carrier_names";

    private final Context ctx;

    Stores(Context ctx) { this.ctx = ctx; }

    SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    void saveZones(ZoneMemory zones) {
        try {
            JSONArray all = new JSONArray();
            for (ZoneMemory.Zone z : zones.zones()) {
                JSONArray outages = new JSONArray();
                for (int s : z.outages()) outages.put(s);
                all.put(new JSONObject()
                        .put("id", z.id).put("operator", z.operator).put("lat", z.lat()).put("lon", z.lon())
                        .put("kind", z.kind().name()).put("losses", z.losses()).put("passes", z.passes())
                        .put("created", z.createdMs).put("updated", z.updatedMs()).put("lastLoss", z.lastLossMs())
                        .put("note", z.note()).put("outages", outages));
            }
            write(ZONES, all.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Could not save places", e);
        }
    }

    void loadZones(ZoneMemory zones) {
        String text = read(ZONES);
        if (text == null) return;
        try {
            JSONArray all = new JSONArray(text);
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                List<Integer> outages = new ArrayList<>();
                JSONArray out = o.optJSONArray("outages");
                if (out != null) for (int k = 0; k < out.length(); k++) outages.add(out.getInt(k));
                zones.restore(o.getString("id"), o.optString("operator", ""), o.getDouble("lat"), o.getDouble("lon"),
                        kind(o.optString("kind")), o.getDouble("losses"), o.getDouble("passes"), o.getLong("created"),
                        o.getLong("updated"), o.getLong("lastLoss"), o.optString("note", ""), outages);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Places file unreadable; starting without places", e);
            zones.clear();
        }
    }

    void saveLog(SafetyLog log) {
        try {
            JSONArray all = new JSONArray();
            for (SafetyLog.Entry e : log.entries()) {
                all.put(new JSONObject()
                        .put("time", e.timeMs).put("event", e.event).put("lat", number(e.lat)).put("lon", number(e.lon))
                        .put("operator", e.operator).put("rsrp", number(e.rsrp)).put("detail", e.detail)
                        .put("prev", e.prevHash).put("hash", e.hash));
            }
            write(LOG, all.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Could not save the safety log", e);
        }
    }

    void loadLog(SafetyLog log) {
        String text = read(LOG);
        if (text == null) return;
        try {
            JSONArray all = new JSONArray(text);
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                log.restore(o.getLong("time"), o.getString("event"), optNumber(o, "lat"), optNumber(o, "lon"),
                        o.optString("operator", ""), optNumber(o, "rsrp"), o.optString("detail", ""),
                        o.getString("prev"), o.getString("hash"));
            }
        } catch (JSONException e) {
            Log.e(TAG, "Safety log unreadable; starting a new one", e);
            log.clear();
        }
    }

    void saveProfile(DeviceProfile p) {
        prefs().edit()
                .putFloat("refresh_s", (float) p.refreshIntervalS())
                .putFloat("noise_db", (float) p.noiseDb())
                .putInt("samples", p.samples())
                .apply();
    }

    void loadProfile(DeviceProfile p) {
        SharedPreferences sp = prefs();
        if (sp.contains("refresh_s")) {
            p.restore(sp.getFloat("refresh_s", Float.NaN), sp.getFloat("noise_db", Float.NaN), sp.getInt("samples", 0));
        }
    }

    void saveTransfers(List<Transfers.Item> items) {
        try {
            JSONArray all = new JSONArray();
            for (Transfers.Item it : items) {
                all.put(new JSONObject()
                        .put("id", it.id).put("url", it.url).put("name", it.name).put("uri", it.uri)
                        .put("size", it.size).put("durable", it.durable).put("etag", it.etag)
                        .put("modified", it.lastModified).put("mime", it.mime).put("state", it.state.name())
                        .put("problem", it.problem.name()).put("code", it.code).put("retryAt", it.retryAtMs)
                        .put("need", it.needBytes).put("free", it.freeBytes).put("attempts", it.attempts)
                        .put("resumes", it.resumes).put("wifiOnly", it.wifiOnly).put("mobileOk", it.mobileOk)
                        .put("created", it.createdMs)
                        .put("finished", it.finishedMs));
            }
            write(TRANSFERS, all.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Could not save downloads", e);
        }
    }

    List<Transfers.Item> loadTransfers() {
        List<Transfers.Item> items = new ArrayList<>();
        String text = read(TRANSFERS);
        if (text == null) return items;
        try {
            JSONArray all = new JSONArray(text);
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                Transfers.Item it = new Transfers.Item(o.getString("id"), o.getString("url"), o.getString("name"),
                        o.optLong("created", System.currentTimeMillis()));
                it.uri = o.optString("uri", "");
                it.size = o.optLong("size", -1L);
                it.durable = o.optLong("durable", 0L);
                it.etag = o.optString("etag", "");
                it.lastModified = o.optString("modified", "");
                it.mime = o.optString("mime", "");
                it.state = state(o.optString("state"));
                it.problem = problem(o.optString("problem"));
                it.code = o.optInt("code", 0);
                it.retryAtMs = o.optLong("retryAt", 0L);
                it.needBytes = o.optLong("need", 0L);
                it.freeBytes = o.optLong("free", 0L);
                it.attempts = o.optInt("attempts", 0);
                it.resumes = o.optInt("resumes", 0);
                it.wifiOnly = o.optBoolean("wifiOnly", false);
                it.mobileOk = o.optBoolean("mobileOk", false);
                it.finishedMs = o.optLong("finished", 0L);
                items.add(it);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Downloads file unreadable; starting with none", e);
            items.clear();
        }
        return items;
    }

    /** Operator names seen on this phone's SIMs, by network code, so places can say "Jio" instead of a number. */
    Map<String, String> loadCarrierNames() {
        Map<String, String> names = new HashMap<>();
        String text = prefs().getString(CARRIERS, null);
        if (text == null) return names;
        try {
            JSONObject o = new JSONObject(text);
            for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
                String code = keys.next();
                names.put(code, o.optString(code, ""));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Operator names unreadable", e);
        }
        return names;
    }

    void saveCarrierNames(Map<String, String> names) {
        prefs().edit().putString(CARRIERS, new JSONObject(names).toString()).apply();
    }

    /** Deletes every file, the encryption key and all settings. */
    void wipe() {
        SecureFiles.delete(ctx, ZONES);
        SecureFiles.delete(ctx, LOG);
        SecureFiles.delete(ctx, TRANSFERS);
        try {
            SecureFiles.deleteKey();
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Could not delete the key", e);
        }
        prefs().edit().clear().apply();
    }

    private void write(String name, String text) {
        try {
            SecureFiles.write(ctx, name, text);
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Could not save " + name, e);
        }
    }

    private String read(String name) {
        try {
            return SecureFiles.read(ctx, name);
        } catch (GeneralSecurityException | IOException e) {
            Log.e(TAG, "Could not read " + name + "; ignoring it", e);
            return null;
        }
    }

    private static Object number(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? JSONObject.NULL : (Object) v;
    }

    private static double optNumber(JSONObject o, String key) {
        return o.isNull(key) ? Double.NaN : o.optDouble(key, Double.NaN);
    }

    private static ZoneMemory.Kind kind(String name) {
        try {
            return ZoneMemory.Kind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ZoneMemory.Kind.UNKNOWN;
        }
    }

    private static Transfers.State state(String name) {
        try {
            return Transfers.State.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Transfers.State.WAITING;
        }
    }

    private static Transfers.Problem problem(String name) {
        try {
            return Transfers.Problem.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Transfers.Problem.NONE;
        }
    }
}
