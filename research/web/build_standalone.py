#!/usr/bin/env python3
"""Build web/SANKET_dashboard.html: the demo page with its scripts inlined, so it opens offline by
double-click (no server, no internet needed except the optional Google font)."""
import re
from pathlib import Path

WEB = Path(__file__).resolve().parent


def main():
    page = (WEB / "index.html").read_text(encoding="utf-8")

    def inline(m):
        src = (WEB / m.group(1)).read_text(encoding="utf-8").replace("</script", "<\\/script")
        return "<script>\n" + src + "\n</script>"

    page = re.sub(r'<script src="([\w.]+)"></script>', inline, page)
    head, sep, rest = page.partition("</style>")
    doc = ("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n"
           "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">\n"
           + head + sep + "\n<style>body{margin:0}</style>\n</head>\n<body>\n" + rest + "\n</body>\n</html>\n")
    out = WEB / "SANKET_dashboard.html"
    out.write_text(doc, encoding="utf-8")
    print(f"wrote {out} ({out.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
