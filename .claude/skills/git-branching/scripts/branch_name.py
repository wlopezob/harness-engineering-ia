#!/usr/bin/env python3
"""Propose and validate Git branch names following Conventional Branch style.

Usage:
  branch_name.py propose <type> <description...> [--ticket ID] [--max-words N]
  branch_name.py check <name> [--allow-types a,b,c] [--no-git]

Exit code 0 = valid / proposed, 1 = invalid, 2 = usage error.
Only stdlib; safe to run anywhere. `check` shells out to git when a repo is
present to run `git check-ref-format` and detect nested-ref collisions.
"""
import argparse
import re
import subprocess
import sys
import unicodedata

DEFAULT_TYPES = [
    "feat", "feature", "fix", "bugfix", "hotfix", "chore", "docs",
    "refactor", "test", "release", "spike", "experiment", "perf", "ci", "build",
]
NAME_RE = re.compile(r"^[a-z0-9]+(?:\.[0-9]+)*(?:-[a-z0-9]+(?:\.[0-9]+)*)*$")


def slugify(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    text = "".join(c for c in text if not unicodedata.combining(c))
    text = text.lower()
    text = re.sub(r"[^a-z0-9.]+", "-", text)
    text = re.sub(r"-{2,}", "-", text).strip("-.")
    return text


def propose(args) -> int:
    btype = args.type.lower().strip("/")
    desc = slugify(" ".join(args.description))
    if not desc:
        print("error: description produced an empty slug", file=sys.stderr)
        return 2
    words = desc.split("-")
    if args.max_words and len(words) > args.max_words:
        desc = "-".join(words[: args.max_words])
    parts = []
    if args.ticket:
        parts.append(slugify(args.ticket))
    parts.append(desc)
    name = f"{btype}/{'-'.join(parts)}"
    print(name)
    return 0


def git(*cmd):
    try:
        return subprocess.run(["git", *cmd], capture_output=True, text=True, check=False)
    except FileNotFoundError:
        return None


def check(args) -> int:
    name = args.name
    problems = []
    types = [t.strip() for t in args.allow_types.split(",") if t.strip()]

    slash_ok = name.count("/") == 1
    if not slash_ok:
        problems.append("use exactly one '/' : <type>/<description> (nested paths collide with existing refs)")
    btype, desc = (name.split("/", 1) + [""])[:2]

    if btype not in types:
        problems.append(f"unknown type '{btype}'; expected one of: {', '.join(types)}")
    if not desc:
        problems.append("missing description after the type")
    elif slash_ok and not NAME_RE.match(desc):
        problems.append("description must be lowercase letters/digits separated by single hyphens (dots only inside version numbers)")
    if len(name) > 60:
        problems.append(f"too long ({len(name)} chars); aim for < 60")

    if not args.no_git:
        r = git("check-ref-format", "--branch", name)
        if r is not None and r.returncode != 0:
            problems.append("git check-ref-format rejects this name")
        r = git("rev-parse", "--is-inside-work-tree")
        if r is not None and r.returncode == 0 and r.stdout.strip() == "true":
            refs = git("for-each-ref", "--format=%(refname:short)", "refs/heads", "refs/remotes")
            existing = set()
            for ref in (refs.stdout.split() if refs else []):
                existing.add(ref[len("origin/"):] if ref.startswith("origin/") else ref)
            if name in existing:
                problems.append(f"branch '{name}' already exists (locally or on origin)")
            for ref in existing:
                if ref.startswith(name + "/"):
                    problems.append(f"cannot create '{name}': existing ref '{ref}' uses it as a directory")
                if name.startswith(ref + "/"):
                    problems.append(f"cannot create '{name}': existing branch '{ref}' blocks the nested path")

    if problems:
        print(f"INVALID: {name}")
        for p in problems:
            print(f"  - {p}")
        return 1
    print(f"OK: {name}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("propose", help="build a name from type + free-text description")
    p.add_argument("type")
    p.add_argument("description", nargs="+")
    p.add_argument("--ticket", help="ticket/issue id, e.g. GH-14 or PROJ-123")
    p.add_argument("--max-words", type=int, default=6)
    p.set_defaults(fn=propose)

    c = sub.add_parser("check", help="validate a branch name")
    c.add_argument("name")
    c.add_argument("--allow-types", default=",".join(DEFAULT_TYPES))
    c.add_argument("--no-git", action="store_true", help="skip git-based checks")
    c.set_defaults(fn=check)

    args = ap.parse_args()
    return args.fn(args)


if __name__ == "__main__":
    sys.exit(main())
