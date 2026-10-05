"""The JavaScript (web demo) and Java (Android) ports must reproduce the Python reference."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

from sanket.core import z_for_probability

ROOT = Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "data" / "golden" / "sanket_golden.json"
# Central branch (0.075 <= p <= 0.925, where every useful p_thr lives) is pure + - * / and must match
# bit for bit. The tails call log(), whose last bit differs between JavaScript engines and C libms.
P_CENTRAL = [0.1, 0.3, 0.5, 0.6, 0.7, 0.8, 0.9, 0.92]
P_TAILS = [1e-6, 0.001, 0.05, 0.95, 0.99, 0.999999]


@pytest.mark.skipif(shutil.which("node") is None, reason="node not installed")
def test_javascript_port_matches_golden(tmp_path):
    z = tmp_path / "z.json"
    z.write_text(json.dumps([[p, z_for_probability(p), 0.0] for p in P_CENTRAL]
                            + [[p, z_for_probability(p), 1e-14] for p in P_TAILS]))
    r = subprocess.run(["node", str(ROOT / "web" / "verify_golden.js"), str(GOLDEN), str(z)],
                       capture_output=True, text=True, timeout=120)
    assert r.returncode == 0, r.stdout + r.stderr
    assert " 0 mismatches" in r.stdout


def _jdk():
    """A working javac/java pair: Homebrew's keg-only openjdk first, then PATH (macOS's stub fails)."""
    for home in (Path("/opt/homebrew/opt/openjdk/bin"), Path("/usr/local/opt/openjdk/bin")):
        if (home / "javac").exists():
            return str(home / "javac"), str(home / "java")
    javac, java = shutil.which("javac"), shutil.which("java")
    if javac and java and subprocess.run([javac, "-version"], capture_output=True).returncode == 0:
        return javac, java
    return None


@pytest.mark.skipif(_jdk() is None, reason="no working JDK")
def test_java_port_matches_golden(tmp_path):
    javac, java = _jdk()
    src = ROOT / "android" / "src"
    files = [str(p) for p in src.rglob("*.java")]
    assert files, "android sources missing"
    c = subprocess.run([javac, "-Xlint:all", "-d", str(tmp_path)] + files, capture_output=True, text=True, timeout=300)
    assert c.returncode == 0, c.stderr
    r = subprocess.run([java, "-cp", str(tmp_path), "org.sanket.GoldenCheck", str(GOLDEN)],
                       capture_output=True, text=True, timeout=300)
    assert r.returncode == 0, r.stdout + r.stderr
    assert " 0 mismatches" in r.stdout
