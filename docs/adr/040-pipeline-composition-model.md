# ADR-040: `InqPipeline` composition model

**Status:** Proposed  
**Date:** 2026-05-14  
**Deciders:** Core team  
**Related:** ADR-002 (functional decoration API), ADR-036 (annotation model),
ADR-037 (module topology), ADR-041 (composition ordering — to follow),
ADR-042 (pipeline contracts — to follow).

## Context

`InqPipeline` is one of the central abstractions in the library. It appears in nearly every user-facing example:
the user constructs a pipeline, adds resilience elements to it, and applies it to a target through one of the
`protect(...)` overloads (per ADR-037).

But what `InqPipeline` actually *is* — as a data structure, with its construction rules, its invariants, its
accessors — is not specified centrally. The relevant information lives scattered across several ADRs:
ADR-002 introduces the functional decoration API and gestures at composition; ADR-017 covers ordering;
ADR-036 mentions the rule that a pipeline carries at most one element per type; ADR-037 places `InqPipeline`
as an interface with default methods.

Each of these ADRs has a different focus, and none of them defines the pipeline as a composition primitive in
its own right. A new contributor reading the codebase has to assemble the picture from fragments.

This ADR consolidates the composition model into one normative reference. It does not specify ordering (that
is ADR-041), does not specify pipeline contracts like `LayerAction` and `InqDecorator` (that is ADR-042),
does not specify annotations (that is ADR-036), and does not specify the module-level realisation (that is
ADR-037). Its scope is the composition primitive itself: what an `InqPipeline` is, how it is built, what
guarantees it carries.

## Decision

### 1. `InqPipeline` as an interface

`InqPipeline` is a Java interface declared in the `inqudium-pipeline` module (per ADR-037). It is not a
concrete class. Concrete implementations are produced by the builder (section 3) and consumed through the
interface contract.

Choosing an interface over a class has three practical consequences:

- **Multiple implementations may coexist.** The default builder produces one implementation; integration
  modules may wrap a pipeline in additional behaviour (for instance, a diagnostic wrapper that records all
  applied elements) by implementing the same interface. None of this is the responsibility of the present
  ADR, but the interface form leaves the door open.
- **The interface is the API surface.** User code references `InqPipeline`, not a specific implementation
  class. Refactoring the implementation does not break user code.
- **Default methods on the interface carry the `protect(...)` overloads** specified by ADR-037. The
  composition model and the integration dispatch share the same surface type, which keeps the user-facing
  API uniform across integrations.

### 2. What an `InqPipeline` contains

An `InqPipeline` is a finite, ordered collection of *resilience elements*. A resilience element is anything
that implements the appropriate pipeline contracts (specified by ADR-042); the canonical examples are
bulkheads, circuit breakers, retries, time limiters, rate limiters, and traffic shapers.

The pipeline does not own its elements in the lifecycle sense. The elements are constructed and managed
elsewhere (typically through their respective registries or factory APIs); the pipeline holds *references*
to them. Multiple pipelines may share references to the same element; removing an element from one pipeline
does not affect any other pipeline.

The element list has three structural properties that this ADR fixes as invariants. The next section
specifies them.

### 3. Invariants

Every `InqPipeline` instance, once built, satisfies the following invariants. Violations are detected at
build time and raise a build-time exception; no `InqPipeline` instance exists in a state that violates them.

**Invariant 1 — non-empty.** A pipeline contains at least one element. A zero-element pipeline has no
defined behaviour: the `protect(...)` call would have to either return the target unchanged (which is
indistinguishable from not protecting at all) or raise an error at every call. Neither is useful. The
builder rejects `build()` if no element has been added.

**Invariant 2 — at most one element per `(InqElementType, ParadigmTag, Name)` triple.** A pipeline may
contain multiple elements of the same type, provided they differ in paradigm or name. The combination of
all three coordinates must be unique.

This rule supports three orthogonal use cases:

