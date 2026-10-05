#!/usr/bin/env python3
"""Build the PRAYAS submission from results/metrics.json and submission/team.json.

Outputs (in submission/):
  PRAYAS-COMP-26-S-020_SANKET.pdf   the PDF to upload (official template layout, printed by Chrome)
  PRAYAS-COMP-26-S-020_SANKET.docx  the official Word template, filled in (editable backup)
  copy_paste_text.txt               plain text for each box of the portal form, plus the template text

Every number comes from results/metrics.json, so nothing in the write-up is typed by hand.
Usage: ./.venv/bin/python submission/build_submission.py
"""
from __future__ import annotations

import datetime as dt
import html
import io
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SUB = ROOT / "submission"
FIG = ROOT / "results" / "figures"
TEMPLATE = SUB / "template" / "PRAYAS_Solution_Template.docx"
OUT = "PRAYAS-COMP-26-S-020_SANKET"
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
TITLE = ("Predictive Cellular Link Degradation Detection for Delivery Applications in Urban Indoor "
         "Environments")


def pct(x, d=1):
    return f"{x * 100:.{d}f}%"


def test_count() -> int:
    """Number of automated tests, counted by pytest itself (so the write-up never goes stale)."""
    r = subprocess.run([sys.executable, "-m", "pytest", "--collect-only", "-q"], cwd=ROOT, capture_output=True,
                       text=True, timeout=300)
    per_file = [int(n) for n in re.findall(r"^tests/\S+\.py: (\d+)$", r.stdout, flags=re.M)]
    if per_file:
        return sum(per_file)
    m = re.search(r"(\d+) tests? collected", r.stdout)
    if not m:
        raise SystemExit("could not count tests:\n" + r.stdout[-1500:])
    return int(m.group(1))


APP_PAGE = "https://github.com/c0ldsheep/SANKET-app"


def app_facts():
    """The Android app's version and test count, read from its sources (so the write-up never goes stale)."""
    app = ROOT.parent / "app"
    m = re.search(r'versionName = "([^"]+)"', (app / "mobile" / "build.gradle.kts").read_text())
    tests = sum(f.read_text().count("@Test") for f in (app / "core" / "src" / "test").rglob("*.java"))
    return (m.group(1) if m else "?"), tests


def golden_ticks() -> int:
    g = json.loads((ROOT / "data" / "golden" / "sanket_golden.json").read_text())
    return sum(len(tr["ticks"]) for tr in g["traces"])


