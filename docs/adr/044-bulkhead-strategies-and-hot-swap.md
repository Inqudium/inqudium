# ADR-044: Bulkhead strategies and hot-swap

**Status:** Proposed  
**Date:** 2026-05-14  
**Deciders:** Core team  
**Related:** ADR-020 (superseded by this ADR and ADR-045), ADR-032 (superseded by this ADR, ADR-045, ADR-046),
ADR-008 (virtual threads), ADR-010 (TimeLimiter), ADR-027 (validation strategy), ADR-028 (component lifecycle
contract), ADR-029 (lifecycle implementation pattern), ADR-042 (pipeline contracts),
ADR-043 (update propagation and veto negotiation), ADR-045 (bulkhead configuration, handle, diagnostics),
ADR-046 (bulkhead imperative implementation).

## Context

A bulkhead isolates failures by limiting the number of concurrent calls to a downstream service. If a service
slows down, the bulkhead prevents the slowdown from consuming all available threads or connections in the
calling application — protecting unrelated services that share the same resources. The name comes from ship
construction: watertight compartments (bulkheads) prevent a hull breach from flooding the entire ship.

A working bulkhead implementation must decide three things that are deeply entangled: which **algorithm** to
use for concurrency control (semaphore vs. thread pool vs. counter), which **contract shape** the permit
operations expose to paradigm-specific code (blocking vs. non-blocking), and how the bulkhead **reacts to
runtime configuration changes** (in-place tuning vs. strategy swap). These three decisions can be made
independently in principle, but in practice they determine each other: a blocking semaphore needs different
implementations than a non-blocking CAS counter, and a strategy swap between them needs a runtime mechanism
beyond simple field updates.

This ADR specifies the paradigm-agnostic Strategy world: the contract hierarchy, the concrete strategy
implementations, the configuration sealed-type that selects between them, and the atomic hot-swap mechanism
that lets a running bulkhead transition from one strategy to another. The paradigm-agnostic snapshot,
handle, DSL, diagnostics, and registry are specified in ADR-045. The imperative-specific facade — the
`InqBulkhead<A, R>` class with its sync/async dispatch and `BulkheadHotPhase<A, R>` — is specified in
ADR-046.

