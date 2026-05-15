# ADR-045: Bulkhead configuration, handle, and diagnostics

**Status:** Proposed  
**Date:** 2026-05-14  
**Deciders:** Core team  
**Related:** ADR-020 (superseded by this ADR and ADR-044), ADR-032 (superseded by this ADR, ADR-044, ADR-046),
ADR-003 (event publisher model), ADR-009 (exception model), ADR-010 (TimeLimiter and orphaned calls),
ADR-015 (registry contract), ADR-027 (validation strategy), ADR-028 (component lifecycle contract),
ADR-029 (lifecycle implementation pattern), ADR-042 (pipeline contracts), ADR-043 (update propagation),
ADR-044 (bulkhead strategies and hot-swap), ADR-046 (bulkhead imperative implementation).

## Context

A bulkhead is more than its strategy. Around the permit-management core (specified in ADR-044) sit several
data structures, contracts, and supporting types: the snapshot that captures a bulkhead's configuration at
a point in time, the field enum that participates in patch routing, the handle interface that consumers
use to interact with the bulkhead, the DSL that lets developers build bulkheads, the event types and
metrics that make a bulkhead observable, and the registry that holds named instances.

These pieces are all paradigm-agnostic: every bulkhead — imperative, future reactive, future coroutines —
has the same snapshot shape, exposes the same handle contract, supports the same DSL, emits the same event
types, and lives in the same registry. The paradigm-specific facade (ADR-046 for imperative) consumes
these data structures; it does not redefine them.

Historically these elements were specified in ADR-020 (overall bulkhead design) and ADR-032 (strategy
hot-swap and DSL). The two ADRs combined paradigm-agnostic and paradigm-specific content in ways that
made the boundaries unclear. This ADR consolidates the paradigm-agnostic "data side" of the bulkhead into
one normative reference; ADR-044 covers the paradigm-agnostic "mechanism side" (strategies, hot-swap);
ADR-046 covers the imperative paradigm's consumption of both.

## Decision

### 1. `BulkheadSnapshot`

A bulkhead's complete configuration state at a point in time is captured in a record:

```java
public record BulkheadSnapshot(
    String name,
    int maxConcurrentCalls,
    Duration maxWaitDuration,
    Set<String> tags,
    String derivedFromPreset,
    BulkheadEventConfig events,
    BulkheadStrategyConfig strategy
) {
    public BulkheadSnapshot {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (maxConcurrentCalls < 1) {
            throw new IllegalArgumentException("maxConcurrentCalls must be >= 1");
        }
        Objects.requireNonNull(maxWaitDuration, "maxWaitDuration");
        if (maxWaitDuration.isNegative()) {
            throw new IllegalArgumentException("maxWaitDuration must not be negative");
        }
        tags = tags == null ? Set.of() : Set.copyOf(tags);
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(strategy, "strategy");
        // derivedFromPreset may be null (not derived from a preset)
    }
}
```

The compact constructor enforces the snapshot's invariants. These checks are class 2 in the validation
classification of ADR-027 — they run whenever a snapshot is constructed, regardless of source.

Two fields deserve specific commentary:

- **`maxWaitDuration`** carries a `Duration` because the user-facing configuration thinks in terms of
  durations. The strategy's hot-path API takes a `long timeoutNanos` (per ADR-044); the conversion is
  done once during materialisation by the paradigm facade (per ADR-046), not on every call.
- **`strategy`** is a `BulkheadStrategyConfig`, the sealed-type discriminator specified in ADR-044. It
  carries the *choice* of strategy and its configuration parameters; the actual strategy instance is
  materialised by the paradigm facade via the strategy factory.

