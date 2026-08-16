#!/usr/bin/env bash
# PR readiness check for the current branch.
# Usage: pr_preflight.sh [--base <branch>] [--remote <name>] [--max-lines N] [--max-files N]
# Exit 0 = ready (warnings allowed), 1 = blocking problems, 2 = usage.
set -uo pipefail

base=""; remote="origin"; max_lines=400; max_files=15
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base) base="$2"; shift 2;;
    --remote) remote="$2"; shift 2;;
    --max-lines) max_lines="$2"; shift 2;;
    --max-files) max_files="$2"; shift 2;;
    *) echo "usage: pr_preflight.sh [--base <branch>] [--remote <name>] [--max-lines N] [--max-files N]" >&2; exit 2;;
  esac
done

git rev-parse --is-inside-work-tree >/dev/null 2>&1 || { echo "not inside a git repository" >&2; exit 1; }
errors=0; warns=0
err()  { echo "  ✗ $*"; errors=$((errors+1)); }
warn() { echo "  ~ $*"; warns=$((warns+1)); }
ok()   { echo "  ✓ $*"; }

branch="$(git symbolic-ref --quiet --short HEAD || echo DETACHED)"
if [[ -z "$base" ]]; then
  base="$(git symbolic-ref --quiet --short "refs/remotes/$remote/HEAD" 2>/dev/null | sed "s|^$remote/||")"
  [[ -z "$base" ]] && for c in main master develop; do git show-ref --verify --quiet "refs/remotes/$remote/$c" && { base="$c"; break; }; done
fi
[[ -z "$base" ]] && { echo "could not determine base branch; pass --base" >&2; exit 1; }
echo "PR preflight: $branch -> $base"

git fetch -q "$remote" 2>/dev/null || warn "could not fetch $remote (offline?) — comparisons may be stale"

# 1. branch
if [[ "$branch" == "$base" || "$branch" == "DETACHED" ]]; then err "you are on '$branch' — PRs come from a feature branch (see git-branching skill)"; else ok "on feature branch '$branch'"; fi

# 2. working tree
if [[ -n "$(git status --porcelain)" ]]; then
  warn "working tree not clean — uncommitted/untracked files won't be in the PR:"; git status --short | head -10 | sed 's/^/      /'
else ok "working tree clean"; fi

# 3. upstream / pushed
up="$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null || true)"
if [[ -z "$up" ]]; then warn "no upstream — run: git push -u $remote $branch"
elif [[ "$up" == "$remote/$base" ]]; then err "upstream is $up (the trunk!) — recreate with --no-track or: git branch --unset-upstream && git push -u $remote $branch"
else
  ok "upstream $up"
  ahead_up="$(git rev-list --count "$up..HEAD" 2>/dev/null || echo 0)"
  [[ "$ahead_up" -gt 0 ]] && warn "$ahead_up local commit(s) not pushed yet"
fi

# 4. relation to base
behind="$(git rev-list --count "HEAD..$remote/$base" 2>/dev/null || echo 0)"
ahead="$(git rev-list --count "$remote/$base..HEAD" 2>/dev/null || echo 0)"
if [[ "$ahead" -eq 0 ]]; then err "no commits ahead of $remote/$base — nothing to PR"; else ok "$ahead commit(s) ahead of $remote/$base"; fi
if [[ "$behind" -gt 0 ]]; then warn "$behind commit(s) behind $remote/$base — rebase first: git rebase $remote/$base (then push --force-with-lease)"; else ok "up to date with $remote/$base"; fi

# 5. size
read -r files ins dels < <(git diff --shortstat "$remote/$base...HEAD" 2>/dev/null | awk '{f=$1; i=0; d=0; for(k=1;k<=NF;k++){ if($k ~ /insertion/) i=$(k-1); if($k ~ /deletion/) d=$(k-1)}; print f, i, d}')
files=${files:-0}; ins=${ins:-0}; dels=${dels:-0}; total=$((ins+dels))
if [[ "$total" -gt "$max_lines" || "$files" -gt "$max_files" ]]; then
  warn "diff is $files files, +$ins/-$dels ($total lines) — above ~$max_lines lines/$max_files files reviewers slow down and miss things; consider splitting (reformat/rename/refactor first)"
else ok "diff size: $files files, +$ins/-$dels"; fi
# large generated/formatting-only files hint
git diff --numstat "$remote/$base...HEAD" 2>/dev/null | awk -v m=300 '$1+$2>m {print "      big: " $3 " (+" $1 "/-" $2 ")"}' | head -5

# 6. commit subjects
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
linter="$here/../../git-commit/scripts/commit_msg_lint.py"
bad=0
while IFS= read -r sha; do
  msg="$(git log -1 --format=%B "$sha")"
  if [[ -f "$linter" ]]; then
    out="$(python3 "$linter" -m "$msg" 2>&1)" || { bad=$((bad+1)); echo "$out" | sed 's/^/      /'; }
  else
    subj="$(printf '%s\n' "$msg" | head -1)"
    [[ "$subj" =~ ^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\([a-z0-9._/-]+\))?!?:\ .{1,72}$ || "$subj" =~ ^(Merge|Revert) ]] || { bad=$((bad+1)); echo "      ✗ $subj"; }
  fi
done < <(git rev-list --no-merges "$remote/$base..HEAD")
if [[ "$bad" -gt 0 ]]; then warn "$bad commit message(s) don't follow Conventional Commits — fix with --fixup/rebase -i before review, or rely on squash-merge + a good PR title"; else ok "commit messages follow Conventional Commits"; fi
dups="$(git log --no-merges --format=%s "$remote/$base..HEAD" | sort | uniq -d)"
[[ -n "$dups" ]] && warn "duplicate subjects (fixup material):$(printf '\n      %s' $dups)"

# 7. CI / existing PR
if command -v gh >/dev/null 2>&1; then
  prnum="$(gh pr view --json number,isDraft,state -q '.number' 2>/dev/null || true)"
  if [[ -n "$prnum" ]]; then
    ok "PR #$prnum already exists for this branch"
    gh pr checks "$prnum" 2>/dev/null | sed 's/^/      /' | head -12
  else
    ok "no PR yet — next: gh pr create --title \"type: subject\" --body-file body.md [--draft]"
  fi
else warn "gh CLI not found — CI status not checked"; fi

echo
if [[ "$errors" -gt 0 ]]; then echo "NOT READY: $errors blocking, $warns warnings"; exit 1; fi
echo "READY (with $warns warning(s))"; exit 0
