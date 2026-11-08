# Evaluation cases

Each folder in `cases/` is a pull request with known problems. The `eval` command reviews every case with the same pipeline as the app, matches the findings with the expected problems, and writes a report to `results/`.

```bash
cd backend
./gradlew -q :eval:run --args="eval --provider gemini"   # 10 cases, 2 runs each: about 20 model calls
./gradlew -q :eval:run --args="eval"                      # fake provider: checks the harness, no network
```

## Case format

```text
cases/<id>/
  diff.patch      output of git diff, one or more files
  expected.json   the problems the review should find
```

```json
{
  "title": "Log user lookups",
  "description": "What the problem is, for humans. Never sent to the model.",
  "expected": [
    { "path": "src/main/kotlin/com/example/users/UserService.kt", "line": 13, "category": "bug", "severity": "major" }
  ]
}
```

- `title` is the pull request title given to the model. Keep it neutral: it must not give the answer away.
- `line` is a line number in the new file, on a line the patch adds.
- `category` and `severity` use the names of the review output schema: `bug`, `security`, `performance`, `reliability`, `tests`, `readability`, `maintainability`; `critical`, `major`, `minor`, `nit`.
- A case whose id starts with `clean-` has an empty `expected` list: any finding on it is noise.
- Avoid blank context lines in patches (a line holding a single space): editors tend to strip them.
- A fake secret must still be caught by FirstPass's redactor but not by gitleaks, which scans this repository. `kotlin-hardcoded-secret` shows how.

The tests in `backend/eval` check every case: the patch parses, the hunk headers count every line, each expected line is an added line, and clean cases expect nothing.

## Metrics

- A finding matches an expected problem on the same file, in the same category, at most 3 lines away. Each finding matches at most one problem.
- Precision: matched findings over published findings, inline and summary alike. Recall: problems found over problems expected. Noise: mean number of findings on clean cases.
- Counts are summed over the runs. Reviews that did not complete (provider unavailable, quota) are left out of the metrics and shown in the per-case table.
- The report also compares our input token estimate with the count reported by the provider, to calibrate the batch planner.

Targets: precision of 70 % or more, recall of 60 % or more, noise of 1 or less.