- **Multiple paradigms in one service.** A service interface may mix synchronous, asynchronous, and reactive
  methods. Each paradigm has its own world of pipeline contracts (per ADR-029) and typically its own
  per-paradigm component implementation. A pipeline serving such a mixed-paradigm service needs one bulkhead
  per active paradigm — an imperative bulkhead for the `Order findById(...)` method, a reactive bulkhead for
  the `Mono<Order> findByIdReactive(...)` method, and so on. Forbidding multiple bulkheads in a pipeline
  would make this impossible.

- **Different configurations within one paradigm.** Two methods on the same interface may need different
  bulkhead settings — e.g. a `write` operation with a tight concurrency limit and a `read` operation with a
  generous one. Annotations select named elements (`@InqBulkhead("writeBh")` vs. `@InqBulkhead("readBh")`).
  The pipeline must carry both for the annotation evaluator to resolve them.

- **Same name in different paradigms.** Reusing the canonical name `orderBh` across an imperative and a
  reactive bulkhead is a legitimate convenience — the operator thinks in terms of "the order bulkhead"
  without tracking which paradigm version is active for which method. The paradigm coordinate of the
  uniqueness rule lets that name reuse work.

The element type, paradigm tag, and name together form the element's identity within a pipeline. The
element type is determined by `InqElement.elementType()`; the paradigm tag is determined by the element's
paradigm-specific interface (e.g. an `InqDecorator<A, R>` implementer carries `ImperativeTag`; an
`InqReactorDecorator` implementer would carry `ReactiveTag`); the name is determined by
`InqElement.name()`. The builder validates the triple at `shield(...)` time; a second `shield(...)` call
with an element whose triple is already present raises an error.

The annotation evaluator (ADR-036) uses the same triple to resolve annotation references. When a method
annotated `@InqBulkhead("orderBh")` is evaluated, the evaluator selects from the pipeline the bulkhead
named `"orderBh"` whose paradigm tag matches the method's dispatch paradigm. If no element matches, or if
the matching element's paradigm-specific contract is incompatible with the method's signature (e.g. the
named bulkhead does not implement `InqAsyncDecorator` for an async method), proxy construction fails per
ADR-036 phase 1. Mismatches are surfaced eagerly, not at first invocation.

**Invariant 3 — immutable after build.** Once `InqPipelineBuilder.build()` returns, the resulting pipeline
is structurally frozen. The element list cannot be extended, replaced, or reordered through any operation
on the pipeline instance. Updates to the configuration of individual elements happen through the elements'
own update mechanisms (per ADR-028); they do not modify the pipeline's composition.

Immutability is structural, not transitive. The elements themselves remain mutable through their own
update paths — a bulkhead in a pipeline can still have its `maxConcurrentCalls` adjusted. The pipeline's
immutability concerns only its element list and the relationship to those elements.

### 4. Construction via `InqPipelineBuilder`

`InqPipeline` instances are constructed exclusively through the builder. There is no public constructor on
any `InqPipeline` implementation; user code uses the builder or one of the entry points specified by other
ADRs (for instance, future factory methods that pre-configure a pipeline from a runtime).

The builder API:

```java
InqPipeline pipeline = InqPipeline.builder()
        .shield(bulkhead)         // adds a bulkhead element
        .shield(circuitBreaker)   // adds a circuit breaker element
        .shield(retry)            // adds a retry element
        .build();
```

The builder's `shield(...)` method accepts any object that implements the relevant pipeline contracts (per
ADR-042). The method validates invariant 2 (no duplicate element types) eagerly: a second `shield(...)`
call with an element of an already-added type fails immediately with a descriptive error.

The order in which `shield(...)` calls are made does not determine the order in which elements are
composed when the pipeline is applied. That ordering is governed by ADR-041 — the pipeline reorganises the
elements according to the configured ordering strategy. The builder's `shield(...)` order is purely a
collection-building order.

The builder is single-use. `build()` may be called at most once; after it returns, the builder is
exhausted, and any further `shield(...)` or `build()` call raises an error. This makes pipelines safely
shareable between threads: a fully-built pipeline can be passed across threads without synchronisation,
because its structure cannot change after publication.

### 5. The `elements()` accessor

