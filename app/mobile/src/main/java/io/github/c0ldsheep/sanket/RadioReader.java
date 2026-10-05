package io.github.c0ldsheep.sanket;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.CellSignalStrengthTdscdma;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import io.github.c0ldsheep.sanket.core.SanketDetector;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the signal of every active SIM once per tick: RSRP and RSRQ of the serving 4G cell and
 * SS-RSRP of 5G. For the mobile-data SIM it also reads the serving cell and the strongest
 * neighbour, on 4G and on standalone 5G, from that SIM's own radio.
 *
 * <p>Since Android 10 the cell list an app can read is a saved copy, and many phones stop sending
 * fresh signal readings while the screen is off. So every two seconds this asks the modem for a
 * fresh list, and uses a serving-cell reading from it when it is newer than the phone's last
 * signal report.
 */
final class RadioReader {
    /** One SIM's reading. Values the phone did not report are NaN. */
    static final class Sim {
        final int slot;
        final int subId;
        final boolean data;
        final String operator;
        final String name;
        final double rsrp;
        final double rsrq;
        final double nr;
        final double neighbour;
        final long cell;
        final double nrNeighbour;
        final long nrCell;
        /** 2G or 3G signal in dBm when the phone is on one of those, else NaN. */
        final double legacy;
        /** "2G", "3G", or "" with no such reading. */
        final String legacyTech;

        Sim(int slot, int subId, boolean data, String operator, String name, double rsrp, double rsrq, double nr,
            double neighbour, long cell, double nrNeighbour, long nrCell, double legacy, String legacyTech) {
            this.slot = slot;
            this.subId = subId;
            this.data = data;
            this.operator = operator;
            this.name = name;
            this.rsrp = rsrp;
            this.rsrq = rsrq;
            this.nr = nr;
            this.neighbour = neighbour;
            this.cell = cell;
            this.nrNeighbour = nrNeighbour;
            this.nrCell = nrCell;
            this.legacy = legacy;
            this.legacyTech = legacyTech;
        }
    }

    /** Choices the user made that look like signal problems but are not. */
    static final class Status {
        static final Status NORMAL = new Status(false, true, true);
        final boolean airplane;
        final boolean simPresent;
        final boolean dataEnabled;

        Status(boolean airplane, boolean simPresent, boolean dataEnabled) {
            this.airplane = airplane;
            this.simPresent = simPresent;
            this.dataEnabled = dataEnabled;
        }
    }

    private static final String TAG = "SanketRadio";
    private static final long FRESH_EVERY_MS = 2_000L;
    private static final long FRESH_MAX_AGE_MS = 10_000L;

    private final Context ctx;
    private final TelephonyManager telephony;
    private final SubscriptionManager subscriptions;
    // Written by the modem's callback thread, read by the service's worker thread.
    private volatile List<CellInfo> freshCells;
    private volatile int freshSub = Integer.MIN_VALUE;
    private volatile long freshAtMs;
    private long requestedAtMs = Long.MIN_VALUE / 2;

    RadioReader(Context ctx) {
        this.ctx = ctx;
        telephony = ctx.getSystemService(TelephonyManager.class);
        subscriptions = ctx.getSystemService(SubscriptionManager.class);
    }

    List<Sim> read() {
        List<Sim> out = new ArrayList<>();
        if (telephony == null) return out;
        List<SubscriptionInfo> active = activeSubscriptions();
        if (active == null || active.isEmpty()) {
            int sub = SubscriptionManager.INVALID_SUBSCRIPTION_ID;
            out.add(read(telephony, 0, sub, true, telephony.getNetworkOperatorName(), cells(telephony, sub)));
            return out;
        }
        int dataSub = SubscriptionManager.getDefaultDataSubscriptionId();
        for (SubscriptionInfo info : active) {
            int sub = info.getSubscriptionId();
            boolean data = sub == dataSub || active.size() == 1;
            TelephonyManager tm = telephony.createForSubscriptionId(sub);
            CharSequence carrier = info.getCarrierName();
            out.add(read(tm, info.getSimSlotIndex(), sub, data, carrier == null ? "" : carrier.toString(),
                    data ? cells(tm, sub) : null));
        }
        return out;
    }

    Status status() {
        boolean airplane = Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        return new Status(airplane, simPresent(), dataEnabled());
    }

    static boolean validNr(double v) { return v >= -140.0 && v <= -44.0; }

    private List<SubscriptionInfo> activeSubscriptions() {
        if (subscriptions == null
                || ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            return subscriptions.getActiveSubscriptionInfoList();
        } catch (SecurityException e) {
            return null;
        }
    }

