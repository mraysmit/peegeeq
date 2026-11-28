## Rules for every task (read first; no task is outside them)

These rules apply to every task and every reply: code, tests, build files, documents, reviews, checks, reports, and
answers to questions. They are not limited to implementation. If it is unclear whether a rule applies, it applies.
The assistant memory files hold the history of each rule. This section is the rule.

1. **Read in full before acting.** Before editing any file, read the whole file. Before a recommendation, decision,
   design, review verdict, or status claim, read in full every file it rests on. A line range, a search hit, an
   extract, a summary, or a filtered print is not a full read. When the owner names a category of files, build the
   list by a name search and a content search before reading, and show the search. A file that can be read is never
   reported as "not read" or "read in part": read it, then report. Owner, 2026-10-12: "how can you make decisions
   without understanding the details". The same failure recurred on 2026-11-28 on a document edit.
2. **Open the memory file before acting on its subject.** A line in `MEMORY.md` is a pointer, not the rule.
3. **Check, do not list.** A "Not checked" list holds only items that are blocked. Anything that can be checked by
   reading, searching, or running is checked before the report. Docker not running on the workstation is not a
   block: use the Jenkins host.
4. **Verify by the right means.** A static fact (wiring, presence of logic, callers) is settled by tracing the source
   across all layers. A "no production caller" claim needs a `\bName\b` search of every `src/main` tree. A runtime
   fact is settled by running a probe or a test. JavaDoc, documents, and sub-agent reports are leads, not evidence.
5. **Reports are literal.** Changed files come from `git status --short` of the whole working tree, each named. Name
   the kinds of execution that were not run (build, test, PostgreSQL, Jenkins). A finding from reading is "static,
   not reproduced".
6. **Ask only for real design decisions.** A new table, a schema change, an edit to a shared code path, new scope, or
   a product choice with no safe default needs an owner ruling before code. Confirmed defects, test fixes, probes,
   runs, and agreed work proceed without asking, finding to finding, with one report at the end (owner, 2026-11-07
   and 2026-11-14). Mentioning something in a report is not approval.
7. **Verify a blocker before citing it, and give the change that removes it.** A rule from the assistant's own notes
   is an owner instruction only when the note quotes the owner. For an environment or tooling symptom, search for the
   documented cause before building a workaround.
8. **Git.** The owner commits. Do not commit, offer to commit, or plan commits unless the owner raises it. When asked
   to commit, commit on the checked-out `master`; never create a branch; do not push unless asked. While any test
   fails, do not mention committing.