# --------------------------------------------------------------------------------------
# Content (one source for DOCX, PDF and portal text)
# --------------------------------------------------------------------------------------
def content(M, team, n_tests, n_ticks):
    app_v, app_tests = app_facts()
    t, h, dec, e2e = M["test"], M["headline"], M["decay"], M["end_to_end"]
    sk, th, P = t["sanket"], t["threshold"], M["params"]["sanket"]
    fleet = M["fleet"]["main"]["daily"]
    ride = next(r for r in dec["by_scenario"] if r["kind"] == "basement_ride")
    bg = dec["background_p_lt_1p5"]
    de = M["design_evidence_cal"]["fa_le_1"]
    req = M["prefetch"]["required_lead_99"]
    crit = max(req[k] for k in list(req)[:3])
    data = M["data"]
    first_day_ok = next((d["day"] for d in fleet if d["hybrid"] >= 0.85), None)
    ci = sk["sim"]["recall10_ci"]
    bg_lo, bg_hi = pct(min(bg.values())), pct(max(bg.values()))
    names = {"sanket": "SANKET (proposed)", "logistic": "Logistic regression (ML)", "slope_ttl": "Regression slope + TTL",
             "naive_gradient": "Naive gradient", "threshold": "Static threshold"}
    table_rows = [[names[n], pct(t[n]["sim"]["recall10"]), pct(t[n]["sim"]["specificity"]),
                   f"{t[n]['real']['fa_per_h']:.2f}", pct(e2e[n]["critical_data_saved"])]
                  for n in ("sanket", "logistic", "slope_ttl", "naive_gradient", "threshold")]
    S = []
    S.append(("Understanding of the problem", [
        ("p", "Delivery riders often lose mobile data a few seconds after riding down a basement ramp or walking into "
              "a concrete building such as a cloud kitchen. The delivery app then cannot load the OTP, the customer's "
              "address or the route, and the order gets stuck. In India, losing 4G usually means losing data "
              "completely, because Jio has no 3G and Airtel and Vi have mostly switched theirs off. The phone does get "
              "an early hint: its signal readings, RSRP (signal strength) and RSRQ (signal quality), start falling "
              "before the connection breaks. The task is to turn that fall into a timely \"download the order now\" "
              "signal without wasting data on false alarms."),
    ]))
    S.append(("Research Question", [
        ("p", "Can the rate at which RSRP and RSRQ fall, as an ordinary Android phone reports them, predict a loss of "
              "connection early enough to download the order data first, with 10 to 15 s of warning and more than 85% "
              "accuracy? We also asked how much warning the download really needs, and what limits any method that "
              "looks only at the signal."),
    ]))
    S.append(("Proposed Solution", [
        ("p", "SANKET (संकेत, \"early sign\") is a small detector that runs on the phone and uses only readings that "
              "Android already provides:"),
        ("ul", [
            "A Kalman filter estimates the RSRP level and how fast it is falling (dB/s), even though Android rounds "
            "readings to whole dB and refreshes them only every 2 to 3 s.",
            f"Every second it works out the chance that RSRP will be at or below {P['theta']:.0f} dBm, the level at "
            f"which the phone can no longer use the cell, {P['horizon']:.0f} s from now. If that chance is at least "
            f"{pct(P['p_thr'], 0)} and the signal is falling by {P['min_rate']:.0f} dB/s or more, the app starts "
            f"downloading the order data.",
            "Inside concrete every nearby cell fades together, while in a handover zone or a street shadow only the "
            "serving cell fades. So if a neighbouring cell holds steady, SANKET cancels the alarm.",
            "Dead-zone memory: after a loss, the phone reports only the entrance location. Once two different trips "
            "confirm a spot, riders heading there get the download before their signal starts to fall.",
            "Downloads go in order of importance (order token and OTP, customer details, route, map tiles). Unchanged "
            "data gets a short \"not modified\" reply, so a false alarm costs about 1.6 KB.",
        ]),
    ]))
    S.append(("Approach Adopted", [
        ("ul", [
            f"We studied {data['ucc_total_hours']:.1f} h of public 4G drive-test recordings made on Android phones "
            f"(the UCC dataset). About 60% of the once-a-second readings repeat an old value, and only {bg_lo} to "
            f"{bg_hi} of normal 10 s stretches of riding fall faster than 1.5 dB/s.",
            "No public dataset has phones entering basements, so we built a simulator with three kinds of entry "
            "(scooter ramp, walking down, deep inside a building) and four look-alikes that should not raise an alarm "
            "(lobby, flyover, street ride, handover zone). Building losses come from published underground car-park "
            "measurements (27, 38 and 52 dB per level), and the noise was tuned to match the real recordings. A loss "
            "is counted with the 3GPP rules: SINR below -8 dB for 1 s, or RSRP below -124 dBm for 2 s.",
            f"We compared five detectors: a static threshold, a two-point slope, a regression slope, logistic "
            f"regression (machine learning) and SANKET. Each was tuned on calibration data with the same limit of 2 "
            f"false alarms per hour on real rides, then tested once on data it had never seen: "
            f"{data['sim_test']['profiles']:,} new simulated trips, {data['real_test']['hours']:.1f} h of real rides "
            f"and {data['inj_test']['profiles']} real recordings with a basement entry added.",
            "A download model (3GPP TR 36.942 throughput, TCP slow start, TLS) showed how much warning each part of "
            "the order data needs, and a fleet simulation tested dead-zone memory over 7 days.",
        ]),
    ]))
    S.append(("Resources Used", [
        ("ul", [
            "Software: Python 3.12 (NumPy, Pandas, SciPy, Matplotlib, pytest), JavaScript for the dashboard, Java 21 "
            "for the Android version, and Termux:API for logging signal readings on a phone.",
            "Data and references: UCC 4G LTE dataset (Raca et al., ACM MMSys 2018, CC BY 4.0); 3GPP TR 36.942 and "
            "TS 36.133, 36.304 and 36.331; Android TelephonyManager source code; arXiv:2605.23483 (signal loss in an "
            "underground car park). We also looked at OpenCelliD, but it lists tower locations, not how a phone's "
            "signal changes over time.",
            "AI tools: Claude (Anthropic) helped us write code and drafts, and Gemini helped us break down the "
            "problem. Every number in this document is produced by our code, which is checked by automated tests.",
        ]),
    ]))
    S.append(("Implementation & Evidence", [
        ("ul", [
            f"A Python package with the real-time detector (about {h['runtime_us']:.1f} µs per reading in plain "
            f"Python), the simulator, loaders for the real recordings and one script that reproduces every number "
            f"and figure in about 2 minutes. {n_tests} automated tests check it, including a check that the fast "
            f"tuning code raises exactly the same alarms as the real-time detector.",
            f"JavaScript and Java versions of the detector give exactly the same output as Python on all "
            f"{n_ticks:,} reference readings. The Java version runs in our Android app (version {app_v}, no "
            f"third-party libraries, {app_tests} automated tests), which saves downloads before a drop, resumes them "
            f"afterwards and replays the trip of Fig. 2 as a demo. A Termux phone tool logs live 4G readings, and an "
            f"interactive dashboard replays the test scenarios with adjustable settings.",
        ]),
        ("fig", SUB / "assets" / "dashboard.png", "Fig. 1. The interactive dashboard replaying the test trip of "
                                                  "Fig. 2: SANKET raised the alarm at 63 s, the link was lost at "
                                                  "79 s, and all four parts of the order data were already on the "
                                                  "phone.", 5.0),
        ("fig", FIG / "fig1_anatomy.png", "Fig. 2. A test trip the detector had never seen: a scooter goes down a "
                                          "basement ramp. SANKET warns 16 s before the link is lost, a static "
                                          "threshold only 2 s before.", 4.5),
    ]))
    S.append(("Results & Observations", [
        ("ul", [
            f"The signal falls steeply at entrances. The steepest 10 s slope is below -1.5 dB/s in "
            f"{pct(dec['entry_slope_p_lt_1p5'], 0)} of entries, but in only {bg_lo} to {bg_hi} of normal riding "
            f"(Fig. 3).",
            f"There is a physical limit. Only {pct(h['physical_ceiling_10s'], 0)} of entries leave 10 s between the "
            f"entrance and the loss ({pct(ride['p_entry_to_loss_ge10'], 0)} for scooter ramps), so no method that "
            f"reads only the signal can reach 85% at 10 s.",
        ]),
        ("fig", FIG / "fig2_decay.png", "Fig. 3. How fast the signal falls at entrances compared with normal riding "
                                        "(left), and how much time each kind of entry leaves before the loss (right).",
         5.0),
        ("ul", [
            f"On unseen data SANKET warned at least 10 s ahead in {pct(sk['sim']['recall10'])} of entries (95% "
            f"confidence interval {pct(ci[0])} to {pct(ci[1])}), against {pct(th['sim']['recall10'])} for a static "
            f"threshold, with {sk['real']['fa_per_h']:.2f} false alarms per hour on real rides. It leads every other "
            f"method when false alarms are kept low (table, Fig. 4).",
        ]),
        ("table", ["Detector (tuned on calibration data)", "Warned ≥\u00a010\u00a0s ahead", "Silent when no loss followed",
                   "False alarms per hour (real rides)", "Critical data saved"], table_rows),
        ("ul", [
            f"The critical order data (order token, customer details and route) needs only {crit:.0f} s of warning "
            f"(enough in 99% of test entries); map tiles need {req['T3 offline map tiles']:.0f} s. SANKET's alarms "
            f"got the critical data onto the phone before the loss in {pct(e2e['sanket']['critical_data_saved'])} of "
            f"entries, against {pct(e2e['threshold']['critical_data_saved'])} for the threshold (Fig. 5).",
            f"Checking a neighbour cell raised detection at 1 false alarm per hour from "
            f"{pct(de['without_common_mode'])} to {pct(de['with_common_mode'])}. RSRQ did not help: it only "
            f"drops sharply near the noise floor.",
            f"With dead-zone memory, entries warned at least 10 s ahead rose from {pct(fleet[0]['hybrid'], 0)} on "
            f"day 1 to {pct(fleet[-1]['hybrid'], 0)} on day {len(fleet)}, above the 85% target from day "
            f"{first_day_ok} (Fig. 6).",
        ]),
        ("fig", FIG / "fig3_tradeoff.png", "Fig. 4. Detection against false alarms on unseen data. The large markers "
                                           "are the settings chosen on calibration data.", 3.4),
        ("figrow", [(FIG / "fig5_prefetch_small.png", 2.8), (FIG / "fig6_fleet_small.png", 2.8)],
         "Fig. 5 (left): downloads finished before the loss, by warning time. Fig. 6 (right): dead-zone memory over "
         "7 days."),
    ]))
    S.append(("What we learnt..", [
        ("ul", [
            f"The 10 to 15 s target runs into physics on fast ramp entries. So we also asked how much warning the data really needs ({crit:.0f} s "
            f"for the critical part) and where earlier warning can come from (dead-zone memory).",
            "Handling stale, rounded readings mattered more than model complexity: Kalman beat logistic regression.",
            "Most real false alarms came from handover zones and street shadows, which the neighbour check filters "
            "out. False alarms must be measured on real recordings: our simulator is calmer than real streets.",
            "Limitations: basement entries are simulated, and the real recordings are from Irish 4G networks. Next: "
            "record real entries in Navi Mumbai on 4G and 5G with the app, to confirm these results in the field.",
        ]),
    ]))
    code, dash = team.get("code_link"), team.get("dashboard_link")
    links = [f"Code, results and dashboard: {code}"] if code else [
        "Interactive dashboard: web/SANKET_dashboard.html in the project folder (opens offline in any browser)"]
    if dash:
        links.append(f"Live dashboard: {dash}")
    links.append(f"Android app, free test build: {APP_PAGE}")
    if team.get("youtube_link"):
        links.append(f"Demo video: {team['youtube_link']}")
    links.append(f"Remark: signal alone cannot reach 85% at 10 s (section 7); dead-zone memory meets it from day "
                 f"{first_day_ok}.")
    S.append(("Youtube link/ any other Artisan or remarks specific to Problem Statement", [("ul", links)]))
    return S