    /** The data SIM's cells: a fresh list when the modem sent one recently, otherwise the saved one. */
    private List<CellInfo> cells(TelephonyManager tm, int sub) {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - requestedAtMs >= FRESH_EVERY_MS) {
            requestedAtMs = now;
            try {
                tm.requestCellInfoUpdate(Runnable::run, new TelephonyManager.CellInfoCallback() {
                    @Override
                    public void onCellInfo(List<CellInfo> cells) {
                        freshCells = cells;
                        freshSub = sub;
                        freshAtMs = SystemClock.elapsedRealtime();
                    }

                    @Override
                    public void onError(int errorCode, Throwable detail) {
                        Log.d(TAG, "Fresh cell list unavailable: " + errorCode);
                    }
                });
            } catch (SecurityException | IllegalStateException e) {
                Log.d(TAG, "Cannot ask for a fresh cell list", e);
            }
        }
        List<CellInfo> fresh = freshCells;
        if (fresh != null && freshSub == sub && now - freshAtMs <= FRESH_MAX_AGE_MS) return fresh;
        try {
            return tm.getAllCellInfo();
        } catch (SecurityException e) {
            return null;
        }
    }

    private static Sim read(TelephonyManager tm, int slot, int sub, boolean data, String name, List<CellInfo> cells) {
        double rsrp = Double.NaN;
        double rsrq = Double.NaN;
        double nr = Double.NaN;
        double legacy = Double.NaN;
        String legacyTech = "";
        long reportedAtMs = Long.MIN_VALUE;
        SignalStrength strength = tm.getSignalStrength();
        if (strength != null) {
            for (CellSignalStrength c : strength.getCellSignalStrengths()) {
                if (c instanceof CellSignalStrengthLte) {
                    CellSignalStrengthLte lte = (CellSignalStrengthLte) c;
                    rsrp = inRange(lte.getRsrp(), SanketDetector.RSRP_MIN, SanketDetector.RSRP_MAX);
                    rsrq = inRange(lte.getRsrq(), SanketDetector.RSRQ_MIN, SanketDetector.RSRQ_MAX);
                } else if (c instanceof CellSignalStrengthNr) {
                    nr = inRange(((CellSignalStrengthNr) c).getSsRsrp(), -140.0, -44.0);
                } else if (c instanceof CellSignalStrengthWcdma || c instanceof CellSignalStrengthTdscdma) {
                    double dbm = inRange(c.getDbm(), -140.0, -1.0);
                    if (!Double.isNaN(dbm)) {
                        legacy = dbm;
                        legacyTech = "3G";
                    }
                } else if ((c instanceof CellSignalStrengthGsm || c instanceof CellSignalStrengthCdma) && legacyTech.isEmpty()) {
                    double dbm = inRange(c.getDbm(), -140.0, -1.0);
                    if (!Double.isNaN(dbm)) {
                        legacy = dbm;
                        legacyTech = "2G";
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) reportedAtMs = strength.getTimestampMillis();
        }
        String operator = tm.getNetworkOperator();
        if (operator == null) operator = "";
        String mcc = operator.length() >= 5 ? operator.substring(0, 3) : null;
        String mnc = operator.length() >= 5 ? operator.substring(3) : null;
        long cell = SanketDetector.UNKNOWN_CELL;
        long nrCell = SanketDetector.UNKNOWN_CELL;
        double neighbour = Double.NaN;
        double nrNeighbour = Double.NaN;
        if (cells != null) {
            for (CellInfo info : cells) {
                boolean fresher = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && info.getTimestampMillis() > reportedAtMs;
                if (info instanceof CellInfoLte) {
                    CellInfoLte lte = (CellInfoLte) info;
                    CellSignalStrengthLte s = lte.getCellSignalStrength();
                    double r = inRange(s.getRsrp(), SanketDetector.RSRP_MIN, SanketDetector.RSRP_MAX);
                    if (info.isRegistered()) {
                        CellIdentityLte id = lte.getCellIdentity();
                        if (samePlmn(id.getMccString(), id.getMncString(), mcc, mnc)) {
                            if (id.getCi() != CellInfo.UNAVAILABLE) cell = id.getCi();
                            if (fresher && !Double.isNaN(r)) {
                                rsrp = r;
                                rsrq = inRange(s.getRsrq(), SanketDetector.RSRQ_MIN, SanketDetector.RSRQ_MAX);
                            }
                        }
                    } else if (!Double.isNaN(r) && (Double.isNaN(neighbour) || r > neighbour)) {
                        neighbour = r;
                    }
                } else if (info instanceof CellInfoNr) {
                    CellInfoNr n = (CellInfoNr) info;
                    double r = inRange(((CellSignalStrengthNr) n.getCellSignalStrength()).getSsRsrp(), -140.0, -44.0);
                    if (info.isRegistered()) {
                        CellIdentityNr id = (CellIdentityNr) n.getCellIdentity();
                        if (samePlmn(id.getMccString(), id.getMncString(), mcc, mnc)) {
                            if (id.getNci() != CellInfo.UNAVAILABLE_LONG) nrCell = id.getNci();
                            if (fresher && !Double.isNaN(r)) nr = r;
                        }
                    } else if (!Double.isNaN(r) && (Double.isNaN(nrNeighbour) || r > nrNeighbour)) {
                        nrNeighbour = r;
                    }
                }
            }
        }
        return new Sim(slot, sub, data, operator, name == null ? "" : name, rsrp, rsrq, nr, neighbour, cell,
                nrNeighbour, nrCell, legacy, legacyTech);
    }

    /** Matches a cell to the SIM's network. A cell that does not name its network belongs to this radio. */
    private static boolean samePlmn(String mcc, String mnc, String wantMcc, String wantMnc) {
        if (mcc == null || mnc == null || wantMcc == null) return true;
        return mcc.equals(wantMcc) && mnc.equals(wantMnc);
    }

    private boolean simPresent() {
        if (telephony == null) return false;
        int slots = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? telephony.getActiveModemCount() : phoneCount();
        for (int i = 0; i < Math.max(1, slots); i++) {
            int state = telephony.getSimState(i);
            if (state != TelephonyManager.SIM_STATE_ABSENT && state != TelephonyManager.SIM_STATE_UNKNOWN) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private int phoneCount() { return telephony.getPhoneCount(); }

    private boolean dataEnabled() {
        if (telephony == null) return false;
        try {
            return telephony.isDataEnabled();
        } catch (SecurityException e) {
            return true;
        }
    }

    private static double inRange(int v, double lo, double hi) {
        return v != CellInfo.UNAVAILABLE && v >= lo && v <= hi ? v : Double.NaN;
    }
}
