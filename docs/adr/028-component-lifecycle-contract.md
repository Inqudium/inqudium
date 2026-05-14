# ADR-028: Component lifecycle contract

**Status:** Accepted  
**Date:** 2026-04-26  
**Last updated:** 2026-05-14  
**Deciders:** Core team  
**Related:** ADR-025 (configuration architecture), ADR-026 (runtime and registry),
ADR-029 (lifecycle implementation pattern), ADR-043 (update propagation and veto negotiation —
extracted from this ADR's earlier form).

## Context

Resilience components — bulkheads, circuit breakers, rate limiters, time limiters — go through phases over
their existence. A component that has just been constructed has no in-flight calls, no accumulated runtime
state, no observations recorded. The same component, after serving production traffic for hours, may be
holding permits, queueing waiters, feeding adaptive-limit algorithms with timing data, and tracking
sliding-window observations.

These two phases are not the same operationally. Configuration changes, internal-state queries, and removal
operations have different semantics in each phase. The framework needs to recognise the distinction in a
uniform way: every component carries the same notion of *what phase it is in*, and every consumer
(application code, the update dispatcher, operational tooling) sees the same notion through the same
accessor.

This ADR specifies the lifecycle contract: what phases exist, when transitions happen, what the transitions
mean, and what the contract guarantees to consumers. It is a small, focused contract. Two related concerns
have their own ADRs:

- **The implementation form** — how components actually realise the lifecycle in code (a state pattern
  with a phase reference, cold and hot phase classes, the `AtomicReference`-orchestrated transition) — is
  specified by ADR-029. This ADR is about behaviour, ADR-029 is about implementation.

- **Update propagation and veto negotiation** — how `runtime.update(...)` calls interact with the lifecycle,
  how listeners and component-internal checks participate in a veto chain, how the dispatcher routes
  patches and removals based on lifecycle state — is specified by ADR-043. That mechanism *uses* the
  lifecycle contract specified here as a routing input, but its rules and execution sequences belong in
  ADR-043, not in this ADR.

The earlier form of ADR-028 carried both the lifecycle contract and the update-propagation mechanics. The
two were separated in 2026-05 because they are conceptually independent — the lifecycle contract is
meaningful even in a hypothetical system without runtime updates — and bundling them in one document
produced a long, dense specification that was hard to navigate. The extracted parts now live in ADR-043;
this ADR retains only the lifecycle contract proper.

## Decision

### 1. Two phases: `COLD` and `HOT`

Every component carries an internal lifecycle state, exposed as the `LifecycleState` enum:

```java
public enum LifecycleState {
    COLD,   // configured, not yet actively serving calls
    HOT     // has begun serving calls
}
```

A `COLD` component has been constructed and is configured, but has not yet served any call. It holds no
in-flight state. Its configuration is freely modifiable through `runtime.update(...)` — the routing rules
in ADR-043 take advantage of this property to skip the veto chain in the cold case.

A `HOT` component has served at least one call. From that moment, it may hold permits, queue waiters, record
observations, run adaptive algorithms, or maintain any other live runtime state. Configuration changes
require the veto chain (per ADR-043) because they may need to coordinate with that live state.

The two states are sufficient. Finer distinctions ("started but no live state yet", "live state present but
not load-bearing") were considered and rejected for two reasons:

- **Operationally, the distinctions describe at most seconds of clock time.** A bulkhead that rejects every
  call from its first moment is a misconfiguration that surfaces immediately; the difference between "hot
  at first call" and "hot at first permit grant" is a few hundred microseconds in practice.

- **A uniform two-state model lets the lifecycle base class (per ADR-029) implement the transition once,
  without per-component logic.** Components do not declare custom phase definitions; they inherit the
  cold/hot pair from the lifecycle base class and contribute only the hot-phase behaviour through
  `createHotPhase()`.

### 2. The cold-to-hot transition

The transition from `COLD` to `HOT` happens automatically on the **first call to `execute`** (or its
paradigm-specific equivalent — `executeAsync` in the imperative-async path, the first subscription on a
returned `Mono`/`Flux` in reactive paradigms, the first `suspend` resume in Kotlin coroutines).

The transition has three properties.

**Monotonic.** A component transitions from `COLD` to `HOT` exactly once. There is no reverse transition.
A component that has briefly served calls and then gone idle stays `HOT`; it does not return to `COLD`.
This is a deliberate simplification — a "cooldown to cold" rule would require defining what "idle long
enough" means, which is application-specific. The cost (long-idle components have unnecessarily restrictive
update semantics under ADR-043) is small in practice; the benefit is a simple, predictable lifecycle.

**Automatic.** Application code does not trigger the transition explicitly. There is no `markHot()` method,
no configuration knob to influence the trigger, no way to force the transition before its natural
conditions are met. The trigger is the act of serving the first call.

**Observable.** The lifecycle state can be queried at any time:

```java
BulkheadHandle<?> handle = runtime.imperative().bulkhead("inventory");
LifecycleState state = handle.lifecycleState();   // returns COLD or HOT
```

The `lifecycleState()` accessor lives on the `LifecycleAware` interface (in `inqudium-config`), which is
inherited by every paradigm's component handle. Reading the state is cheap — a single field read with no
synchronisation cost beyond what the underlying phase reference itself requires.

The transition fires a `ComponentBecameHotEvent` exactly once per component lifetime (per ADR-043 events
section). Operational tooling that wants to observe the moment of transition subscribes to that event;
application code that wants to check the current state polls `lifecycleState()`.

### 3. The contract: observable but not controllable

The lifecycle is *observable*. Any consumer holding a component handle can read its state. The state is
exposed deliberately, because operational tooling, debugging output, and update routing all benefit from
seeing it.

The lifecycle is *not controllable* from outside the component. There is no public API to:

- Force a transition (`markHot()` does not exist).
- Reverse a transition (`markCold()` does not exist).
- Influence the trigger condition (no configuration option to defer or accelerate it).
- Test components in a synthetic phase (tests that need a hot component invoke `execute` to drive the
  transition, like production traffic does).

The single rule — "first execute triggers the transition" — is the entire contract on transition timing.
Components do not expose alternatives, and the framework does not provide them.

This restriction is consequential. The most visible cost falls on tests: a test that needs to exercise the
hot-phase behaviour cannot construct the component and immediately query it as `HOT`; it must drive a real
`execute` call first. The cost is accepted because the alternative — exposing a test-only hot-transition
hook — would create a public method that production code could accidentally use. The test gesture (a
single `execute` call) is small enough that the cost is paid easily and the risk of misuse is eliminated.

### 4. The contract's relationship to other concerns

The lifecycle contract has narrow scope. Three adjacent concerns are explicitly outside this ADR's reach:

**Implementation form.** How a component actually holds the cold and hot states — the State pattern, the
`AtomicReference<Phase>` field, the CAS-based transition, the cold-phase non-static inner class, the
post-commit initialisation hook — is specified by ADR-029. Components in the framework's paradigm modules
follow that pattern; third-party components are not strictly required to but gain consistency by doing so.

**Update propagation and veto.** When `runtime.update(...)` produces a patch for a component, the
dispatcher's routing decision is based on the component's current lifecycle state: cold patches apply
directly, hot patches run through a veto chain involving listeners and the component's internal mutability
check. The rules, sequences, listener APIs, internal-check contracts, and event types governing that
mechanism are specified by ADR-043. This ADR specifies only the lifecycle states and the transition
trigger; the consequences of those states for update routing belong to ADR-043.

**Per-component lifecycle policy.** Specific components may carry additional lifecycle-related rules —
for instance, a bulkhead's hot-phase teardown requirements, or a circuit breaker's sliding-window
initialisation. Such rules are documented in each component's own ADR (ADR-020 for bulkhead, future ADRs
for other elements). This ADR specifies the lifecycle contract that every component honours; component
specifics are layered on top.

