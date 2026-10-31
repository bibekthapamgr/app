"""Summarise a spike evidence run into SUMMARY.md and summary.json.

Usage: python3 report.py <evidence/run-...> [--go-tests go-test.json]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def load_verdicts(run: Path):
    out = []
    for v in sorted(run.glob("*/verdict.json")):
        out.append(json.loads(v.read_text()))
    return out


def go_summary(path: Path | None):
    if not path or not path.exists():
        return None
    tests, passed, failed, pkgs = 0, 0, 0, set()
    for line in path.read_text().splitlines():
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        if e.get("Test") and e.get("Action") in ("pass", "fail"):
            tests += 1
            passed += e["Action"] == "pass"
            failed += e["Action"] == "fail"
            pkgs.add(e.get("Package"))
    return {"tests": tests, "passed": passed, "failed": failed, "packages": sorted(p for p in pkgs if p)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--go-tests")
    ap.add_argument("--junit")
    a = ap.parse_args()
    run = Path(a.run)
    verdicts = load_verdicts(run)
    env = json.loads((run / "environment.json").read_text()) if (run / "environment.json").exists() else {}
    skipped = []
    if a.junit and Path(a.junit).exists():
        import xml.etree.ElementTree as ET
        for tc in ET.parse(a.junit).getroot().iter("testcase"):
            sk = tc.find("skipped")
            if sk is not None:
                skipped.append({"test": tc.get("name"), "reason": sk.get("message", "")})
    go = go_summary(Path(a.go_tests) if a.go_tests else None)
    summary = {
        "run": run.name, "environment": env,
        "e2e": {"tests": len(verdicts), "passed": sum(v["verdict"] == "PASS" for v in verdicts),
                "failed": sum(v["verdict"] != "PASS" for v in verdicts), "skipped": skipped,
                "checks": sum(len(v["checks"]) for v in verdicts),
                "checks_passed": sum(c["passed"] for v in verdicts for c in v["checks"])},
        "go_unit_tests": go,
        "tests": [{"test": v["test"], "verdict": v["verdict"], "title": v["title"], "requirement": v["requirement"],
                   "checks": len(v["checks"]), "metrics": v["metrics"]} for v in verdicts],
    }
    (run / "summary.json").write_text(json.dumps(summary, indent=2, default=str))

    L = [f"# Feasibility spike evidence — {run.name}", ""]
    L.append(f"- Kernel: `{env.get('kernel', '?')}`  ·  engine binary: `{Path(env.get('engine_binary', '?')).name}`")
    e = summary["e2e"]
    L.append(f"- End-to-end: **{e['passed']}/{e['tests']} passed**, {len(skipped)} skipped, "
             f"{e['checks_passed']}/{e['checks']} individual checks passed")
    if go:
        L.append(f"- Go unit/hermetic tests: **{go['passed']}/{go['tests']} passed** ({', '.join(go['packages'])})")
    L += ["", "| Test | Verdict | Checks | What it proves |", "|---|---|---|---|"]
    for v in verdicts:
        L.append(f"| `{v['test']}` | {v['verdict']} | {sum(c['passed'] for c in v['checks'])}/{len(v['checks'])} | {v['title']} |")
    for s in skipped:
        L.append(f"| `{s['test']}` | SKIPPED | – | {s['reason']} |")
    L += ["", "## Checks", ""]
    for v in verdicts:
        L.append(f"### {v['test']} — {v['verdict']}")
        L.append(f"_{v['title']}_" + (f"  \nRequirement: {v['requirement']}" if v["requirement"] else ""))
        L.append("")
        for c in v["checks"]:
            L.append(f"- {'✅' if c['passed'] else '❌'} {c['check']}")
        if v["metrics"]:
            L.append("")
            L.append("```json")
            L.append(json.dumps(v["metrics"], indent=2, default=str))
            L.append("```")
        for n in v.get("notes", []):
            L.append(f"> {n}")
        L.append("")
    (run / "SUMMARY.md").write_text("\n".join(L))
    print(f"wrote {run / 'SUMMARY.md'}")


if __name__ == "__main__":
    main()
