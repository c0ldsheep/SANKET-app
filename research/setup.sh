#!/bin/sh
# One-time setup: creates .venv inside this folder and installs the pinned packages.
set -e
cd "$(dirname "$0")"
python3 -m venv .venv
./.venv/bin/pip install --quiet --upgrade pip
./.venv/bin/pip install --quiet -r requirements.txt
./.venv/bin/python -m pytest
echo "Ready. Try:  ./.venv/bin/python live/sanket_live.py --demo --speed 4"