`InqPipeline.elements()` returns the element list in *canonical composition order* — that is, the order in
which the elements will be applied when the pipeline wraps a target. This order is determined by the
ordering strategy specified in ADR-041, not by the builder's `shield(...)` call order.

The returned list is unmodifiable. Callers cannot mutate the pipeline through it. Repeated calls to
`elements()` on the same pipeline return lists of the same length and content, in the same order.

```java
List<InqElement> elements = pipeline.elements();
// elements is unmodifiable; pipeline structure is preserved.
```

`elements()` is the primary introspection mechanism for the pipeline's composition. Diagnostic tooling
(per ADR-039), debugging, and educational examples all use it to display what a pipeline contains.

### 6. The relation to integration dispatch

The pipeline carries `protect(...)` overloads via default methods on the interface (per ADR-037). These
default methods route the pipeline to the appropriate integration dispatcher — functional decoration for
`protect(Supplier<T>)`, proxy for `protect(Class<T>, T)`, and so on.

The composition model specified here is independent of which `protect(...)` overload is invoked. The
pipeline's structure, invariants, and `elements()` accessor are the same regardless of how the pipeline is
applied. The integration dispatchers consume the pipeline through the same interface that user code uses.

## Consequences

**Positive:**

- The composition model has a single normative location. New contributors can read one ADR to understand
  what `InqPipeline` is structurally, rather than assembling the picture from ADR-002, ADR-017, ADR-036,
  and ADR-037.
- The invariants are stated explicitly and enforced at build time. Pipelines never exist in malformed
  states.
- The builder pattern with `shield(...)` and `build()` is a familiar idiom. User code that constructs
  pipelines reads cleanly without surprising semantics.
- Immutability after `build()` makes pipelines safe to share across threads without locking. Concurrent
  reads of the same pipeline produce identical results.
- Separation between collection-order (builder calls) and composition-order (after `build()`, per ADR-041)
  is explicit. User code does not need to keep track of which `shield(...)` call came first.

**Negative:**

- The uniqueness rule operates on the `(type, paradigm, name)` triple, not on the type alone. Two elements
  that share all three coordinates collide; two elements that differ in any one coordinate coexist. This is
  more permissive than a type-level invariant but more restrictive than free composition. The cost is a
  more complex rule to communicate; the benefit is supporting three real use cases (multi-paradigm
  services, configuration variation within a paradigm, name reuse across paradigms) that a stricter
  invariant would forbid.
- The builder is single-use. Users who want to create several pipelines with the same composition must
  rebuild from scratch for each. This is a minor inconvenience that can be addressed by a future
  convenience layer (e.g. a `PipelineTemplate` that produces builders), without changing this ADR.
- The pipeline does not own its elements' lifecycles. Users must understand that removing an element from
  a registry while a pipeline still references it leaves the pipeline holding a reference to a removed
  component. ADR-028 specifies how component removal interacts with references; the pipeline plays no
  special role in that mechanism.

**Neutral:**

- The relation to ADR-002 (functional decoration) is preserved. ADR-002 introduced the idea of composing
  elements as a decoration chain; this ADR makes that composition primitive a first-class type with
  invariants.
- The relation to ADR-036 (annotation model) is preserved. ADR-036 specifies how annotations select
  elements from a pipeline; the pipeline-as-universe rule lives there. This ADR specifies what the
  pipeline is, ADR-036 specifies how it is consulted.
- The relation to ADR-041 (ordering, to follow) is sequential. ADR-040 specifies the structure; ADR-041
  specifies the ordering that determines `elements()`'s return order. The two are independent: a pipeline
  with a different ordering strategy is structurally still an `InqPipeline` per this ADR.
- The relation to ADR-042 (pipeline contracts, to follow) is delegated. ADR-040 references the contracts
  that an element must implement to be `shield(...)`-able; ADR-042 specifies what those contracts are.
- The relation to ADR-037 (module topology) is locational. ADR-040 says `InqPipeline` is an interface;
  ADR-037 says the interface lives in `inqudium-pipeline`.
