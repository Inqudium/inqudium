# ADR-038: Diagnostic logging and audit as event exporters

**Status:** Proposed
**Date:** 2026-05-13
**Deciders:** Core team
**Related:** ADR-003 (event system), ADR-007 (JFR over logging), ADR-014 (ServiceLoader conventions),
ADR-030 (component event publisher scope).

## Context

The event system established by ADR-003 was justified primarily by three differentiators that
JFR and JMX cannot cover cleanly: per-element programmatic in-process subscription, synchronous
delivery semantics on the call thread, and paradigm-aware bridging (Reactor `Flux`, RxJava 3
`Observable`, Kotlin `Flow`). Those three uses are not in question.

Two further observability concerns sit alongside them and need an explicit channel decision:

1. **Diagnostic logging** — high-frequency operational detail (every call through a circuit
   breaker, every retry attempt, every rate limiter decision). Always-on, ring-buffered, very
   low overhead. Consumed by post-mortem tooling (JMC, async-profiler, log-aggregation
   pipelines) rather than by live program logic.

2. **Audit export** — durable record of resilience interventions (rejects, fallbacks, state
   transitions) shipped to an external system (Kafka, file, audit database). Must not lose
   events on slow sinks; ordering and durability matter; throughput requirements are bounded
   but non-trivial.

Both could plausibly be implemented as separate channels — elements emitting directly into
SLF4J/JFR for diagnostics and into a dedicated `AuditSink` interface for audit. The alternative
is to layer both on top of the existing `InqEventPublisher` / `InqEventExporter` infrastructure,
where the publisher fans out to exporters and each exporter decides what to do with the event.

The framework already has the SPI (`InqEventExporter`, `InqEventExporterRegistry`) for this
fan-out, and existing bridge modules (`inqudium-jfr`, `inqudium-slf4j`, `inqudium-micrometer`)
already follow the exporter shape. The decision is whether to keep those bridges as the only
sanctioned route for diagnostic and audit data, or to permit element code to emit into
diagnostic and audit channels directly.

## Two options

### Option A — Separate channels for diagnostics and audit

Resilience elements emit events into the publisher *and* call dedicated APIs for diagnostic
logging and audit. Logging happens through SLF4J directly (or through an internal logger
abstraction); audit happens through a typed `AuditSink` interface owned by each element.

```java
// In element code — three separate emit sites per interesting moment
publisher.publish(new BulkheadOnRejectEvent(...));
logger.info("Bulkhead {} rejected call after {} ms", name, waitMs);   // diagnostic
auditSink.record(new BulkheadRejectAuditRecord(...));                  // audit
```

**Pros:**

- Each channel can be tuned independently. Diagnostic logging can be disabled at the SLF4J
  level; audit can have its own durability and back-pressure contract; the event publisher
  stays a low-volume channel for programmatic subscribers.
- Audit gets a typed contract (`AuditSink`) that can encode durability requirements directly
  in the SPI (e.g. `record()` returns a `CompletionStage` so the element can decide whether
  to await durability).

**Cons:**

- Three emit sites per event in element code. Drift risk is real: a new event type is added
  to the publisher, but the corresponding log line or audit record is forgotten. The compiler
  cannot enforce parity.
- Violates the "no logging in core" rule established by ADR-003 and reinforced by ADR-007.
  Elements would now contain SLF4J calls or wrap them behind an indirection — both options
  reintroduce the coupling those ADRs explicitly removed.
- Duplicate test surface. Each emit site needs its own assertions; a single test that
  observes a `BulkheadOnRejectEvent` does not prove the log line and audit record were also
  produced.
- Cross-cutting consistency is no longer free. If a dashboard asks "why did metrics see 41
  rejects but the audit log only 39?", the answer is no longer "those numbers come from the
  same emission" — it is "those numbers come from three independent emit sites and one of
  them dropped events".

### Option B — Diagnostic logging and audit as event exporters

Element code emits exactly one event per observable moment, into the per-element
`InqEventPublisher` (ADR-030). The exporter registry fans the event out to every registered
`InqEventExporter`. Diagnostic logging, audit, metrics, and in-process subscribers are all
exporters or direct subscribers — none of them require additional emit sites in element code.

| Concern                        | Exporter                                | Character                                      |
|--------------------------------|-----------------------------------------|------------------------------------------------|
| Diagnostic logging (primary)   | `inqudium-jfr`                          | always-on, ring-buffered, sub-1 % overhead     |
| Diagnostic logging (log lines) | `inqudium-slf4j`                        | opt-in; for users who want log-aggregation output |
| Aggregated metrics             | `inqudium-micrometer`                   | counters, gauges, histograms                   |
| Audit export                   | dedicated exporter (Kafka, file, etc.)  | durable sink, exporter-local buffering         |
| In-process reaction            | direct subscriber on the per-element publisher | sync; paradigm-specific                  |

Publication stays synchronous and sequential on the calling thread, exactly as ADR-003
specifies. The exporter contract gains one explicit obligation: **return in O(µs); buffer
internally if the downstream sink is slow or durable**. Slow or durable exporters are
responsible for their own asynchrony (lock-free queue plus a dedicated virtual thread is the
canonical pattern); the core publisher does not introduce an async boundary.

**Pros:**

- One emit site per event in element code. The "no logging in core" rule (ADR-003, ADR-007)
  stays the rule, not the exception. Drift between diagnostic, audit, and programmatic views
  becomes structurally impossible — they all derive from the same emission.
