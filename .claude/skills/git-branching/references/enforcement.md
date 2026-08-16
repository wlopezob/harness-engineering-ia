# Enforcing branch conventions

Read this when the user wants the convention to be *checked automatically* (hook, CI, GitHub rules) or wants a policy snippet for CONTRIBUTING.md. Pick the lightest layer that solves their problem; stacking all of them is rarely worth it.

## Contents
1. The regex
2. Layer 1 — local git hook (fast feedback, easy to bypass)
3. Layer 2 — CI check on pull requests (authoritative, visible to reviewers)
4. Layer 3 — GitHub rulesets / branch protection (server-side, hard to bypass)
5. Handy git config
6. CONTRIBUTING.md snippet

---

## 1. The regex

```
^(feat|fix|hotfix|chore|docs|refactor|test|release|spike)\/[a-z0-9]+([.-][a-z0-9]+)*$
```

Adjust the type list to what the repo actually uses (`feature|bugfix` instead of `feat|fix`, etc.). The tail allows `release/1.4.0` and `feat/gh-14-plan-lifecycle`, rejects uppercase, `_`, spaces, double hyphens and nested `/`.

`scripts/branch_name.py check <name>` implements the same rule plus Git's own ref rules and the nested-ref collision check — reuse it in hooks/CI instead of copy-pasting regexes.

## 2. Local hook (pre-push)

Blocks pushing a badly named branch. Runs on the developer's machine, so it is advisory — anyone can `--no-verify`. Good for fast feedback, not for guarantees.

```bash
#!/usr/bin/env bash
# .git/hooks/pre-push  (or manage via husky / lefthook / pre-commit)
branch="$(git symbolic-ref --short HEAD)"
case "$branch" in main|master|develop) exit 0;; esac
if ! [[ "$branch" =~ ^(feat|fix|hotfix|chore|docs|refactor|test|release|spike)/[a-z0-9]+([.-][a-z0-9]+)*$ ]]; then
  echo "✗ branch name '$branch' does not follow <type>/<kebab-description>" >&2
  echo "  see CONTRIBUTING.md#branches" >&2
  exit 1
fi
```

For teams using `pre-commit`, `lefthook`, or `husky`, wire the same script into their config so it is versioned with the repo (hooks in `.git/hooks` are not).

## 3. CI check on pull requests (GitHub Actions)

Authoritative and visible in the PR. Fails fast, before tests run.

```yaml
# .github/workflows/branch-name.yml
name: branch-name
on:
  pull_request:
    types: [opened, edited, synchronize, reopened]
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - name: Validate head branch name
        env:
          BRANCH: ${{ github.head_ref }}
        run: |
          if [[ ! "$BRANCH" =~ ^(feat|fix|hotfix|chore|docs|refactor|test|release|spike)/[a-z0-9]+([.-][a-z0-9]+)*$ ]]; then
            echo "::error::Branch '$BRANCH' must match <type>/<kebab-description>"
            exit 1
          fi
```

Skip it for bot branches (`dependabot/`, `renovate/`) if the repo uses them: add `if: ${{ !startsWith(github.head_ref, 'dependabot/') }}`.

## 4. GitHub rulesets / branch protection

Server-side, cannot be bypassed by contributors. Two separate things:

**Protect the trunk** (Settings → Rules → Rulesets → New branch ruleset, target `main`):
- Require a pull request before merging (≥1 approval if there is a team)
- Require status checks to pass (pick the CI job names)
- Block force pushes; restrict deletions
- Optionally: require linear history (forces squash/rebase merges)

**Block badly named branches at push time.** Two options, pick by plan:

- *Enterprise Cloud only* — "Restrict branch names" (metadata restriction): in the ruleset, Restrictions → Restrict branch names → "Must match a given regex pattern", paste the regex from §1 (RE2 syntax). Target all branches, exclude `main`.
- *Everything else (Free public repos, Pro, Team)* — invert the logic with **Restrict creations**: target **all branches**, exclude the trunk and every allowed prefix, then enable "Restrict creations". Anything not excluded cannot be created, so `git push origin wip` is rejected server-side. Ready to paste:

  ```bash
  gh api repos/{owner}/{repo}/rulesets --method POST --input - <<'JSON'
  {
    "name": "branch-naming-guard",
    "target": "branch",
    "enforcement": "active",
    "conditions": { "ref_name": {
      "include": ["~ALL"],
      "exclude": ["refs/heads/main",
                  "refs/heads/feat/**", "refs/heads/fix/**", "refs/heads/hotfix/**",
                  "refs/heads/chore/**", "refs/heads/docs/**", "refs/heads/refactor/**",
                  "refs/heads/test/**", "refs/heads/release/**",
                  "refs/heads/dependabot/**", "refs/heads/renovate/**"] } },
    "rules": [{ "type": "creation" }]
  }
  JSON
  ```

  Ruleset patterns are fnmatch, not regex, so this guarantees the *prefix* only; the CI check in §3 is what enforces kebab-case after the slash. The two layers complement each other. Leave the bypass list empty — rulesets do not auto-exempt admins, which is usually what you want.

Rulesets are available in public repos on any plan and in private repos on Pro/Team/Enterprise; on a private Free repo you only get classic branch protection for the trunk, so the CI check plus a local hook is the whole story.

**Auto-delete merged branches**: Settings → General → Pull Requests → "Automatically delete head branches". Recommend this to everyone; it removes the most common source of branch clutter.

GitLab equivalent: Settings → Repository → Push rules → "Branch name" regex; protected branches under Settings → Repository → Protected branches.

## 5. Handy git config

```bash
git config --global push.autoSetupRemote true   # `git push` sets upstream automatically
git config --global fetch.prune true            # drop remote-tracking refs for deleted branches
git config --global pull.ff only                # never create accidental merge commits on pull
git config --global rebase.autoStash true       # `git rebase` works with a dirty tree
git config --global init.defaultBranch main
```

## 6. CONTRIBUTING.md snippet

```markdown
## Branches

- Branch from an up-to-date `main`: `git fetch origin && git switch -c <name> origin/main`.
- Name: `<type>/<short-kebab-description>`, optionally with the issue id first:
  `feat/gh-14-plan-lifecycle`, `fix/null-order-total`, `chore/bump-quarkus-3-15`.
  Types: `feat`, `fix`, `hotfix`, `chore`, `docs`, `refactor`, `test`, `release`.
- One purpose per branch; open a PR early; rebase on `main` regularly.
- After the PR merges, the head branch is deleted automatically — start new work from a fresh branch.
```
