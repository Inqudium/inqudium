# ADR-035: Proxy integration architecture

**Status:** Proposed  
**Date:** 2026-05-05  
**Deciders:** Core team

## Context

The library applies resilience compositions to existing code through several integration technologies (see ADR-002).
One of those technologies is *dynamic proxy*: the application code interacts with an interface, and the library
transparently routes selected method calls on that interface through a resilience-stack.

This ADR specifies the proxy integration architecture as a normative reference for the implementation. The previous
proxy implementation, predating this ADR, served as a learning exercise; the architecture described here supersedes it
in full. The implementation following this ADR is built fresh against the specification, not as an incremental
modification of the previous code.

The proxy integration occupies a particular niche. It is framework-agnostic (no Spring, no AspectJ weaving), works at
runtime (no build-time bytecode generation), and yet provides annotation-driven, transparent interception. To make
this work cleanly, the architecture addresses several concerns: how synchronous and asynchronous methods coexist on
a single proxy, how correlation identifiers are carried, how exceptional cases (default methods, `Object` methods)
are handled, and how methods are dispatched efficiently per call.

The annotation model itself — which methods carry which annotations, how annotations are resolved, how class-level
and method-level annotations combine — is specified separately in ADR-036. This ADR references ADR-036 wherever the
annotation behaviour is relevant but does not repeat it.

## Decision

The proxy integration is built from a coherent set of design decisions, presented in the order in which they build
on each other.

### 1. Application model

The library exposes proxy construction as a method on `InqPipeline`:

```java
InqPipeline pipeline = InqPipeline.builder()
        .shield(bulkhead)         // named "orderBh"
        .shield(circuitBreaker)   // named "orderCb"
        .build();

OrderService service = pipeline.protect(OrderService.class, new DefaultOrderService(runtime));
```

The `protect` call returns a proxy that implements the `OrderService` interface. The proxy is the object the
application uses; the original `DefaultOrderService` implementation is held internally as the *real target* and is
invoked at the bottom of the resilience-stack for each protected call.

The pipeline is the *available* set of resilience elements. Which elements actually wrap a specific method, and in
what order, is determined by annotations on the implementation. The annotation evaluation follows ADR-036.

There is no separate factory class in the user-facing API. The choice between synchronous, asynchronous, or hybrid
dispatch is determined internally by the library based on the method signatures it encounters (per section 6) and
on the paradigm modules present on the user's classpath (per ADR-037). Users who need only synchronous dispatch
declare a dependency on `inqudium-pipeline`; users who need asynchronous dispatch additionally declare
`inqudium-imperative`. The `pipeline.protect(...)` API is identical in both cases.

### 2. JDK Dynamic Proxy as the proxy mechanism

The library uses the JDK's built-in `java.lang.reflect.Proxy` rather than a bytecode-generation library (ByteBuddy,
CGLib, ASM-based generators).

The JDK proxy mechanism imposes the constraint that proxies can only implement *interfaces*, not subclass concrete
types. The library accepts this constraint as aligning with sound architectural practice: it forces consumers to
program against interfaces, which is independently desirable.

Methods declared on `java.lang.Object` (`equals`, `hashCode`, `toString`, `wait`, `notify`, `notifyAll`,
`getClass`) are dispatched to the proxy along with interface methods. The JDK provides no special handling for
them — the invocation handler must classify and route them explicitly. This is addressed in section 8.

### 3. Three resolution phases

The proxy follows three resolution phases. The first two run at proxy-construction time; the third runs per call.

#### Phase 1 — annotation scanning (proxy-construction time)

When the factory's `protect(serviceInterface, target)` is called, the library scans the methods of the
implementation class according to the rules in ADR-036. For each method that the proxy will dispatch, the
evaluator determines:

- The annotation source method (per ADR-036 section 5: bridge methods skipped, generics handled).
- The set of resilience-element annotations that apply, after class-level and method-level inheritance (per
  ADR-036 section 6).
- Whether the method is *protected* (has resilience annotations after resolution), *pass-through* (no resilience
  annotations after resolution), or a *default method* on the interface that the implementation does not override
  (also pass-through; see ADR-036 section 7).
