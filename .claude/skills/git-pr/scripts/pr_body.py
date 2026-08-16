#!/usr/bin/env python3
"""Draft a PR body from the branch's commits and diff, using the repo's PR template when present.

Usage: pr_body.py [--base main] [--remote origin] [--template PATH] [--write FILE]

Fills: summary bullets from commit subjects, `Closes #N` from commit footers, diffstat under Changes,
and the verify section from any 'Verified:' lines in commit bodies. Human context still required —
this is a draft, not the description.
"""
import argparse, os, re, subprocess, sys

def git(*a):
    return subprocess.run(["git", *a], capture_output=True, text=True).stdout

def default_base(remote):
    head = git("symbolic-ref", "--quiet", "--short", f"refs/remotes/{remote}/HEAD").strip()
    if head:
        return head.split("/", 1)[1]
    for c in ("main", "master", "develop"):
        if subprocess.run(["git", "show-ref", "--verify", "--quiet", f"refs/remotes/{remote}/{c}"]).returncode == 0:
            return c
    return "main"

def find_template(explicit):
    if explicit:
        return open(explicit).read()
    root = git("rev-parse", "--show-toplevel").strip()
    for p in (".github/pull_request_template.md", ".github/PULL_REQUEST_TEMPLATE.md", "pull_request_template.md", "docs/pull_request_template.md"):
        fp = os.path.join(root, p)
        if os.path.exists(fp):
            return open(fp).read()
    here = os.path.dirname(os.path.abspath(__file__))
    return open(os.path.join(here, "..", "assets", "pull_request_template.md")).read()

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base"); ap.add_argument("--remote", default="origin")
    ap.add_argument("--template"); ap.add_argument("--write")
    a = ap.parse_args()
    base = a.base or default_base(a.remote)
    rng = f"{a.remote}/{base}...HEAD"

    commits = [l for l in git("log", "--no-merges", "--format=%s%x00%b%x01", rng).split("\x01") if l.strip()]
    subjects, closes, verified = [], [], []
    for c in commits:
        s, _, b = c.partition("\x00")
        s = s.strip()
        if s:
            subjects.append(s)
        for m in re.finditer(r"^(?:Closes|Fixes|Resolves|Refs)\s+#\d+.*$", b, re.M | re.I):
            closes.append(m.group(0).strip())
        for m in re.finditer(r"^Verified:.*(?:\n(?!\s*$).*)*", b, re.M):  # paragraph starting with Verified:
            verified.append(re.sub(r"\s+", " ", m.group(0)).strip())
    stat = git("diff", "--stat=100", rng).rstrip()

    tpl = find_template(a.template)
    tpl = re.sub(r"<!--.*?-->\n?", "", tpl, flags=re.S)  # drop template comments

    summary = "\n".join(f"- {s}" for s in subjects) or "- "
    closes_txt = "\n".join(dict.fromkeys(closes)) or "Closes #"
    changes = f"```\n{stat}\n```" if stat else "-\n-"
    verify = "\n".join(f"- {v}" for v in dict.fromkeys(verified)) if verified else "```\n<command> -> <expected result>\n```"

    body = tpl
    body = re.sub(r"(## What & why\s*\n)(.*?)(\n## )", lambda m: f"{m.group(1)}\n{summary}\n\n{closes_txt}\n{m.group(3)}", body, count=1, flags=re.S)
    body = re.sub(r"(## Changes\s*\n)(.*?)(\n## )", lambda m: f"{m.group(1)}\n{changes}\n{m.group(3)}", body, count=1, flags=re.S)
    body = re.sub(r"(## How to verify\s*\n)(.*?)(\n## )", lambda m: f"{m.group(1)}\n{verify}\n{m.group(3)}", body, count=1, flags=re.S)
    if body == tpl:  # template without our headings: append a generated block
        body = f"## Summary\n\n{summary}\n\n{closes_txt}\n\n## Changes\n\n{changes}\n\n## How to verify\n\n{verify}\n\n" + tpl

    body = re.sub(r"\n{3,}", "\n\n", body).strip() + "\n"   # tidy blank lines left by stripped comments
    title = subjects[-1] if len(subjects) == 1 else ""
    if a.write:
        open(a.write, "w").write(body)
        print(f"wrote {a.write}" + (f"  (title suggestion: {title})" if title else ""))
    else:
        if title:
            print(f"# title suggestion: {title}\n", file=sys.stderr)
        print(body)
    return 0

if __name__ == "__main__":
    sys.exit(main())
