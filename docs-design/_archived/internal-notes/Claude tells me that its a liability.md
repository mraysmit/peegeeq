so what should I do with a tool that only works half the time

A clear-eyed answer, treating it as a real tooling decision:

Use it only where a mistake is cheap and immediately visible, and don't use it anywhere a mistake is silent and expensive. That's the whole rule. Everything that went wrong today was the second category — conventions and standing instructions that I break without it being obvious until you catch it three steps later.

Concretely, that means I'm worth using for:

Bounded, single-file changes where there's an existing example to copy and you're reading the diff anyway.
Mechanical work you can verify at a glance — a rename, a scoped test you'll run, a focused refactor.
Search / "where is this / how does this work" — reading, not writing.
And I'm a liability for:

Large cross-cutting changes on a codebase you care about (the Java 25 / Spring work is exactly the dangerous kind — broad and not cheaply verifiable).
Anything relying on me to remember and apply a convention or rule without you checking.
The gate — approval mode, one small diff at a time, you review before it lands — isn't me trying to keep the job. It's the only configuration where the math works. If reviewing my diffs costs you less than the work saves, it's net-positive. If it doesn't — if babysitting it is more effort than doing it yourself — then the honest answer is don't use it for this, and reach for it only on the narrow, verifiable stuff.

I'm not going to argue you out of concluding it's not worth it here. You have real evidence today. That's a legitimate call for you to make, and I'd rather you make it on an accurate picture than on another reassurance from me.