- Audit, metrics, and diagnostics see identical event streams. The cross-cutting consistency
  question ("why does audit disagree with metrics?") collapses to a single sink-side question
  ("is your exporter dropping?").
- The framework already has the SPI. `InqEventExporter`, `ServiceLoader` discovery (ADR-014),
  and the registry state machine (Open → Resolving → Frozen) are all in place. Adding a new
  observability concern is a new exporter, not a new core contract.
- Test surface stays unified. One assertion on the event proves the data is available; tests
  for the JFR / SLF4J / audit translations live in the respective bridge modules.

**Cons:**

- Sync fan-out is dangerous if an exporter blocks. A slow audit sink could stall the
  resilience element it is supposed to be auditing. This is the central design risk of this
  option, and the mitigation (exporter-local buffering) shifts responsibility to exporter
  authors rather than removing it. The exporter SPI contract has to spell this out explicitly.
- Audit semantics (durability, ordering, at-least-once vs at-most-once) are not encoded in
  the `InqEventExporter` interface. Each audit exporter restates them in its own
  documentation. This is acceptable because audit guarantees are sink-specific anyway (Kafka,
  file, and audit-DB exporters have different durability stories), but it is less explicit
  than a typed `AuditSink` would be.

## Decision

**Option B.** Diagnostic logging and audit export are implemented as `InqEventExporter`
implementations. Element code retains exactly one emit site per observable moment — the
per-element `InqEventPublisher` established by ADR-030.

The sync-fan-out contract from ADR-003 is preserved unchanged. The exporter SPI documentation
is amended to make the speed obligation explicit:

> An `InqEventExporter#export(InqEvent)` implementation must return in O(µs). Exporters that
> write to a slow, blocking, or durable sink (file, network, audit database) must buffer
> internally — typically a lock-free queue drained by a dedicated daemon virtual thread — and
> must not block the calling thread on the sink's acknowledgement.

JFR is the **primary diagnostic substrate**. `inqudium-jfr` translates every
`InqEvent` to a typed `jdk.jfr.Event` with `@Name` and `@Label`. When no recording is active,
the cost is dominated by the JFR commit fast-path (sub-100 ns); when a recording is active,
JFR's own ring buffer absorbs the volume. SLF4J output is opt-in via `inqudium-slf4j` for users
who want their existing log-aggregation pipelines fed.

Audit exporters are not provided by inqudium itself; they are downstream implementations. The
project documents the buffering pattern in the user guide and ships an `AsyncExporterWrapper`
helper *only* if concrete demand emerges. Until then, exporter authors implement their own
buffering — the pattern is small enough (an `MpscQueue` plus a `Thread.ofVirtual().start(...)`
loop) that a generic wrapper would not save meaningful code.

The decision is driven by three factors, in order of weight:

1. **ADR-003 / ADR-007 continuity.** Both ADRs treat the event system as the single source of
   observability truth for resilience elements. Layering diagnostic logging and audit on top
   of that system preserves the rule; carving them out as parallel channels would create the
   exact "logging in core" coupling those ADRs removed.
2. **Cross-cutting consistency by construction.** A single emission point means metrics,
   diagnostics, and audit cannot drift apart. The "why does metric X disagree with audit Y?"
   class of bug becomes impossible to produce at the framework level.
3. **No new SPI surface.** The framework's existing exporter contract carries the load. New
   observability concerns become new exporters, not new core types.

## Consequences

**Positive:**

- One emit site per event in element code. Adding a new event type or a new exporter does not
  require touching unrelated code paths.
- Diagnostic logging is JFR-first. Users get always-on ring-buffered diagnostics with no
  configuration; SLF4J is available for those who explicitly want log lines.
- Audit export is uniform with every other consumer of the event stream. Audit-vs-metrics
  drift is structurally prevented.
- The exporter SPI gains exactly one documented obligation (return quickly, buffer
  internally) — a small, well-scoped clarification rather than a new contract.
- Tests assert on the event; bridge-specific translations are tested in their own modules.

**Negative:**

- Audit exporter authors carry the burden of buffering, durability, and at-least-once /
  at-most-once semantics. The framework does not enforce these. The risk: a naive audit
  exporter that blocks on Kafka acknowledgement directly slows the call site. Mitigation: the
  user guide documents the buffering pattern, and code review for in-tree exporters checks it.
- A misbehaving exporter can still affect every other exporter and every consumer registered
  on the same publisher, because dispatch is sequential per ADR-003. This is inherent to the
  sync-fan-out model; the alternative (per-exporter async dispatch in core) would re-introduce
  the async boundary ADR-003 explicitly avoided.
- Cross-component audit dashboards must subscribe to events from every relevant component, as
  per ADR-030. This was already a consequence of per-component publishers; this ADR does not
  change it.

**Neutral:**

- The remaining event types that do not yet extend `InqEvent` (`RetryEvent`,
  `RateLimiterEvent`, `TimeLimiterEvent`, `TrafficShaperEvent`, `FallbackEvent`) need to be
  consolidated for this decision to apply uniformly. That consolidation is the open work
  flagged by ADR-003 and is a prerequisite for a complete audit exporter; it is not a
  consequence introduced by this ADR.
- Whether an in-tree `AsyncExporterWrapper` helper is shipped is left open. It can be added
  later as a backwards-compatible utility once a concrete exporter implementation demonstrates
  the need.
- The runtime-scoped publisher (topology events) is unaffected by this ADR. Topology events
  also flow through the exporter registry; the same fan-out and same buffering obligation
  apply.
