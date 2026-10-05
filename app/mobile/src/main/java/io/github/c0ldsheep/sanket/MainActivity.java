package io.github.c0ldsheep.sanket;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import io.github.c0ldsheep.sanket.core.GuardPolicy;
import io.github.c0ldsheep.sanket.core.ZoneMemory;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The one screen: protection status, signal details, protected downloads, places, safety log and privacy. */
public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1;
    private static final String PREF_CONSENT = "consent_v1";
    private static final Pattern HTTPS_LINK = Pattern.compile("https://\\S+");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 500L);
        }
    };

    private Engine engine;
    private TextView status;
    private TextView reasons;
    private TextView risk;
    private TextView details;
    private TextView downloadsEmpty;
    private ProgressBar riskBar;
    private Button protect;
    private LinearLayout downloads;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        engine = Engine.get(this);
        status = findViewById(R.id.status);
        reasons = findViewById(R.id.reasons);
        risk = findViewById(R.id.risk);
        riskBar = findViewById(R.id.riskBar);
        details = findViewById(R.id.details);
        protect = findViewById(R.id.protect);
        downloads = findViewById(R.id.downloads);
        downloadsEmpty = findViewById(R.id.downloadsEmpty);
        protect.setOnClickListener(v -> toggleProtection());
        findViewById(R.id.download).setOnClickListener(v -> withConsent(this::askForLink));
        findViewById(R.id.demo).setOnClickListener(v -> GuardService.send(this, GuardService.ACTION_DEMO));
        findViewById(R.id.places).setOnClickListener(v -> showPlaces());
        findViewById(R.id.log).setOnClickListener(v -> showLog());
        findViewById(R.id.privacy).setOnClickListener(v -> showPrivacy());
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
        if (engine.stoppedBySystem()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.killed_title)
                    .setMessage(R.string.killed_text)
                    .setPositiveButton(R.string.open_settings,
                            (d, w) -> startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)))
                    .setNegativeButton(R.string.close, null)
                    .show();
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Snapshot s = engine.snapshot();
        if (requestCode == REQUEST_PERMISSIONS && s.running && !s.demo) {
            GuardService.send(this, GuardService.ACTION_START);   // picks up the new permissions
        }
    }

    private void toggleProtection() {
        Snapshot s = engine.snapshot();
        if (s.running && !s.demo) {
            GuardService.stop(this);
            return;
        }
        withConsent(() -> {
            askPermissions();
            GuardService.send(this, GuardService.ACTION_START);
        });
    }

    private void withConsent(Runnable then) {
        SharedPreferences prefs = getSharedPreferences(Stores.PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(PREF_CONSENT, false)) {
            then.run();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.consent_title)
                .setMessage(R.string.privacy_text)
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
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private void askForLink() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.link_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.download_file)
                .setView(input)
                .setPositiveButton(R.string.download, (d, w) -> startDownload(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        setIntent(new Intent(this, MainActivity.class));   // do not ask again after rotation
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        Matcher m = HTTPS_LINK.matcher(text == null ? "" : text);
        if (!m.find()) {
            Toast.makeText(this, R.string.only_https, Toast.LENGTH_LONG).show();
            return;
        }
        String link = m.group();
        withConsent(() -> new AlertDialog.Builder(this)
                .setTitle(R.string.download_file)
                .setMessage(link)
                .setPositiveButton(R.string.download, (d, w) -> startDownload(link))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private void startDownload(String link) {
        if (!engine.transfers.add(link)) {
            Toast.makeText(this, R.string.only_https, Toast.LENGTH_LONG).show();
            return;
        }
        if (!engine.snapshot().running) GuardService.send(this, GuardService.ACTION_DOWNLOADS);
    }

    private void render() {
        Snapshot s = engine.snapshot();
        status.setText(title(s));
        status.setTextColor(getColor(color(s)));
        reasons.setText(s.running ? String.join(" · ", s.reasons) : getString(R.string.off_hint));
        int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, s.risk)) * 100.0);
        riskBar.setProgress(s.running ? pct : 0);
        risk.setText(s.running ? riskLine(s, pct) : "");
        details.setText(String.join("\n", s.details));
        protect.setText(s.running && !s.demo ? R.string.stop_protection : R.string.start_protection);
        renderDownloads();
    }

    private String title(Snapshot s) {
        if (!s.running) return getString(R.string.status_off);
        String text;
        switch (s.level) {
            case WATCH:
                text = getString(R.string.status_watch);
                break;
            case PROTECT:
                text = getString(R.string.status_protect);
                break;
            case OFFLINE:
                text = getString(R.string.status_offline);
                break;
            default:
                text = getString(R.string.status_clear);
        }
        return s.demo ? getString(R.string.demo_prefix, Math.round(s.demoSeconds), text) : text;
    }

    private static int color(Snapshot s) {
        if (!s.running) return R.color.status_off;
        switch (s.level) {
            case WATCH:
                return R.color.status_watch;
            case PROTECT:
                return R.color.status_protect;
            case OFFLINE:
                return R.color.status_offline;
            default:
                return R.color.status_clear;
        }
    }

    private String riskLine(Snapshot s, int pct) {
        String line = getString(R.string.risk_line, pct, Math.round(s.horizonS));
        if (s.timeToLossS < 60.0 && s.level != GuardPolicy.Level.OFFLINE) {
            line += " " + getString(R.string.ttl_line, Math.round(s.timeToLossS));
        }
        return line;
    }

    private void renderDownloads() {
        downloads.removeAllViews();
        List<Transfers.Item> items = engine.transfers.items();
        downloadsEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        for (Transfers.Item it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));
            TextView label = new TextView(this);
            int pct = it.percent();
            String amount = pct >= 0 ? pct + "%" : Transfers.human(it.done);
            label.setText(getString(R.string.download_row, it.name, amount, it.message));
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setIndeterminate(pct < 0 && it.state == Transfers.State.RUNNING);
            bar.setProgress(Math.max(0, pct));
            row.addView(label);
            row.addView(bar);
            row.setOnClickListener(v -> {
                if (it.state == Transfers.State.WAITING || it.state == Transfers.State.FAILED) {
                    engine.transfers.retry(it);
                    if (!engine.snapshot().running) GuardService.send(this, GuardService.ACTION_DOWNLOADS);
                }
            });
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.remove_download)
                        .setPositiveButton(R.string.remove, (d, w) -> engine.transfers.remove(it))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });
            downloads.addView(row);
        }
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.place_note_title)
                .setMessage(engine.describe(z))
                .setView(note)
                .setPositiveButton(R.string.save, (d, w) -> engine.setNote(z, note.getText().toString()))
                .setNeutralButton(R.string.forget_place, (d, w) -> engine.forget(z))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showLog() {
        String text = engine.safetyLogText();
        TextView view = new TextView(this);
        view.setText(text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(20), dp(8), dp(20), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle(R.string.safety_log)
                .setView(scroll)
                .setPositiveButton(R.string.share, (d, w) -> share(text))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void share(String text) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, getString(R.string.share)));
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
                .setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    GuardService.stop(this);
                    engine.wipe();
                    Toast.makeText(this, R.string.deleted, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
