"""Figures for the report (static, print-ready PNG).

Style: thin marks, hairline solid gridlines, text in ink colours (never the series colour),
a legend whenever there are two or more series plus selective direct labels, no dual axes.
Series colours follow a fixed, colour-blind-validated order (slot 1 = the proposed method).
"""
from __future__ import annotations

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

INK = "#0b0b0b"
INK2 = "#52514e"
MUTED = "#898781"
GRID = "#e1e0d9"
AXIS = "#c3c2b7"
SURFACE = "#ffffff"
SERIES = {"sanket": "#2a78d6", "threshold": "#eb6834", "slope_ttl": "#1baf7a",
          "naive_gradient": "#eda100", "logistic": "#e87ba4", "extra": "#4a3aa7"}
LABEL = {"sanket": "SANKET (proposed)", "threshold": "Static threshold", "slope_ttl": "Regression slope + TTL",
         "naive_gradient": "Naive gradient", "logistic": "Logistic regression (ML)"}
MARKER = {"sanket": "o", "threshold": "s", "slope_ttl": "^", "naive_gradient": "D", "logistic": "v"}
CRITICAL = "#d03b3b"
GOOD = "#0ca30c"


def setup():
    plt.rcParams.update({
        "font.family": "sans-serif",
        "font.sans-serif": ["Helvetica Neue", "Helvetica", "Arial", "DejaVu Sans"],
        "font.size": 9, "axes.titlesize": 10, "axes.labelsize": 9, "xtick.labelsize": 8, "ytick.labelsize": 8,
        "axes.edgecolor": AXIS, "axes.linewidth": 0.8, "axes.labelcolor": INK2, "axes.titlecolor": INK,
        "axes.titleweight": "bold", "axes.titlelocation": "left", "axes.grid": True, "grid.color": GRID,
        "grid.linewidth": 0.6, "grid.linestyle": "-", "xtick.color": MUTED, "ytick.color": MUTED,
        "xtick.labelcolor": INK2, "ytick.labelcolor": INK2, "axes.spines.top": False, "axes.spines.right": False,
        "legend.frameon": False, "legend.fontsize": 8, "lines.linewidth": 1.8, "lines.solid_capstyle": "round",
        "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
        "savefig.dpi": 220, "savefig.bbox": "tight",
    })


def _note(ax, text, xy=(0.0, -0.22)):
    ax.annotate(text, xy=xy, xycoords="axes fraction", fontsize=7, color=MUTED, ha="left", va="top")


def anatomy(tr, alarms: dict, path, title):
    """One entry: observed reports, true level, RSRQ and the moment each method fires."""
    setup()
    fig, axes = plt.subplots(3, 1, figsize=(7.2, 5.0), sharex=True, height_ratios=[3.0, 1.4, 1.2])
    T = tr.episodes[0][0] if tr.episodes else None
    te = tr.meta.get("t_entry")
    tt = tr.truth["t"]
    ax = axes[0]
    ax.plot(tt, tr.truth["s_dbm"], color=MUTED, lw=1.0, label="True signal (hidden from the phone)")
    ax.step(tr.t, tr.rsrp, where="post", color=SERIES["sanket"], lw=1.6, label="What the phone reports (RSRP)")
    ax.axhline(-124, color=AXIS, lw=0.8)
    ax.text(tr.t[0] + 1, -123.2, "no-service level (Qrxlevmin, -124 dBm)", fontsize=7, color=INK2, va="bottom")
    ax.set_ylabel("RSRP (dBm)")
    ax.set_title(title)
    for a in axes:
        if te is not None and np.isfinite(te):
            a.axvline(te, color=AXIS, lw=0.8)
        if T is not None:
            a.axvspan(T, tr.t[-1], color=CRITICAL, alpha=0.07, lw=0)
    if te is not None and np.isfinite(te):
        ax.text(te + 0.5, ax.get_ylim()[1] - 2, "enters ramp", fontsize=7, color=INK2, va="top")
    if T is not None:
        ax.text(T + 0.5, ax.get_ylim()[1] - 2, "link lost", fontsize=7, color=CRITICAL, va="top")
    ymin, ymax = ax.get_ylim()
    t_s = alarms.get("sanket")
    if t_s is not None and T is not None:
        ax.axvspan(t_s, T, color=SERIES["sanket"], alpha=0.10, lw=0)
        ax.text((t_s + T) / 2, ymax - (ymax - ymin) * 0.40, f"{T - t_s:.0f} s to\nprefetch", fontsize=7,
                color=INK, ha="center", va="center")
    for k, (name, t_alarm) in enumerate(alarms.items()):
        if t_alarm is None:
            continue
        y = ymax - (ymax - ymin) * (0.16 + 0.11 * k)
        ax.plot([t_alarm], [y], marker=MARKER.get(name, "o"), ms=6, color=SERIES.get(name, INK), ls="none",
                mec=SURFACE, mew=1.2)
        lead = (T - t_alarm) if T is not None else None
        txt = f"{LABEL.get(name, name)} alarm" + (f": {lead:.0f} s before loss" if lead is not None else "")
        x_txt = t_alarm + 0.8 if (T is None or (T - t_alarm) > 12) else T + 1.5   # keep text off the curve
        ax.text(x_txt, y, txt, fontsize=7, color=INK, va="center", ha="left")
    ax.legend(loc="lower left", fontsize=7)
    axes[1].step(tr.t, tr.rsrq, where="post", color=SERIES["slope_ttl"], lw=1.4)
    axes[1].set_ylabel("RSRQ (dB)")
    axes[2].plot(tt, tr.truth["sinr"], color=SERIES["extra"], lw=1.2)
    axes[2].axhline(-8, color=AXIS, lw=0.8)
    axes[2].text(tr.t[0] + 1, -7.2, "radio-link-failure level (-8 dB)", fontsize=7, color=INK2, va="bottom")
    axes[2].set_ylabel("True SINR (dB)")
    axes[2].set_xlabel("Time (s)")
    fig.align_ylabels(axes)
    fig.savefig(path)
    plt.close(fig)


