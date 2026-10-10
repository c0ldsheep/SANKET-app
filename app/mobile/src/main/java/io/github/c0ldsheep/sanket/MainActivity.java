package io.github.c0ldsheep.sanket;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import io.github.c0ldsheep.sanket.core.GuardPolicy;
import io.github.c0ldsheep.sanket.core.LinkHealth;
import io.github.c0ldsheep.sanket.core.Links;
import io.github.c0ldsheep.sanket.core.SanketDetector;
import io.github.c0ldsheep.sanket.core.SignalWords;
import io.github.c0ldsheep.sanket.core.VerticalMotion;
import io.github.c0ldsheep.sanket.core.ZoneMemory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The one screen: the status, protected downloads, the signal in detail, and the tools. */
public final class MainActivity extends Activity {
    static final String ACTION_START_PROTECTION = "io.github.c0ldsheep.sanket.action.START_PROTECTION";
    static final String EXTRA_OPEN_DOWNLOAD = "open_download";
    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREF_CONSENT = "consent_v1";
    private static final String PREF_ASKED = "asked_";
    private static final String PREF_BATTERY_LATER = "battery_banner_later";
    private static final String STATE_PENDING_LINK = "pending_link";
    private static final String WEBSITE = "https://c0ldsheep.github.io/SANKET-app/";
    private static final long REFRESH_MS = 500L;
    private static final long BANNERS_EVERY_MS = 2_000L;
    private static final long BATTERY_LATER_MS = 7L * 24L * 60L * 60L * 1000L;
    /** Phone makers whose battery savers often stop background apps. */
    private static final List<String> STRICT_BATTERY = List.of("xiaomi", "redmi", "poco", "oppo", "realme",
            "oneplus", "vivo", "iqoo", "samsung", "huawei", "honor", "tecno", "infinix", "itel");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, REFRESH_MS);
        }
    };
    private final Map<String, DownloadRow> rows = new HashMap<>();
    private final List<InfoRow> simRows = new ArrayList<>();

    private Engine engine;
    private LinearLayout banners;
    private LinearLayout riskBlock;
    private LinearLayout downloads;
    private LinearLayout signalSection;
    private LinearLayout signalRows;
    private LinearLayout menu;
    private TextView demoLabel;
    private TextView statusTitle;
    private TextView statusBody;
    private TextView riskLabel;
    private TextView riskValue;
    private TextView riskHint;
    private TextView protectHint;
    private TextView downloadsEmpty;
    private RiskMeter riskMeter;
    private Button protect;
    private Button clearFinished;
    private InfoRow internetRow;
    private InfoRow movementRow;
    private InfoRow phoneRow;
    private InfoRow placeRow;
    private InfoRow betterSimRow;
    private InfoRow lowPowerRow;
    private InfoRow dispatchRow;
    private MenuRow placesRow;
    private MenuRow recordRow;
    private GradientDrawable cardBg;
    private GradientDrawable dotBg;
    private int shownColor;
    private int shownTint;
    private int buttonMode = -1;
    private ValueAnimator colorAnim;
    private String bannerKeys = "";
    private long bannersAt;
    private boolean stoppedBySystem;
    private String pendingLink;
    private AlertDialog linkDialog;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        engine = Engine.get(this);
        banners = findViewById(R.id.banners);
        riskBlock = findViewById(R.id.riskBlock);
        downloads = findViewById(R.id.downloads);
        signalSection = findViewById(R.id.signalSection);
        signalRows = findViewById(R.id.signalRows);
        menu = findViewById(R.id.menu);
        demoLabel = findViewById(R.id.demoLabel);
        statusTitle = findViewById(R.id.statusTitle);
        statusBody = findViewById(R.id.statusBody);
        riskLabel = findViewById(R.id.riskLabel);
        riskValue = findViewById(R.id.riskValue);
        riskHint = findViewById(R.id.riskHint);
        riskMeter = findViewById(R.id.riskMeter);
        protectHint = findViewById(R.id.protectHint);
        downloadsEmpty = findViewById(R.id.downloadsEmpty);
        protect = findViewById(R.id.protect);
        clearFinished = findViewById(R.id.clearFinished);

        float radius = getResources().getDimension(R.dimen.radius);
        cardBg = new GradientDrawable();
        cardBg.setCornerRadius(radius);
        findViewById(R.id.statusCard).setBackground(cardBg);
        dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        findViewById(R.id.statusDot).setBackground(dotBg);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(radius);
        pill.setColor(getColor(R.color.surface));
        demoLabel.setBackground(pill);

        internetRow = new InfoRow(R.drawable.ic_globe);
        movementRow = new InfoRow(R.drawable.ic_updown);
        phoneRow = new InfoRow(R.drawable.ic_phone);
        placeRow = new InfoRow(R.drawable.ic_place);
        betterSimRow = new InfoRow(R.drawable.ic_swap);
        lowPowerRow = new InfoRow(R.drawable.ic_battery);
        dispatchRow = new InfoRow(R.drawable.ic_info);
        placesRow = new MenuRow(R.drawable.ic_place, R.string.places, R.string.places_sub, this::showPlaces);
        new MenuRow(R.drawable.ic_log, R.string.safety_log, R.string.safety_log_sub, this::showLog);
        new MenuRow(R.drawable.ic_play, R.string.demo, R.string.demo_sub,
                () -> GuardService.send(this, GuardService.ACTION_DEMO));
        recordRow = new MenuRow(R.drawable.ic_record, R.string.record, R.string.record_sub, this::onRecordClicked);
        new MenuRow(R.drawable.ic_battery, R.string.keep_running, R.string.keep_running_sub, this::showKeepRunning);
        new MenuRow(R.drawable.ic_shield, R.string.privacy, R.string.privacy_sub, this::showPrivacy);
        new MenuRow(R.drawable.ic_info, R.string.about, R.string.about_sub, this::showAbout);
        ShareProvider.clean(this);

        protect.setOnClickListener(v -> onProtectClicked());
        findViewById(R.id.addDownload).setOnClickListener(v -> withConsent(() -> showLinkDialog(null, null)));
        clearFinished.setOnClickListener(v -> engine.transfers.clearFinished());
        findViewById(R.id.help).setOnClickListener(v -> showHowItWorks());
        ((TextView) findViewById(R.id.footer)).setText(getString(R.string.footer, versionName()));

        pendingLink = state == null ? null : state.getString(STATE_PENDING_LINK);
        if (pendingLink != null) {
            showLinkDialog(pendingLink, null);
        } else {
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine.stoppedBySystem()) stoppedBySystem = true;
        bannersAt = 0L;
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (pendingLink != null) out.putString(STATE_PENDING_LINK, pendingLink);
    }

    @Override
    protected void onDestroy() {
        if (linkDialog != null && linkDialog.isShowing()) linkDialog.dismiss();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (engine.snapshot().protecting) GuardService.send(this, GuardService.ACTION_START);
        bannerKeys = "";
        bannersAt = 0L;
    }

    // ---- Actions ----

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            setIntent(new Intent(this, MainActivity.class));   // don't ask again after rotation
            String link = Links.firstHttps(intent.getStringExtra(Intent.EXTRA_TEXT));
            if (link == null) {
                Toast.makeText(this, R.string.only_https, Toast.LENGTH_LONG).show();
                return;
            }
            withConsent(() -> showLinkDialog(link, null));
        } else if (ACTION_START_PROTECTION.equals(action)) {
            setIntent(new Intent(this, MainActivity.class));
            if (!engine.snapshot().protecting) withConsent(this::startProtection);
        } else if (intent.hasExtra(EXTRA_OPEN_DOWNLOAD)) {
            String id = intent.getStringExtra(EXTRA_OPEN_DOWNLOAD);
            setIntent(new Intent(this, MainActivity.class));
            Transfers.Item it = id == null ? null : engine.transfers.find(id);
            if (it != null && it.state == Transfers.State.DONE) open(it);
        }
    }

    private void onProtectClicked() {
        Snapshot s = engine.snapshot();
        if (s.demo) {
            GuardService.send(this, GuardService.ACTION_STOP_DEMO);
        } else if (s.protecting) {
            GuardService.requestStop();
        } else {
            withConsent(this::startProtection);
        }
    }

    private void startProtection() {
        askPermissions();
        GuardService.send(this, GuardService.ACTION_START);
    }

    private void ensureService() {
        if (!engine.snapshot().running) GuardService.send(this, GuardService.ACTION_DOWNLOADS);
    }

    private void withConsent(Runnable then) {
        SharedPreferences prefs = prefs();
        if (prefs.getBoolean(PREF_CONSENT, false)) {
            then.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.consent_title)
                .setMessage(R.string.consent_text)
                .setPositiveButton(R.string.consent_agree, (d, w) -> {
                    prefs.edit().putBoolean(PREF_CONSENT, true).apply();
                    then.run();
                })
                .setNegativeButton(R.string.not_now, null)
                .show();
    }

    private void askPermissions() {
        List<String> wanted = new ArrayList<>();
        wanted.add(Manifest.permission.ACCESS_FINE_LOCATION);
        wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        wanted.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String p : wanted) {
            if (!granted(p)) missing.add(p);
        }
        request(missing);
    }

    private void request(List<String> permissions) {
        if (permissions.isEmpty()) return;
        SharedPreferences.Editor e = prefs().edit();
        for (String p : permissions) e.putBoolean(PREF_ASKED + p, true);
        e.apply();
        requestPermissions(permissions.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    /** Asked before and Android won't show the dialog again: only the settings screen can help. */
    private boolean blocked(String permission) {
        return !granted(permission) && prefs().getBoolean(PREF_ASKED + permission, false)
                && !shouldShowRequestPermissionRationale(permission);
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void fixNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)
                && !blocked(Manifest.permission.POST_NOTIFICATIONS)) {
            request(List.of(Manifest.permission.POST_NOTIFICATIONS));
        } else {
            startSafely(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        }
    }

    private void fixLocation() {
        if (blocked(Manifest.permission.ACCESS_FINE_LOCATION)) {
            openAppSettings();
        } else {
            request(List.of(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION));
        }
    }

    private void openAppSettings() {
        startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
    }

    private void startSafely(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            openAppSettings();
        }
    }

    private void open(Transfers.Item it) {
        try {
            startActivity(Transfers.viewIntent(it));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_LONG).show();
        }
    }

    // ---- Rendering ----

    private void render() {
        Snapshot s = engine.snapshot();
        renderStatus(s);
        renderDownloads();
        renderSignal(s);
        int places = engine.places().size();
        placesRow.badge(places > 0 ? String.valueOf(places) : "");
        recordRow.badge(recordBadge());
        long now = SystemClock.uptimeMillis();
        if (now - bannersAt >= BANNERS_EVERY_MS) {
            bannersAt = now;
            renderBanners(s);
        }
    }

    private void renderStatus(Snapshot s) {
        StatusWords w = StatusWords.of(this, s);
        setText(statusTitle, w.title);
        setText(statusBody, w.body);
        showColors(getColor(w.color), getColor(w.tint));
        if (s.demo) {
            demoLabel.setVisibility(View.VISIBLE);
            setText(demoLabel, getString(R.string.demo_label, Math.round(s.demoSeconds)));
        } else {
            demoLabel.setVisibility(View.GONE);
        }
        boolean showRisk = s.running && s.offline == Snapshot.Offline.NONE && s.level != GuardPolicy.Level.OFFLINE
                && (s.tech == Snapshot.Tech.LTE || s.tech == Snapshot.Tech.NR);
        riskBlock.setVisibility(showRisk ? View.VISIBLE : View.GONE);
        if (showRisk) {
            int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, s.risk)) * 100.0);
            setText(riskLabel, getString(R.string.risk_label, Math.round(s.horizonS)));
            setText(riskValue, getString(R.string.risk_value, pct));
            riskMeter.setValue(pct / 100f);   // the bar shows exactly the number beside it
            riskMeter.setContentDescription(getString(R.string.risk_description, pct));
            boolean soon = s.timeToLossS > 0.0 && s.timeToLossS < 60.0;
            setText(riskHint, soon ? getString(R.string.risk_hint_ttl, Math.round(s.timeToLossS))
                    : getString(R.string.risk_hint));
        }
        int mode = s.demo ? 2 : s.protecting ? 1 : 0;
        if (mode != buttonMode) {
            buttonMode = mode;
            protect.setText(mode == 2 ? R.string.stop_demo : mode == 1 ? R.string.stop_protection : R.string.start_protection);
            protect.setBackgroundResource(mode == 0 ? R.drawable.bg_button_primary : R.drawable.bg_button_tonal);
            protect.setTextColor(getColor(mode == 0 ? R.color.on_accent : R.color.accent));
        }
        int hint = s.demo ? R.string.protect_hint_demo : s.protecting ? R.string.protect_hint_on
                : s.running ? R.string.protect_hint_downloads : R.string.protect_hint_off;
        setText(protectHint, getString(hint));
    }

    /** Fades the status colours over 220 ms, so a change is noticed without a jump. */
    private void showColors(int color, int tint) {
        if (color == shownColor && tint == shownTint) return;
        int fromColor = shownColor;
        int fromTint = shownTint;
        boolean first = shownColor == 0 && shownTint == 0;
        shownColor = color;
        shownTint = tint;
        if (colorAnim != null) colorAnim.cancel();
        if (first) {
            applyColors(color, tint);
            return;
        }
        ArgbEvaluator argb = new ArgbEvaluator();
        colorAnim = ValueAnimator.ofFloat(0f, 1f);
        colorAnim.setDuration(220L);
        colorAnim.setInterpolator(new DecelerateInterpolator());
        colorAnim.addUpdateListener(a -> {
            float f = a.getAnimatedFraction();
            applyColors((Integer) argb.evaluate(f, fromColor, color), (Integer) argb.evaluate(f, fromTint, tint));
        });
        colorAnim.start();
    }

    private void applyColors(int color, int tint) {
        cardBg.setColor(tint);
        dotBg.setColor(color);
        statusTitle.setTextColor(color);
        riskMeter.setColors(color, (color & 0x00FFFFFF) | 0x40000000);
    }

    private void renderDownloads() {
        List<Transfers.Item> items = engine.transfers.items();
        Set<String> seen = new HashSet<>();
        boolean anyDone = false;
        int index = 0;
        for (Transfers.Item it : items) {
            seen.add(it.id);
            DownloadRow row = rows.get(it.id);
            if (row == null) {
                row = new DownloadRow();
                rows.put(it.id, row);
                downloads.addView(row.view, index);
            } else if (downloads.indexOfChild(row.view) != index) {
                downloads.removeView(row.view);
                downloads.addView(row.view, index);
            }
            row.bind(it);
            if (it.state == Transfers.State.DONE) anyDone = true;
            index++;
        }
        for (Iterator<Map.Entry<String, DownloadRow>> i = rows.entrySet().iterator(); i.hasNext(); ) {
            Map.Entry<String, DownloadRow> e = i.next();
            if (!seen.contains(e.getKey())) {
                downloads.removeView(e.getValue().view);
                i.remove();
            }
        }
        downloadsEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        clearFinished.setVisibility(anyDone ? View.VISIBLE : View.GONE);
    }

    private void renderSignal(Snapshot s) {
        signalSection.setVisibility(s.running ? View.VISIBLE : View.GONE);
        if (!s.running) return;
        if (simRows.size() != s.sims.size()) {
            for (InfoRow r : simRows) signalRows.removeView(r.view);
            simRows.clear();
            for (int i = 0; i < s.sims.size(); i++) {
                InfoRow r = new InfoRow(0);
                signalRows.removeView(r.view);
                signalRows.addView(r.view, i);
                simRows.add(r);
            }
        }
        for (int i = 0; i < s.sims.size(); i++) {
            InfoRow row = simRows.get(i);
            if (s.offline == Snapshot.Offline.AIRPLANE) {
                row.bars(0);
                row.set(s.sims.get(i).name, null, getString(R.string.sim_off));
            } else {
                bindSim(row, s.sims.get(i), s);
            }
        }

        boolean live = !s.demo;
        internetRow.show(live);
        if (live) {
            internetRow.icon(s.onWifi ? R.drawable.ic_wifi : R.drawable.ic_globe);
            String value;
            String sub = null;
            switch (s.health) {
                case OK:
                    value = Double.isNaN(s.latencyMs) ? getString(R.string.net_ok)
                            : getString(R.string.net_ok_ms, Math.round(s.latencyMs));
                    if (s.onWifi) sub = getString(R.string.net_wifi_sub);
                    break;
                case SLOW:
                    value = getString(R.string.net_slow);
                    sub = getString(R.string.net_slow_sub);
                    break;
                case NO_INTERNET:
                    value = getString(R.string.net_no_internet);
                    sub = getString(R.string.net_no_internet_sub);
                    break;
                default:
                    value = getString(R.string.net_none);
            }
            internetRow.set(getString(R.string.internet), sub, value);
        }

        movementRow.show(live && s.motionSensed);
        if (live && s.motionSensed) {
            boolean fast = s.movement != VerticalMotion.Movement.LEVEL;
            boolean up = s.movement == VerticalMotion.Movement.UP_FAST;
            String value = !fast ? getString(R.string.move_level)
                    : s.liftOnly ? getString(up ? R.string.move_lift_up : R.string.move_lift_down)
                    : up ? getString(R.string.move_up, s.verticalSpeed) : getString(R.string.move_down, -s.verticalSpeed);
            String sub = fast ? getString(R.string.move_sub) : s.liftOnly ? getString(R.string.move_sub_accel) : null;
            movementRow.set(getString(R.string.movement), sub, value);
        }

        phoneRow.show(live);
        if (live) {
            boolean learning = Double.isNaN(s.refreshS);
            phoneRow.set(getString(R.string.phone),
                    getString(learning ? R.string.phone_learning_sub : R.string.phone_profile_sub),
                    learning ? getString(R.string.phone_learning) : getString(R.string.phone_profile, s.refreshS));
        }

        placeRow.show(!s.place.isEmpty());
        if (!s.place.isEmpty()) {
            String sub = s.placeNote.isEmpty() ? s.place : s.place + "\n" + getString(R.string.place_note, s.placeNote);
            placeRow.set(getString(R.string.place_here), sub, getString(R.string.place_known_value));
        }

        betterSimRow.show(!s.betterSim.isEmpty());
        if (!s.betterSim.isEmpty()) {
            betterSimRow.set(getString(R.string.other_sim), getString(R.string.other_sim_sub, s.betterSim), null);
        }

        lowPowerRow.show(s.lowPower);
        if (s.lowPower) {
            lowPowerRow.set(getString(R.string.low_power), getString(R.string.low_power_sub),
                    getString(R.string.low_power_value));
        }

        dispatchRow.show(s.noticeBackMin > 0);
        if (s.noticeBackMin > 0) {
            dispatchRow.set(getString(R.string.dispatch), getString(R.string.dispatch_sub, s.noticeBackMin),
                    getString(R.string.dispatch_value));
        }
    }

    private void bindSim(InfoRow row, Snapshot.Sim sim, Snapshot s) {
        boolean lte = SanketDetector.validRsrp(sim.lteDbm);
        boolean nr = RadioReader.validNr(sim.nrDbm);
        boolean legacy = !lte && !nr && !Double.isNaN(sim.legacyDbm);
        SignalWords.Strength strength = lte ? SignalWords.lte(sim.lteDbm)
                : nr ? SignalWords.nr(sim.nrDbm)
                : legacy ? SignalWords.legacy(sim.legacyDbm) : SignalWords.Strength.NONE;
        double dbm = lte ? sim.lteDbm : nr ? sim.nrDbm : legacy ? sim.legacyDbm : Double.NaN;
        String label = sim.data && !s.demo ? getString(R.string.sim_mobile_data, sim.name) : sim.name;
        String tech = lte && nr ? getString(R.string.tech_4g_5g) : lte ? getString(R.string.tech_4g)
                : nr ? getString(sim.data && s.tech == Snapshot.Tech.NR ? R.string.tech_5g_sa : R.string.tech_5g)
                : legacy ? getString(R.string.tech_legacy, sim.legacyTech) : null;
        String value = Double.isNaN(dbm) ? getString(R.string.strength_none)
                : getString(R.string.signal_value, strengthWord(strength), minus(Math.round(dbm)));
        row.bars(SignalWords.bars(strength));
        row.set(label, tech, value);
    }

    private String strengthWord(SignalWords.Strength s) {
        switch (s) {
            case STRONG:
                return getString(R.string.strength_strong);
            case GOOD:
                return getString(R.string.strength_good);
            case FAIR:
                return getString(R.string.strength_fair);
            case WEAK:
                return getString(R.string.strength_weak);
            case ALMOST_NONE:
                return getString(R.string.strength_almost_none);
            default:
                return getString(R.string.strength_none);
        }
    }

    private void renderBanners(Snapshot s) {
        List<Banner> list = new ArrayList<>();
        boolean active = s.protecting || engine.transfers.busy();
        if (active && !notificationsOn()) {
            list.add(new Banner("notifications", R.drawable.ic_bell, getString(R.string.banner_notifications),
                    R.string.banner_turn_on, this::fixNotifications, false));
        }
        if (s.protecting) {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                boolean approx = granted(Manifest.permission.ACCESS_COARSE_LOCATION);
                list.add(new Banner(approx ? "approx" : "location", R.drawable.ic_place,
                        getString(approx ? R.string.banner_location_approx : R.string.banner_location_denied),
                        R.string.banner_allow, this::fixLocation, false));
            } else if (!locationOn()) {
                list.add(new Banner("location_off", R.drawable.ic_place, getString(R.string.banner_location_off),
                        R.string.banner_turn_on, () -> startSafely(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
                        false));
            }
        }
        boolean batteryRisk = stoppedBySystem
                || (s.protecting && strictBattery() && !batteryExempt() && !batteryLater());
        if (batteryRisk) {
            list.add(new Banner("battery", R.drawable.ic_battery, stoppedBySystem
                    ? getString(R.string.banner_battery_stopped) : getString(R.string.banner_battery_brand, brand()),
                    R.string.banner_how, this::showKeepRunning, true));
        }
        if (list.size() > 2) list = list.subList(0, 2);
        StringBuilder keys = new StringBuilder();
        for (Banner b : list) keys.append(b.key).append(';');
        if (keys.toString().equals(bannerKeys)) return;
        bannerKeys = keys.toString();
        banners.removeAllViews();
        for (Banner b : list) banners.addView(bannerView(b));
    }

    private View bannerView(Banner b) {
        View v = getLayoutInflater().inflate(R.layout.item_banner, banners, false);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(getResources().getDimension(R.dimen.radius));
        bg.setColor(getColor(R.color.status_watch_tint));
        v.setBackground(bg);
        TextView text = v.findViewById(R.id.text);
        text.setText(b.text);
        text.setCompoundDrawablesRelativeWithIntrinsicBounds(b.icon, 0, 0, 0);
        text.setCompoundDrawableTintList(ColorStateList.valueOf(getColor(R.color.status_watch)));
        Button action = v.findViewById(R.id.action);
        action.setText(b.action);
        action.setOnClickListener(x -> b.run.run());
        Button dismiss = v.findViewById(R.id.dismiss);
        if (b.dismissible) {
            dismiss.setVisibility(View.VISIBLE);
            dismiss.setOnClickListener(x -> {
                stoppedBySystem = false;
                prefs().edit().putLong(PREF_BATTERY_LATER, System.currentTimeMillis()).apply();
                bannerKeys = "";
                renderBanners(engine.snapshot());
            });
        }
        return v;
    }

    private boolean notificationsOn() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    private boolean locationOn() {
        LocationManager lm = getSystemService(LocationManager.class);
        return lm == null || lm.isLocationEnabled();
    }

    private boolean batteryExempt() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean batteryLater() {
        return System.currentTimeMillis() - prefs().getLong(PREF_BATTERY_LATER, 0L) < BATTERY_LATER_MS;
    }

    private static boolean strictBattery() {
        return STRICT_BATTERY.contains(Build.MANUFACTURER.toLowerCase(Locale.ROOT))
                || STRICT_BATTERY.contains(Build.BRAND.toLowerCase(Locale.ROOT));
    }

    private static String brand() {
        String m = Build.MANUFACTURER;
        return m.isEmpty() ? m : m.substring(0, 1).toUpperCase(Locale.ROOT) + m.substring(1);
    }

    // ---- Dialogs ----

    /** Asks for a link: a new download when {@code item} is null, or a fresh link for that download. */
    @SuppressLint("InflateParams")
    private void showLinkDialog(String prefill, Transfers.Item item) {
        View form = getLayoutInflater().inflate(R.layout.dialog_download, null);
        EditText link = form.findViewById(R.id.link);
        CheckBox wifiOnly = form.findViewById(R.id.wifiOnly);
        if (prefill != null) link.setText(prefill);
        if (item != null) {
            wifiOnly.setVisibility(View.GONE);
            ((TextView) form.findViewById(R.id.help)).setText(R.string.new_link_help);
        } else {
            pendingLink = prefill;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(item == null ? R.string.download_title : R.string.new_link_title)
                .setView(form)
                .setPositiveButton(R.string.download_start, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String found = Links.firstHttps(link.getText().toString());
            if (found == null) {
                link.setError(getString(R.string.link_invalid));
                return;
            }
            if (item != null) {
                engine.transfers.replaceUrl(item, found);
            } else {
                Transfers.Added added = engine.transfers.add(found, wifiOnly.isChecked());
                if (added == Transfers.Added.NOT_HTTPS) {
                    link.setError(getString(R.string.only_https));
                    return;
                }
                if (added == Transfers.Added.DUPLICATE) {
                    Toast.makeText(this, R.string.already_downloading, Toast.LENGTH_LONG).show();
                }
            }
            ensureService();
            d.dismiss();
        }));
        d.setOnDismissListener(x -> pendingLink = null);
        linkDialog = d;
        d.show();
    }

    private void confirmCancel(Transfers.Item it) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dl_cancel_title)
                .setMessage(R.string.dl_cancel_text)
                .setPositiveButton(R.string.dl_cancel_confirm, (d, w) -> engine.transfers.cancel(it))
                .setNegativeButton(R.string.dl_keep, null)
                .show();
    }

    private void showHowItWorks() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.how_it_works)
                .setMessage(R.string.how_text)
                .setPositiveButton(R.string.got_it, null)
                .show();
    }

    private void showPlaces() {
        List<ZoneMemory.Zone> places = engine.places();
        if (places.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.places)
                    .setMessage(R.string.places_empty)
                    .setPositiveButton(R.string.close, null)
                    .show();
            return;
        }
        String[] labels = new String[places.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = engine.describe(places.get(i));
        new AlertDialog.Builder(this)
                .setTitle(R.string.places)
                .setItems(labels, (d, which) -> editPlace(places.get(which)))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void editPlace(ZoneMemory.Zone z) {
        EditText note = new EditText(this);
        note.setText(z.note());
        note.setHint(R.string.note_hint);
        LinearLayout box = new LinearLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, 0, pad, 0);
        box.addView(note, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.place_note_title)
                .setMessage(engine.describe(z))
                .setView(box)
                .setPositiveButton(R.string.save, (d, w) -> engine.setNote(z, note.getText().toString()))
                .setNeutralButton(R.string.forget_place, (d, w) -> engine.forget(z))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showLog() {
        if (engine.logEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.safety_log)
                    .setMessage(R.string.log_empty)
                    .setPositiveButton(R.string.close, null)
                    .show();
            return;
        }
        String text = engine.safetyLogText();
        TextView view = new TextView(this);
        view.setText(text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(12f);
        view.setTextColor(getColor(R.color.text));
        view.setTextIsSelectable(true);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad / 2, pad, pad / 2);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle(R.string.safety_log)
                .setView(scroll)
                .setPositiveButton(R.string.share, (d, w) -> confirmShare(text))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void confirmShare(String text) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.log_share_title)
                .setMessage(R.string.log_share_text)
                .setPositiveButton(R.string.share, (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(send, getString(R.string.share)));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---- Test ride (a field test recording) ----

    private String recordBadge() {
        switch (engine.ride()) {
            case RECORDING: return rideLive() ? getString(R.string.record_badge, clock(engine.rideSeconds()))
                    : getString(R.string.record_paused_badge);
            case SAVED: return getString(R.string.record_saved_badge);
            default: return "";
        }
    }

    /** A test ride records only while protection reads the real signal (not during the demo). */
    private boolean rideLive() {
        Snapshot s = engine.snapshot();
        return s.running && !s.demo;
    }

    private static String clock(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private void onRecordClicked() {
        switch (engine.ride()) {
            case RECORDING:
                if (!rideLive()) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.record_paused_title)
                            .setMessage(R.string.record_paused_text)
                            .setPositiveButton(R.string.record_stop, (d, w) -> {
                                engine.stopRide();
                                render();
                                showSavedRide();
                            })
                            .setNegativeButton(R.string.record_keep, null)
                            .show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.record_now_title, clock(engine.rideSeconds())))
                        .setMessage(R.string.record_now_text)
                        .setPositiveButton(R.string.record_stop, (d, w) -> {
                            engine.stopRide();
                            render();
                            showSavedRide();
                        })
                        .setNeutralButton(R.string.record_mark, (d, w) -> {
                            engine.markRide();
                            Toast.makeText(this, R.string.record_marked, Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.record_keep, null)
                        .show();
                return;
            case SAVED:
                showSavedRide();
                return;
            default:
                break;
        }
        Snapshot s = engine.snapshot();
        if (s.demo) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.record)
                    .setMessage(R.string.record_not_in_demo)
                    .setPositiveButton(R.string.close, null)
                    .show();
        } else if (!s.running) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.record)
                    .setMessage(R.string.record_need_protection)
                    .setPositiveButton(R.string.record_turn_on, (d, w) -> onProtectClicked())
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.record)
                    .setMessage(R.string.record_intro)
                    .setPositiveButton(R.string.record_start, (d, w) -> {
                        engine.startRide();
                        render();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        }
    }

    private void showSavedRide() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.record_saved_title)
                .setMessage(getString(R.string.record_saved_text, clock(engine.rideRows())))
                .setPositiveButton(R.string.share, (d, w) -> confirmShareRide())
                .setNeutralButton(R.string.record_delete, (d, w) -> confirmDeleteRide())
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void confirmShareRide() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.record_share_title)
                .setMessage(R.string.record_share_text)
                .setPositiveButton(R.string.share, (d, w) -> shareRide())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void shareRide() {
        String name = "SANKET-test-ride-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date(engine.rideStartMs())) + ".csv";
        File out = new File(ShareProvider.dir(this), name);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(engine.rideCsv());
        } catch (IOException e) {
            Toast.makeText(this, R.string.record_share_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = ShareProvider.uri(out);
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(name, uri));
        startActivity(Intent.createChooser(send, getString(R.string.share)));
    }

    private void confirmDeleteRide() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.record_delete_title)
                .setMessage(R.string.record_delete_text)
                .setPositiveButton(R.string.record_delete, (d, w) -> {
                    engine.deleteRide();
                    render();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about)
                .setMessage(getString(R.string.about_text, versionName()))
                .setPositiveButton(R.string.about_website, (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, WEBSITE, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showPrivacy() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.privacy)
                .setMessage(R.string.privacy_text)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.delete_everything, (d, w) -> confirmWipe())
                .show();
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_title)
                .setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    GuardService.stop(this);
                    engine.wipe();
                    Toast.makeText(this, R.string.deleted, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showKeepRunning() {
        String m = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        int steps;
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) steps = R.string.keep_xiaomi;
        else if (m.contains("samsung")) steps = R.string.keep_samsung;
        else if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) steps = R.string.keep_oppo;
        else if (m.contains("vivo") || m.contains("iqoo")) steps = R.string.keep_vivo;
        else steps = R.string.keep_generic;
        stoppedBySystem = false;
        bannerKeys = "";
        new AlertDialog.Builder(this)
                .setTitle(R.string.keep_title)
                .setMessage(getString(R.string.keep_intro) + "\n\n" + getString(steps) + "\n\n" + getString(R.string.keep_where))
                .setPositiveButton(R.string.keep_open_app, (d, w) -> openAppSettings())
                .setNegativeButton(R.string.close, null)
                .show();
    }

    // ---- Small helpers ----

    private SharedPreferences prefs() { return getSharedPreferences(Stores.PREFS, MODE_PRIVATE); }

    private static void setText(TextView v, CharSequence text) {
        if (!text.toString().contentEquals(v.getText())) v.setText(text);
    }

    /** -92 written with a real minus sign, which reads better than a hyphen. */
    private static String minus(long value) {
        return String.format(Locale.ROOT, "%d", value).replace('-', '−');
    }

    private String versionName() {
        try {
            PackageInfo info = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? getPackageManager().getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0))
                    : packageInfo();
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    private PackageInfo packageInfo() throws PackageManager.NameNotFoundException {
        return getPackageManager().getPackageInfo(getPackageName(), 0);
    }

    private String amountText(Transfers.Item it) {
        long shown = it.shown();
        if (it.state == Transfers.State.DONE) return Transfers.human(it.size > 0 ? it.size : shown);
        if (it.size > 0) return getString(R.string.dl_amount, Transfers.human(shown), Transfers.human(it.size));
        return shown > 0 ? Transfers.human(shown) : "";
    }

    private static boolean needsNewLink(Transfers.Item it) {
        return it.problem == Transfers.Problem.LINK_EXPIRED || it.problem == Transfers.Problem.NOT_FOUND
                || it.problem == Transfers.Problem.NOT_A_FILE;
    }

    private String statusText(Transfers.Item it) {
        switch (it.state) {
            case QUEUED:
                return getString(R.string.dl_starting);
            case RUNNING:
                return getString(R.string.dl_running);
            case DONE:
                if (it.uri.isEmpty()) return getString(R.string.dl_done_old);
                return it.resumes > 0
                        ? getResources().getQuantityString(R.plurals.dl_done_resumed, it.resumes, it.resumes)
                        : getString(R.string.dl_done);
            case FAILED:
                switch (it.problem) {
                    case LINK_EXPIRED:
                        return getString(R.string.dl_link_expired, it.code);
                    case NOT_FOUND:
                        return getString(R.string.dl_not_found, it.code);
                    case NOT_A_FILE:
                        return getString(R.string.dl_not_a_file);
                    case NO_SPACE:
                        return getString(R.string.dl_no_space, Transfers.human(it.needBytes), Transfers.human(it.freeBytes));
                    default:
                        return getString(R.string.dl_failed, it.code);
                }
            default:
                switch (it.problem) {
                    case RETRYING:
                        return getString(R.string.dl_retrying);
                    case WIFI_ONLY:
                        return getString(R.string.dl_waiting_wifi);
                    case ASK_MOBILE:
                        return getString(R.string.dl_ask_mobile, Transfers.human(Math.max(0L, it.size - it.durable)));
                    case SERVER_UNREACHABLE:
                        return getString(R.string.dl_server_unreachable, until(it.retryAtMs));
                    case SERVER_BUSY:
                        return getString(R.string.dl_server_busy, until(it.retryAtMs));
                    default:
                        Transfers.Offline o = engine.transfers.offline();
                        if (o == Transfers.Offline.AIRPLANE) return getString(R.string.dl_waiting_airplane);
                        if (o == Transfers.Offline.DATA_OFF) return getString(R.string.dl_waiting_data_off);
                        return getString(R.string.dl_waiting_signal);
                }
        }
    }

    private String until(long atMs) {
        long s = Math.max(1L, (atMs - System.currentTimeMillis() + 999L) / 1000L);
        return s < 60L ? getString(R.string.time_seconds, s) : getString(R.string.time_minutes, (s + 59L) / 60L);
    }

    // ---- Rows ----

    /** One download, updated in place every half second so taps and presses are never interrupted. */
    private final class DownloadRow {
        final View view;
        final TextView name;
        final TextView amount;
        final TextView status;
        final ProgressBar progress;
        final Button primary;
        final Button secondary;
        Transfers.Item item;
        int barColor;

        DownloadRow() {
            view = getLayoutInflater().inflate(R.layout.item_download, downloads, false);
            name = view.findViewById(R.id.name);
            amount = view.findViewById(R.id.amount);
            status = view.findViewById(R.id.status);
            progress = view.findViewById(R.id.progress);
            primary = view.findViewById(R.id.primary);
            secondary = view.findViewById(R.id.secondary);
            primary.setOnClickListener(v -> onPrimary(item));
            secondary.setOnClickListener(v -> onSecondary(item));
        }

        void bind(Transfers.Item it) {
            item = it;
            setText(name, it.name);
            setText(amount, amountText(it));
            boolean done = it.state == Transfers.State.DONE;
            progress.setVisibility(done ? View.GONE : View.VISIBLE);
            int permille = it.permille();
            boolean unknown = permille < 0
                    && (it.state == Transfers.State.RUNNING || it.state == Transfers.State.QUEUED);
            if (progress.isIndeterminate() != unknown) progress.setIndeterminate(unknown);
            progress.setProgress(Math.max(0, permille));
            int color = getColor(it.state == Transfers.State.FAILED ? R.color.status_offline
                    : it.state == Transfers.State.WAITING ? R.color.status_watch : R.color.accent);
            if (color != barColor) {
                barColor = color;
                progress.setProgressTintList(ColorStateList.valueOf(color));
                progress.setIndeterminateTintList(ColorStateList.valueOf(color));
            }
            setText(status, statusText(it));
            status.setTextColor(getColor(it.state == Transfers.State.FAILED ? R.color.status_offline : R.color.text_2));
            switch (it.state) {
                case DONE:
                    button(primary, R.string.dl_open);
                    button(secondary, R.string.dl_clear);
                    break;
                case FAILED:
                    button(primary, needsNewLink(it) ? R.string.dl_new_link : R.string.dl_try_again);
                    button(secondary, R.string.dl_cancel);
                    break;
                case WAITING:
                    button(primary, it.problem == Transfers.Problem.WIFI_ONLY ? 0
                            : it.problem == Transfers.Problem.ASK_MOBILE ? R.string.dl_use_mobile : R.string.dl_try_now);
                    button(secondary, R.string.dl_cancel);
                    break;
                default:
                    button(primary, 0);
                    button(secondary, R.string.dl_cancel);
            }
        }

        private void button(Button b, int text) {
            if (text == 0) {
                b.setVisibility(View.GONE);
                return;
            }
            b.setVisibility(View.VISIBLE);
            setText(b, getString(text));
        }
    }

    private void onPrimary(Transfers.Item it) {
        if (it == null) return;
        if (it.state == Transfers.State.DONE) {
            open(it);
        } else if (it.state == Transfers.State.FAILED && needsNewLink(it)) {
            showLinkDialog(null, it);
        } else if (it.problem == Transfers.Problem.ASK_MOBILE) {
            engine.transfers.allowMobile(it);
            ensureService();
        } else {
            engine.transfers.retry(it);
            ensureService();
        }
    }

    private void onSecondary(Transfers.Item it) {
        if (it == null) return;
        if (it.state == Transfers.State.DONE) {
            engine.transfers.clear(it);
        } else {
            confirmCancel(it);
        }
    }

    /** One fact about the signal: what it is, a line that explains it, and its value. */
    private final class InfoRow {
        final View view;
        final ImageView icon;
        final SignalBars bars;
        final TextView label;
        final TextView sub;
        final TextView value;
        int iconRes;

        /** {@code iconRes} 0 means a SIM row, which shows signal bars instead of an icon. */
        InfoRow(int iconRes) {
            view = getLayoutInflater().inflate(R.layout.item_info, signalRows, false);
            icon = view.findViewById(R.id.icon);
            bars = view.findViewById(R.id.bars);
            label = view.findViewById(R.id.label);
            sub = view.findViewById(R.id.sub);
            value = view.findViewById(R.id.value);
            view.setScreenReaderFocusable(true);
            if (iconRes == 0) {
                icon.setVisibility(View.GONE);
                bars.setVisibility(View.VISIBLE);
            } else {
                icon(iconRes);
                view.setVisibility(View.GONE);
                signalRows.addView(view);
            }
        }

        void icon(int res) {
            if (res == iconRes) return;
            iconRes = res;
            icon.setImageResource(res);
        }

        void bars(int n) { bars.setBars(n); }

        void show(boolean visible) {
            int v = visible ? View.VISIBLE : View.GONE;
            if (view.getVisibility() != v) view.setVisibility(v);
        }

        void set(String l, String s, String v) {
            setText(label, l);
            boolean hasSub = s != null && !s.isEmpty();
            sub.setVisibility(hasSub ? View.VISIBLE : View.GONE);
            if (hasSub) setText(sub, s);
            boolean hasValue = v != null && !v.isEmpty();
            value.setVisibility(hasValue ? View.VISIBLE : View.GONE);
            if (hasValue) setText(value, v);
        }
    }

    /** A row in the "More" list. */
    private final class MenuRow {
        final TextView badge;

        MenuRow(int iconRes, int title, int subtitle, Runnable action) {
            View view = getLayoutInflater().inflate(R.layout.item_menu, menu, false);
            ((ImageView) view.findViewById(R.id.icon)).setImageResource(iconRes);
            ((TextView) view.findViewById(R.id.title)).setText(title);
            ((TextView) view.findViewById(R.id.subtitle)).setText(subtitle);
            badge = view.findViewById(R.id.badge);
            view.setOnClickListener(v -> action.run());
            menu.addView(view);
        }

        void badge(String text) {
            badge.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            setText(badge, text);
        }
    }

    /** Something to fix, with the button that fixes it. */
    private static final class Banner {
        final String key;
        final int icon;
        final String text;
        final int action;
        final Runnable run;
        final boolean dismissible;

        Banner(String key, int icon, String text, int action, Runnable run, boolean dismissible) {
            this.key = key;
            this.icon = icon;
            this.text = text;
            this.action = action;
            this.run = run;
            this.dismissible = dismissible;
        }
    }
}
