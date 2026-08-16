---
name: git-commit
description: Write and make good Git commits — atomic, verified, with Conventional Commits messages (`type(scope): imperative subject` + a body that explains why + footers like `Closes #14`). Use this skill whenever the user asks to commit, "haz commit", "guarda los cambios", wants a commit message written or reviewed, wants to split/squash/fixup/amend commits, asks how to write commit messages, or wants commit conventions enforced (commitlint, commit-msg hook, PR title check, changelog/semver automation). Also use it when a task naturally ends in a commit (feature done, fix done) even if the user doesn't say "commit" — the message quality is part of the deliverable.
---

# Git Commit

Make commits that a reviewer, `git bisect`, and a changelog generator can all trust. Answer in the user's language; write commit subjects in the language the repo already uses (check `git log`), defaulting to English.

## Workflow

### 1. Look before you write

```bash
git status --short                 # what's staged vs not
git diff --cached --stat           # what will actually go in
git log --format='%s' -20          # the repo's existing style: types, scope, language, casing
```

Match the repo. If it uses `feat(api):`, don't switch to `Add:`; if subjects are in Spanish, stay in Spanish. Only fall back to the defaults below when the repo has no visible pattern. Check for a `.gitmessage` template, `commitlint.config.*`, `.commitlintrc*`, `lefthook.yml`/`.husky/` — if a linter exists, its rules win over this document.

### 2. Decide the granularity: one intention per commit

Ask "could a reviewer describe this commit with one sentence and no 'and'?" If not, split:

- `git add -p` to stage hunks selectively; `git add <file>` for whole files.
- Reformatting, renames, and dependency bumps go in their own commits — mixing them with logic hides the logic in the diff.
- Each commit should build and pass tests on its own; `git bisect` lands on individual commits, not PRs.
- Never commit secrets, local config, or generated artifacts (`target/`, `__pycache__/`, `.env`, IDE files). If `git status` shows them, fix `.gitignore` in the same PR (own commit: `chore: ignore …`).

If the user has one big diff and wants one commit, that's their call — but say when a split would be cheap and clearer.

### 3. Verify before committing

Run the project's fast checks so the commit isn't a lie: the repo's own gate if it has one (`./harness verify`, `make check`, `npm test`, `mvn -q verify`, …), else at least the linter/formatter. Mention what ran in the body ("Verified: …"). Do not commit on red unless the user explicitly wants a WIP commit — then label it `wip:` and plan to squash it.

### 4. Compose the message

```
<type>(<scope>)!: <imperative subject, ≤50 chars ideal, 72 hard, no trailing period>

<body: what changed and WHY — motivation, alternatives rejected, side effects.
 Wrap at 72. Bullets are fine. Skip if the subject truly says it all.>

<footers: Closes #14 | Refs #14 | BREAKING CHANGE: … | Co-authored-by: Name <email>>
```

| Type | Use for | SemVer |
|---|---|---|
| `feat` | new user-visible capability | MINOR |
| `fix` | bug fix | PATCH |
| `docs` | documentation only | — |
| `test` | add/fix tests only | — |
| `refactor` | restructure, no behavior change | — |
| `perf` | performance, no behavior change | PATCH |
| `style` | formatting, whitespace, no code change | — |
| `build` | build system, dependencies, build plugins (Maven, Gradle, JaCoCo, Spotless, PIT…) | — |
| `ci` | pipelines and workflows | — |
| `chore` | repo maintenance that fits nowhere else (`.gitignore`, tooling, skills) | — |
| `revert` | reverting a commit; body says which and why | — |

`!` after type/scope or a `BREAKING CHANGE:` footer marks a MAJOR bump.

Rules that matter and why:
- **Imperative mood**: the subject completes "If applied, this commit will …" — *add JaCoCo gate* ✓, *added* / *adding* ✗. It's what Git itself writes (`Merge…`, `Revert…`) and it reads as a changelog entry.
- **Subject says the outcome, not the file**: `fix(orders): reject negative quantities`, not `fix: update OrderService.java`.
- **Type reflects the change, not the effort**: adding a build plugin is `build`, a workflow is `ci`, a Claude skill is `chore`. Labeling everything `feat` makes automated changelogs and version bumps lie.
- **Body explains why**: the diff already shows what. Record motivation, rejected options, risks, and how it was verified. Use `git commit -v` (or read `git diff --cached`) while writing so the message matches the diff.
- **Scope**: module or traceable id when useful (`docs(github-14): …`, `feat(orders): …`); skip it rather than invent one.
- **Footers**: `Closes #N` auto-closes the issue on merge; `Refs #N` only links. Keep them at the very end, one per line.

Lint the message before committing with the bundled checker (paths relative to this skill's directory); it also works as a `commit-msg` hook:

```bash
python3 scripts/commit_msg_lint.py --message "build: add Spotless formatting check"
python3 scripts/commit_msg_lint.py .git/COMMIT_EDITMSG      # hook usage
```

### 5. Commit

Use a heredoc so the body keeps its line breaks:

```bash
git commit -F- <<'MSG'
build: add Spotless formatting check to Maven build

Enforce google-java-format on every `mvn verify` so style drift stops
reaching review. Ratcheted from origin/main to avoid a giant reformat.

Verified: ./harness verify -> PASSED (spotless: 30 files clean).
Refs #6
MSG
```

Avoid `git commit -am` unless every modified file was reviewed — it silently sweeps in unrelated edits (and never new files).

### 6. After the fact: fix history the safe way

- Forgot something in the last commit and it's **not pushed**: `git commit --amend` (message or content).
- Fix an **older** commit on your branch: `git commit --fixup <sha>` then `git rebase -i --autosquash origin/main` before opening/refreshing the PR — this is how "fix the fix" commits with duplicate subjects should disappear.
- Squash noise before review; keep meaningful steps separate.
- Never rewrite commits that others may have pulled (`main`, shared branches). On your own pushed branch, `git push --force-with-lease` after a rebase is fine.
- If the repo squash-merges PRs, the **PR title** becomes the final commit subject — write it with the same care.

## When the user asks about policy or enforcement

For commitlint / husky / lefthook config, the minimal `commit-msg` hook, PR-title checks in CI, commit signing, `.gitmessage` templates, and changelog/semver tooling (release-please, semantic-release), read `references/enforcement.md`. A ready-to-use template is in `assets/gitmessage.txt`.

## Anti-patterns to steer away from

- `wip`, `fix`, `update`, `changes`, `asdf` as subjects — say what changed.
- Subjects that are a filename or a dependency name (`feat: quarkus-junit-mockito`).
- Several identical subjects in a row (`feat: enforce coverage` ×3) — that's fixup material.
- Past tense / gerund (`Fixed`, `Adding`); trailing period; ALL CAPS.
- Mega-commits mixing feature + reformat + dependency bump.
- Amending or rebasing commits already on `main`.