The default value for `strategy`, applied by the imperative provider's default snapshot, is `new
SemaphoreStrategyConfig()`. A bulkhead constructed without an explicit strategy choice runs on a
semaphore.

### 2. `BulkheadField` enum

Patches that touch the bulkhead's configuration report which fields were touched via the
`touchedFields()` accessor on the patch object (per ADR-043). The enum:

```java
public enum BulkheadField implements ComponentField {
    MAX_CONCURRENT_CALLS,
    MAX_WAIT_DURATION,
    TAGS,
    DERIVED_FROM_PRESET,
    EVENTS,
    STRATEGY
}
```

Each value corresponds to one snapshot field. A patch may touch any subset; the dispatcher (per ADR-043)
consults `touchedFields()` to drive listener notifications, the component-internal mutability check, and
the resulting `BuildReport`.

The component-internal mutability check (per ADR-044 section 10) uses two specific entries:

- `STRATEGY` — triggers the in-flight-zero precondition for strategy hot-swap.
- `MAX_CONCURRENT_CALLS` — triggers the live-tunability check against the post-patch strategy.

The other fields (`MAX_WAIT_DURATION`, `TAGS`, `DERIVED_FROM_PRESET`, `EVENTS`) are unconditionally
live-tunable: a patch touching any of them, alone or in combination, passes the mutability check
regardless of the runtime state.

### 3. `BulkheadHandle<P extends ParadigmTag>`

`BulkheadHandle` is the consumer-facing interface that user code holds. It is the type returned by
`runtime.imperative().bulkhead("name")` (and analogous accessors for future paradigms) and the type that
listener registrations are scoped to:

```java
public interface BulkheadHandle<P extends ParadigmTag>
        extends InqElement,
                LifecycleAware,
                ListenerRegistry<BulkheadSnapshot>,
                InternalMutabilityCheck<BulkheadSnapshot> {

    /** The current snapshot. Reads from the live container without copying. */
    BulkheadSnapshot snapshot();

    /** The number of permits currently available for acquisition. */
    int availablePermits();

    /** The number of permits currently held by in-flight calls. */
    int concurrentCalls();
}
```

The four super-interfaces give the handle its full personality:

- **`InqElement`** (per ADR-042) supplies `name()`, `elementType()`, and `eventPublisher()`. A
  `BulkheadHandle` is a pipeline element in the same sense as a `CircuitBreakerHandle` or a
  `RetryHandle`.
- **`LifecycleAware`** (per ADR-028) supplies `lifecycleState()`. Consumers can observe whether the
  bulkhead is cold or hot.
- **`ListenerRegistry<BulkheadSnapshot>`** (per ADR-043) supplies the `onChangeRequest(...)` method for
  registering veto listeners. Listeners are typed against `BulkheadSnapshot`, so they see typed patch
  data.
- **`InternalMutabilityCheck<BulkheadSnapshot>`** (per ADR-043) is the dispatcher-facing surface for the
  component-internal veto. The bulkhead's hot phase implements the `evaluate(...)` and
  `evaluateRemoval(...)` methods specified in ADR-044 section 10.

The paradigm tag `P` lets consumers and integrations work with a specific paradigm's handle type:
`BulkheadHandle<ImperativeTag>` for imperative bulkheads, `BulkheadHandle<ReactiveTag>` for future
reactive bulkheads. The tag is purely a type-system aid; it carries no runtime data.

The handle's own surface — beyond what the four super-interfaces provide — is three accessors:
`snapshot()` for the current configuration, `availablePermits()` and `concurrentCalls()` for runtime
state. These three are paradigm-agnostic; their implementations differ between paradigms only in how they
read from the underlying state (which strategy is active, how to handle cold versus hot reads), but the
contract is identical.

### 4. User-facing configuration

The user-facing configuration record carries the same field set as `BulkheadSnapshot`, plus general
infrastructure references (per ADR-026's configuration architecture):

```java
public record InqBulkheadConfig(
    GeneralConfig general,
    InqElementCommonConfig common,
    int maxConcurrentCalls,
    Duration maxWaitDuration,
    BulkheadStrategyConfig strategy,        // sealed-type, not strategy instance
    BulkheadEventConfig eventConfig
) implements InqElementConfig, ConfigExtension<InqBulkheadConfig> {
    // Compact constructor enforces invariants analogous to BulkheadSnapshot.
}
```

The earlier form (per ADR-020) carried a `BulkheadStrategy strategy` field — a strategy *instance* — plus
a separate `InqLimitAlgorithm limitAlgorithm` field and an `inference()` method that built one from the
other. With the sealed-type `BulkheadStrategyConfig` (per ADR-044), this two-field arrangement is
replaced by a single `strategy: BulkheadStrategyConfig` field that carries both the strategy choice and
its algorithm configuration in one sealed variant. The strategy *instance* is materialised by the
paradigm facade via the strategy factory; the configuration record never holds an instance.

For paradigm-specific extensions, a paradigm module composes its own configuration record:

```java
public record InqImperativeBulkheadConfig(
    GeneralConfig general,
    InqBulkheadConfig bulkhead
) implements InqElementConfig, ConfigExtension<InqImperativeBulkheadConfig> { ... }
```

The imperative-specific record (per ADR-046) wraps the paradigm-agnostic `InqBulkheadConfig` and adds
imperative-paradigm-specific fields if any. Other paradigms follow the same compositional pattern.

A separate DSL record `BulkheadConfig(name, maxConcurrentCalls, maxWaitDuration, inqConfig)` exists for
annotation-driven and DSL setups; it is a thin user-facing wrapper that resolves to one of the records
above. The DSL surface (section 5) operates on this thin record and lowers to `InqBulkheadConfig`
internally.

#### Defaults

| Parameter            | Default                          | Rationale                                                                                              |
|----------------------|----------------------------------|--------------------------------------------------------------------------------------------------------|
| `maxConcurrentCalls` | builder-defined                  | Set by the application based on downstream capacity; there is no universal default                     |
| `maxWaitDuration`    | builder-defined                  | Common choice: `Duration.ZERO` (fail immediately when full)                                            |
| `eventConfig`        | `BulkheadEventConfig.standard()` | Only rejection events on; lifecycle and trace off. See ADR-003 for the framework rationale             |
| `strategy`           | `new SemaphoreStrategyConfig()`  | The semaphore strategy is the simplest and most predictable choice                                     |

### 5. DSL sub-builders

The bulkhead builder offers four mutually-exclusive setters, one per strategy. They are declared on
`BulkheadBuilderBase` and are therefore available wherever bulkheads are built (per-paradigm builders all
inherit the same surface):

```java
b.semaphore()                                  // explicit no-op (the default)
b.codel(c -> c.targetDelay(...).interval(...))
b.adaptive(c -> c.aimd(a -> a.minLimit(...).maxLimit(...)))
b.adaptiveNonBlocking(c -> c.vegas(v -> v.alpha(...).beta(...)))
```

Each setter is a sub-builder over its strategy's configuration. Calling more than one is a
last-writer-wins override (consistent with every other DSL setter). Calling none leaves the default
`SemaphoreStrategyConfig` in place.

The sub-builders mirror the snapshot record shape: a `CoDelConfigBuilder` builds a `CoDelStrategyConfig`,
an `AdaptiveConfigBuilder` builds an `AdaptiveStrategyConfig` and internally hosts an
`AimdAlgorithmBuilder` / `VegasAlgorithmBuilder` for the nested algorithm choice.

A bulkhead built with the DSL:

```java
.bulkhead("inventory", b -> b
    .balanced()                              // preset for limits and wait
    .codel(c -> c
        .targetDelay(Duration.ofMillis(50))
        .interval(Duration.ofMillis(500)))
    .events(BulkheadEventConfig.allEnabled()))