## Consequences

**Positive:**

- The two-state lifecycle is uniform across components. Every component answers `lifecycleState()` the
  same way, and operators, tools, and the update dispatcher all consume the answer through the same
  accessor.
- The "first execute" trigger is automatic and visible from the outside. Application code does not need
  to remember to mark components hot; the trigger fires by itself when the component genuinely starts
  serving traffic.
- The transition is observable through both the polling accessor (`lifecycleState()`) and the event
  (`ComponentBecameHotEvent` per ADR-043). Operational tooling picks whichever shape fits its
  observation model.
- Restricting controllability prevents accidental misuse. Code paths that would otherwise call
  `markHot()` to "speed things up" or `markCold()` to "reset" do not exist; the framework's invariants
  cannot be circumvented.
- The contract is small enough to specify, review, and document on a single ADR page. The historical
  long-form ADR-028, which combined lifecycle with update mechanics, was hard to navigate; the
  separated contract is straightforward.

**Negative:**

- Tests that need to exercise the hot phase must drive a real `execute` call to trigger the transition.
  This is a small usability cost for the test author, paid in exchange for the correctness win of no
  test-only entry point in production code.
- The monotonic transition means long-idle components stay `HOT` forever. This produces no functional
  problem but means that ADR-043's veto-chain machinery runs on updates to such components even when
  they have no in-flight state to protect. The simplification is accepted.
- The contract uses only two states. Components that conceptually have more phases (a circuit breaker
  with open / half-open / closed states, a bulkhead with warm-up periods) must encode those internal
  distinctions within the hot phase rather than through additional `LifecycleState` values. The
  framework's `LifecycleState` is about *update routing eligibility*, not about every internal phase a
  component may have.

**Neutral:**

- The transition trigger ("first execute") is paradigm-aware: imperative uses the first `execute` call,
  async-imperative uses the first `executeAsync` call, reactive uses the first subscription, and so on.
  The paradigm-specific implementation lives in ADR-029; this ADR specifies only that the trigger is
  uniform conceptually across paradigms.
- The relationship to ADR-029 is layered. ADR-028 says *what* the lifecycle is; ADR-029 says *how* it
  is implemented. A consumer reading just the contract (without the implementation) gets a complete
  conceptual model.
- The relationship to ADR-043 is consumption. ADR-043's update-routing rules read the lifecycle state
  this ADR defines. The reverse is not true: the lifecycle contract is meaningful regardless of whether
  any update mechanism exists at all.
- An earlier form of this ADR included the full update-propagation mechanism, the veto chain, the
  listener APIs, and the execution sequences for patches and removals. Those contents are now in
  ADR-043, leaving this ADR focused on the lifecycle contract proper. Readers consulting the ADR for
  the update-propagation rules find them at ADR-043; readers consulting it for the lifecycle contract
  itself find a small, self-contained specification.