9. **Test runs.** Commands come from `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md`. Workstation runs are in the
   foreground, through `Tee-Object`, with nothing after it. Never `run_in_background`, never loops or runner scripts.
   Scope each run to the changed test or module; never use `-Pall-tests` as a verification loop. Every run that
   starts a container or needs PostgreSQL (probes, single classes, guards that touch Docker), and any long run, goes
   to the Jenkins host or a Jenkins job build, even when the workstation has Docker. The workstation is the owner's
   machine; such runs slow it to a halt (owner, 2026-11-28: "why are you running these tests on my local machine
   when you have a powerful jenkins server"). Verdicts
   come from the saved log's per-class `Tests run:` lines, never the exit code. The guard run is the last step, after
   the final test-file edit.
10. **Severity by condition.** A failure that costs ownership, safety, durability, or availability is ERROR, whatever
    level the code uses. Read every log entry before describing it.
11. **Flag danger.** For a destructive or silent behaviour, state what is lost, the trigger, the blast radius,
    detectability, the safety net, and the severity.
12. **Purpose first.** A design, a section, a class comment, or an error message states what the feature is for
    before its parts, with each reason in plain words. A plan reference may follow a reason, never replace it.
13. **Documents.** New content goes into the document already in scope, unless the owner asks for a new file.
    Replace stale text with the current fact.
14. **Criticism.** Engage it as the substance of the reply. Fix first; no defence, no blame.
15. **Before the final message of every turn, check three things.** Every file edited or relied on was read in full
    in this session. Nothing in a "Not checked" list could have been checked. Every status sentence comes from
    command output in this turn. If any check fails, do the work before sending the message.

---

## PeeGeeQ Mandatory Pre-Work (apply before any change to code, tests, or build files; rule 1 above covers every other file)

### STOP — read these documents before writing a single line of code

**Step 1.** Read `docs-design/dev/pgq-coding-principles.md` in full.

**Step 2.** Read `docs-design/testing/PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` in full.

**Step 3.** Read the full content of every file you intend to modify. Do not skim. Read it.

**Step 4.** Read existing tests in the same module for the code you are changing. Follow their established pattern exactly. Do not invent a new pattern.

**Step 5.** Before writing any code, grep every file you intend to touch for banned patterns. All must return zero results, and your edits must not introduce any:
- `\.recover\(` — banned everywhere, no exceptions
- `\.otherwise\(` — banned everywhere, no exceptions
- `\.await\(` on a Future — banned in production and tests
- `CompletableFuture|toCompletionStage|toCompletableFuture|\.join\(\)|\.get\(\)` — blocking bridges, banned
- `Thread\.sleep|LockSupport\.parkNanos` — banned
- `Handler<AsyncResult` — callback style, banned
- `\.onComplete\(ar -> .*succeeded` — use `.onSuccess`/`.onFailure` instead
- `fire-and-forget Future` — every Future must be observed via `.onFailure(...)` or chained
- pass-on-failure tests — an `.onFailure(err -> { ... })` block whose body reaches
  `testContext.completeNow()` without an assertion. The test then passes whether the operation
  succeeded or failed. The handler must call `testContext.failNow(err)`, or assert the expected
  failure inside `testContext.verify(...)`. This applies to `@AfterEach` teardown as well: a
  failed close leaks pool connections and must fail the test. Detection needs a body-scoped
  scan, not a flat regex — a plain regex runs past the handler's closing brace and reports the
  next `completeNow()` in an `else` branch as a hit.

**Step 6.** If any existing code in the files you are reading uses a banned pattern, flag it. Do not copy it. "The file already does it" is not a defence — it is a pre-existing violation.

**Only after all six steps are complete, proceed to the implementation.**

**Step 7.** After the code changes have been made you must validate the changes against the testing standard, at the very least `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` in full.

---

### Additional mandatory rules

- **One phase at a time.** Make the change, rebuild and verify that phase, then stop and report. For
  confirmed-defect remediation and agreed work, rule 6 of "Rules for every task" applies: continue finding to
  finding and report once at the end.
- **Rebuild before verification.** After every Java or Maven implementation change, run
  `mvn clean install -DskipTests -pl :<changed-module> -am` through `Tee-Object` before
  downstream testing. This rebuilds the affected reactor slice and installs upstream
  artifacts; `-DskipTests` is permitted only for this build step and still compiles tests.
- **Run targeted tests.** The agent runs the smallest relevant test scope — method, class,
  or single module — with the profile carrying that test's tag. Pipe Maven output through
  `Tee-Object`, inspect the saved log, and report the exact scope and per-class `Tests run:`
  counts. `BUILD SUCCESS` alone is not verification. The approximately 90-minute
  `-Pall-tests` run is an owner-run or explicitly requested release gate, not part of the
  normal edit-test loop.
- **No error swallowing.** Every catch block must surface the error (`message.error(...)` in UI, log + propagate in Java). Silent catches are a defect.
- **No mocking.** No Mockito, no mocked database connections, no mocked repositories. TestContainers for all database tests.
- **Mirror existing patterns exactly.** Do not invent new patterns. Read the surrounding code first.
- **Never state runtime behaviour as fact from static reading.** If behaviour needs verifying, say so — do not assert it.

---

### Communication style (mandatory, applies to every response)

Write short declarative sentences. No idioms, no metaphors, no hedging words ("candidate", "arguably", "sort of", "essentially", "basically"), no rhetorical setups ("here's the thing", "the key insight is"), no fake-casual phrasing ("hang off", "lean into", "sweet spot"). No programmer slang in prose, comments, or docs — "mint" is "create/generate", "hydrate" is "load", "bubble up" is "propagate", "happy path" is "normal path". State the fact, then stop. If a recommendation is being made, commit to it in one sentence and give the reason in the next.

---

### Working agreement (mandatory, from defects found in this project)

1. **Data model first.** Every design presents its data model and contracts before anything else. For every stored field state: source of truth, or derivable? Derivable data is computed, never stored. When a new requirement subsumes an old mechanism, delete the old mechanism — never keep both.
2. **TDD includes dependency failure modes.** Before writing code that calls a dependency, list that dependency's failure modes (throws, rejections, nulls) and write a failing test per mode first. `void asyncFn()` and un-caught async calls are banned — grep for them like the Java banned patterns.
3. **Claims require same-turn evidence.** Any statement about test results, coverage, or repo state must come from command output produced in that turn — never from memory, a summary, or a document. Read the per-class "Tests run:" lines; "Tests run: 0" and silent profile exclusions count as failures. Never pipe a completeness sweep through `head`.
4. **Docs are not state.** Status lives in code, tests, and logs. Before reporting the state of anything, verify against the artifact; documents get updated to match reality, not quoted as it.
5. **Fix first, never litigate blame.** When a defect is reported or suspected, sweep the whole class, fix everything found regardless of origin, report the fix. Attribution only if asked.
6. **Report errors as wrong judgments, not oversights.** A post-mortem names what was decided wrongly and why. If an explanation sounds like a process story, it is probably self-serving — check it against the record.
7. **Say what was NOT checked.** Every phase report ends with known gaps and unverified paths, stated plainly. Only
   blocked items belong there (rule 3 of "Rules for every task").
