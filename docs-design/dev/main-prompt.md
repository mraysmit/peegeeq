---

main-prompt.md

## PeeGeeQ Mandatory Pre-Work (prepend to every implementation prompt)

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

** Step 7. After the code changes have been made you must validate the changes against the testing standard, at the very least PEEGEEQ_TESTING_STANDARDS_ANTIPATTERNS.md` in full.


follow `docs-design/testing/PEEGEEQ-TEST-COMMANDS.md'

---