def decay_characterisation(entry_slopes: dict, background: dict, entry_to_loss: dict, path):
    setup()
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(7.4, 3.4))
    cols = [SERIES["sanket"], SERIES["threshold"], SERIES["slope_ttl"]]
    for (name, x), c in zip(entry_slopes.items(), cols):
        x = np.sort(np.asarray(x))
        a1.plot(x, np.arange(1, x.size + 1) / x.size, color=c, lw=1.8, label=name)
    greys = ["#52514e", "#898781", "#c3c2b7"]
    for (name, x), c in zip(background.items(), greys):
        x = np.sort(np.asarray(x))
        a1.plot(x, np.arange(1, x.size + 1) / x.size, color=c, lw=1.4, label=name)
    a1.set_xlim(-6, 1)
    a1.set_xlabel("Steepest 10 s RSRP slope (dB/s)")
    a1.set_ylabel("Cumulative fraction")
    a1.set_title("How fast the signal falls")
    a1.legend(loc="upper center", bbox_to_anchor=(0.62, -0.30), ncol=2, fontsize=7.5, handlelength=1.5)
    names = list(entry_to_loss)
    data = [np.asarray(entry_to_loss[n]) for n in names]
    pos = np.arange(len(names))
    for i, d in enumerate(data):
        d = d[np.isfinite(d)]
        q1, med, q3 = np.percentile(d, [25, 50, 75])
        p10, p90 = np.percentile(d, [10, 90])
        a2.plot([p10, p90], [i, i], color=AXIS, lw=1.2)
        a2.plot([q1, q3], [i, i], color=cols[i % 3], lw=6, solid_capstyle="round")
        a2.plot([med], [i], marker="o", ms=6, color=INK, mec=SURFACE, mew=1.2)
        a2.text(p90 + 1, i, f"median {med:.0f} s; {np.mean(d >= 10) * 100:.0f}% allow \u2265 10 s", va="center",
                fontsize=7, color=INK2)
    a2.axvline(10, color=CRITICAL, lw=1.0)
    a2.text(10.5, len(names) - 0.45, "10 s target", fontsize=7, color=CRITICAL)
    a2.set_yticks(pos, names)
    a2.set_xlim(0, 75)
    a2.set_ylim(-0.6, len(names) - 0.3)
    a2.set_xlabel("Time from entrance to link loss (s)")
    a2.set_title("Physical limit on warning time")
    a2.grid(axis="y", visible=False)
    fig.tight_layout(w_pad=2.0)
    fig.savefig(path)
    plt.close(fig)