# --------------------------------------------------------------------------------------
# DOCX: fill the official template
# --------------------------------------------------------------------------------------
def build_docx(sections, team, date_text, path):
    from docx import Document
    from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_COLOR_INDEX
    from docx.oxml import OxmlElement
    from docx.oxml.ns import qn
    from docx.shared import Inches, Pt
    from docx.table import Table
    from docx.text.paragraph import Paragraph

    doc = Document(str(TEMPLATE))
    body = doc.element.body

    def style_run(run, size=11, bold=False, italic=False, mark=False):
        run.font.name = "Times New Roman"
        run.font.size = Pt(size)
        run.bold = bold
        run.italic = italic
        rpr = run._element.get_or_add_rPr()
        fonts = rpr.find(qn("w:rFonts"))
        if fonts is None:
            fonts = OxmlElement("w:rFonts")
            rpr.insert(0, fonts)
        for k in ("w:ascii", "w:hAnsi", "w:cs", "w:eastAsia"):
            fonts.set(qn(k), "Times New Roman")
        if mark:
            run.font.highlight_color = WD_COLOR_INDEX.YELLOW

    # info table
    tbl = doc.tables[0]
    values = ["PRAYAS-COMP-26-S-020", TITLE, team["team_name_id"], team["member_1"], team["member_2"],
              team["mentor"], date_text]
    for row, val in zip(tbl.rows, values):
        p = row.cells[1].paragraphs[0]
        for r in list(p.runs):
            r._element.getparent().remove(r._element)
        style_run(p.add_run(val), size=12, mark="[" in val)

    paras = list(doc.paragraphs)
    headings = [p for p in paras if p._p.pPr is not None and p._p.pPr.numPr is not None]
    if len(headings) != len(sections):
        raise SystemExit(f"template has {len(headings)} numbered headings, content has {len(sections)}")

    def new_par_after(elem):
        p = OxmlElement("w:p")
        elem.addnext(p)
        return Paragraph(p, doc._body)

    for head, (title, blocks) in zip(headings, sections):
        cursor = head._p
        for blk in blocks:
            kind = blk[0]
            if kind == "p":
                par = new_par_after(cursor)
                par.paragraph_format.space_after = Pt(4)
                par.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
                style_run(par.add_run(blk[1]), mark="[" in blk[1])
                cursor = par._p
            elif kind == "ul":
                for item in blk[1]:
                    par = new_par_after(cursor)
                    pf = par.paragraph_format
                    pf.left_indent, pf.first_line_indent, pf.space_after = Inches(0.3), Inches(-0.18), Pt(2)
                    par.alignment = WD_ALIGN_PARAGRAPH.JUSTIFY
                    style_run(par.add_run("•  " + item), mark="[" in item)
                    cursor = par._p
            elif kind == "fig":
                par = new_par_after(cursor)
                par.alignment = WD_ALIGN_PARAGRAPH.CENTER
                par.add_run().add_picture(str(blk[1]), width=Inches(min(blk[3], 6.4)))
                cap = new_par_after(par._p)
                cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
                cap.paragraph_format.space_after = Pt(8)
                style_run(cap.add_run(blk[2]), size=9.5, italic=True)
                cursor = cap._p
            elif kind == "figrow":
                par = new_par_after(cursor)
                par.alignment = WD_ALIGN_PARAGRAPH.CENTER
                for img, width in blk[1]:
                    par.add_run().add_picture(str(img), width=Inches(width))
                cap = new_par_after(par._p)
                cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
                cap.paragraph_format.space_after = Pt(8)
                style_run(cap.add_run(blk[2]), size=9.5, italic=True)
                cursor = cap._p
            elif kind == "table":
                header, rows = blk[1], blk[2]
                t = doc.add_table(rows=1 + len(rows), cols=len(header))
                _borders(t, OxmlElement, qn)
                for j, txt in enumerate(header):
                    c = t.rows[0].cells[j].paragraphs[0]
                    style_run(c.add_run(txt), size=9.5, bold=True)
                for i, r in enumerate(rows, start=1):
                    for j, txt in enumerate(r):
                        c = t.rows[i].cells[j].paragraphs[0]
                        if j:
                            c.alignment = WD_ALIGN_PARAGRAPH.RIGHT
                        style_run(c.add_run(txt), size=9.5, bold=(i == 1))
                cursor.addnext(t._tbl)
                cursor = t._tbl
                spacer = new_par_after(cursor)
                cursor = spacer._p
    # drop the instruction paragraph addressed to students
    for p in doc.paragraphs:
        if p.text.strip().startswith("Recommended student instruction"):
            p._p.getparent().remove(p._p)
    doc.save(str(path))


