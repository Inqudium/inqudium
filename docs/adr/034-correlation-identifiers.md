# ADR-034: Correlation identifiers `stackId` and `callId`

**Status:** Proposed  
**Date:** 2026-05-05  
**Deciders:** Core team

## Context

The library emits log entries and event payloads from multiple points along a resilience composition (bulkhead acquire,
retry attempt, circuit-breaker state change, …). To correlate these entries — both within a single invocation and
across many invocations of the same resilience composition — two identifiers are needed in the public surface.

This ADR defines those two identifiers, names them, and fixes the relationship between them.

### Resilience-stack

The library uses the term *resilience-stack* (or simply *stack*) to denote a concrete, applied composition of resilience
elements at the point where it is bound to an execution surface. A resilience-stack is the runtime artefact that
processes calls — distinct from the abstract pipeline composition (`InqPipeline`, see ADR-002), which is
technology-agnostic and only describes which elements participate in which order.

The form a resilience-stack takes depends on the integration technology: a wrapper instance for function-based
decoration, a proxy for dynamic-proxy integration, a terminal for AspectJ, a per-method cache entry for Spring AOP.
What unites them is that they are the concrete objects holding the state needed to process calls — including the
correlation identifiers defined below.

## Decision

### `callId`

The `callId` is a per-invocation correlation identifier. Each call through a resilience-stack receives one `callId`,
which is propagated through every layer of the stack and made available to log statements and event payloads issued by
those layers.

The `callId` is allocated from a counter that is local to the resilience-stack. The counter is not shared across
stacks. This is a deliberate performance choice: a JVM-global counter would create cache-line contention under parallel
load.

A `callId` value is unique within its resilience-stack but not globally unique. To form a globally unique correlation
key, it must be combined with the `stackId` defined below.

### `stackId`

The `stackId` identifies the resilience-stack that processed a given call. It is allocated once when the
resilience-stack is created and is stable for that stack's lifetime.

> **Central invariant:** the `stackId` and the `callId` counter live on the same level. The level at which the `callId`
> counter resides is the level the `stackId` identifies.

This invariant is what makes the pair `(stackId, callId)` globally unique: the `stackId` distinguishes counters from
each other, and within a single counter the `callId` values are unique by construction.

Consequence of the invariant: the granularity of the `stackId` is determined by the granularity at which the `callId`
counter is partitioned, not chosen for semantic reasons. Where the technology permits a coarse granularity without
contention, the `stackId` identifies a coarse unit; where contention concerns force a finer partitioning, the
`stackId` identifies that finer unit.

The carrier of `stackId` and `callId` counter per integration technology:

| Technology               | Carrier of `stackId` and `callId` counter |
|--------------------------|-------------------------------------------|
| Function-based decorator | Wrapper instance                          |
| Dynamic proxy            | Proxy instance                            |
| AspectJ                  | Terminal instance                         |
| Spring AOP               | Per-method cache entry within the aspect  |

The Spring AOP case is finer-grained than the others because the Spring aspect itself is typically a singleton bean.
Holding the counter on the aspect would force all annotated methods through one counter, violating the
contention-avoidance goal. The per-method cache entry — which exists to memoise the resolved pipeline anyway — is the
natural carrier on that side.

The `stackId` does not carry per-method semantics. Multiple methods sharing one carrier (e.g. four methods on one
proxy) share one `stackId`. Operators who need per-method correlation should rely on the method name in the log
output, not on the `stackId`.

### Notation

When `stackId` and `callId` appear together in log output or event payloads, the recommended notation is:

```
[stackId:callId]
```

Example: `[3:9]` denotes call number 9 within stack number 3.

This notation is greppable both as a complete pair (`grep '\[3:9\]'`) and by stack alone (`grep '\[3:'`), which
assists log analysis in plain files and structured stores alike.

The library provides a single formatting function that produces this notation, so that all internal log and event
output uses the identical format. The notation is recommended for application code that emits its own log statements
but is not enforced.

## Consequences

**Positive:**

- The `callId` counter is partitioned to the level at which contention is naturally absent. No JVM-global counter is
  needed for `callId`.
- The `stackId` follows the natural carrier of the `callId` counter; no separate caching mechanism is required to make
  it stable.
- The `[stackId:callId]` notation gives operators a single, distinctive token to search for in logs.
- The differing granularity across integration technologies (coarse for proxy/AspectJ, finer for Spring AOP) is
  derived from a uniform principle (the central invariant) rather than from inconsistent design choices.

**Negative:**

- The `stackId` does not identify a method or a logical operation; it identifies an internal carrier of the library.
  Operators reading logs cannot infer business meaning from a `stackId` value alone.
- Two `stackId` values from different integration technologies are not directly comparable, because they identify
  different kinds of carriers. Operators working with mixed integrations must be aware of this.
- The notion of a *resilience-stack* and the central invariant must be explained in user-facing documentation;
  otherwise operators will form intuitions about `stackId` that the library does not support.

**Neutral:**

- The relation to `InqPipeline` (ADR-002): a single `InqPipeline` composition can give rise to many resilience-stacks
  if it is applied at multiple integration points. Each stack has its own `stackId`. The pipeline itself does not
  carry a `stackId`.

## History

The library originally used the name `chainId` for the identifier now called `stackId`. The original semantics were
stronger: `chainId` was intended to identify *the protected execution unit* (one method, one function), giving the
operator a per-method correlation handle in log output.

The original semantics arose from the following sequence of design decisions:

1. *Goal:* multiple log entries produced during one invocation should share a correlation handle. This is the role of
   the `callId`.
2. *Constraint:* the `callId` source must be partitioned to avoid contention. A JVM-global atomic counter was rejected
   on performance grounds.
3. *Consequence:* a partitioned `callId` is not globally unique, so a second identifier was introduced to disambiguate
   `callId` values across partitions. This identifier was originally named `chainId`.
4. *Additional intent:* given that the second identifier existed anyway, it was given semantic value for the operator —
   *"identifies the protected execution unit"*.

Step 4 is the additional intent that this ADR retracts. Implementing it uniformly across the four integration
technologies turned out to be substantially complex (requiring per-method caching at three locations) and would have
introduced hot-path overhead conflicting with the library's performance goals. Meanwhile, the operator-facing benefit
was modest, because the per-method correspondence of `chainId` was not robust against the heterogeneity of the
integration technologies and could not be relied on without internal library knowledge.

The current ADR retains the technical core (steps 1–3) and removes the additional intent (step 4). The renaming from
`chainId` to `stackId` reflects the changed meaning: the identifier denotes the *resilience-stack* that processed the
call, not the execution unit within it.
