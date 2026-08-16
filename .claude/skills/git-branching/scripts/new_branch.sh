#!/usr/bin/env bash
# Create a branch from an up-to-date remote base, validating the name first.
#
# Usage: new_branch.sh <branch-name> [--base <branch>] [--remote <name>] [--push] [--force-name]
#   --base    base branch (default: remote HEAD, else main/master)
#   --remote  remote name (default: origin)
#   --push    push with -u after creating
#   --force-name  skip name validation
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
name="${1:-}"; shift || true
[[ -z "$name" ]] && { echo "usage: new_branch.sh <branch-name> [--base <branch>] [--remote <name>] [--push] [--force-name]" >&2; exit 2; }

base=""; remote="origin"; push=0; force=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) base="$2"; shift 2;;
    --remote) remote="$2"; shift 2;;
    --push) push=1; shift;;
    --force-name) force=1; shift;;
    *) echo "unknown option: $1" >&2; exit 2;;
  esac
done

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "not inside a git repository" >&2; exit 1; }

if [[ $force -eq 0 ]]; then
  python3 "$here/branch_name.py" check "$name" || exit 1
fi

if git remote get-url "$remote" >/dev/null 2>&1; then
  echo "→ git fetch $remote"
  git fetch "$remote" --prune
  if [[ -z "$base" ]]; then
    base="$(git symbolic-ref --quiet --short "refs/remotes/$remote/HEAD" 2>/dev/null | sed "s|^$remote/||" || true)"
    if [[ -z "$base" ]]; then
      for cand in main master develop; do
        git show-ref --verify --quiet "refs/remotes/$remote/$cand" && { base="$cand"; break; }
      done
    fi
  fi
  [[ -z "$base" ]] && { echo "could not determine base branch; pass --base" >&2; exit 1; }
  start="$remote/$base"
else
  [[ -z "$base" ]] && base="$(git symbolic-ref --quiet --short HEAD)"
  start="$base"
  echo "! no remote '$remote'; branching from local '$base'"
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "! working tree has uncommitted changes; they will be carried onto '$name'"
fi

echo "→ git switch --no-track -c $name $start"
git switch --no-track -c "$name" "$start"

if [[ $push -eq 1 ]]; then
  echo "→ git push -u $remote $name"
  git push -u "$remote" "$name"
fi
echo "✓ on branch $name (from $start)"
