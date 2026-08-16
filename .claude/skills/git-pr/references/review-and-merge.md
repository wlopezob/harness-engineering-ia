# PR policy: review, merge strategy, repo settings

Read this when the user asks about repo-level PR rules (what to require, how to merge, who reviews), stacked PRs, or wants the PR process automated.

## Contents
1. PR template
2. Merge strategy — pick one
3. Rulesets for the trunk
4. Auto-delete head branches & branch hygiene
5. CODEOWNERS and reviewer assignment
6. CI checks worth requiring (PR title lint, size)
7. Stacked PRs
8. Review etiquette (author & reviewer)

---

## 1. PR template

`.github/pull_request_template.md` is pre-filled by GitHub's web UI and by `gh pr create` when you open the editor (`gh pr create` without `--body`). A copy lives in `assets/pull_request_template.md`. Multiple templates: `.github/PULL_REQUEST_TEMPLATE/<name>.md` + `?template=<name>.md` in the compare URL.

Keep it short — a template that takes longer to fill than the change took to write gets deleted, not filled.

## 2. Merge strategy — pick one

| Strategy | History on trunk | Requires | Good when |
|---|---|---|---|
| **Squash** | one commit per PR, linear | a good PR **title/body** (they become the commit) | branch commits are messy; PR = unit of change |
| **Rebase** | branch commits replayed, linear | every branch commit clean & atomic | you want bisectable steps inside a PR |
| **Merge commit** | branch commits + merge node | clean commits; tolerates non-linear history | you want to preserve "this set landed together" and exact SHAs |

Enable only the chosen one (Settings → General → Pull Requests) so history stays consistent, and if squashing tick "Default to pull request title for squash merge commits". Check current state:

```bash
gh repo view --json mergeCommitAllowed,squashMergeAllowed,rebaseMergeAllowed,deleteBranchOnMerge
```

## 3. Rulesets for the trunk

Settings → Rules → Rulesets → New branch ruleset, target `main` (or `~DEFAULT_BRANCH`):

- Require a pull request before merging (≥1 approval when there's a team; for solo repos still require the PR — it forces CI and self-review)
- Require status checks to pass — pick the job names exactly (`Maven verify`, `Dependency review`, `CodeQL`, …); "strict" = branch must be up to date with base
- Block force pushes; restrict deletions
- Optional: require linear history (forces squash/rebase), require conversation resolution, require signed commits

```bash
gh api repos/{owner}/{repo}/rulesets --method POST --input - <<'JSON'
{ "name": "main-protection", "target": "branch", "enforcement": "active",
  "conditions": { "ref_name": { "include": ["~DEFAULT_BRANCH"], "exclude": [] } },
  "rules": [
    { "type": "deletion" }, { "type": "non_fast_forward" },
    { "type": "pull_request", "parameters": { "required_approving_review_count": 0,
        "dismiss_stale_reviews_on_push": true, "require_code_owner_review": false,
        "require_last_push_approval": false, "required_review_thread_resolution": true } },
    { "type": "required_status_checks", "parameters": { "strict_required_status_checks_policy": true,
        "required_status_checks": [ { "context": "Maven verify" } ] } }
  ] }
JSON
```

Leave the bypass list empty unless there's a documented reason; admins are not auto-exempt from rulesets, which is the point.

## 4. Auto-delete head branches & hygiene

Settings → General → Pull Requests → **Automatically delete head branches**. Then locally: `git fetch --prune` and `git branch --merged main | grep -v main | xargs -n1 git branch -d`. Merged branches left on the remote invite reuse and hide what's actually in flight.

## 5. CODEOWNERS

`.github/CODEOWNERS` auto-requests reviewers per path and can be made mandatory in the ruleset ("Require review from Code Owners"):

```
# default
*                       @org/backend
/orders-platform/       @org/orders-team
/.github/workflows/     @org/platform
```

Request 1–2 people, not the team; use `gh pr edit --add-reviewer`.

## 6. CI checks worth requiring

**PR title follows Conventional Commits** (essential when squash-merging):

```yaml
# .github/workflows/pr-title.yml
name: pr-title
on:
  pull_request:
    types: [opened, edited, synchronize, reopened]
permissions: { pull-requests: read }
jobs:
  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: amannn/action-semantic-pull-request@v5
        env: { GITHUB_TOKEN: "${{ secrets.GITHUB_TOKEN }}" }
        with:
          types: feat fix docs test refactor perf style build ci chore revert
          requireScope: false
          subjectPattern: ^(?![A-Z]).+$        # no leading capital
```

**Size label** (informational): `codelytv/pr-size-labeler` or `pascalgn/size-label-action` labels `size/XS…XL`; pair with a policy like "XL needs a written justification".

**Every commit linted** (only if you rebase/merge-commit): `wagoid/commitlint-github-action` with `fetch-depth: 0`.

## 7. Stacked PRs

For a big change: PR A (refactor prep) → PR B (feature, base = A's branch) → PR C. Each is small and reviewable; merge in order and retarget the next to `main` (`gh pr edit B --base main` — GitHub retargets automatically when the base branch is deleted on merge). Rebase downstream branches after each merge (`git rebase --onto main A B`). Tools: Graphite, `git-branchless`, `spr`; plain `gh` works fine for 2–3 levels.

## 8. Review etiquette

Author:
- Open as draft until CI is green and you've self-reviewed; then `gh pr ready`.
- Say what feedback you want ("mainly the concurrency change in X; the rest is mechanical").
- Respond to every comment; push fix commits (no force-push mid-review); resolve threads you opened or that are clearly done.
- Don't merge your own PR where review is expected; don't bypass required checks.

Reviewer:
- Start with the description, then the diff in reading order; check tests reflect the stated behavior.
- Prefix non-blocking remarks with `nit:`; make blocking ones explicit and actionable ("this NPEs when list is empty — add a guard + test").
- Approve with what you actually verified; request changes only for real problems, not taste.
- Aim to respond within a working day — a stale PR is the most expensive kind.