- For protected methods, the composition order resolved from `@InqShield(...)` (per ADR-036 section 3).

Validation runs eagerly. If an annotation references a name not present in the pipeline, or any other annotation-
related error is detected (per ADR-036 section 9), the factory throws at construction time, not at first
invocation.

#### Phase 2 — per-method materialisation (proxy-construction time)

For each protected method, the library builds a *folded layer-action chain* and stores it in a per-method cache
entry. The folded chain is a single `InternalExecutor` (sync) or `InternalAsyncExecutor` (async) lambda that, when
invoked, runs the entire selected resilience-stack around the real target's method.

The cache entry holds:

- The folded layer-action chain (the hot-path artefact).
- The list of layer descriptions in outermost-first order (for diagnostic rendering, addressed separately at a
  later time).
- The dispatch mode (sync or async, derived from the method's return type).

The folding is done once at construction time and never repeated. Per-call dispatch reads the folded chain from the
cache and invokes it with no additional allocation beyond what the chain itself demands.

The fold strategy is chosen for performance: a single composed lambda allows JIT escape analysis to inline the
entire chain. The alternative — building a `Wrapper` hierarchy per method — would allocate one wrapper instance per
layer per method and add an indirection per layer per call. Diagnostic introspection (hierarchy rendering and
similar) is provided through the layer-description list rather than through a wrapper hierarchy. This trade-off
prioritises hot-path performance over a uniform diagnostic surface across all integration technologies.

#### Phase 3 — dispatch (per-call)

When the application calls a method on the proxy, the JDK proxy mechanism delivers the call to the
`InvocationHandler`. The handler classifies the method:

- *`Object` method* → handled per section 8.
- *Default method on the interface, not overridden by the implementation* → invoked per section 7.
- *Pass-through method* → invoked directly on the real target, with no resilience processing.
- *Protected method* → looked up in the per-method cache; the folded chain is invoked. The handler supplies the
  `stackId` and a fresh `callId` to the chain (see section 6).

Dispatch is intentionally simple. All structural decisions are made in phase 1 and 2; phase 3 looks up and
executes.

### 4. `LayerAction` and `AsyncLayerAction` as the around-advice abstraction

The fundamental abstraction in the pipeline framework is the *layer action*: a piece of code that intercepts a call,
decides whether and how to invoke the next step, and returns a result. There are two parallel interfaces:

- `LayerAction<A, R>` — synchronous. Single abstract method:
  ```java
  R execute(long stackId, long callId, A argument, InternalExecutor<A, R> next)
  ```
- `AsyncLayerAction<A, R>` — asynchronous. Single abstract method:
  ```java
  CompletionStage<R> executeAsync(long stackId, long callId, A argument, InternalAsyncExecutor<A, R> next)
  ```

Both interfaces follow the around-advice contract: the implementation is given a reference to `next` and decides
when, whether, and how often to invoke it. Implementations may pre-process the argument, post-process the result,
catch exceptions, short-circuit (skip `next` entirely), retry (invoke `next` multiple times), or transform the
result.

The two interfaces are not interchangeable. The async variant has two semantic peculiarities the sync variant does
not have:

- **Two-phase execution.** Code before the `next.executeAsync(...)` call runs synchronously on the calling thread
  (typical use: acquire permits, start timers, set context). Code attached to the returned `CompletionStage` via
  `whenComplete` / `thenApply` runs asynchronously when the operation completes (typical use: release permits, stop
  timers, record metrics).
- **Decorated-copy contract.** Per ADR-023, the method must always return the decorated copy produced by
  `whenComplete()`, never the original `CompletionStage`. This ensures exceptions from the post-completion callback
  surface on the caller's future rather than disappearing on a detached branch.

The Generic parameters `<A, R>` represent the argument and return types flowing through the layer action. In typed
function-based usage (e.g. `BulkheadDecorator<String, Integer>` decorating a `Function<String, Integer>`), these
parameters carry concrete types throughout. In proxy usage they are erased to `<Void, Object>` because the JDK
proxy mechanism delivers methods through `Method.invoke(Object[])` and offers no compile-time type information at
the proxy boundary; the proxy stores layer actions as `LayerAction<Void, Object>` regardless of the typed signature
of the underlying resilience element. Layer-action implementations themselves do not know which use case applies —
the same `BulkheadDecorator` instance can serve a typed function decoration and an erased proxy dispatch
simultaneously.

A pass-through layer action is provided as a shared singleton via `LayerAction.passThrough()` and
`AsyncLayerAction.passThrough()`. The singleton forwards the call to `next` without any logic, implemented as an
`enum` to guarantee a single JVM-wide instance via the JVM class loader's thread-safe initialization.

### 5. `InqDecorator` and `InqAsyncDecorator` as element-with-metadata

Resilience elements (bulkheads, circuit breakers, retries, …) need more than a layer action. They need a name, a
type identifier (`InqElementType`), an event-publishing handle, and a set of factory methods that wrap standard
functional interfaces.

`InqDecorator<A, R> extends InqElement, LayerAction<A, R>` provides this combination. `InqElement` contributes the
metadata; `LayerAction` contributes the around-advice. `InqDecorator` provides default methods for
`decorateRunnable`, `decorateSupplier`, `decorateCallable`, `decorateFunction`, and `decorateJoinPoint` — these
delegate to the corresponding wrapper class with `this` as the action.

A concrete resilience element implements `InqDecorator<A, R>` and only needs to provide the abstract
`LayerAction.execute` method; everything else is inherited.

The async counterpart is `InqAsyncDecorator`, structurally analogous: it extends `InqElement` and `AsyncLayerAction`
and provides default `decorateXxxAsync` methods.

The choice of which action interfaces an element implements is per-element. A typical synchronous-and-asynchronous
bulkhead implements both `InqDecorator` and `InqAsyncDecorator`. A bulkhead specialised for Reactor types might
implement `InqDecorator` plus a future `InqReactorDecorator`, but not `InqAsyncDecorator`. The element controls
which dispatch modes it supports; the proxy infrastructure adapts to whatever the element exposes.

### 6. Hybrid dispatch and the `InvocationHandler` as carrier

A single service interface may mix synchronous methods (`Order findById(String id)`) with asynchronous ones
(`CompletionStage<Order> placeOrderAsync(Cart cart)`). The proxy dispatches each method through the appropriate
chain.

The dispatch mode for a method is determined at proxy-construction time (phase 2) from the method's return type:
methods returning `CompletionStage` are async; all others are sync. Each cache entry records its dispatch mode,
and phase-3 dispatch invokes the matching chain (`InternalExecutor` or `InternalAsyncExecutor`).

The recognition of the dispatch mode and the routing to the corresponding dispatcher follow the rules of
ADR-037. Recognition predicates live in `inqudium-pipeline` (the module that owns the pipeline and the proxy
construction); paradigm-specific dispatchers live in their own modules (`inqudium-imperative` for async, future
`inqudium-reactor` for reactive). The recognition uses class-literal references for JDK types
(`CompletionStage`) and string-based hierarchy walks for external types (`Mono`); the dispatcher is selected
through a hard-wired branch chain that prevents eager loading of unused paradigm classes.

For the hybrid case to work, every resilience element annotated on an async method must implement
`InqAsyncDecorator`. The library validates this at construction time: if a method's return type is `CompletionStage`
and any of its referenced pipeline elements does not implement `InqAsyncDecorator`, proxy construction fails.

The architecture is open to additional dispatch modes via additional return-type recognitions and
correspondingly added paradigm modules (per ADR-037). A future Reactor-aware extension would recognise
`Mono`/`Flux` return types and produce reactive cache entries; phase 3 would dispatch through reactive
subscription chains. The protocol for adding such an extension is treated in ADR-037.

#### `InvocationHandler` as carrier of `stackId` and `callId` source

ADR-034 introduces the correlation identifiers `stackId` and `callId`. Each call through a resilience-stack carries
both: the `callId` distinguishes calls within a stack; the `stackId` identifies the stack itself, making the pair
`(stackId, callId)` globally unique.

In the proxy architecture, the carrier of `stackId` and the `callId` source is the `InvocationHandler` instance —
that is, one carrier per proxy. All methods on the proxy share the same `stackId`, regardless of whether they
trigger different protected resilience-stacks (different per-method cache entries). The `stackId` identifies the
proxy instance, not the method.

This is a structural difference to the Spring AOP carrier model, where the aspect is a singleton bean and the
carrier must be finer-grained (per-method cache entry) to avoid contention on the `callId` counter. In the proxy
model, contention is not a concern because each proxy carries its own counter; multiple proxies (one per `protect`
call) have independent counters.

The implication for log readers: a proxy with four annotated methods produces log entries with the same `stackId`
across all four. Correlation by `stackId` correlates within one proxy instance, not within one method. For
per-method correlation, log readers should use the method name in the log entry, not the `stackId`.

### 7. Default-method handling

When the proxy's invocation handler receives a default method that the implementation does not override, the
method is dispatched directly without involvement of the resilience-stack (per ADR-036 section 7).

The default-method invocation must execute on the proxy itself, not on the real target. This ensures that internal
calls from the default method to other interface methods (`this.someOtherMethod(...)`) flow through the proxy
machinery and receive their own resilience protection where applicable.

The Java standard library provides `InvocationHandler.invokeDefault(Object proxy, Method method, Object... args)`
since Java 16 for exactly this purpose. The implementation uses this method. It handles the JPMS module-boundary
concerns transparently — no `MethodHandles.privateLookupIn(...)` setup, no `Lookup.unreflectSpecial(...)`
acrobatics, no module-opening directives required from application authors.

When the implementation overrides the default method, the overriding method is treated as a normal implementation
method and processed per the standard rules (per ADR-036 section 7). The dispatch behaviour follows phase 3's
classification logic for protected or pass-through methods, exactly as for any other implementation method.

The visibility and self-invocation considerations of ADR-036 section 8 apply to the proxy as the canonical
example: only public interface methods reach the proxy, and self-invocation from within the implementation
bypasses the proxy.

### 8. `Object`-method handling

`Object`-methods on the proxy follow these rules:

- `equals` — proxies compare equal if and only if they are JDK proxies with `InvocationHandler` instances of the
  same concrete type that hold equal real targets. A proxy is never equal to a bare (unwrapped) object, preserving
  the symmetry contract of `Object.equals`.
- `hashCode` — delegates to the real target.
- `toString` — returns a descriptive representation including the proxy's name and the real target's `toString`.
- `wait` / `notify` / `notifyAll` — invoked on the proxy itself, not on the handler. Callers synchronise on the
  proxy reference; thread-coordination primitives must operate on that reference.
- `getClass` — returns the proxy's class, not the handler's class.

These methods do not run through the resilience-stack and are not subject to the annotation model.

### 9. Proxy stacking

Proxy stacking — wrapping one proxy in another — is supported as a structural composition mechanism. The outer
proxy treats the inner proxy as its real target; calls to the outer proxy are dispatched normally and delegate to
the inner proxy at the chain's bottom; the inner proxy, in turn, dispatches its own resilience-stack.

Stacking is *not* optimised. Each proxy in the stack performs its own dispatch independently, and each call
re-enters the JDK proxy machinery once per layer. The architecture does not include a chain-walk optimisation that
bypasses inner-proxy re-entry.

The previous proxy implementation included such an optimisation (linking type-compatible dispatch components across
the layer boundary). The new architecture intentionally omits this optimisation: its complexity is not justified by
the rarity of stacked-proxy use cases in annotation-driven configurations, where users compose resilience by listing
all desired elements in one pipeline rather than by stacking proxies.

If stacked-proxy performance becomes a concrete bottleneck in practice, the optimisation can be revisited. The
architecture leaves the door open without implementing it speculatively.

### 10. Exception classification

When an exception escapes the resilience-stack, two distinct paths exist depending on whether the dispatch is
synchronous or asynchronous. The classification rules apply to one of them; the other is left to standard
`CompletionStage` conventions.

#### Synchronous dispatch

When an exception reaches the proxy boundary synchronously — that is, it is thrown out of the
`InvocationHandler.invoke` method on the calling thread — the proxy classifies it before propagating to the
application:

- **`RuntimeException`** — propagates directly.
- **`Error`** — propagates directly.
- **Checked exception declared in the method's `throws` clause** — propagates directly, preserving the original
  type.
- **Checked exception not declared in the method's `throws` clause** — wrapped in `InqUndeclaredCheckedException`,
  a library-specific exception type that extends `java.lang.reflect.UndeclaredThrowableException`. The `Method`
  object that was being invoked is included as a property on the wrapper, alongside the cause, to aid diagnosis.

The library-specific exception type allows generic catch clauses for `UndeclaredThrowableException` (the JDK proxy
mechanism's own wrapper) to also catch the library's wrapper, while the specific type aids stack-trace diagnosis
when the application narrows its catch clauses.

Reflection-related exception unwrapping (`UndeclaredThrowableException`, `InvocationTargetException`) happens
before classification: the cause is iteratively unwrapped to expose the real exception, then the rules above are
applied.

#### Asynchronous dispatch

When the dispatched method returns a `CompletionStage` and the resilience-stack completes successfully (i.e. the
returned Stage is delivered to the caller without a synchronous throw), any subsequent failure is propagated
asynchronously through the standard `CompletionStage` mechanism. The proxy does not intercept, classify, or
rewrap such failures. The Stage carries whatever exception the resilience-stack or the real target produced,
typically wrapped in `CompletionException` per the JDK convention.

Application code is expected to handle asynchronous failures using the `CompletionStage` API (`whenComplete`,
`exceptionally`, `handle`, `thenCompose`, etc.) per JDK conventions. The classification rules above are not
applied; an undeclared checked exception delivered through a failed Stage is *not* wrapped in
`InqUndeclaredCheckedException`.

This is a deliberate boundary. Asynchronous failures occur outside the proxy boundary in time — the calling thread
has already received the Stage and moved on. Reclassifying failures at completion time would require attaching
post-completion handlers to every dispatched Stage, an overhead unjustified for a behaviour that diverges from
JDK conventions and would surprise application authors.

If the dispatched method's signature declares `CompletionStage` as its return type but the resilience-stack throws
synchronously before producing a Stage (for instance, a permit-acquire fails before the asynchronous operation
starts), the synchronous classification rules apply. In practice, methods returning `CompletionStage` rarely
declare checked exceptions on their signature, so most synchronous failures on async dispatch will be unchecked
and propagate directly without wrapping.

### 11. Implementation requirements

The implementation must satisfy the following non-functional requirements without prescribing a specific approach:

- **Hot-path performance.** Per-call dispatch on protected methods must avoid the per-call costs that would
  ordinarily accompany reflective method invocation: arity-switching at the call site, repeated method lookup,
  redundant access checks. The implementation chooses the mechanism (cached `MethodHandle`s, cached arity-
  specialised invokers, direct `Method.invoke`, or a combination), guided by benchmarks against representative
  workloads. The architecture imposes no specific mechanism, only the result. In particular, the implementation
  is free to rely on the improvements to `Method.invoke` introduced in recent JDK releases if benchmarks confirm
  the performance is sufficient.
- **Thread-safe construction.** The per-method cache is populated during phase 2, before the proxy is published to
  application code. Once published, the cache is immutable. No thread-safety mechanism is required during dispatch.
- **Bounded cache lifetime.** The per-method cache is held by the `InvocationHandler` instance and lives as long as
  that handler does. When the proxy and its handler become unreachable, the cache is collected with them. No
  global cache is permitted; each proxy holds its own cache.
- **Construction-time validation.** All errors that can be detected from the configuration (missing pipeline
  elements, unsupported method signatures, malformed annotations) must be raised during phase 1, before the proxy
  is returned to the caller.
- **Null arguments.** The JDK proxy mechanism delivers `null` for the argument array of zero-argument methods, not
  an empty array. The implementation must normalise this consistently so that downstream code does not need to
  distinguish the two cases.

### 12. Out of scope

The following capabilities are explicitly not supported:

- **Serialisation.** Proxies created by this library are not `Serializable`. Resilience-stacks are runtime
  constructs whose lifecycle is bound to the JVM; serialising a proxy across JVM boundaries has no defined
  semantics. Applications that need to transmit service references should serialise the underlying domain objects
  and reconstruct the resilience-stack on the receiving side.
- **Concrete-class proxying.** The library proxies only interfaces. Service implementations must be programmed
  against an interface. This is a constraint imposed by the JDK proxy mechanism, accepted as aligning with
  general architectural good practice.

## Consequences

**Positive:**

- The annotation-driven dispatch model gives application authors fine-grained control over which methods are
  protected, with the full annotation semantics specified separately in ADR-036.
- The phase-2 folding produces a hot-path artefact (a single composed lambda per protected method) that is
  amenable to JIT inlining. Dispatch overhead is bounded.
- Pass-through methods incur no resilience-stack cost. Application code is free to mix protected and unprotected
  methods on the same service interface without paying for unused infrastructure.
- The hybrid sync/async dispatch is built into the architecture rather than added as a configuration mode. A
  service interface that mixes synchronous and asynchronous methods is the general case, not a special case.
- Default-method handling via `InvocationHandler.invokeDefault` (Java 16+) is JPMS-safe and requires no
  module-opening from application authors. The previous proxy implementation's `MethodHandles.privateLookupIn`
  acrobatics are replaced by a one-line standard call.
- Correlation IDs (ADR-034) follow naturally from the carrier model: one `stackId` per proxy, partitioned `callId`
  source per proxy, no contention.

**Negative:**

- Stacked proxies are not optimised. Applications that benefit from stacking — for instance, layering a metrics
  proxy over a resilience proxy — pay the full cost of nested proxy machinery. The trade-off is accepted because
  the optimisation's complexity outweighs its benefit in expected use cases.
- `Wrapper`-style introspection of the resilience-stack (for diagnostic rendering, similar to the previous
  architecture's hierarchy rendering) is not supplied as a uniform method on every proxy. The layer-description
  list is available in the cache entries but reaching it requires a deliberate diagnostic API rather than a casual
  cast. This is treated as a follow-up concern.
- The minimum supported JDK is Java 16 due to the dependence on `InvocationHandler.invokeDefault`. The library
  can document this as a baseline; application authors using older JDK versions must upgrade or use a different
  integration technology.
- Exception classification differs between synchronous and asynchronous dispatch. Synchronous exceptions are
  classified and possibly wrapped in `InqUndeclaredCheckedException`; asynchronous failures delivered through
  `CompletionStage` are not. Application authors who write generic exception-handling code that works for both
  paths must account for this asymmetry — typically by handling raw causes from `CompletionException` separately
  from synchronous classification.

**Neutral:**

- The relationship to `InqPipeline` (ADR-002) is unchanged in shape but reinterpreted in role. The pipeline is
  still a concrete composition of resilience elements; in the proxy context, that composition defines the universe
  of available protections rather than the protection applied uniformly to all methods. The detailed relationship
  is specified in ADR-036.
- The relationship to ADR-023 (decorated-copy contract for `AsyncLayerAction`) is preserved. Async cache entries
  invoke the contract's required pattern.
- The relationship to ADR-034 (correlation identifiers) places the proxy's `InvocationHandler` as the carrier of
  `stackId` and `callId` source, consistent with ADR-034's heterogeneity table.
- The relationship to ADR-036 (annotation model) is foundational: the proxy is one of the integrations whose
  annotation evaluation ADR-036 specifies. Visibility and self-invocation considerations from ADR-036 section 8
  apply to the proxy as the canonical example.
- The relationship to ADR-037 (module topology) is realisational: ADR-037 specifies how the proxy's dispatch
  routing is laid out across modules and how optional dependencies for paradigm-specific dispatchers (async,
  future Reactor) are handled. The user-facing API (`pipeline.protect(...)`) is identical across all paradigm
  configurations; ADR-037 governs the module-level realisation that makes this single API work for varying
  classpath shapes.
