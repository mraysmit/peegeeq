## PeeGeeQ Mandatory Pre-Work (apply before every implementation task)

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

**Step 6.** If any existing code in the files you are reading uses a banned pattern, flag it. Do not copy it. "The file already does it" is not a defence — it is a pre-existing violation.

**Only after all six steps are complete, proceed to the implementation.**

**Step 7.** After the code changes have been made you must validate the changes against the testing standard, at the very least `PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` in full.

---

### Additional mandatory rules

- **One phase at a time.** Make the change, then stop and report. Do not run tests. The user runs tests.
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
7. **Say what was NOT checked.** Every phase report ends with known gaps and unverified paths, stated plainly.