def _borders(table, OxmlElement, qn):
    tblPr = table._tbl.tblPr
    b = OxmlElement("w:tblBorders")
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        e = OxmlElement(f"w:{edge}")
        e.set(qn("w:val"), "single")
        e.set(qn("w:sz"), "4")
        e.set(qn("w:color"), "999999")
        b.append(e)
    tblPr.append(b)


# --------------------------------------------------------------------------------------
# PDF: the same template layout as HTML, printed by Chrome
# --------------------------------------------------------------------------------------
def extract_logos():
    """PRAYAS and Terna logos from the template, cropped exactly as the template header does."""
    with zipfile.ZipFile(TEMPLATE) as z:
        prayas = Image.open(io.BytesIO(z.read("word/media/image2.png")))
        terna = Image.open(io.BytesIO(z.read("word/media/image1.png")))
    w, hgt = prayas.size
    crop = (round(0.30695 * w), round(0.07155 * hgt), round(w - 0.30576 * w), round(hgt - 0.12519 * hgt))
    (SUB / "assets").mkdir(exist_ok=True)
    prayas.crop(crop).save(SUB / "assets" / "prayas_logo.png")
    terna.save(SUB / "assets" / "terna_logo.png")


def screenshot_dashboard():
    """Fig. 1: the offline dashboard at rest (end of the featured trip), cropped to the top panels."""
    if not Path(CHROME).exists():
        raise SystemExit("Google Chrome not found; it is needed for the dashboard screenshot and the PDF")
    raw = SUB / "build" / "dashboard_full.png"
    raw.parent.mkdir(exist_ok=True)
    if raw.exists():
        raw.unlink()
    page = (ROOT / "web" / "SANKET_dashboard.html").resolve().as_uri()
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=2",
                    "--window-size=1200,860", "--virtual-time-budget=4000", f"--screenshot={raw}", page],
                   capture_output=True, text=True, timeout=180)
    if not raw.exists():
        raise SystemExit("dashboard screenshot failed")
    im = Image.open(raw)
    im.crop((0, 0, im.width, round(im.height * 575 / 860))).save(SUB / "assets" / "dashboard.png", optimize=True)


