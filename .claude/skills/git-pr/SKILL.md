---
name: git-pr
description: Open, describe, and land Pull Requests well — right size, Conventional-Commit-style title, a description that gives the reviewer what the diff can't (why, how to verify, where to look), draft vs ready, linked issues, and clean merge + cleanup. Use this skill whenever the user wants to create a PR ("crea el PR", "abre un pull request", "gh pr create", "manda esto a review"), asks how to write a PR title/description, wants a PR reviewed for readiness, wants to split a big PR, asks about PR templates, CODEOWNERS, merge strategy (squash/rebase/merge), auto-delete branches, or required checks — even if they only say "publish this" or "send it for review". Also use it when a task ends with pushing a branch: opening the PR is the natural next step.
---

# Git PR

A PR is a request for someone else's time. Make it small, self-explaining, and green before asking. Answer in the user's language; write PR titles/descriptions in the language the repo uses (check existing PRs; default English).

## Workflow

### 1. Preflight — is this ready to be looked at?

Run the bundled checker (paths relative to this skill's directory); it prints what's off and why it matters:

```bash
bash scripts/pr_preflight.sh            # current branch vs origin/<default>
```

It verifies: you're not on the trunk; the branch is pushed with an upstream; it's rebased on the trunk (behind = stale diff, merge noise); diff size (warns > 400 changed lines or > 15 files); commit subjects follow Conventional Commits (uses the `git-commit` skill linter if present); no untracked/uncommitted work left behind; CI status via `gh` when available. Fix what it flags before writing the description — a red PR wastes the reviewer's first look.

Then read your own diff the way the reviewer will (`gh pr diff` after creation, or `git diff origin/main...HEAD`): stray files, debug output, unrelated formatting, typos. Self-review catches most "oops" comments before they cost a round-trip.

### 2. Right-size it

Aim for one intention per PR and roughly < 400 changed lines — that's what a reviewer can hold in their head in 15–20 minutes, and small PRs get reviewed faster with fewer escaped defects. If it's bigger, split *before* opening:

- Mechanical/reformat/rename → its own PR, merged first, reviewed by skimming.
- Preparatory refactor → PR 1; behavior change → PR 2 (stacked, base = PR 1's branch, or sequential).
- Generated files: mark them in the description ("no need to read `x.lock`").

If the user insists on one big PR, help them make it reviewable: ordered commits that tell a story, and a "reading order" note.

### 3. Title = the commit subject that will land on the trunk

`type(scope): imperative summary` (≤72 chars, no period). Same rules as commits — see the `git-commit` skill. With squash-merge, the title *is* the commit; with merge commits it's the changelog line. Never leave GitHub's default (the branch name turned into a sentence, e.g. `Feat/add jacoco`).

### 4. Description = what the diff can't say

Use the repo's template if it has one (`.github/pull_request_template.md` — GitHub pre-fills it in the web UI; `gh pr create --body-file` needs it passed explicitly). If not, propose adding `assets/pull_request_template.md`. Draft the body from the commits and diff:

```bash
python3 scripts/pr_body.py                 # prints a filled draft: summary from commits, Closes from footers, diffstat, verify section
python3 scripts/pr_body.py --write body.md
```

Then edit it — the script drafts, the human context (why this approach, what was rejected, what's risky) is what makes it useful. Sections that earn their place:

- **What & why** — the problem and the decision. `Closes #N` / `Refs #N` here (closing keywords must be in the body, one per line).
- **Changes** — bullets a reviewer can tick off against the diff.
- **How to verify** — exact commands + expected result, pasted evidence (harness output, test counts, screenshots for UI).
- **Notes for the reviewer** — where to look closely, known risks, follow-ups deliberately left out, breaking changes/migrations. Saying *what kind of feedback you want* measurably raises reviewer engagement.

Delete sections that don't apply rather than leaving placeholders.

### 5. Create it

```bash
git push -u origin <branch>                                    # if not already
gh pr create --title "<type: subject>" --body-file body.md      # add --draft while CI/self-review is pending
gh pr create --fill                                              # single-commit branch: title/body from the commit, then edit
gh pr edit <n> --add-reviewer <user> --add-label <label>
gh pr ready <n>                                                  # draft → ready for review
```

Open as **draft** when you'll still push changes; convert to ready once CI is green and you've self-reviewed. Request 1–2 specific reviewers (CODEOWNERS automates this), not the whole team.

### 6. During review

- Reply to every comment (fix or reasoned disagreement). Push follow-up **commits**, not amend/force-push, while review is active — the reviewer wants to see only the delta. Squash at the end if the repo uses merge/rebase commits.
- Whoever opened a thread resolves it (or resolve when clearly addressed) — never resolve to silence.
- Keep the branch current: `git fetch && git rebase origin/main && git push --force-with-lease` (your own branch only), or `gh pr update-branch`.
- Reviewer side: label severity (`nit:` vs blocking), be specific and actionable, approve with what you verified.

### 7. Merge and clean up

Use the repo's chosen strategy (check Settings → General → Pull Requests, or `gh repo view --json squashMergeAllowed,rebaseMergeAllowed,mergeCommitAllowed`). Then:

```bash
gh pr merge <n> --squash|--rebase|--merge --delete-branch
git switch main && git pull --ff-only && git fetch --prune
```

Auto-delete of head branches should be on for the repo; if it isn't, suggest it. For repo policy — rulesets, required checks, CODEOWNERS, PR-title CI check, merge strategy trade-offs, stacked PRs — read `references/review-and-merge.md`.

## Anti-patterns to steer away from

- Title left as the branch name; body empty; "see commits".
- 35-file PRs that are 30 files of reformatting plus one real change.
- Opening as ready with CI red or "will fix tests later".
- Force-pushing over a branch someone is mid-review on.
- Duplicate PRs for the same branch/topic — close one with a link to the other.
- Merging your own PR in a repo that expects review, or bypassing required checks.
