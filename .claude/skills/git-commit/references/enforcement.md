# Enforcing commit conventions

Read this when the user wants commit rules *checked automatically* or wants tooling around commits. Pick the lightest layer that solves their problem.

## Contents
1. Message template (`commit.template`)
2. Layer 1 — local `commit-msg` hook (fast, bypassable)
3. Layer 2 — commitlint via husky / lefthook (versioned, team-wide)
4. Layer 3 — CI: lint commits and/or the PR title
5. Signing commits
6. Changelog / version automation
7. Merge strategy and what it means for messages
8. CONTRIBUTING.md snippet

---

## 1. Message template

Copy `assets/gitmessage.txt` into the repo (e.g. `.gitmessage`) and point git at it:

```bash
git config commit.template .gitmessage        # per repo (each clone runs it once)
git config --global commit.template ~/.gitmessage
```

Lines starting with `#` are stripped, so the template is pure guidance. Pair with `git config --global commit.verbose true` so the diff shows in the editor.

## 2. Local `commit-msg` hook

Zero dependencies — reuse the bundled linter:

```bash
#!/usr/bin/env bash
# .git/hooks/commit-msg  (chmod +x). Or point at a versioned copy via `git config core.hooksPath .githooks`
python3 .claude/skills/git-commit/scripts/commit_msg_lint.py "$1"
```

Or the one-line regex version if the project can't assume python:

```bash
#!/usr/bin/env bash
msg="$(head -1 "$1")"
case "$msg" in Merge\ *|Revert\ *|fixup!\ *|squash!\ *) exit 0;; esac
[[ "$msg" =~ ^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\([a-z0-9._/-]+\))?!?:\ .{1,72}$ ]] \
  || { echo "✗ commit subject must be: type(scope): description  (Conventional Commits)"; exit 1; }
```

Hooks in `.git/hooks` are not versioned; for a team, use §3 or `core.hooksPath`.

## 3. commitlint (Node projects, or any repo that tolerates a dev dependency)

```bash
npm i -D @commitlint/cli @commitlint/config-conventional husky
npx husky init
echo 'npx --no -- commitlint --edit "$1"' > .husky/commit-msg
```

```js
// commitlint.config.js
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'header-max-length': [2, 'always', 72],
    'subject-case': [2, 'never', ['sentence-case', 'start-case', 'pascal-case', 'upper-case']],
    'scope-enum': [1, 'always', ['api', 'orders', 'harness', 'ci', 'docs']],   // warn, not error, until scopes settle
  },
};
```

Non-Node repos: `lefthook` (single Go binary) with a `commit-msg` job calling the python linter above, or `pre-commit` with the `conventional-pre-commit` hook.

## 4. CI

**Lint every commit in the PR** (catches `--no-verify`):

```yaml
# .github/workflows/commitlint.yml
name: commitlint
on: [pull_request]
jobs:
  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - uses: wagoid/commitlint-github-action@v6
```

**Lint the PR title** — the right check when the repo squash-merges (title becomes the commit):

```yaml
      - uses: amannn/action-semantic-pull-request@v5
        env: { GITHUB_TOKEN: "${{ secrets.GITHUB_TOKEN }}" }
        with:
          types: feat fix docs test refactor perf style build ci chore revert
          requireScope: false
```

Then require the check in the `main` ruleset (Require status checks to pass).

## 5. Signing commits

SSH signing is the least friction if the user already pushes over SSH:

```bash
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/id_ed25519.pub
git config --global commit.gpgsign true
git config --global tag.gpgsign true
```

Then add the same public key to GitHub as a **Signing key** (Settings → SSH and GPG keys) to get the "Verified" badge. Rulesets can enforce it ("Require signed commits").

## 6. Changelog / version automation

Conventional Commits pays off here — pick one:

| Tool | Fit |
|---|---|
| **release-please** (GitHub Action) | opens a "release PR" with CHANGELOG + version bump computed from commits; language-agnostic; simplest for GitHub |
| **semantic-release** | fully automatic publish on merge; Node ecosystem |
| **git-cliff** | just generates the CHANGELOG from history; single binary |
| **jreleaser / maven-release-plugin + conventional-changelog** | Java-native pipelines |

They read `feat`→minor, `fix`→patch, `!`/`BREAKING CHANGE`→major, and ignore `chore/docs/build/ci` in the notes — which is exactly why mislabeling tooling as `feat` hurts.

## 7. Merge strategy vs. messages

- **Merge commits** (default in many repos): every branch commit lands in `main` — each one must be clean; use `--fixup` + `rebase --autosquash` before review.
- **Squash merge**: only the PR title/body survive — enforce the PR title (§4) and let branch commits be messy.
- **Rebase merge**: like merge commits but linear — same discipline as merge commits.

Set it in Settings → General → Pull Requests (allow only one strategy to keep history consistent) and, if squashing, "Default to PR title for squash merge commits".

## 8. CONTRIBUTING.md snippet

```markdown
## Commits

- One intention per commit; each commit builds and passes tests.
- Message: `type(scope): imperative subject` (Conventional Commits), ≤50 chars, no period.
  Types: feat, fix, docs, test, refactor, perf, style, build, ci, chore, revert.
- Body explains *why* (motivation, rejected options, verification). Wrap at 72.
- Footers: `Closes #N`, `BREAKING CHANGE: …`.
- Fix earlier commits with `git commit --fixup <sha>` + `git rebase -i --autosquash origin/main`;
  never rewrite `main`.
```
