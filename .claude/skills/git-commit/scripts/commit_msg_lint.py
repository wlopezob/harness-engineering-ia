#!/usr/bin/env python3
"""Lint a commit message against Conventional Commits + the "seven rules".

Usage:
  commit_msg_lint.py <file>              # e.g. .git/COMMIT_EDITMSG (works as a commit-msg hook)
  commit_msg_lint.py --message "<msg>"   # inline (use $'...\n...' or a heredoc for multi-line)
  commit_msg_lint.py --last              # lint HEAD's message
  options: --types feat,fix,...  --subject-max 72  --subject-warn 50  --body-wrap 72

Exit 0 = OK (warnings allowed), 1 = errors, 2 = usage. Stdlib only.
"""
import argparse
import re
import subprocess
import sys

DEFAULT_TYPES = "feat,fix,docs,style,refactor,perf,test,build,ci,chore,revert,wip"
HEADER_RE = re.compile(r"^(?P<type>[a-z]+)(?:\((?P<scope>[^()\s]+)\))?(?P<bang>!)?: (?P<subject>.+)$")
# GitHub/GitLab merge & revert subjects are generated; don't lint them.
GENERATED_RE = re.compile(r"^(Merge |Revert \"|fixup! |squash! |amend! )")
NON_IMPERATIVE_HINTS = {
    "added", "fixed", "updated", "changed", "removed", "moved", "renamed", "created", "deleted",
    "adding", "fixing", "updating", "changing", "removing", "moving", "creating", "deleting",
    "adds", "fixes", "updates", "changes", "removes", "creates", "deletes",
}
VAGUE_SUBJECTS = {"wip", "fix", "fixes", "update", "updates", "changes", "change", "stuff", "misc", "test", "asdf", "tmp"}


def lint(text: str, types, subject_max, subject_warn, body_wrap):
    errors, warnings = [], []
    lines = [l for l in text.splitlines()]
    # drop comment lines (git template) and trailing blanks
    lines = [l for l in lines if not l.startswith("#")]
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines or not lines[0].strip():
        return ["empty commit message"], warnings
    header = lines[0].rstrip()

    if GENERATED_RE.match(header):
        return errors, ["generated subject (merge/revert/fixup) — skipped"]

    m = HEADER_RE.match(header)
    if not m:
        errors.append("subject must look like `type(scope)!: description` — lowercase type, optional (scope), optional !, then ': '")
        subject = header
    else:
        t, subject = m.group("type"), m.group("subject")
        if t not in types:
            errors.append(f"unknown type '{t}'; allowed: {', '.join(types)}")
        if t == "wip":
            warnings.append("wip commit — squash before opening the PR")
        scope = m.group("scope")
        if scope and not re.match(r"^[a-z0-9][a-z0-9._/-]*$", scope):
            errors.append(f"scope '{scope}' should be lowercase kebab/dotted (e.g. api, orders, github-14)")

    if len(header) > subject_max:
        errors.append(f"subject line is {len(header)} chars; hard limit {subject_max}")
    elif len(header) > subject_warn:
        warnings.append(f"subject line is {len(header)} chars; aim for <= {subject_warn}")
    if subject.rstrip().endswith("."):
        errors.append("subject must not end with a period")
    if subject != subject.strip():
        errors.append("subject has leading/trailing whitespace")
    if subject.isupper() and len(subject) > 4:
        errors.append("subject is ALL CAPS")
    words = re.findall(r"[A-Za-z']+", subject)
    if words:
        first = words[0].lower()
        if first in NON_IMPERATIVE_HINTS:
            errors.append(f"use imperative mood: '{words[0]}' -> '{re.sub(r'(ed|ing|es|s)$', '', first)}' (test: 'If applied, this commit will <subject>')")
        if subject.strip().lower() in VAGUE_SUBJECTS or (len(words) == 1 and first in VAGUE_SUBJECTS):
            errors.append(f"subject '{subject}' is too vague — say what changed and where")
    tokens = subject.split()
    if len(tokens) == 1 and tokens[0].lower() not in VAGUE_SUBJECTS:
        warnings.append("one-token subject — is that the outcome, or just a filename/dependency name? Say what it does (e.g. 'add X to Y')")

    # body / footers
    if len(lines) > 1:
        if lines[1].strip():
            errors.append("leave a blank line between subject and body (git log/shortlog/rebase rely on it)")
        for i, l in enumerate(lines[2:], start=3):
            if len(l) > body_wrap and not re.search(r"https?://\S+|\S{60,}", l):
                warnings.append(f"line {i} is {len(l)} chars; wrap body at {body_wrap}")
        body = "\n".join(lines[1:])
        if re.search(r"^BREAKING[ -]CHANGE:", body, re.M) and m and not m.group("bang"):
            warnings.append("BREAKING CHANGE footer present — consider `type!:` in the subject too")
        if re.search(r"^breaking change:", body, re.M | re.I) and not re.search(r"^BREAKING[ -]CHANGE:", body, re.M):
            errors.append("'BREAKING CHANGE:' footer is case-sensitive (uppercase)")
    return errors, warnings


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file", nargs="?", help="path to a message file (e.g. .git/COMMIT_EDITMSG)")
    ap.add_argument("--message", "-m", help="message text")
    ap.add_argument("--last", action="store_true", help="lint HEAD's commit message")
    ap.add_argument("--types", default=DEFAULT_TYPES)
    ap.add_argument("--subject-max", type=int, default=72)
    ap.add_argument("--subject-warn", type=int, default=50)
    ap.add_argument("--body-wrap", type=int, default=72)
    a = ap.parse_args()

    if a.message is not None:
        text = a.message
    elif a.last:
        text = subprocess.run(["git", "log", "-1", "--format=%B"], capture_output=True, text=True).stdout
    elif a.file:
        with open(a.file, encoding="utf-8", errors="replace") as f:
            text = f.read()
    else:
        ap.print_usage(); return 2

    types = [t.strip() for t in a.types.split(",") if t.strip()]
    errors, warnings = lint(text, types, a.subject_max, a.subject_warn, a.body_wrap)
    first = (text.strip().splitlines() or [""])[0]
    for w in warnings:
        print(f"  ~ {w}")
    for e in errors:
        print(f"  ✗ {e}")
    if errors:
        print(f"INVALID: {first}")
        return 1
    print(f"OK: {first}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