Historically the bulkhead's design was specified in ADR-020 (overall design) and ADR-032 (strategy hot-swap
and DSL). ADR-020 mixed paradigm-agnostic decisions (strategy contracts) with imperative-specific
implementation details (the facade's execute method body); ADR-032 mixed strategy configuration with
hot-swap mechanics and DSL details. The two ADRs are superseded by the three-ADR split — ADR-044
(strategies), ADR-045 (configuration), ADR-046 (imperative implementation) — which separates concerns more
clearly. This ADR is the first piece of that split.

### Algorithm choice

Two common bulkhead implementations exist outside Inqudium:

**Thread-pool isolation** — Each downstream service gets its own thread pool. Calls execute on the pool's
threads, not the caller's thread. If the pool is exhausted, new calls are rejected. Used by Hystrix
(Netflix). Provides strong isolation because the caller's thread is never blocked — it submits to the pool
and waits on a Future.

**Semaphore (counter) isolation** — A counter limits concurrent calls. The caller's thread executes the
call directly. If the counter is at its limit, the call is rejected or waits. Used by Resilience4j.

Inqudium chooses counter-based isolation exclusively. Thread-pool isolation was designed for a world of
expensive platform threads. Its purpose was to prevent one slow service from consuming all threads in a
shared `ExecutorService`. With virtual threads (Java 21+, per ADR-008), this rationale collapses:

- **Virtual threads are cheap.** Creating thousands of virtual threads is a lightweight operation. There is
  no shared thread pool to protect — each call gets its own virtual thread.
- **Context loss.** Thread-pool isolation moves execution to a different thread. `ThreadLocal` state (MDC,
  security context, transaction) does not propagate automatically.
- **Overhead.** Submitting to a thread pool, waiting on a Future, and handling the result adds latency and
  allocation overhead compared to a direct call with a permit check.
- **Reactive incompatibility.** Thread-pool isolation conflicts with reactive programming models (Reactor,
  RxJava) where execution should stay on the subscriber's scheduler. Injecting a thread pool breaks
  backpressure semantics.

Counter-based isolation works on the caller's thread — no context loss, no thread switch. It works
identically for imperative, coroutine, and reactive paradigms. The concurrency limit is the only property
that matters; a counter (semaphore or atomic) provides exactly that. For projects running on Java 8-17
without virtual threads, the bulkhead combined with a TimeLimiter (per ADR-010) provides equivalent
protection: the TimeLimiter bounds the caller's wait time, and the bulkhead bounds the number of concurrent
calls.

## Decision

### 1. Strategy contract hierarchy

Bulkhead permit management is split into three contracts at the core level. The split is signature-driven:
the two acquire-method variants — instant and timed — describe structurally different operations, and a
strategy implements whichever variant matches its character. The split is not paradigm-typed; any paradigm
may consume any contract, with the implementations' actual paradigm-suitability determined by their
internal mechanism rather than by the contract type.

```java
public interface BulkheadStrategy {                  // shared base — lifecycle and introspection
    void release();
    void rollback();
    default void onCallComplete(long rttNanos, boolean isSuccess) { /* adaptive only */ }
    int availablePermits();
    int concurrentCalls();
    int maxConcurrentCalls();
}

public interface InstantBulkheadStrategy extends BulkheadStrategy {
    /** Decides immediately. Returns null on permit grant, RejectionContext on denial. */
    RejectionContext tryAcquire();
}

public interface TimedBulkheadStrategy extends BulkheadStrategy {
    /**
     * Tries to acquire a permit, waiting up to {@code timeoutNanos} nanoseconds.
     * Returns null on permit grant, RejectionContext on denial. Throws
     * {@link InterruptedAcquireException} (unchecked) if the wait is interrupted;
     * the strategy is required to restore the thread's interrupt flag before throwing.
     *
     * <p>The nanosecond unit matches the underlying JDK wait primitives
     * ({@link java.util.concurrent.locks.Condition#awaitNanos},
     * {@link java.util.concurrent.locks.LockSupport#parkNanos},
     * {@link java.util.concurrent.Semaphore#tryAcquire(long, java.util.concurrent.TimeUnit)})
     * and avoids per-call {@link Duration} allocations on the hot path. The user-facing
     * configuration carries {@link Duration} values (per ADR-045); the conversion to
     * nanoseconds happens once during strategy materialisation, not per call.</p>
     *
     * <p>{@code timeoutNanos} must be non-negative. Negative values produce undefined
     * behaviour; callers are responsible for clamping to zero before invoking. The
     * facade clamps once during snapshot materialisation (per ADR-046), keeping the
     * hot path branch-free.</p>
     */
    RejectionContext tryAcquire(long timeoutNanos);
}
```

The acquire signatures differ on purpose. `InstantBulkheadStrategy.tryAcquire()` makes a decision without
any waiting mechanism; `TimedBulkheadStrategy.tryAcquire(long)` may wait — through whatever mechanism
the implementation chooses (parking, lock-based wait, CAS-loop with backoff) — up to the timeout.

The names reflect what the signature *promises structurally*, not what a particular implementation does
internally. An `InstantBulkheadStrategy` cannot wait by contract: its signature has no place for a timeout.
A `TimedBulkheadStrategy` can wait, but how it does so is implementation-defined; a future reactive
implementation could wait non-blockingly via a backoff loop rather than thread-parking.

The earlier draft used the names `BlockingBulkheadStrategy` and `NonBlockingBulkheadStrategy`, which
conflated the signature shape with a paradigm-specific behaviour assumption. The replacement
`InstantBulkheadStrategy` / `TimedBulkheadStrategy` names describe the contract more honestly: instant is
"decides without waiting", timed is "may wait up to timeout", and the implementation mechanism is left
open.

**Acquire return convention.** Both methods return `null` on the happy path — no allocation when a permit
is granted — or a `RejectionContext` describing why the request was denied. This is a deliberate
zero-allocation optimisation: the common case is "permit granted" and pays no object cost; the rejection
case carries diagnostic data for downstream use in exceptions and events.

**Strategies are stateful.** Real strategies own internal concurrency primitives — a `Semaphore` for
`SemaphoreBulkheadStrategy`, an `AtomicInteger` (CAS loop) for `AtomicInstantBulkheadStrategy`, a
`ReentrantLock` with a `Condition` for the timed adaptive variant. Strategy instances are bound to a
single bulkhead instance for that bulkhead's lifetime.

#### Interrupt handling

Some `TimedBulkheadStrategy` implementations use a wait mechanism that responds to thread interrupts (the
imperative-paradigm parking-based implementations all do). When the wait is interrupted before a permit is
acquired, the strategy:

1. Catches the underlying `InterruptedException` internally.
2. Restores the thread's interrupt flag via `Thread.currentThread().interrupt()`.
3. Throws an unchecked `InterruptedAcquireException` (in `inqudium-core`) with the original cause attached.

The exception propagates up to the paradigm-specific facade, which translates it into a
paradigm-appropriate form. The imperative facade (per ADR-046) catches `InterruptedAcquireException` and
throws `InqBulkheadInterruptedException` — an `InqException` subtype carrying `stackId`, `callId`,
`name`, and an error code. A future reactive facade would translate the same exception into a reactive
error signal (`Mono.error(...)`), and a coroutines facade would translate it into a `CancellationException`
or similar.

`InterruptedAcquireException` is paradigm-agnostic. It carries no `stackId`, `callId`, or other
correlation data — those belong to the paradigm-specific exception that the facade produces. The strategy
knows only its own internal state; the per-call correlation is a facade concern.

Strategies whose wait mechanism cannot be interrupted (a future CAS-loop-with-backoff implementation, for
instance) never throw `InterruptedAcquireException`. The exception is part of the contract surface only
for implementations whose wait mechanism honours thread interrupts.

#### Where the contracts live

Both `InstantBulkheadStrategy` and `TimedBulkheadStrategy` live in `inqudium-core`. The `InqLimitAlgorithm`
SPI (section 3) drives both an instant adaptive variant in core and a timed adaptive variant in the
imperative module; keeping both contracts in core lets the algorithm SPI be defined once and consumed by
either family. The `InterruptedAcquireException` class also lives in `inqudium-core` — it is the canonical
"acquire-wait interrupted" signal and must be visible to every paradigm facade that consumes timed
strategies.

#### Paradigm consumption

There is no contract-level wiring rule that restricts which paradigm may consume which strategy contract.
The imperative paradigm consumes both instant and timed strategies and dispatches over them via
`instanceof`. A reactive or coroutines paradigm could consume either contract; the choice is governed by
the *implementation* of the chosen strategy: lock-based timed strategies (like the imperative
`SemaphoreBulkheadStrategy`) pin carrier threads and are unsuitable for reactive subscribers, but a
lock-free timed strategy (none ships today, but the contract does not forbid one) would be reactive-safe.

The mapping of paradigms to specific strategy implementations is therefore a paradigm-module decision,
documented in the respective paradigm ADR. ADR-044 only specifies the contracts.

### 2. `RejectionContext` and `RejectionReason`

`RejectionContext` is the canonical rejection payload:

```java
public record RejectionContext(
    RejectionReason reason,
    int limitAtDecision,        // limit enforced at decision time (current adaptive limit, not configured max)
    int activeCallsAtDecision,  // calls holding a permit at decision time
    long waitedNanos,           // time spent waiting before rejection (0 for non-blocking strategies)
    long sojournNanos           // CoDel post-lock queue wait (0 for non-CoDel strategies)
) { ... }

public enum RejectionReason {
    CAPACITY_REACHED,         // limit met or exceeded at the moment of decision
    TIMEOUT_EXPIRED,          // blocking only — caller's wait expired without a permit becoming free
    CODEL_SOJOURN_EXCEEDED    // CoDel only — load shed despite a permit being available
}
```

The `RejectionContext` is captured **inside** the strategy's decision logic — within the CAS loop or the
lock-guarded block — so every field reflects the true state that caused the rejection. This eliminates the
time-of-check / time-of-use pitfall where a post-hoc call to `concurrentCalls()` returns a value that has
already changed between the rejection and the diagnostic read.

The CoDel reason is operationally important: a `CODEL_SOJOURN_EXCEEDED` with low active calls is *expected
behaviour* during sustained congestion, not a bug. Distinguishing it from `CAPACITY_REACHED` matters for
incident triage.

`RejectionContext.toString()` produces human-readable summaries (`"CAPACITY_REACHED (10/10 concurrent
calls, no wait)"`, `"TIMEOUT_EXPIRED (10/10 concurrent calls, waited 500ms)"`, `"CODEL_SOJOURN_EXCEEDED
(8/10 concurrent calls, sojourn 1200ms, waited 1500ms)"`) suitable for exception messages and
structured-log fields.

### 3. Strategy implementations

Five concrete strategies implement the contract hierarchy. Their distribution across modules reflects the
"no blocking in core" architectural rule: paradigm-agnostic strategies live in `inqudium-core`,
imperative-specific (potentially blocking) strategies live in `inqudium-imperative`.

| Strategy                            | Module                  | Contract                                              | Acquire mechanism                                  |
|-------------------------------------|-------------------------|-------------------------------------------------------|----------------------------------------------------|
| `AtomicInstantBulkheadStrategy`     | `inqudium-core`         | `InstantBulkheadStrategy`                             | CAS loop on a single `AtomicInteger`               |
| `AdaptiveInstantBulkheadStrategy`   | `inqudium-core`         | `InstantBulkheadStrategy`                             | CAS loop reading the algorithm's current limit     |
| `SemaphoreBulkheadStrategy`         | `inqudium-imperative`   | `TimedBulkheadStrategy`                               | Fair `Semaphore.tryAcquire(timeout, NANOSECONDS)`  |
| `AdaptiveBulkheadStrategy`          | `inqudium-imperative`   | `TimedBulkheadStrategy`                               | `Condition.awaitNanos(long)` under a `ReentrantLock` |
| `CoDelBulkheadStrategy`             | `inqudium-imperative`   | `TimedBulkheadStrategy`                               | `Condition.awaitNanos` + sojourn-time evaluation   |

#### Static strategies

**`AtomicInstantBulkheadStrategy`** (core) uses a single `AtomicInteger` with `compareAndSet`. The
acquire path is one volatile read plus one CAS; the release path is one CAS with a decrement-if-positive
guard. No AQS infrastructure, no queue, no fairness concept — fairness is irrelevant for instant
decisions. Lock-free and allocation-free on the happy path.

**`SemaphoreBulkheadStrategy`** (imperative) uses a *fair* `Semaphore` (FIFO queue) and an `AtomicInteger`
shadow counter for the over-release guard, since `Semaphore.release()` does not check whether the caller
holds a permit. The fair semaphore means even `tryAcquire(0L)` respects the queue — a zero-timeout call
may be rejected when the bulkhead has free permits but other threads are already queued.

#### Adaptive strategies

A pluggable `InqLimitAlgorithm` SPI computes a dynamic concurrency limit based on call outcomes:

```java
public interface InqLimitAlgorithm {
    int getLimit();                                                 // current dynamic limit
    void update(long rttNanos, boolean isSuccess, int inFlight);    // feedback after each completed call
}
```

Two implementations ship today:

- **`AimdLimitAlgorithm`** — Additive Increase / Multiplicative Decrease. Increases the limit on success,
  multiplicatively reduces it on failure or latency violation. Predictable, simple to reason about.
- **`VegasLimitAlgorithm`** — TCP Vegas-inspired. Uses round-trip-time trends to detect queueing before
  failures appear. More responsive in latency-sensitive workloads.

Two adaptive strategies wrap the algorithm:

- **`AdaptiveInstantBulkheadStrategy`** (core) — CAS loop reads the algorithm's current limit on each
  iteration. No locks, no signalling — limit changes are visible to concurrent acquirers via volatile
  reads inside the algorithm.
- **`AdaptiveBulkheadStrategy`** (imperative) — `Condition.awaitNanos(long)` under a `ReentrantLock`. The
  condition is signalled on release and on limit changes so parked threads re-evaluate.

**Feedback ordering matters.** The paradigm facade must call `onCallComplete(rttNanos, isSuccess)` *before*
`release()` so the in-flight count passed to the algorithm still includes the completing call. Reversing
the order makes the algorithm see an artificially low in-flight count, suppressing limit increases when
`minUtilizationThreshold > 0`. Forgetting `onCallComplete` entirely silently degrades the adaptive strategy
to a static limiter.

To eliminate this hazard, adaptive strategies expose a `completeAndRelease(long rttNanos, boolean
isSuccess)` convenience method that performs both steps in the correct order with a `try`/`finally`
guarantee. Facades should prefer it over the two-step form.

**Over-limit transient state.** When the algorithm decreases the limit (e.g. 20 → 10) while 15 calls are
in flight, `activeCalls` (15) exceeds the new limit. The strategy does *not* forcibly revoke permits — it
simply refuses new acquisitions until the count drops below the new limit naturally. Both adaptive
strategies follow this rule.

#### CoDel (controlled delay) load shedding

**`CoDelBulkheadStrategy`** (imperative) implements the CoDel queue-management algorithm. A permit is
granted normally if sojourn time — the post-lock wait — stays below the target delay; if sojourn time
exceeds the target for longer than one interval, the strategy starts dropping requests *even when permits
are available*, breaking the queueing-collapse cycle.

Rejected requests carry `RejectionReason.CODEL_SOJOURN_EXCEEDED` plus a `BulkheadCodelRejectedTraceEvent`
(TRACE category) for diagnostics — the event types are specified in ADR-045.

#### What "no blocking in core" means here

An earlier draft of ADR-020 claimed "no blocking in the core algorithm — ever". That overstates the rule.
The accurate version:

- The core contracts `BulkheadStrategy`, `InstantBulkheadStrategy`, and `TimedBulkheadStrategy` carry no
  blocking semantics themselves. The signatures permit waiting (in the timed variant), but they do not
  mandate it.
- The `TimedBulkheadStrategy` *contract* lives in core, but its current *implementations* (Semaphore,
  Adaptive, CoDel) live in the imperative module because they all use lock- or parking-based wait
  mechanisms. A future paradigm could implement a lock-free `TimedBulkheadStrategy` that lives in core; the
  contract permits it.
- The pure-algorithm rule from the project conventions (`no Thread.sleep`, `no synchronized`, `no
  schedulers in core`) holds for the core strategy implementations.
  `AtomicInstantBulkheadStrategy` and `AdaptiveInstantBulkheadStrategy` are CAS-only, lock-free,
  allocation-free on the happy path.

The architectural rule is therefore not "timed contracts live outside core" but "lock-based and
parking-based implementations live outside core". The distinction matters: the contract is
paradigm-agnostic; the implementation mechanism is what determines where the code physically lives.

### 4. Lifecycle methods: `release` vs. `rollback`

The two methods on the base `BulkheadStrategy` contract are mechanically identical (decrement the active
count) but semantically distinct:

- `release()` — the business call ran (success or failure) and is now done.
- `rollback()` — the permit was acquired, but the *acquire-side telemetry* failed (an event publisher
  threw) and the business call never started. The rollback exists so the failed publish does not leak
  permits.

The imperative facade (per ADR-046) uses both: a normal completion calls `release()` from the `finally`
block; a thrown acquire-event publish triggers `rollback()` plus an optional trace event before the
original exception propagates.

### 5. `BulkheadStrategyConfig` sealed-type

Strategy configuration is expressed as a sealed-type discriminator:

```java
public sealed interface BulkheadStrategyConfig
        permits SemaphoreStrategyConfig, CoDelStrategyConfig, AdaptiveStrategyConfig,
                AdaptiveNonBlockingStrategyConfig {
}

public record SemaphoreStrategyConfig() implements BulkheadStrategyConfig { }

public record CoDelStrategyConfig(
    Duration targetDelay,
    Duration interval
) implements BulkheadStrategyConfig {
    public CoDelStrategyConfig {
        Objects.requireNonNull(targetDelay, "targetDelay");
        Objects.requireNonNull(interval, "interval");
        if (targetDelay.isNegative() || targetDelay.isZero()) {
            throw new IllegalArgumentException("targetDelay must be positive");
        }
        if (interval.isNegative() || interval.isZero()) {
            throw new IllegalArgumentException("interval must be positive");
        }
    }
}

public record AdaptiveStrategyConfig(
    LimitAlgorithm algorithm
) implements BulkheadStrategyConfig {
    public AdaptiveStrategyConfig {
        Objects.requireNonNull(algorithm, "algorithm");
    }
}

public record AdaptiveNonBlockingStrategyConfig(
    LimitAlgorithm algorithm
) implements BulkheadStrategyConfig {
    public AdaptiveNonBlockingStrategyConfig {
        Objects.requireNonNull(algorithm, "algorithm");
    }
}

public sealed interface LimitAlgorithm
        permits AimdLimitAlgorithmConfig, VegasLimitAlgorithmConfig {
}

public record AimdLimitAlgorithmConfig(/* ... */) implements LimitAlgorithm { }
public record VegasLimitAlgorithmConfig(/* ... */) implements LimitAlgorithm { }
```

The sealed type pattern produces three properties the framework needs:

- **Exhaustive pattern-matching.** Adding a fifth strategy later requires updating exactly one factory
  `switch` (section 6). The compiler catches the omission.
- **Construction validation.** Each config's compact constructor enforces its own invariants. A
  `CoDelStrategyConfig` cannot be constructed with a negative `targetDelay`; the error surfaces at the
  point of construction rather than later at strategy materialisation.
- **Serialisable shape.** Configs are records — value-based, immutable, suitable for snapshot inclusion
  (per ADR-045).

The default config, applied by the imperative provider's default snapshot, is `new SemaphoreStrategyConfig()`.
A bulkhead constructed without an explicit strategy choice runs on a semaphore.

### 6. The strategy factory

The bulkhead's hot phase (per ADR-046) does not hard-code a strategy. Instead it delegates to a factory
that pattern-matches on the snapshot's `BulkheadStrategyConfig`:

```java
final class BulkheadStrategyFactory {
    static BulkheadStrategy create(BulkheadSnapshot snapshot, GeneralConfig general) {
        return switch (snapshot.strategy()) {
            case SemaphoreStrategyConfig s -> new SemaphoreBulkheadStrategy(
                    snapshot.maxConcurrentCalls());
            case CoDelStrategyConfig c -> new CoDelBulkheadStrategy(
                    snapshot.maxConcurrentCalls(), c.targetDelay(), c.interval(), /* ... */);
            case AdaptiveStrategyConfig a -> new AdaptiveBulkheadStrategy(
                    snapshot.maxConcurrentCalls(), buildAlgorithm(a.algorithm()), /* ... */);
            case AdaptiveNonBlockingStrategyConfig a -> new AdaptiveInstantBulkheadStrategy(
                    snapshot.maxConcurrentCalls(), buildAlgorithm(a.algorithm()), /* ... */);
        };
    }

    static InqLimitAlgorithm buildAlgorithm(LimitAlgorithm config) {
        return switch (config) {
            case AimdLimitAlgorithmConfig a -> new AimdLimitAlgorithm(/* ... */);
            case VegasLimitAlgorithmConfig v -> new VegasLimitAlgorithm(/* ... */);
        };
    }
}
```

The pattern matches are exhaustive by construction (sealed types), so the compiler enforces that adding a
new strategy or algorithm updates the factory.

The factory itself lives wherever the calling code lives. In the imperative paradigm, that is
`inqudium-imperative` — the factory is private to the imperative module and produces strategies from both
core (`AtomicNonBlocking`, `AdaptiveNonBlocking`) and imperative (`Semaphore`, `Adaptive`, `CoDel`)
modules. Other paradigms provide their own factories that select from their own strategy implementations;
the sealed-type config is paradigm-agnostic, the factory is paradigm-private.

### 7. Over-release safety

All strategies defend against double-release and release-without-acquire:

- **`SemaphoreBulkheadStrategy`** — the shadow `AtomicInteger.getAndUpdate(c -> c > 0 ? c - 1 : 0)` only
  releases the underlying semaphore when the shadow count was positive. A double-release becomes a no-op.
- **`AtomicInstantBulkheadStrategy`** and **`AdaptiveInstantBulkheadStrategy`** — CAS loop with a
  decrement-if-positive guard. Going below zero is impossible.

Permit leakage (forgetting to call `release` after a successful acquire) is still a fatal correctness bug —
but it must come from a paradigm-module bug, not from a misuse pattern that the strategies silently
absorb.

### 8. Wait behaviour

The user-facing configuration carries a `Duration maxWaitDuration` (per ADR-045). At strategy
materialisation the facade converts the value to `long maxWaitNanos` and passes that on each
`tryAcquire(long)` call (per ADR-046's hot-path discipline).

When `maxWaitNanos > 0`, a `TimedBulkheadStrategy` implementation decides how to wait. The mechanism
depends on the strategy:

| Strategy                       | Wait mechanism                                                     |
|--------------------------------|--------------------------------------------------------------------|
| `SemaphoreBulkheadStrategy`    | `Semaphore.tryAcquire(timeout, TimeUnit.NANOSECONDS)` (fair queue) |
| `AdaptiveBulkheadStrategy`     | `Condition.awaitNanos(long)` under a `ReentrantLock`               |
| `CoDelBulkheadStrategy`        | `Condition.awaitNanos(long)` plus sojourn-time evaluation          |

When `maxWaitNanos == 0L` (corresponding to `Duration.ZERO` in the configuration), the same APIs are used
with a zero timeout. The fair semaphore still respects the FIFO queue — a zero-timeout call may be
rejected even when permits are nominally available, because the fair queue takes priority.

`InstantBulkheadStrategy` implementations do not wait by definition; they decide instantly. Whether a
reactive facade then re-attempts on a delayed signal is a paradigm-module concern — not part of the
strategy contract.

### 9. Hot-swap concept

A bulkhead's strategy is part of its snapshot (per ADR-045). When `runtime.update(...)` produces a patch
that touches the strategy field, the bulkhead must transition from running on strategy `A` to running on
strategy `B`. This is **strategy hot-swap**.

The hot-swap is **atomic**:

- Every call after the swap commit runs on `B` and no call straddles the boundary, or the swap is rejected
  and every call continues to run on `A`.
- The transition does not produce a window where some calls run on `A` and others on `B` simultaneously.
  The cutover is a single state transition, not a phased migration.
- The transition is gated by a precondition that the dispatcher checks before applying: **zero in-flight
  calls**. If the bulkhead has any active permits at the moment the patch reaches the component-internal
  check, the swap is vetoed with a clear reason.

The atomic shape is symmetric to how the cold-to-hot transition already works (per ADR-029): the lifecycle
base class performs a single state transition that either succeeds or loses to a concurrent transition,
and there is no state in which "some calls see cold and others see hot" — observers see one or the other,
deterministically.

**The cost of atomicity.** A continuously-busy bulkhead — one that always has at least one permit held —
never sees a moment of quiescence and therefore can never be swapped. Operators who want to migrate a hot
bulkhead from semaphore to CoDel under sustained load must either drain it manually (route traffic away
until concurrent calls reach zero) or accept the swap attempt as a no-op-with-veto outcome until traffic
naturally subsides. This is acknowledged. Phased swap — letting in-flight calls finish on the old strategy
while new acquires bind to the new one — is more powerful operationally but introduces race conditions,
transition-window bookkeeping, and re-entrancy concerns under repeated swaps. Phased swap is a future
extension; the atomic form is the current decision.

### 10. Component-internal mutability check for strategy patches

The bulkhead's `InternalMutabilityCheck.evaluate(...)` method (per ADR-043) is the dispatcher's gate
before a strategy patch is applied. It inspects two things: the runtime state (for the strategy-swap
precondition) and the post-patch snapshot (for any field-value constraints that depend on the strategy the
runtime will land on).

```java
@Override
public ChangeDecision evaluate(ChangeRequest<BulkheadSnapshot> request) {
    Set<? extends ComponentField> touched = request.touchedFields();
    BulkheadSnapshot postPatch = request.postPatchSnapshot();

    // Transition-operation check: strategy swap requires the runtime to be quiescent.
    if (touched.contains(BulkheadField.STRATEGY)) {
        int inFlight = strategy.concurrentCalls();
        if (inFlight > 0) {
            return ChangeDecision.veto(
                    "strategy swap requires zero in-flight calls; current = " + inFlight);
        }
    }

    // Field-value check: maxConcurrentCalls is live-tunable only when the post-patch
    // strategy is the semaphore. A combined STRATEGY=Semaphore + MAX_CONCURRENT_CALLS=N
    // patch passes here even on a hot CoDel bulkhead, because the post-patch view is
    // what matters for live-tunability.
    if (touched.contains(BulkheadField.MAX_CONCURRENT_CALLS)
            && !(postPatch.strategy() instanceof SemaphoreStrategyConfig)) {
        return ChangeDecision.veto(
                "maxConcurrentCalls is not live-tunable on "
                        + postPatch.strategy().getClass().getSimpleName());
    }

    return ChangeDecision.accept();
}
```

The bulkhead's live-tunability matrix is small: every field is live-tunable when the post-patch strategy
is the semaphore; only `maxConcurrentCalls` has the strategy-dependent constraint. The other fields read
fresh from the snapshot on every operation and need no strategy mutation, so they fall through to accept
regardless of the post-patch strategy.

If the check accepts, the dispatcher proceeds to `live.apply(patch)`. The snapshot is updated, and the
live-container subscription handler in the hot phase (per ADR-046) receives the change. The handler
detects that the snapshot's strategy config differs from the running strategy and performs the swap
synchronously — the implementation specifics are in ADR-046.

The mutability check's logic is the same regardless of paradigm: the in-flight precondition for STRATEGY
patches and the live-tunability constraint for MAX_CONCURRENT_CALLS apply to every bulkhead. Per-paradigm
facades implement the check via their hot-phase class; the rules specified here are the contract every
implementation honours.

### 11. Listener visibility of strategy patches

Listeners registered on the bulkhead handle (per ADR-045) see strategy patches like any other patch — the
veto chain runs them in registration order, and any of them can reject. The veto chain is conjunctive
(any single veto kills the patch); a strategy swap that survives every listener reaches the
component-internal check and is then gated on the in-flight precondition.

A listener that vetoes specifically on strategy changes uses the same mechanism every other veto-policy
listener uses:

```java
bulkhead.onChangeRequest(req -> req.touchedFields().contains(BulkheadField.STRATEGY)
    ? ChangeDecision.veto("strategy locked by policy")
    : ChangeDecision.accept());
```

The listener sees a typed `ChangeRequest<BulkheadSnapshot>`, can inspect `request.proposedValue(BulkheadField.STRATEGY,
BulkheadStrategyConfig.class)` to see which strategy the patch proposes, and can compose its veto reason
accordingly.

## Consequences

**Positive:**

- The signature-driven strategy split (`InstantBulkheadStrategy` for immediate decisions,
  `TimedBulkheadStrategy` for wait-up-to-timeout) makes the contract's intent visible at the type level
  without restricting which paradigm can consume which contract. Paradigms choose strategy implementations
  whose internal mechanism suits their execution model; the contract types themselves carry no paradigm
  assumption.
- Counter-based isolation works identically across paradigms. No thread-pool conflicts with reactive or
  coroutine models.
- Virtual-thread friendly. No unnecessary thread switches, no platform-thread waste.
- O(1) acquire and release on the static strategies; lock-free on both non-blocking variants.
- Adaptive strategies (AIMD, Vegas) plug in without changing the facade. The algorithm SPI is the single
  extension point.
- CoDel provides explicit load shedding without coupling to the rest of the bulkhead surface. Rejection
  diagnostics distinguish CoDel drops from capacity-reached situations.
- `RejectionContext` captured inside the decision lock eliminates time-of-check / time-of-use pitfalls in
  diagnostics. The values match the cause.
- The sealed-type approach for `BulkheadStrategyConfig` is exhaustive at compile time. Adding a strategy
  later requires updating exactly one factory `switch`, and the compiler catches the omission.
- Atomic hot-swap is simple to reason about and to test. The veto chain is the visible gate; the swap
  itself is a single state transition. There is no transition state to debug.
- The component-internal mutability check — a generic veto hook from ADR-043 — gets its first concrete
  "veto on something genuine" use here. The infrastructure pays back.

**Negative:**

- Counter-based isolation does not protect against caller-thread exhaustion in non-virtual-thread
  environments. If all in-flight caller threads are blocked on slow downstream calls, the application has
  stuck threads. Mitigation: use virtual threads (per ADR-008) or combine with TimeLimiter (per ADR-010).
- Adaptive feedback ordering (`onCallComplete` before `release`) is a correctness contract that paradigm
  modules must honour. The `completeAndRelease` helper exists to remove this footgun, but callers wiring
  the steps manually can still get it wrong.
- The lock-based `TimedBulkheadStrategy` implementations (Semaphore, Adaptive, CoDel) park the caller's
  thread while waiting for a permit. With virtual threads this is cheap; on a pre-21 JVM running in a
  thread pool it can starve the pool — the same hazard the TimeLimiter mitigates. Non-park-based
  `TimedBulkheadStrategy` implementations are possible in the contract but none ship today.
- The fair `SemaphoreBulkheadStrategy` may reject zero-timeout requests even when permits are available,
  because the fair queue takes priority. This is the correct trade-off for FIFO fairness but is
  surprising; documented in the strategy's Javadoc and in section 8.
- A continuously-busy bulkhead cannot be hot-swapped under load. Operators must drain manually or accept
  that the swap is a no-op-with-veto until traffic subsides. Phased swap remains a future extension.

**Neutral:**

- No thread-pool isolation option. Projects that require strict thread-pool isolation (for example for
  regulatory reasons) should use a dedicated `ExecutorService` outside Inqudium. The bulkhead is a
  concurrency counter, not a thread-pool manager.
- `maxConcurrentCalls` does not weight individual calls. A bulkhead of N treats a lightweight read and a
  heavyweight batch operation as equal. Weighted bulkheads remain a possible future extension but are not
  in scope.
- The factory dispatch pattern (sealed-type `switch`) follows the precedent established by
  `BulkheadEventConfig` and other configuration sub-records.
- The relationship to ADR-045 is consumption. ADR-045 specifies the snapshot field, the
  `BulkheadField.STRATEGY` enum value, the DSL sub-builders for each strategy, and the event types
  referenced from this ADR (`BulkheadCodelRejectedTraceEvent` and the others). This ADR specifies what the
  strategies *are* and how they interact; ADR-045 specifies how they appear in the bulkhead's
  configuration shape.
- The relationship to ADR-046 is dispatch. ADR-046 specifies how the imperative facade invokes the
  strategies — the `execute(...)` body, the `executeAsync(...)` two-phase around-advice, the hot-phase
  field-write for hot-swap. This ADR specifies the strategy contract; ADR-046 specifies the imperative
  paradigm's consumption of it.
- ADR-020 and ADR-032 are superseded by this ADR (together with ADR-045 and ADR-046). The substantive
  content from both is preserved across the new three-ADR structure, normalised where the original was
  telegraphic and aligned with the post-consolidation pipeline and lifecycle ADRs.
