# Debug a call with logs

The runtime logs each call through `System.getLogger("io.github.graphmaquina.runtime")`, standalone machines and
machine sets alike, at two levels. `DEBUG` logs how each call started, the hops and starts it committed and how it
ended; `TRACE` adds each step the call took on the way. Nothing is logged on this logger at `INFO` or above: the
caller already receives the outcome or the exception. The library's only other record is a `WARNING` on the logger
`io.github.graphmaquina` for an observer that threw. The logger is off by default. `java.util.logging`, the JDK's default backend for
`System.Logger`, maps `DEBUG` to `FINE` and `TRACE` to `FINER`, and its default configuration logs neither.

## Contents

- [Turn the logs on](#turn-the-logs-on)
- [What a call logs](#what-a-call-logs)
- [SLF4J and Log4j](#slf4j-and-log4j)
- [Line formats](#line-formats)
- [Order, threads and privacy](#order-threads-and-privacy)

## Turn the logs on

To see the lines, lower the logger and a handler to `FINE`, for instance in a `logging.properties` file:

```properties
handlers = java.util.logging.ConsoleHandler
java.util.logging.ConsoleHandler.level = FINE
java.util.logging.SimpleFormatter.format = %4$s: %5$s%n
io.github.graphmaquina.runtime.level = FINE
```

Start the JVM with `-Djava.util.logging.config.file=logging.properties`. The console handler drops records below
`INFO` until its own level is lowered, so the logger's level alone shows nothing. The format line is optional: it
prints each record on one line. As a child of `io.github.graphmaquina`, the runtime logger takes that logger's level
when it has none of its own. For the `TRACE` lines too, write `FINER` in place of `FINE` on both levels: a handler left
at `FINE` drops them.

## What a call logs

With this file, a ticket machine like the quickstart's, without its `committed` hook, called as its `App` begins
(submit and approve `T-1`, then submit `T-2` and `T-3`), logs these lines; the elapsed times vary from run to run:

```text
FINE: call=1 start Ticket/TicketState SUBMIT
FINE: call=1 committed Ticket/TicketState#T-1 OPEN -SUBMIT-> REVIEW depth=0
FINE: call=1 end code=APPLIED state=REVIEW entity=Ticket/TicketState#T-1 records=1 starts=0 reactions=0 aborted=0 elapsed=15033us
FINE: call=2 start Ticket/TicketState APPROVE
FINE: call=2 committed Ticket/TicketState#T-1 REVIEW -APPROVE-> DONE depth=0
FINE: call=2 end code=APPLIED state=DONE entity=Ticket/TicketState#T-1 records=1 starts=0 reactions=0 aborted=0 elapsed=322us
FINE: call=3 start Ticket/TicketState SUBMIT
FINE: call=3 end code=GUARD_REJECTED state=OPEN entity=Ticket/TicketState#T-2 records=0 starts=0 reactions=0 aborted=0 reason="a ticket needs a title" elapsed=658us
FINE: call=4 start Ticket/TicketState SUBMIT
FINE: call=4 committed Ticket/TicketState#T-3 OPEN -DECLINE-> FAILED depth=0
FINE: call=4 end code=FAILURE_ROUTED state=FAILED entity=Ticket/TicketState#T-3 records=1 starts=0 reactions=0 aborted=0 reason="the review board declined the ticket" elapsed=1060us
```

With both levels at `FINER`, the fourth of those calls, whose action fails into its route, logs these lines:

```text
FINE: call=4 start Ticket/TicketState SUBMIT
FINER: call=4 depth=0 resolved Ticket/TicketState#T-3 OPEN -SUBMIT-> REVIEW kind=ACTION
FINER: call=4 depth=0 guard hasTitle PASS elapsed=5us
FINER: call=4 depth=0 action register start mode=sync
FINER: call=4 depth=0 action register FAILED reason="the review board declined the ticket" elapsed=284us
FINER: call=4 depth=0 route SUBMIT -> DECLINE rollback-to=0 reason="the review board declined the ticket"
FINER: call=4 commit-check entities=1 hops=1
FINER: call=4 setter Ticket/TicketState#T-3 OPEN -> FAILED entries=1
FINE: call=4 committed Ticket/TicketState#T-3 OPEN -DECLINE-> FAILED depth=0
FINE: call=4 end code=FAILURE_ROUTED state=FAILED entity=Ticket/TicketState#T-3 records=1 starts=0 reactions=0 aborted=0 reason="the review board declined the ticket" elapsed=2644us
```

The quickstart's own machine also runs its `committed` hook for the `DECLINE` hop, so at `TRACE` it also logs a `hook`
line after its `committed` line.

## SLF4J and Log4j

SLF4J and Log4j receive the same lines through their `System.LoggerFinder` bridges on the class path, which map
`DEBUG` and `TRACE` to their own levels of the same name. Set the logger `io.github.graphmaquina.runtime` to `debug` or
`trace` there:

- SLF4J 2: add `org.slf4j:slf4j-jdk-platform-logging` next to `slf4j-api` and a binding. With `slf4j-simple`, pass
  `-Dorg.slf4j.simpleLogger.log.io.github.graphmaquina.runtime=trace`.
- Log4j 2: add `org.apache.logging.log4j:log4j-jpl` next to `log4j-api` and `log4j-core`. In a `log4j2.properties`
  file, `logger.graphmaquina.name = io.github.graphmaquina.runtime` and `logger.graphmaquina.level = trace`.

These two bridges were checked with SLF4J 2.0.20 and Log4j 2.26.1 on JDK 21.

## Line formats

Each message starts with `call=<id>`. The id numbers the calls that log, at either level, from 1, with one counter shared
by every machine and registry in the JVM. A `TRACE` line of a step names its depth next: 0 for the root's own step, one more for each cascade and
each exit of a sub-machine below it. The `TRACE` lines of the commit, the hooks and the watch dispatch name none.
Square brackets below mark the parts a line may leave out.

| Level | Line | Logged when |
| --- | --- | --- |
| `DEBUG` | `call=<id> start <machine> <request>[ trigger=<id>]` | A call starts. `<machine>` is the machine key, such as `Ticket/TicketState`, and `<request>` the identifier `execute` was asked for, or `reevaluate`. A watch reaction adds `trigger=` and the id of the call whose commit triggered it, if that call logged its own lines. |
| `DEBUG` | `call=<id> committed <ref> <from> -<T>-> <to> depth=<d>` | The call committed a hop: one line per hop, cascaded hops and the exits of sub-machines included, at the depth of the step that took it. |
| `DEBUG` | `call=<id> started <ref> <previous> -> <initial> parent=<ref> node=<node> depth=<d>` | The call committed the start of a sub-machine: the child moved from `<previous>` to its initial state when its parent entered `<node>`. |
| `DEBUG` | `call=<id> end code=<code> state=<state>[ entity=<ref>] records=<n> starts=<n> reactions=<n> aborted=<n>[ reason="<reason>"] elapsed=<us>` | The call ended with its outcome, just before its [observers](commit-and-persistence.md#observers) hear of it: its `code()` and `recordedState()`, the sizes of its `records()`, `starts()` and `reactions()`, how many of those reactions were aborted, its `explanation()` when it has one, as given and unescaped, and the time since the call started, in whole microseconds. An `Error` that an observer then throws reaches the caller in place of the outcome. |
| `DEBUG` | `call=<id> failed phase=<phase> written=<written>[ entity=<ref>] error=<error> elapsed=<us>` | The call ended with its `CallFailedException`, just before its [observers](commit-and-persistence.md#observers) hear of it: its `phase()` and `statesWritten()`, the exception's class name and message, without a stack trace, and the time since the call started. An `Error` that an observer then throws reaches the caller in place of the exception. |
| `TRACE` | `call=<id> depth=<d> resolved <ref> <from> -<T>-> <to> kind=<kind>[ exit-of=<ref>]` | A step resolved the single edge keyed by its entity's state and the requested identifier, before its guards run. `<kind>` is the edge's `kind()`, `ACTION` or `MOVE`. The step of a sub-machine's exit adds `exit-of=` and the child whose terminal state selected the exit. |
| `TRACE` | `call=<id> depth=<d> no-edge <ref> <from> <T>` | A step found no edge for its entity's state and the requested identifier: the request is rejected, or, inside a cascade or an exit, the call aborts. |
| `TRACE` | `call=<id> depth=<d> binding <ref> parent=<ref> node=<node>` | The step of a sub-machine's child found the parent that holds it in `<node>`, before it looked up its edge. |
| `TRACE` | `call=<id> depth=<d> detached <ref> reason="<reason>"` | The step of a sub-machine's child found parents, but none that holds it: the request is rejected as `DETACHED`, for `<reason>`, or, inside a cascade or an exit, the call aborts. |
| `TRACE` | `call=<id> depth=<d> guard <name> PASS elapsed=<us>` / `call=<id> depth=<d> guard <name> REJECT reason="<reason>" elapsed=<us>` | A guard passed, or rejected the request for `<reason>`, as given and unescaped, with the time it took. A guard that throws logs neither. |
| `TRACE` | `call=<id> depth=<d> action <name> start mode=<sync\|async>` | An action starts, synchronous or asynchronous: the edge's own action, or the recovery action of a failure route. |
| `TRACE` | `call=<id> depth=<d> action <name> awaiting budget=<us\|none>` | An asynchronous action returned its stage, and the step waits for it at most `<us>`: what is left of the edge's `timeout` or of the call's `asyncTimeout`, whichever ends first. `none` when neither sets a limit. |
| `TRACE` | `call=<id> depth=<d> action <name> OK elapsed=<us>` / `call=<id> depth=<d> action <name> FAILED[ kind=<kind>] reason="<reason>" elapsed=<us>` / `call=<id> depth=<d> action <name> THREW error=<class> elapsed=<us>` | The action ended, after the time since its `start` line. `OK`: it returned `ActionOutcome.ok()`. `FAILED`: it returned a failure, with the simple name of its failure kind when it has one, and its reason. `THREW`: it threw or completed its stage exceptionally, or the step gave up on it, for a null outcome or stage or a wait that timed out, was interrupted or was refused. The line names the class of what the action threw or completed its stage with, once unwrapped from an exact `CompletionException` or `ExecutionException`, or `io.github.graphmaquina.error.TransitionAbortedException` when the step gave up on it, never a message. An `Error` the action throws logs no end. |
| `TRACE` | `call=<id> depth=<d> route <failed T> -> <recovery T> rollback-to=<savepoint>[ kind=<kind>] reason="<reason>"` | The failure of `<failed T>` took its route: the step dropped the hops and starts the call took after the first `<savepoint>` of them, with the writes to the call's flow made since, and takes `<recovery T>`. The line names the failure kind the route reports, if any, and the failure's reason; for a routed throwable, that is the reason composed from it, as [recover by failure kind and exception type](actions-and-failures.md#recover-by-failure-kind-and-exception-type) says, which the outcome's `explanation()` leaves out. |
| `TRACE` | `call=<id> depth=<d> unhandled <failed T> reason="<reason>"` | The failure of `<failed T>` has no route: the step dropped the hops and starts it took and its writes to the call's flow, and the call throws `UnhandledFailureException`, or, inside a cascade or an exit, aborts. |
| `TRACE` | `call=<id> depth=<d> cascade <ref> <T>[ policy=<policy>]` | An action cascades `<T>` to the peer `<ref>`, at the action's depth; each element of `cascadeAll` adds its `CascadePolicy`. The lines of the cascaded step follow, one depth deeper. |
| `TRACE` | `call=<id> depth=<d> cascade-result <ref> <T> code=<code>` | The cascaded step returned its outcome, with its `code()`. |
| `TRACE` | `call=<id> depth=<d> collect-rollback <ref> <T> error=<error>` | Under `COLLECT`, the element `<ref>` aborted with `<error>`, in its step or, for a cycle or the depth limit, before its step could run, and was rolled back. The call stays poisoned while the line is written; then the poison the element left is cleared and the next element runs. An identity whose `equals` or `hashCode` throws during that rollback leaves no such line: the abort of the cascading step that carries what it threw replaces the element's abort as the poison, with a `poison` line, and the cascade stops. |
| `TRACE` | `call=<id> depth=<d> submachine-start <ref> <previous> -> <initial> parent=<ref> node=<node>` | A hop entered `<node>`, which starts its sub-machine: the child moves from `<previous>` to its initial state, at the depth of the entering step. |
| `TRACE` | `call=<id> depth=<d> submachine-exit <ref> terminal=<state> parent=<ref> exit=<T>` | The child `<ref>` stands in `<state>`, a terminal state of its graph, which selects the exit `<T>` of its parent. The step of the exit follows, one depth deeper. |
| `TRACE` | `call=<id> depth=<d> poison error=<error>` | A failure poisoned the call, which then commits nothing: the abort of a step at a depth above 0, a cascade that failed before its step could run, such as a cycle or a machine the registry does not hold, or the aggregate of a `COLLECT`. A failure that finds the call already poisoned logs no line, since the call keeps its first poison. `COLLECT` clears the poison of each element it rolls back, so one call can log the poison of several elements, then that of the aggregate; it clears it too when an identity's `equals` or `hashCode` throws while it rolls an element back, and logs the poison of the abort that carries what it threw. An `Error` that leaves a cascade poisons the call without a line. A step's own abort logs its line after the lines the step logged before it aborted, its action's end line included, whether the action threw or the step gave up on it: a `poison` line between an action's `start` and end lines comes from a cascade of that action. |
| `TRACE` | `call=<id> depth=<d> flow-put <key>[=<value>]` | An action put a value in [the call's flow](call-flow.md), at the depth of its step. `<key>` is the key as `name:Type`, with the simple name of its class; a key made with `FlowKey.visible` adds `=` and the value's `toString()`. |
| `TRACE` | `call=<id> depth=<d> flow-remove <key>` | An action removed the value of `<key>` from the call's flow. |
| `TRACE` | `call=<id> commit-check entities=<n> hops=<n>` / `call=<id> commit-conflict reason="<conflict>"` | The commit checks the original state of each entity the call's hops and starts name, whether or not its state changed: `entities=` counts them, and `hops=` the hops the call took, starts not counted. A state that changed while the call ran is a conflict: the commit writes nothing and throws `CommitFailedException`. |
| `TRACE` | `call=<id> setter <ref> <from> -> <to> entries=<n>` | The commit is about to call the setter of `<ref>`, which moves it from its first state to its last, through `<n>` hops and starts the call took. |
| `TRACE` | `call=<id> hook <hook> <ref> <T>` | The hook `<hook>` is about to run for the committed hop `<T>` of `<ref>`. |
| `TRACE` | `call=<id> hook-flow-put <hook> <key>[=<value>]` | The hook `<hook>` put a value in [the call's flow](call-flow.md). `<key>` and `<value>` print as in `flow-put`. |
| `TRACE` | `call=<id> hook-flow-remove <hook> <key>` | The hook `<hook>` removed the value of `<key>` from the call's flow. |
| `TRACE` | `call=<id> watch watcher=<machine> moved=<ref> owner=<ref\|none>` / `call=<id> watch watcher=<machine> moved=<ref> owner-state=<state>` / `call=<id> watch-lookup-threw watcher=<machine> moved=<ref> error=<error>` | The watch of `<machine>` looked up the owner of the entity the call moved: the owner it found in the watched state, which a reaction reevaluates, or `none` when it found no owner; `owner-state=` and the state of an owner it found in another state, which no reaction reevaluates; or the lookup threw `<error>`, which aborts that reaction. |

## Order, threads and privacy

The `committed` and `started` lines follow the order the call took its hops and starts, and come after the commit,
before the hooks: a call whose commit fails logs none. A watch reaction is a call of its own, whose lines come between
the `committed` lines and the `end` line of the call that triggered it. A cascade is part of the call that runs it: it
logs no `start`, `end` or `failed` line of its own, and its `TRACE` lines carry the id of that call. A request refused
before it starts, as [observers](commit-and-persistence.md#observers) see it, logs nothing. Each call logs on the thread that runs it: one
submitted through `async()` logs on the executor's thread, and the submission itself logs nothing.

At `TRACE`, a step logs as it goes: its binding check, its edge, its guards, the start of the sub-machine its hop
enters, its action and, in the order the action makes them, its writes to the flow and its cascades, then its failure
route, if any, and last the exits it runs. The lines of the commit come before the `committed` lines; the hook and
watch lines come after them, before the lines of the reactions.

An entity appears in a line only as `<ref>`: its machine key and its identity, such as `Ticket/TicketState#T-1`. A
standalone machine without `identity(...)` prints `#@` and the entity's identity hash code in hexadecimal instead.
The runtime prints the identity it has already computed, so a line never calls the identity function, a getter or
the entity's `toString()`, unless your code puts the entity in the flow under a visible key: a `flow-put` or
`hook-flow-put` line of a key made with `FlowKey.visible` prints the value's `toString()`, whatever the value is. The
`start` line names no entity, and `end` and `failed` name it only once the call computed it, which its step does as it
begins: a reevaluation that selected no edge names none, and so does one that failed before its step began, while it
read the state, ran its watch or exceeded the watch chain. A reevaluation whose step began names it, even when the step
failed before its edge ran, in its binding check for instance. The identity may be personal data, such as an e-mail
address used as an identifier, and the reasons, exception messages and visible flow values the lines print may carry
internal details; treat these logs like any record that carries your identifiers. If the `toString()` of an identity,
of an exception or of a visible flow value throws anything but an `Error`, a checked exception included, the line prints
`<toString threw <exception class>>` in its place, after the machine key and its `#` for an identity, and the call
goes on, with the thread interrupted again when what it threw is an `InterruptedException`; an `Error` propagates. A
`toString()` that returns `null` prints `<toString returned null>` for an
exception or a visible flow value, and `null` after the `#` of an identity.

The runtime asks the logger for its two levels once, when a call starts, and builds no line of a level that is off: a
level raised while a call runs applies from the next call, a call logged at `DEBUG` alone builds no `TRACE` line, and a
call that logs nothing pays for no line. Anything but an `Error` from the logging backend, a checked exception included,
loses the line and changes nothing in the call, except that an `InterruptedException` interrupts the thread again; an
`Error` propagates. An `Error` on a `committed`, `started`, `end` or
`failed` line ends the call with that `Error`, which the [observers](commit-and-persistence.md#observers) receive as the call's end event, in the
phase the call reached: the `committed` and `started` lines come once every setter returned, in `HOOK`. An `Error` that
ends a call is not a `CallFailedException` and gets no `failed` line: the lines logged before it stay, and none follows
them.

---

Next: [The call's flow](call-flow.md). Back to the [documentation index](README.md).
