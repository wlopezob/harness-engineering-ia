---
name: git-branching
description: Create, name, and manage Git branches following team conventions (Conventional Branch style — `feat/`, `fix/`, `chore/`, `hotfix/`, `release/` + kebab-case description + optional ticket id), always from an up-to-date base branch, with proper publish and cleanup. Use this skill whenever the user wants to start work on a feature, bug, ticket, or issue ("crea una rama", "create a branch", "start working on X", "new branch for issue 42"), asks how to name a branch, wants a branch name proposed or validated, asks about branch naming conventions or branch protection rules, or wants to clean up merged branches — even if they don't say the word "branch". Also use it when a task implies branching (e.g., "let's implement X" on a repo where main is protected).
---

# Git Branching

Help the user create branches that are consistent, self-describing, and easy to automate around, then keep them short-lived. Answer in the user's language; keep branch names in English (they are identifiers shared across tools and CI).

## Workflow

### 1. Detect the repo's existing convention first

Consistency inside a repo matters more than any external standard. Before proposing anything, look at what the repo already does:

```bash
git branch -r --format='%(refname:short)' | sed 's|origin/||' | grep -v HEAD | sort | head -40
git log --merges --oneline -15          # merged PR titles reveal prefixes in use
git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null   # default branch (main/master/develop)
```

Also check for a documented policy: `CONTRIBUTING.md`, `docs/branching.md`, `.github/` workflows that filter on branch names, or GitHub rulesets. If the repo already uses `feature/` instead of `feat/`, or `bugfix/` instead of `fix/`, follow the repo. Only fall back to the defaults below when the repo has no visible pattern.

### 2. Compose the name: `<type>/[<ticket>-]<short-kebab-description>`

| Prefix | Use for |
|---|---|
| `feat/` | new functionality |
| `fix/` | bug fixes |
| `hotfix/` | urgent patch to production, branched from the release/main that is deployed |
| `chore/` | maintenance with no product change: deps, CI, tooling, formatting |
| `docs/` | documentation only |
| `refactor/` | restructuring with no behavior change |
| `test/` | adding or fixing tests only |
| `release/` | release preparation, e.g. `release/1.4.0` |
| `spike/` or `experiment/` | throwaway exploration, expected to be deleted |

Guidelines that make names useful rather than merely valid:

- **Describe the outcome, not the mechanics**: `feat/export-orders-csv`, not `feat/add-new-code-for-exporting`.
- **Include the ticket/issue id when there is one** (`feat/gh-14-plan-lifecycle`, `fix/PROJ-123-null-order-total`). It links branch → PR → issue automatically and lets CI/changelog tooling do the rest. Ask for it only if the user clearly works with a tracker and didn't mention it; otherwise don't block on it.
- **Keep it short**: 2–5 words after the prefix. Names get typed, tab-completed, and printed in CI logs.
- **Lowercase, digits, hyphens only.** No spaces, no `_`, no CamelCase — Git branch names are case-sensitive on Linux but collide on macOS/Windows filesystems, so mixed case creates "works on my machine" bugs.
- **One level of `/` only.** Refs are stored as files, so once `feat/login` exists, `feat/login/ui` cannot be created (and vice-versa). Never nest.

Use the bundled helper (paths relative to this skill's directory) to slugify a free-text description into a valid name and to validate it against Git's ref rules and existing refs:

```bash
python3 scripts/branch_name.py propose feat "Export orders to CSV" --ticket GH-14
# → feat/gh-14-export-orders-to-csv
python3 scripts/branch_name.py check feat/gh-14-export-orders-to-csv
```

`check` runs `git check-ref-format`, enforces the kebab-case regex, and detects the nested-ref collision. Prefer it over eyeballing — the collision case in particular is easy to miss and only fails at `git switch -c` time.

### 3. Create from an up-to-date base, not from a stale local branch

Branching from a local `main` that is behind `origin/main` bakes in a future merge conflict. Fetch first and branch straight from the remote-tracking ref:

```bash
git fetch origin
git switch --no-track -c <name> origin/<default>   # default = main unless the repo says otherwise
```

`--no-track` matters: without it Git sets `origin/main` as the new branch's upstream, so `git status` shows misleading "ahead of origin/main" counts, a bare `git pull` merges `origin/main` into the branch, and a bare `git push` fails with a confusing "upstream branch name does not match" error. The real upstream gets set in step 4 with `push -u`.

Or use `scripts/new_branch.sh <name> [--base <branch>] [--push]`, which does fetch → validate → switch and optionally publishes with upstream tracking. Hotfixes branch from whatever is deployed (`main` or a `release/x.y` branch), not from `develop`.

If the working tree has uncommitted changes, `git switch -c` carries them onto the new branch — that's usually what the user wants (they started editing before branching). Mention it rather than stashing silently.

### 4. Publish early with tracking

```bash
git push -u origin <name>
```

Pushing early gives the user a backup and lets CI run from the first commit. Suggest `git config --global push.autoSetupRemote true` once so `-u` is never forgotten again.

### 5. Keep it short-lived, then delete it

A branch is a unit of review, not a home. Encourage: one purpose per branch, rebase/merge the base often (`git fetch && git rebase origin/main`, then `git push --force-with-lease` — never plain `--force`, and never rewrite shared branches), open the PR early. After merge:

```bash
git switch main && git pull --ff-only
git branch -d <name>
git push origin --delete <name>    # unless GitHub auto-deletes head branches
git fetch --prune
```

## When the user asks about policy or enforcement

For hooks, CI checks, GitHub rulesets / branch protection, the validation regex, or a team `CONTRIBUTING` snippet, read `references/enforcement.md`. It has ready-to-paste snippets and the trade-offs of each layer.

## Anti-patterns to steer away from

- Committing directly on `main`/`master` (suggest a branch + PR even for "tiny" changes when the repo uses PRs).
- Personal-name branches (`juan/stuff`, `wip`, `test2`) — they say who, not what. If the team wants ownership, put it in a ticket id or a suffix, not the type slot.
- Long-lived integration branches per developer.
- Reusing a merged branch for new work — start a fresh one from the updated base.