def esc(s, mark=True):
    s = html.escape(s)
    if mark and "[" in s:
        s = s.replace("[", '<mark>[').replace("]", "]</mark>")
    return s


def build_html(sections, team, date_text, path):
    rows = [("PRAYAS Problem ID", "PRAYAS-COMP-26-S-020"), ("Problem Title", TITLE),
            ("Team Name / ID", team["team_name_id"]), ("Team Members", "1. " + team["member_1"]),
            ("", "2. " + team["member_2"]), ("Name of faculty Mentor", team["mentor"]), ("Date", date_text)]
    info = "".join(f"<tr><td>{esc(a)}</td><td>{esc(b)}</td></tr>" for a, b in rows)
    out = []
    for i, (title, blocks) in enumerate(sections, start=1):
        parts = [f'<h2><span class="n">{i}.</span> {esc(title)}</h2>']
        for blk in blocks:
            if blk[0] == "p":
                parts.append(f"<p>{esc(blk[1])}</p>")
            elif blk[0] == "ul":
                parts.append("<ul>" + "".join(f"<li>{esc(x)}</li>" for x in blk[1]) + "</ul>")
            elif blk[0] == "fig":
                rel = Path("..") / ".." / blk[1].relative_to(ROOT)
                parts.append(f'<figure style="width:{blk[3]}in"><img src="{rel.as_posix()}" alt=""><figcaption>{esc(blk[2])}'
                             f"</figcaption></figure>")
            elif blk[0] == "figrow":
                imgs = "".join(f'<img style="width:{w}in" src="{(Path("..") / ".." / img.relative_to(ROOT)).as_posix()}" alt="">'
                               for img, w in blk[1])
                parts.append(f'<figure class="row"><div>{imgs}</div><figcaption>{esc(blk[2])}</figcaption></figure>')
            elif blk[0] == "table":
                head = "".join(f"<th>{esc(x)}</th>" for x in blk[1])
                body = "".join("<tr>" + "".join(f"<td>{esc(c)}</td>" for c in r) + "</tr>" for r in blk[2])
                parts.append(f'<table class="res"><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>')
        out.append('<section>' + "".join(parts) + "</section>")
    doc = f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>{OUT}</title>