def tradeoff(curves: dict, chosen: dict, path, target=0.85, hybrid=None):
    setup()
    fig, ax = plt.subplots(figsize=(6.4, 3.6))
    for name, (fa, rec) in curves.items():
        o = np.argsort(fa)
        fa, rec = np.asarray(fa)[o], np.asarray(rec)[o]
        ax.plot(fa, rec * 100, color=SERIES[name], lw=1.8, label=LABEL[name], marker=MARKER[name], ms=4,
                mec=SURFACE, mew=0.6, markevery=max(1, fa.size // 6))
    for name, (fa, rec) in chosen.items():
        ax.plot([fa], [rec * 100], marker=MARKER[name], ms=8, color=SERIES[name], mec=INK, mew=0.9, ls="none")
    ax.axhline(target * 100, color=CRITICAL, lw=1.0)
    ax.text(55, target * 100 + 1.2, f"{target * 100:.0f}% target", fontsize=7, color=CRITICAL, ha="right")
    ax.set_xscale("log")
    ax.set_xlim(0.1, 60)
    ax.set_ylim(0, 100)
    ax.set_xlabel("False alarms per hour on real rides (UCC traces, test split, log scale)")
    ax.set_ylabel("Entries warned \u2265 10 s ahead (%)")
    ax.set_title("Detection vs false alarms (big markers = settings chosen on calibration data)")
    ax.legend(loc="upper left", fontsize=7, ncol=1, bbox_to_anchor=(0.0, 0.82))
    fig.savefig(path)
    plt.close(fig)


def lead_cdf(leads: dict, oracle, path):
    setup()
    fig, ax = plt.subplots(figsize=(6.4, 3.2))
    o = np.sort(np.asarray([x for x in oracle if np.isfinite(x)]))
    n_ev = len(oracle)
    ax.plot(np.r_[0, o], np.r_[1, 1 - np.arange(1, o.size + 1) / n_ev] * 100, color=INK2, lw=1.2,
            label="Physical limit (time from entrance to loss)")
    for name, x in leads.items():
        x = np.asarray(x, dtype=float)
        n = x.size
        xs = np.sort(x[np.isfinite(x)])
        ax.plot(np.r_[0, xs], np.r_[np.isfinite(x).sum() / n, np.isfinite(x).sum() / n - np.arange(1, xs.size + 1) / n] * 100,
                color=SERIES[name], lw=1.8, label=LABEL[name], drawstyle="steps-post")
    for L in (10, 15):
        ax.axvline(L, color=AXIS, lw=0.8)
        ax.text(L + 0.4, 97, f"{L} s", fontsize=7, color=INK2, va="top")
    at10 = [("limit", float(np.sum(o >= 10)) / n_ev)]
    at10 += [(nm, float(np.sum(np.asarray(v, dtype=float) >= 10)) / len(v)) for nm, v in leads.items()]
    for _, frac in at10:
        ax.text(9.4, frac * 100 + 1.5, f"{frac * 100:.0f}%", fontsize=7, color=INK, ha="right", va="bottom",
                bbox=dict(boxstyle="round,pad=0.15", fc=SURFACE, ec="none", alpha=0.9))
    ax.set_xlim(0, 60)
    ax.set_ylim(0, 100)
    ax.set_xlabel("Warning time before the link is lost (s)")
    ax.set_ylabel("Entries warned at least this early (%)")
    ax.set_title("Warning time on unseen simulated entries")
    ax.legend(loc="upper right", fontsize=7)
    fig.savefig(path)
    plt.close(fig)


SHORT_TIER = {"T0": "Order token", "T1": "Customer card", "T2": "Route", "T3": "Map tiles"}


def prefetch_curves(leads, curves: dict, req: dict, path, compact=False):
    setup()
    fig, ax = plt.subplots(figsize=(3.5, 2.75) if compact else (6.4, 3.0))
    cols = [SERIES["sanket"], SERIES["threshold"], SERIES["slope_ttl"], SERIES["naive_gradient"]]
    for (name, ys), c in zip(curves.items(), cols):
        label = SHORT_TIER.get(name[:2], name) if compact else name
        ax.plot(leads, np.asarray(ys) * 100, color=c, lw=1.8, marker="o", ms=3.5, mec=SURFACE, mew=0.6,
                label=f"{label} (99% at {req[name]:.0f} s)" if np.isfinite(req[name]) else label)
    ax.axhline(99, color=AXIS, lw=0.8)
    ax.set_xlabel("Warning time before the link is lost (s)")
    ax.set_ylabel("Downloads finished in time (%)")
    ax.set_title("Warning each part of the order data needs" if compact
                 else "How much warning each part of the delivery manifest needs")
    ax.set_ylim(0, 102)
    ax.set_xlim(0, max(leads))
    ax.legend(loc="lower right", fontsize=7)
    fig.savefig(path)
    plt.close(fig)


def fleet(daily, path, target=0.85, compact=False):
    setup()
    fig, ax = plt.subplots(figsize=(3.5, 2.75) if compact else (6.4, 3.0))
    d = daily["day"].to_numpy()
    names = (("SANKET + memory", "Memory alone", "Signal only") if compact else
             ("SANKET + dead-zone memory", "Dead-zone memory alone", "SANKET signal-only"))
    for key, name, c in (("hybrid", names[0], SERIES["sanket"]),
                         ("memory", names[1], SERIES["slope_ttl"]),
                         ("signal", names[2], SERIES["threshold"])):
        y = daily[key].to_numpy() * 100
        ax.plot(d, y, color=c, lw=1.8, marker="o", ms=4, mec=SURFACE, mew=0.8, label=name)
        if not (compact and key == "memory"):           # close to the hybrid line: the legend names it
            ax.text(d[-1] + 0.15, y[-1], f"{y[-1]:.0f}%", fontsize=7, color=INK, va="center")
    ax.axhline(target * 100, color=CRITICAL, lw=1.0)
    ax.text(d[-1], target * 100 - 2.0, f"{target * 100:.0f}% target", fontsize=7, color=CRITICAL, ha="right", va="top")
    ax.set_xticks(d)
    ax.set_xlim(d[0] - 0.3, d[-1] + 0.8)
    ax.set_ylim(0, 100)
    ax.set_xlabel("Day of fleet operation")
    ax.set_ylabel("Entries warned \u2265 10 s ahead (%)")
    ax.set_title("A fleet that remembers dead zones" if compact else
                 "A fleet that remembers dead zones keeps getting better")
    ax.legend(loc="lower right", fontsize=7)
    fig.savefig(path)
    plt.close(fig)


def common_mode(fa_pts, tp_pts, path):
    setup()
    fig, ax = plt.subplots(figsize=(4.6, 3.6))
    tp = np.asarray(tp_pts)
    fa = np.asarray(fa_pts)
    ax.plot([-6, 2], [-6, 2], color=AXIS, lw=0.8)
    ax.scatter(tp[:, 0], tp[:, 1], s=9, color=SERIES["sanket"], alpha=0.55, lw=0,
               label=f"Simulated basement entries (physics model, n={len(tp)})")
    ax.scatter(fa[:, 0], fa[:, 1], s=14, color=SERIES["threshold"], alpha=0.85, lw=0, marker="s",
               label=f"Real false alarms, UCC traces (n={len(fa)})")
    ax.set_xlim(-6, 1)
    ax.set_ylim(-6, 3)
    ax.set_xlabel("Serving cell trend (dB/s)")
    ax.set_ylabel("Neighbour cell trend (dB/s)")
    ax.set_title("Inside concrete, every cell fades together")
    ax.text(-5.8, -5.3, "on the diagonal:\nall cells fade together", fontsize=7, color=INK2)
    ax.text(-2.9, 2.2, "neighbour holds: handover\nor street shadowing", fontsize=7, color=INK2)
    ax.legend(loc="lower right", fontsize=6.5)
    fig.savefig(path)
    plt.close(fig)


def calibration(real_slopes: dict, sim_slopes: dict, path):
    setup()
    fig, ax = plt.subplots(figsize=(5.6, 3.0))
    cols = {"walk": SERIES["sanket"], "ride": SERIES["threshold"]}
    for key, label in (("walk", "walking"), ("ride", "riding")):
        r = np.sort(real_slopes[key])
        s = np.sort(sim_slopes[key])
        ax.plot(r, np.arange(1, r.size + 1) / r.size, color=cols[key], lw=1.8, label=f"Real UCC traces, {label}")
        ax.plot(s, np.arange(1, s.size + 1) / s.size, color=cols[key], lw=1.0, alpha=0.7,
                label=f"Simulator, {label}")
    ax.set_xlim(-3, 0.5)
    ax.set_ylim(0, 0.35)
    ax.set_xlabel("10 s RSRP slope (dB/s), outdoor riding and walking")
    ax.set_ylabel("Cumulative fraction")
    ax.set_title("Simulator noise calibrated to real drive tests")
    ax.legend(loc="upper left", fontsize=7)
    fig.savefig(path)
    plt.close(fig)