```

The DSL surface is paradigm-agnostic — every paradigm builder inherits the same strategy sub-builders.
The sealed-type `BulkheadStrategyConfig` ensures that adding a new strategy later requires adding one
sub-builder method on `BulkheadBuilderBase`, one sub-builder class, and one factory case (per ADR-044
section 6). The compiler enforces all four through the sealed-type exhaustiveness.

### 6. `BulkheadEventConfig` and event types

The bulkhead participates in the two-tier observability model defined in ADR-003. This section documents
the bulkhead-specific surface; refer to ADR-003 for the publisher contract, `InqEventPublisher`
lifecycle, exporter registry, and `publishTrace` / `isTraceEnabled` mechanics.

#### Event categories

```java
public enum BulkheadEventCategory {
    LIFECYCLE,      // permit acquisition and release outcomes
    REJECTION,      // permit denials
    TRACE           // detailed diagnostic events
}
```

Each category gates a subset of bulkhead events. The configuration:

```java
public record BulkheadEventConfig(
    Set<BulkheadEventCategory> enabledCategories
) {
    public static BulkheadEventConfig standard() {
        return new BulkheadEventConfig(EnumSet.of(REJECTION));
    }

    public static BulkheadEventConfig diagnostic() {
        return new BulkheadEventConfig(EnumSet.allOf(BulkheadEventCategory.class));
    }

    public static BulkheadEventConfig of(BulkheadEventCategory... categories) { ... }

    /** Resolved into a boolean at construction time; checked once per hot-path operation. */
    public boolean isLifecycleEnabled() { return enabledCategories.contains(LIFECYCLE); }
    public boolean isRejectionEnabled() { return enabledCategories.contains(REJECTION); }
    public boolean isTraceEnabled() { return enabledCategories.contains(TRACE); }
}
```

The lifecycle, rejection, and trace gates are resolved into plain `boolean` fields at construction, so the
hot-path guard is one field read with no set lookup or virtual dispatch.

| Preset         | Categories enabled        | Happy-path cost |
|----------------|---------------------------|-----------------|
| `standard()`   | `REJECTION` only          | 0 B/op          |
| `diagnostic()` | All three categories      | ~80 B/op        |

#### Event types

| Event                                | Category    | When emitted                                                                                  |
|--------------------------------------|-------------|-----------------------------------------------------------------------------------------------|
| `BulkheadOnAcquireEvent`             | `LIFECYCLE` | Permit granted; includes `concurrentCalls` at acquisition                                     |
| `BulkheadOnReleaseEvent`             | `LIFECYCLE` | Permit released; includes `concurrentCalls` after release                                     |
| `BulkheadOnRejectEvent`              | `REJECTION` | Permit denied; includes the full `RejectionContext`                                           |
| `BulkheadWaitTraceEvent`             | `TRACE`     | Wait duration for an acquire attempt (success or failure)                                     |
| `BulkheadRollbackTraceEvent`         | `TRACE`     | Permit rolled back because the acquire-event publish threw                                    |
| `BulkheadCodelRejectedTraceEvent`    | `TRACE`     | CoDel-specific complement to `BulkheadOnRejectEvent` (sojourn-based drop)                     |
| `BulkheadLimitChangedTraceEvent`     | `TRACE`     | Adaptive algorithm changed the effective limit                                                |

TRACE events are published via `eventPublisher.publishTrace(Supplier)` so the event object is only
constructed when a consumer or exporter is interested (per ADR-003).

The events are paradigm-agnostic. The same event types are emitted regardless of which paradigm consumes
the bulkhead; only the calling stack differs. Subscribers and exporters work uniformly across paradigms.

### 7. Tier-1 metrics

Polling-based metrics are always on and carry zero per-call overhead:

| Metric                                   | Source                          | Type    |
|------------------------------------------|---------------------------------|---------|
| `inqudium.bulkhead.concurrent.calls`     | `strategy.concurrentCalls()`    | Gauge   |
| `inqudium.bulkhead.available.permits`    | `strategy.availablePermits()`   | Gauge   |
| `inqudium.bulkhead.max.concurrent.calls` | `strategy.maxConcurrentCalls()` | Gauge   |
| `inqudium.bulkhead.rejections.total`     | `LongAdder` in rejection path   | Counter |

For adaptive strategies, `maxConcurrentCalls()` returns the algorithm's *current* limit, not the
configured initial value — so the gauge reflects adaptive movement. A Micrometer `MeterBinder` (in
`inqudium-micrometer`) registers all four once per bulkhead.

The rejection counter is the only counter incremented on the hot path. It uses `LongAdder` for
contention-free updates under high rejection rates; reads via `sum()` are eventually consistent, which is
acceptable for monitoring.

### 8. Exceptions

Two `InqException` subtypes are thrown from the bulkhead facade. The error codes follow ADR-009's `INQ-BH-NNN`
pattern; the element symbol is `BH` (per ADR-021).

| Code         | Type                              | Cause                                                                          |
|--------------|-----------------------------------|--------------------------------------------------------------------------------|
| `INQ-BH-001` | `InqBulkheadFullException`        | Permit denied; reason in `getRejectionContext()` / `getRejectionReason()`      |
| `INQ-BH-002` | `InqBulkheadInterruptedException` | Caller thread interrupted while waiting for a permit (no rejection decision)   |

**`InqBulkheadFullException`:**

- Carries the immutable `RejectionContext` captured at decision time. `getRejectionReason()`,
  `getLimitAtDecision()`, and the rest are convenience accessors over that record.
- Overrides `fillInStackTrace()` to a no-op. A bulkhead rejection is a flow-control signal, not a
  programming error; `RejectionContext` already carries the diagnostic data, and stack-trace generation
  dominates the rejection path's cost under high rejection rates. Callers that need a stack trace should
  rethrow.

**`InqBulkheadInterruptedException`:**

- Is the paradigm-specific translation of the `InterruptedAcquireException` thrown by a
  `TimedBulkheadStrategy` (per ADR-044). The paradigm facade catches the strategy's exception and throws
  this one.
- The strategy is responsible for restoring the thread's interrupt flag before throwing
  `InterruptedAcquireException`; the facade does not re-set the flag.
- Carries no `RejectionContext` — the strategy never made a rejection decision, so any post-hoc snapshot
  would misrepresent the cause.

Both exceptions extend `InqException` and therefore carry `stackId`, `callId`, `code`, `elementName`, and
`elementType` (per ADR-009).

### 9. Registry

Named bulkhead instances are managed by `BulkheadRegistry` (per ADR-015's registry contract):

- `register(BulkheadHandle<?>)` — first-registration-wins; subsequent registrations under the same name
  are no-ops.
- `get(String name)` — returns the handle, or throws if absent.
- `get(String name, InqBulkheadConfig config)` — returns the existing handle if present, otherwise
  constructs from config and registers.
- `find(String name)` — returns `Optional<BulkheadHandle<?>>`.
- `getAllNames()`, `getAll()` — enumeration access.

The registry follows the first-registration-wins rule from ADR-015. The provided config is ignored if
the name is already present; this is the contract every registry in the library honours.

The registry is paradigm-aware via the handle's `ParadigmTag`. A `BulkheadHandle<ImperativeTag>` and a
hypothetical `BulkheadHandle<ReactiveTag>` registered under the same name `"orderBh"` coexist — they
are distinct entries because the `(name, paradigm)` tuple is the registry key, not the name alone.
This matches the pipeline composition model's `(InqElementType, ParadigmTag, Name)` triple from
ADR-040.

### 10. Interaction with TimeLimiter and orphaned calls

When a TimeLimiter (per ADR-010) fires and the caller moves on, the orphaned call still holds a
bulkhead permit. The permit is released when the orphaned call completes — success or failure — because
the bulkhead facade's `finally` block runs regardless of whether the caller is still waiting.

This means: under sustained timeouts, the bulkhead may fill up with orphaned calls. This is by design —
it is exactly the "TimeLimiter + Bulkhead" mitigation pattern from ADR-010. The bulkhead caps the
number of orphaned calls. Once full, new calls are rejected immediately, preventing unbounded resource
consumption.

The interaction is paradigm-agnostic in concept. The imperative paradigm's facade (per ADR-046)
implements the `finally`-block release; future paradigms implement the analogous mechanism for their
execution model (a reactive `doFinally(...)`, a coroutines `finally` block in a `suspend fun`, etc.).
The contract is the same: release happens when the call completes, regardless of caller observation.

## Consequences

**Positive:**

- The data side of the bulkhead is specified in one place. `BulkheadSnapshot`, `BulkheadField`,
  `BulkheadHandle`, the DSL, the events, the metrics, the exceptions, the registry — all live in this
  ADR. A reader looking for "what is a bulkhead, structurally" finds the answer here.
- The paradigm-agnostic surface is large. The handle interface, the snapshot, the DSL, the events, the
  exceptions, the registry — all are the same across paradigms. A second paradigm (future reactive or
  coroutines) inherits the entire surface and adds only its paradigm-specific facade.
- The DSL is type-safe and exhaustive. The sealed-type `BulkheadStrategyConfig` plus the sub-builders
  guarantee that every strategy is accessible from the DSL and the compiler catches omissions on
  extension.
- Two-tier observability gives operators the right trade-offs. Tier-1 metrics are zero-cost; Tier-2
  events provide depth on demand. The `standard()` preset is allocation-free; `diagnostic()` is
  short-lived for incident investigation.
- The handle's four-interface composition (`InqElement`, `LifecycleAware`, `ListenerRegistry`,
  `InternalMutabilityCheck`) means the handle naturally participates in pipelines, in lifecycle queries,
  in update veto chains, and in component-internal mutability decisions — without a single
  god-interface.
- The user-facing configuration aligns with the snapshot. Both records carry the same fields in the
  same shape; conversion between them is a direct mapping rather than a translation.
- The registry's `(name, paradigm)` key matches the pipeline composition model's triple (ADR-040). Two
  bulkheads with the same name in different paradigms coexist naturally.

**Negative:**

- The `BulkheadHandle` interface has four super-interfaces. A consumer holding a handle sees a wide
  surface — methods from `InqElement`, `LifecycleAware`, `ListenerRegistry`, and
  `InternalMutabilityCheck` all on the same type. Tooling and IDEs show them all. Some users may find
  this overwhelming; the alternative — a narrower handle plus separate query objects — would split the
  surface across multiple types and be more cumbersome to use.
- The configuration record has two near-identical siblings: `BulkheadSnapshot` (runtime state) and
  `InqBulkheadConfig` (user-facing config). Their fields overlap heavily. The duplication is real but
  serves a purpose: the snapshot is materialised post-validation and includes derived data
  (`derivedFromPreset`), while the config is the input shape with implicit infrastructure references
  (`general`, `common`).
- The DSL surface grows with every new strategy. Adding a fifth strategy adds a fifth sub-builder
  method on `BulkheadBuilderBase`, a fifth builder class, and a fifth case in the strategy factory.
  This is acceptable — the sealed type ensures compiler enforcement — but the DSL is not infinitely
  extensible.

**Neutral:**

- The relationship to ADR-044 is consumption. ADR-044 specifies the strategy contracts, configurations,
  and hot-swap mechanism; ADR-045 specifies how strategies appear in the bulkhead's snapshot and DSL,
  what events get emitted around strategy operations, and how the strategy's runtime state surfaces in
  metrics and the handle's `availablePermits` / `concurrentCalls` accessors.
- The relationship to ADR-046 is consumption from the other direction. ADR-046 specifies how the
  imperative paradigm consumes the data structures specified here: how the imperative facade reads the
  snapshot, dispatches over the handle's interfaces, emits the events, populates the exceptions, and
  participates in the registry.
- The relationship to ADR-043 is partial implementation. ADR-043 specifies the dispatcher-facing
  contracts (`ListenerRegistry`, `InternalMutabilityCheck`); ADR-045's `BulkheadHandle` extends them,
  and the per-paradigm facade (per ADR-029 and ADR-046) provides the concrete implementations.
- ADR-020 and ADR-032 are superseded by this ADR (together with ADR-044 and ADR-046). The substantive
  content from both is preserved across the new three-ADR structure, with the configuration record
  updated to reflect the sealed-type strategy approach and the handle interface aligned with the
  post-consolidation pipeline and lifecycle ADRs.
- Future paradigms add a paradigm-specific facade plus a paradigm-specific configuration extension; they
  inherit everything in this ADR unchanged. The data-side surface is genuinely paradigm-agnostic, not
  paradigm-agnostic-by-convention.
