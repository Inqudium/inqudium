# ADR-042: Pipeline contracts

**Status:** Accepted  
**Date:** 2026-05-17  
**Deciders:** Core team  
**Related:** ADR-033 (Bulkhead lifecycle integration — the rules consolidated here originated there),
ADR-029 (lifecycle implementation pattern — components that follow it use the contracts specified here),
ADR-040 (`InqPipeline` composition model — `shield(...)` accepts implementations of these contracts),
ADR-041 (pipeline composition ordering).

## Implementation status

**Accepted.** The contracts specified by this ADR — the layer-
action / layer-terminal hierarchy and the per-paradigm decorator
interfaces — are implemented:

- `eu.inqudium.core.pipeline.LayerAction` / `LayerTerminal` — the
  synchronous contracts (in `inqudium-core`)
- `eu.inqudium.core.pipeline.InqDecorator` — the synchronous
  paradigm interface (extends `LayerAction`)
- `eu.inqudium.imperative.core.pipeline.AsyncLayerAction` /
  `AsyncLayerTerminal` — the asynchronous contracts (in
  `inqudium-imperative`)
- `eu.inqudium.imperative.core.pipeline.InqAsyncDecorator` — the
  async paradigm interface (extends `AsyncLayerAction`)

The proxy's `SyncParadigmValidator` and `AsyncParadigmValidator`
enforce that every element resolved for a service method
implements the corresponding paradigm interface (ADR-035 §6).

## Context

A pipeline (per ADR-040) is a collection of resilience elements that participate in a composed call chain.
For an element to participate, it has to expose a set of behaviours: how it identifies itself, how it
publishes events, how it accepts a delegated call and decides what to do with it. Without a uniform contract
surface, the pipeline mechanism cannot dispatch through the chain consistently — every element would have
its own protocol, and the pipeline would devolve into a switch over element types.

The library establishes a small set of pipeline contracts that every element implements. The contracts have
existed in the codebase for a long time, but their specification is scattered: `LayerAction<A, R>` lives in
`inqudium-core`, `InqElement` lives nearby, the asynchronous variants live in `inqudium-imperative`, and
the rules describing how concrete components compose these contracts were last specified in ADR-033 —
mixed with bulkhead-specific decisions.

