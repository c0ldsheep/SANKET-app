package io.github.c0ldsheep.sanket;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.CellIdentityLte;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import io.github.c0ldsheep.sanket.core.SanketDetector;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the signal of every active SIM once per tick, the way the study's logger did: RSRP and
 * RSRQ of the serving 4G cell and SS-RSRP of 5G when present. For the mobile-data SIM it also
 * reads the serving cell id and the strongest neighbour, which need location permission. Android
 * repeats the last value until the modem refreshes it; the detector handles that.
 */
final class RadioReader {
    /** One SIM's reading. Values the phone did not report are NaN. */
    static final class Sim {
        final int slot;
        final boolean data;
        final String operator;
        final String name;
        final double rsrp;
        final double rsrq;
        final double nr;
        final double neighbour;
        final long cell;

        Sim(int slot, boolean data, String operator, String name, double rsrp, double rsrq, double nr,
            double neighbour, long cell) {
            this.slot = slot;
            this.data = data;
            this.operator = operator;
            this.name = name;
            this.rsrp = rsrp;
            this.rsrq = rsrq;
            this.nr = nr;
            this.neighbour = neighbour;
            this.cell = cell;
        }
    }

    private final Context ctx;
    private final TelephonyManager telephony;
    private final SubscriptionManager subscriptions;

    RadioReader(Context ctx) {
        this.ctx = ctx;
        telephony = ctx.getSystemService(TelephonyManager.class);
        subscriptions = ctx.getSystemService(SubscriptionManager.class);
    }

    List<Sim> read() {
        List<Sim> out = new ArrayList<>();
        if (telephony == null) return out;
        List<CellInfo> cells = null;
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                cells = telephony.getAllCellInfo();
            } catch (SecurityException e) {
                cells = null;
            }
        }
        List<SubscriptionInfo> active = null;
        if (subscriptions != null
                && ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                active = subscriptions.getActiveSubscriptionInfoList();
            } catch (SecurityException e) {
                active = null;
            }
        }
        if (active == null || active.isEmpty()) {
            out.add(read(telephony, 0, true, telephony.getNetworkOperatorName(), cells));
            return out;
        }
        int dataSubscription = SubscriptionManager.getDefaultDataSubscriptionId();
        for (SubscriptionInfo info : active) {
            boolean data = info.getSubscriptionId() == dataSubscription || active.size() == 1;
            CharSequence carrier = info.getCarrierName();
            TelephonyManager tm = telephony.createForSubscriptionId(info.getSubscriptionId());
            out.add(read(tm, info.getSimSlotIndex(), data, carrier == null ? "" : carrier.toString(), data ? cells : null));
        }
        return out;
    }

    private static Sim read(TelephonyManager tm, int slot, boolean data, String name, List<CellInfo> cells) {
        double rsrp = Double.NaN;
        double rsrq = Double.NaN;
        double nr = Double.NaN;
        SignalStrength strength = tm.getSignalStrength();
        if (strength != null) {
            for (CellSignalStrength c : strength.getCellSignalStrengths()) {
                if (c instanceof CellSignalStrengthLte) {
                    CellSignalStrengthLte lte = (CellSignalStrengthLte) c;
                    rsrp = inRange(lte.getRsrp(), SanketDetector.RSRP_MIN, SanketDetector.RSRP_MAX);
                    rsrq = inRange(lte.getRsrq(), SanketDetector.RSRQ_MIN, SanketDetector.RSRQ_MAX);
                } else if (c instanceof CellSignalStrengthNr) {
                    nr = inRange(((CellSignalStrengthNr) c).getSsRsrp(), -140.0, -44.0);
                }
            }
        }
        String operator = tm.getNetworkOperator();
        if (operator == null) operator = "";
        long cell = SanketDetector.UNKNOWN_CELL;
        double neighbour = Double.NaN;
        if (cells != null && operator.length() >= 5) {
            String mcc = operator.substring(0, 3);
            String mnc = operator.substring(3);
            for (CellInfo info : cells) {
                if (!(info instanceof CellInfoLte)) continue;
                CellInfoLte lte = (CellInfoLte) info;
                if (info.isRegistered()) {
                    CellIdentityLte id = lte.getCellIdentity();
                    if (mcc.equals(id.getMccString()) && mnc.equals(id.getMncString()) && id.getCi() != CellInfo.UNAVAILABLE) {
                        cell = id.getCi();
                    }
                } else {
                    double r = inRange(lte.getCellSignalStrength().getRsrp(), SanketDetector.RSRP_MIN, SanketDetector.RSRP_MAX);
                    if (!Double.isNaN(r) && (Double.isNaN(neighbour) || r > neighbour)) neighbour = r;
                }
            }
        }
        return new Sim(slot, data, operator, name == null ? "" : name, rsrp, rsrq, nr, neighbour, cell);
    }

    static boolean validNr(double v) { return v >= -140.0 && v <= -44.0; }

    private static double inRange(int v, double lo, double hi) {
        return v != CellInfo.UNAVAILABLE && v >= lo && v <= hi ? v : Double.NaN;
    }
}