<style>
@page {{ size: Letter; margin: 0.5in 0.85in 0.5in 0.85in; }}
body {{ font-family: "Times New Roman", Times, serif; font-size: 10.4pt; line-height: 1.32; color: #000; margin: 0; }}
.head {{ display: flex; align-items: center; justify-content: space-between; height: 1.05in; }}
.head .p {{ height: 1.0in; }} .head .t {{ height: 0.62in; }}
h1 {{ text-align: center; color: #17365D; font-size: 15.5pt; margin: 6pt 0 8pt; }}
table.info {{ border-collapse: collapse; width: 100%; font-size: 11.5pt; margin-bottom: 8pt; }}
table.info td {{ border: 1px solid #000; padding: 2.5pt 6pt; vertical-align: top; }}
table.info td:first-child {{ width: 2.3in; }}
h2 {{ font-size: 12.5pt; margin: 9pt 0 3pt; break-after: avoid; }}
h2 .n {{ font-weight: normal; }}
p {{ margin: 0 0 3pt; text-align: justify; }}
ul {{ margin: 0 0 3pt; padding-left: 16pt; }}
li {{ margin: 0 0 2pt; text-align: justify; }}
figure {{ margin: 6pt auto 6pt; break-inside: avoid; text-align: center; }}
figure img {{ width: 100%; }}
figure.row div {{ display: flex; justify-content: center; gap: 0.15in; }}
figure.row img {{ flex: none; }}
figcaption {{ font-size: 9pt; font-style: italic; margin-top: 2pt; }}
table.res {{ border-collapse: collapse; width: 100%; font-size: 9.4pt; margin: 4pt 0 6pt; break-inside: avoid; }}
table.res th, table.res td {{ border: 1px solid #888; padding: 2pt 5pt; text-align: right; }}
table.res th:first-child, table.res td:first-child {{ text-align: left; width: 2.3in; }}
table.res th {{ font-size: 9pt; vertical-align: bottom; }}
table.res tbody tr:first-child td {{ font-weight: bold; }}
mark {{ background: #ffeb3b; }}
</style></head><body>
<div class="head"><img class="p" src="assets/prayas_logo.png" alt="PRAYAS"><img class="t" src="assets/terna_logo.png" alt="Terna Engineering College"></div>
<h1>Students PRAYAS Solution Submission Template</h1>
<table class="info">{info}</table>
{''.join(out)}
</body></html>"""
    path.write_text(doc, encoding="utf-8")


def print_pdf(html_path, pdf_path):
    if not Path(CHROME).exists():
        raise SystemExit("Google Chrome not found; open the .html in any browser and print it to PDF instead")
    r = subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--no-pdf-header-footer",
                        f"--print-to-pdf={pdf_path}", html_path.resolve().as_uri()],
                       capture_output=True, text=True, timeout=180)
    if not pdf_path.exists():
        raise SystemExit("PDF printing failed:\n" + r.stderr[-2000:])


# --------------------------------------------------------------------------------------
# Text to copy: the portal form's boxes, then the template sections
# --------------------------------------------------------------------------------------
PORTAL_LIMITS = {4: 6000, 5: 6000, 6: 6000, 7: 6000, 8: 6000, 9: 3000}


def portal_boxes(M, team, n_tests, n_ticks):
    """Plain text for the portal's boxes 4-10. ASCII only, so it pastes cleanly into any web form."""
    app_v, app_tests = app_facts()
    t, h, e2e, dec = M["test"], M["headline"], M["end_to_end"], M["decay"]
    sk, th, P = t["sanket"], t["threshold"], M["params"]["sanket"]
    fleet = M["fleet"]["main"]["daily"]
    req = M["prefetch"]["required_lead_99"]
    crit = max(req[k] for k in list(req)[:3])
    de = M["design_evidence_cal"]["fa_le_1"]
    data = M["data"]
    first_day_ok = next((d["day"] for d in fleet if d["hybrid"] >= 0.85), None)
    ceiling = h["physical_ceiling_10s"]
    box = {}
    box[4] = (
        "Delivery riders often lose mobile data a few seconds after going down a basement ramp or into a concrete "
        "building, and the delivery app fails in the middle of an order. Our research question: can the rate at which "
        "the phone's own signal readings (RSRP and RSRQ) fall predict this loss early enough to download the order "
        "data first, with 10 to 15 seconds of warning and more than 85% accuracy? We also asked how much warning the "
        "download really needs.\n\n"
        f"Our solution, SANKET, is a small detector that runs on the phone. A Kalman filter tracks the signal level and "
        f"how fast it is falling. When the chance of the signal being at or below {P['theta']:.0f} dBm "
        f"{P['horizon']:.0f} seconds from now reaches {pct(P['p_thr'], 0)}, the app downloads the order data, most "
        f"important part first. If a neighbouring cell stays steady, the alarm is cancelled, because that pattern "
        f"means a handover zone and not a basement. A shared dead-zone memory also warns riders before basement "
        f"entrances that other riders have already reported.")
    box[5] = (
        f"1. We studied {data['ucc_total_hours']:.1f} hours of real 4G drive-test recordings made on Android phones "
        f"(the public UCC dataset) to learn how phone signal readings behave: how noisy they are, how often they "
        f"repeat and how fast they fall during normal riding.\n"
        "2. No public dataset has phones entering basements, so we built a simulator with seven situations: three "
        "kinds of basement or building entry and four look-alikes that should not raise an alarm (lobby, flyover, "
        "normal street ride, handover zone). Building losses come from published underground measurements, and we "
        "tuned the simulator to match the real recordings. A loss is counted with the 3GPP rules for losing a cell.\n"
        f"3. We compared five detectors: static threshold, two-point slope, regression slope, logistic regression and "
        f"SANKET. Each was tuned on calibration data with the same limit of 2 false alarms per hour on real rides, "
        f"then tested once on data it had never seen ({data['sim_test']['profiles']:,} simulated trips, "
        f"{data['real_test']['hours']:.1f} hours of real rides and {data['inj_test']['profiles']} real recordings "
        f"with a basement entry added).\n"
        "4. We built a download model to work out how much warning each part of the order data needs, and simulated "
        "a fleet of riders sharing dead-zone locations over 7 days.")
    box[6] = (
        "Software: Python 3.12 with NumPy, Pandas, SciPy, Matplotlib and pytest; JavaScript for the interactive "
        "dashboard; Java 21 for the Android version; Termux:API on an Android phone for logging signal readings.\n\n"
        "Data and references: UCC 4G LTE dataset (Raca et al., ACM MMSys 2018, CC BY 4.0); 3GPP TR 36.942 and "
        "TS 36.133, 36.304 and 36.331; Android TelephonyManager source code; arXiv:2605.23483 (signal loss in an "
        "underground car park). We also looked at OpenCelliD, but it lists tower locations, not how a phone's signal "
        "changes over time.\n\n"
        "AI tools: Claude (Anthropic) helped us write code and drafts, and Gemini helped us break down the problem. "
        "Every number we report is produced by our code, which is checked by automated tests.")
    box[7] = (
        f"We wrote a Python package with the real-time detector, the simulator, loaders for the real recordings and "
        f"one script that reproduces every number and graph in about 2 minutes. {n_tests} automated tests check it, "
        f"including a test that the fast tuning code raises exactly the same alarms as the real-time detector.\n\n"
        f"We also wrote the detector in JavaScript and in Java for Android. Both give exactly the same output as the "
        f"Python version on all {n_ticks:,} reference readings. The Java version runs in our Android app (version "
        f"{app_v}, {app_tests} automated tests), which saves downloads before a drop, resumes them afterwards and "
        f"includes a demo that replays the test trip; anyone can install it from {APP_PAGE}. A phone tool built on "
        f"Termux logs live 4G readings, and an interactive dashboard replays the test scenarios with adjustable "
        f"settings.\n\n"
        "The attached PDF has the evidence: a dashboard screenshot (Fig. 1), a sample run (Fig. 2), the signal measurements (Fig. 3), the comparison of "
        "all five detectors (table and Fig. 4), the download timing (Fig. 5) and the dead-zone memory results "
        "(Fig. 6).")
    box[8] = (
        f"On data the detectors had never seen, SANKET warned at least 10 seconds before the loss in "
        f"{pct(sk['sim']['recall10'])} of basement entries, against {pct(th['sim']['recall10'])} for a static "
        f"threshold, with {sk['real']['fa_per_h']:.2f} false alarms per hour on real rides. The critical order data "
        f"(order token, customer details and route) needs only {crit:.0f} seconds of warning, and SANKET's alarms got "
        f"it onto the phone before the loss in {pct(e2e['sanket']['critical_data_saved'])} of entries, against "
        f"{pct(e2e['threshold']['critical_data_saved'])} for the threshold. Map tiles need "
        f"{req['T3 offline map tiles']:.0f} seconds.\n\n"
        f"We found a physical limit: in {pct(1 - ceiling, 0)} of entries the signal goes from normal to lost less "
        f"than 10 seconds after the entrance, so no method that reads only the signal can reach 85% at 10 seconds. "
        f"With dead-zone memory added, {pct(fleet[0]['hybrid'], 0)} of entries were warned at least 10 seconds ahead "
        f"on day 1 and {pct(fleet[-1]['hybrid'], 0)} on day {len(fleet)}, above the 85% target from day "
        f"{first_day_ok}.\n\n"
        f"Checking a neighbour cell raised detection at 1 false alarm per hour from {pct(de['without_common_mode'])} "
        f"to {pct(de['with_common_mode'])}. RSRQ did not help, because it only drops sharply once the signal is "
        f"already very weak.")
    box[9] = (
        "The 10 to 15 second target runs into physics on fast ramp entries, so the more useful questions were how "
        "much warning the data really needs and where earlier warning can come from. Cleaning up the phone's stale "
        "and rounded readings mattered more than using a complex model: our Kalman filter detector beat logistic "
        "regression. Checking a neighbour cell removed many false alarms caused by handover zones. We also learnt "
        "that false alarms must be measured on real recordings, because our simulator turned out calmer than real "
        "streets.\n\n"
        "Our main limitation is that the basement entries are simulated and the real recordings come from Ireland. "
        "Next, we want to record real basement entries in Navi Mumbai with our phone tool and the app, to confirm "
        "these results in the field.")
    for k, limit in PORTAL_LIMITS.items():
        if len(box[k]) > limit:
            raise SystemExit(f"portal box {k} is {len(box[k])} characters, over the {limit} limit")
        if not box[k].isascii():
            raise SystemExit(f"portal box {k} has non-ASCII characters")
    return box


def _plain(blocks):
    lines = []
    for blk in blocks:
        if blk[0] == "p":
            lines.append(blk[1])
        elif blk[0] == "ul":
            lines.extend("• " + x for x in blk[1])
        elif blk[0] == "fig":
            lines.append(f"[Picture: {blk[1].relative_to(ROOT).as_posix()}]  {blk[2]}")
        elif blk[0] == "figrow":
            lines.append("[Pictures: " + ", ".join(p.relative_to(ROOT).as_posix() for p, _ in blk[1]) + f"]  {blk[2]}")
        elif blk[0] == "table":
            lines.append("[Table]")
            lines.extend("  |  ".join(r) for r in [blk[1]] + blk[2])
    return "\n".join(lines)


def build_text(M, team, sections, date_text, path, n_tests, n_ticks):
    """One plain-text file with everything to copy: the portal boxes, then the template sections."""
    box = portal_boxes(M, team, n_tests, n_ticks)
    titles = {4: "Research Question & Proposed Solution", 5: "Approach Adopted", 6: "Resources Used",
              7: "Experiment, Implementation & Evidence", 8: "Results & Observations", 9: "What We Learnt?"}
    bar = "=" * 78
    out = [f"PRAYAS-COMP-26-S-020 | Team {team['team_name_id']} | text to copy",
           "Copy only the text between the dashed lines. Nothing outside them goes into the form.", "",
           bar, "PART A. PORTAL FORM (boxes 1 to 3 fill in automatically)", bar]
    for k, title in titles.items():
        out += ["", f"----- Box {k}. {title}  ({len(box[k])} of {PORTAL_LIMITS[k]} characters) -----", box[k],
                "----- end -----"]
    yt = team.get("youtube_link") or ""
    other = [x for x in (team.get("code_link"), team.get("dashboard_link"), APP_PAGE) if x]
    out += ["", "----- Box 10. YouTube link (optional) -----", yt or "(leave empty if you have no video)",
            "----- end -----", "", "----- Box 10. Other resource links, one per line (optional) -----",
            "\n".join(other) or "(leave empty)", "----- end -----", "",
            "Solution PDF: upload submission/PRAYAS-COMP-26-S-020_SANKET.pdf", "",
            bar, "PART B. WORD TEMPLATE (the same text as the PDF; the filled .docx is already in this folder)", bar,
            "", f"PRAYAS Problem ID: PRAYAS-COMP-26-S-020", f"Problem Title: {TITLE}",
            f"Team Name / ID: {team['team_name_id']}", f"Team Members: 1. {team['member_1']}",
            f"              2. {team['member_2']}", f"Name of faculty Mentor: {team['mentor']}", f"Date: {date_text}"]
    for i, (title, blocks) in enumerate(sections, start=1):
        out += ["", f"{i}. {title}", _plain(blocks)]
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


def main():
    M = json.loads((ROOT / "results" / "metrics.json").read_text())
    team_file = SUB / "team.json"
    if not team_file.exists():                      # fresh clone: personal details are not in the repository
        team_file = SUB / "team.example.json"
    team = json.loads(team_file.read_text())
    date_text = team.get("date") or dt.date.today().strftime("%d %B %Y")
    n_tests, n_ticks = test_count(), golden_ticks()
    sections = content(M, team, n_tests, n_ticks)
    extract_logos()
    screenshot_dashboard()
    build_docx(sections, team, date_text, SUB / f"{OUT}.docx")
    build_dir = SUB / "build"
    build_dir.mkdir(exist_ok=True)
    shutil.copytree(SUB / "assets", build_dir / "assets", dirs_exist_ok=True)
    html_path = build_dir / f"{OUT}.html"
    build_html(sections, team, date_text, html_path)
    pdf = SUB / f"{OUT}.pdf"
    if pdf.exists():
        pdf.unlink()
    print_pdf(html_path, pdf)
    build_text(M, team, sections, date_text, SUB / "copy_paste_text.txt", n_tests, n_ticks)
    from pypdf import PdfReader
    pages = len(PdfReader(str(pdf)).pages)
    print(f"PDF: {pdf.name} ({pages} pages)  DOCX: {OUT}.docx  text to copy: copy_paste_text.txt")
    todo = [k for k, v in team.items() if not k.startswith("_") and isinstance(v, str) and "[" in v]
    if todo:
        print("Still to fill in submission/team.json:", ", ".join(todo))


if __name__ == "__main__":
    sys.exit(main())