This ADR consolidates the general pipeline-contract rules into one place. It says what `LayerAction`,
`InqElement`, `InqDecorator`, and `InqAsyncDecorator` are; how they relate to each other; and where they
must sit in a concrete component's class hierarchy. The bulkhead-specific applications of these rules
(ADR-033's Rules 4, 5, 7) remain in ADR-033 and will be revisited when the bulkhead area is consolidated
separately.

A note on history. ADR-033 originally specified four general rules (numbered 1, 2, 3, 6 in that ADR). The
content of those rules is reproduced here, normalised against the current state of the codebase, and
slightly expanded where the original was telegraphic. ADR-033 will be updated to reference this ADR for
the general rules once the bulkhead consolidation is undertaken; until then, the two documents carry the
general rules in parallel, with the same meaning.

## Decision

### 1. `LayerAction<A, R>` is the single source of `execute(...)`

The `LayerAction<A, R>` interface in `inqudium-core` declares the around-advice contract:

```java
public interface LayerAction<A, R> {

    /**
     * Execute one layer of the pipeline. The layer may inspect or transform the argument,
     * call the next layer via {@code next.execute(stackId, callId, argument)}, observe the
     * result or any exception, and return a (possibly transformed) value.
     *
     * @param stackId    correlation identifier for the resilience-stack (ADR-034)
     * @param callId     per-call counter within the stack
     * @param argument   the call's argument
     * @param next       the next layer in the chain
     */
    R execute(long stackId, long callId, A argument, LayerTerminal<A, R> next);
}
```

The chain terminator — the innermost step that runs the actual target call — is a separate, narrower
interface:

```java
public interface LayerTerminal<A, R> {

    R execute(long stackId, long callId, A argument);
}
```

`LayerAction` and `LayerTerminal` deliberately have different signatures. A middle-of-chain layer needs to
delegate to the rest of the chain, so its `execute(...)` carries a `next` parameter. The terminator has
nothing further to delegate to — it *is* the chain's end — so it has no `next` parameter. Making `next`
itself a `LayerAction` would force every middle layer to supply a `next`-for-its-next argument that has
no meaningful value in the around-advice pattern; making the terminator a `LayerAction` that ignores its
`next` argument would violate the contract that the parameter exists for a reason. The asymmetry
expresses the chain's actual structure: middle layers and the terminator have different roles, and the
type system encodes that difference.

The naming reflects the two roles: both types share the `Layer` prefix to signal their structural
membership in the same family; `LayerAction` is the *action* a middle layer performs;
`LayerTerminal` is the *terminator* that ends the chain.

This signature shape — `LayerAction` declaring `execute(...)` with a `LayerTerminal` parameter — is the
only legitimate place where `execute(...)` is declared in the synchronous pipeline. Other code that needs
the same shape — a phase class, a component base class, a wrapper — inherits the method from
`LayerAction<A, R>` rather than declaring its own `execute(...)`. Multiple parallel declarations of
`execute(...)` in unrelated interfaces would fragment the contract, force consumers to disambiguate
between them, and make refactoring expensive.

The asynchronous counterpart is `AsyncLayerAction<A, R>` in `inqudium-imperative`, with the same
single-source rule and the analogous `executeAsync(...)` signature and terminator:

```java
public interface AsyncLayerAction<A, R> {

    CompletionStage<R> executeAsync(
            long stackId, long callId, A argument, AsyncLayerTerminal<A, R> next);
}

public interface AsyncLayerTerminal<A, R> {

    CompletionStage<R> executeAsync(long stackId, long callId, A argument);
}
```

The two layer-action interfaces (`LayerAction`, `AsyncLayerAction`) are deliberately separate. A component
that supports both sync and async dispatch implements both interfaces; a component that supports only one
implements only one. There is no super-type that combines them, because forcing components into a combined
contract would make the async-only or sync-only cases declare methods they do not implement.

### 2. `InqElement` with record-style accessors

`InqElement` is the identity-and-metadata contract carried by every pipeline element:

```java
public interface InqElement {

    /** The element's name within its registry. Stable across the element's lifetime. */
    String name();

    /** The element's category. Used by the pipeline ordering and the annotation evaluator. */
    InqElementType elementType();

    /** The element's event publisher. Per-element, not shared with other elements. */
    InqEventPublisher eventPublisher();
}
```

Three accessors, all in record style (`name()`, not `getName()`). The record style aligns with the rest of
the codebase: snapshots (`BulkheadSnapshot`, `GeneralSnapshot`), handles (`BulkheadHandle`), and
configuration records all use record-style accessors. Mixing styles between `InqElement` and adjacent
types would force components that implement both an element interface and a snapshot or handle interface
to expose two differently-named methods for the same semantic property — for example, `name()` from one
source and `getName()` from another, both returning the same string.

The record-style convention is therefore not stylistic preference but coherence with the surrounding
type system. The accessor style on interfaces matches the style on records, and the style on records
follows from the Java records feature itself.

### 3. `InqDecorator` and `InqAsyncDecorator` extend `InqElement` and the layer-action contract

The decorator contracts are the user-facing surface of a pipeline element. A decorator is *named, reusable,
and identity-bearing*: it has a registry name (inherited from `InqElement`), a category (`elementType()`),
and an event publisher; it carries the `execute(...)` method (inherited from `LayerAction`) so that the
pipeline mechanism can drive a call through it; and it provides default helper methods for wrapping
suppliers, runnables, and functions:

```java
public interface InqDecorator<A, R> extends InqElement, LayerAction<A, R> {

    default Supplier<R> decorateSupplier(Supplier<R> supplier) { ... }
    default Runnable decorateRunnable(Runnable runnable) { ... }
    default Function<A, R> decorateFunction(Function<A, R> function) { ... }
}
```

The asynchronous counterpart:

```java
public interface InqAsyncDecorator<A, R> extends InqElement, AsyncLayerAction<A, R> {

    default Supplier<CompletionStage<R>> decorateAsyncSupplier(Supplier<CompletionStage<R>> supplier) { ... }
    default Function<A, CompletionStage<R>> decorateAsyncFunction(
            Function<A, CompletionStage<R>> function) { ... }
}
```

Both contracts inherit `InqElement` directly. This combination — name, type, publisher, layer action,
helper methods — is what makes an object a *pipeline-grade decorator* rather than a bare layer-action
lambda. The pipeline builder (per ADR-040) accepts implementations of these contracts; bare
`LayerAction` lambdas are not pipeline-grade because they lack identity.

The library does not provide a one-shot executor surface — there is no `InqExecutor` or `InqAsyncExecutor`
interface offering `executeSupplier(...)`-style methods that combine wrapping and immediate invocation. An
earlier version of the codebase carried such surfaces; they duplicated the decorator surface
(`bulkhead.executeSupplier(s)` was equivalent to `bulkhead.decorateSupplier(s).get()`) and introduced an
additional path through which call-id allocation could happen. They have been removed. Users who want to
invoke a decorated function immediately call `.get()` on the decorated `Supplier`, or `.apply(arg)` on the
decorated `Function`. The decorator surface is the only call surface for pipeline elements.

### 4. Concrete components carry the pipeline contracts; lifecycle base classes do not

Concrete component classes — `InqBulkhead`, future `InqCircuitBreaker`, `InqRetry`, `InqTimeLimiter`,
`InqRateLimiter`, `InqTrafficShaper` — implement the pipeline contracts (`InqElement`, `InqDecorator`, and
`InqAsyncDecorator` where applicable) directly. The contracts go on the concrete class declaration.

The per-paradigm lifecycle base classes (`ImperativeLifecyclePhasedComponent`,
`ReactiveLifecyclePhasedComponent`, etc., per ADR-029) do *not* carry the pipeline contracts. They are
implementation infrastructure: they house the cold/hot lifecycle machinery, the phase-reference field,
the listener registry, the snapshot subscription mechanism. They are paradigm-internal and not part of the
user-facing API.

The rationale for keeping the contracts off the base classes:

- **Each component declares which contracts it semantically supports.** A bulkhead supports both
  `InqDecorator` and `InqAsyncDecorator` because permits and concurrency limits apply equally to
  synchronous and asynchronous call patterns. A future time-limiter might support only
  `InqAsyncDecorator` (the synchronous form of "interrupt this call after N seconds" has different
  semantics than the asynchronous form and may need different design). Putting the contracts on the
  lifecycle base would force one-size-fits-all.

- **Mechanism and concept live at different layers.** The lifecycle base is a *mechanism* — shared
  scaffolding for phase transitions, listener storage, snapshot subscription. The pipeline contracts are
  *concepts* in the user-facing API — they describe what an element is from the outside. Conflating the
  two on one type mixes two different communication levels.

- **The boilerplate cost is small.** The decorator interfaces' default methods cover most of the surface;
  the concrete class needs to inherit `execute(...)` (which the lifecycle base provides via
  `LayerAction<A, R>`'s contract — see section 5) plus the three `InqElement` accessors. The remaining
  identity-and-publishing methods are typically a handful of one-liners.

A canonical declaration for a synchronous-and-asynchronous bulkhead:

```java
public final class InqBulkhead<A, R>
        extends ImperativeLifecyclePhasedComponent<BulkheadSnapshot, A, R>
        implements BulkheadHandle<ImperativeTag>,
                   InqDecorator<A, R>,
                   InqAsyncDecorator<A, R> {
    // The base class provides the execute(...) machinery via the phase reference;
    // the decorator interfaces' default methods cover the wrap-helper surface;
    // the bulkhead-specific identity and configuration accessors fill the rest.
}
```

The bulkhead-specific application of this rule (the explicit type parameters `<A, R>`, the relationship
between `BulkheadHandle` and `InqElement`, the deletion of legacy interfaces) lives in ADR-033 and will be
revisited when the bulkhead area is consolidated.

### 5. Type-parameter propagation

The pipeline contracts are generic in `<A, R>`. The same type parameters propagate uniformly through every
layer of a component's class hierarchy:

- `LayerAction<A, R>` declares the type-parameterised `execute(...)`.
- `InqDecorator<A, R>` (and `InqAsyncDecorator<A, R>`) inherit `<A, R>` from `LayerAction` /
  `AsyncLayerAction` and carry it through their helper methods.
- The lifecycle base class for a paradigm is parameterised by `<S, A, R>` (per ADR-029, where `S` is the
  snapshot type).
- The phase classes inside the lifecycle base are parameterised identically.
- The concrete component class is parameterised by `<A, R>` matching its decorator contracts.

There is no point in the hierarchy where `<A, R>` is dropped or replaced with a wildcard. A type-erased
declaration somewhere in the middle of the stack would force casts at the boundary and lose compile-time
safety; the framework's design propagates the parameters consistently from top to bottom.

The dominant access path — Spring AOP, AspectJ, dynamic proxies — operates type-erased at the proceed
boundary, so the generic parameters carry no overhead and require no developer attention in those paths.
The user writes a normal annotated service method; the proxy mechanism wraps it transparently. The
generic parameters earn their cost on the manual `decorateFunction(...)` path, where the caller's own
type information at the call site flows naturally into the decorated wrapper.

## Consequences

**Positive:**

- The pipeline contracts have a single normative specification. New components — third-party or future
  built-in — have a clear contract to satisfy: implement `InqElement`, implement the appropriate
  decorator contract(s), inherit the `execute(...)` shape from `LayerAction`. No discovery, no scattered
  documentation.
- Single-source `execute(...)` declaration prevents contract fragmentation. Refactoring the layer-action
  signature happens in one place; consumers inherit the change automatically.
- Record-style accessors on `InqElement` align with the surrounding type system (snapshots, handles).
  Components implementing multiple interfaces avoid the awkward `name()` vs. `getName()` drift.
- Separation between contracts (on the concrete component) and mechanism (on the lifecycle base) keeps
  the user-facing surface focused. A user reading the bulkhead class sees what bulkhead *is*; the
  scaffolding lives in the base class where it does not distract.
- Per-component contract selection lets components honestly declare what they support. A bulkhead supports
  sync and async; a hypothetical time-limiter might support only async. The interface set on the concrete
  class is the truth.
- Removal of the one-shot executor surface simplifies the contract count from four (`InqExecutor`,
  `InqDecorator`, `InqAsyncExecutor`, `InqAsyncDecorator`) to two (`InqDecorator`, `InqAsyncDecorator`).
  Users have one wrap-and-invoke idiom, not two equivalent ones.

**Negative:**

- The contracts are spread across modules: `LayerAction` and `InqElement` in `inqudium-core`,
  `AsyncLayerAction` in `inqudium-imperative`, `InqDecorator` and `InqAsyncDecorator` in the same modules
  respectively. New contributors have to learn which contract lives where. The trade-off is intentional
  — paradigm-specific contracts live with their paradigm — but the distribution adds initial learning
  overhead.
- Components that semantically support both sync and async paths implement two decorator contracts. The
  declaration is more verbose than a hypothetical combined contract would be, but the verbosity is honest:
  the component genuinely supports two call patterns.
- The general rules (sections 1–5) and the bulkhead-specific applications (ADR-033) live in separate
  documents. Until the bulkhead consolidation, a reader investigating bulkhead's pipeline integration has
  to consult both. After the bulkhead consolidation, ADR-033 will reference this ADR for the general
  rules.

**Neutral:**

- The relationship to ADR-029 (lifecycle implementation pattern) is layered: ADR-029 specifies the
  per-paradigm base classes and their phase mechanism; this ADR specifies the contracts that concrete
  components extending those base classes implement. The two ADRs describe complementary halves of a
  component's class hierarchy.
- The relationship to ADR-040 (composition model) is consumption: a pipeline accepts implementations of
  these contracts via `shield(...)`. The contracts define what *can* go into a pipeline; ADR-040 defines
  what a pipeline does with those things.
- The relationship to ADR-041 (ordering) is dispatcher-side: the ordering operates over `InqElement`'s
  `elementType()` accessor. A component that does not implement `InqElement` cannot be ordered, which is
  consistent with not being pipeline-grade.
- Future paradigms (reactive, RxJava, Kotlin coroutines) will introduce additional contracts paralleling
  `InqDecorator` / `InqAsyncDecorator` — for instance, a hypothetical `InqReactorDecorator` for
  `Mono`/`Flux` return types. The pattern established here scales: each paradigm contributes its own
  decorator contract; concrete components implement whichever contracts match their semantic capabilities.
- The chain-terminator types `LayerTerminal` and `AsyncLayerTerminal` introduced in this ADR replace the
  earlier names `InternalExecutor` and `InternalAsyncExecutor`. ADR-034 and ADR-035 still reference the
  earlier names in places (signatures of `execute(...)` and `executeAsync(...)`); those references must
  be updated to the new names as a small follow-up. The semantics are unchanged — only the type names
  evolve.
