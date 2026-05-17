# REFACTORING_PROXY_REWRITE.md

**Status:** Active
**Started:** 2026-05-16
**Plan owner:** review/planning Claude session
**Implementation:** local Claude Code session, sub-step at a time

This document is the plan for the from-scratch implementation of the
`inqudium-proxy` module mandated by ADR-035. It is a **parallel thread**;
the main `REFACTORING_BULKHEAD_LOGGING_AND_RUNTIME_CONFIG.md` work is
currently paused and is not affected by this refactor.

The document is deleted at the end of the refactor (sub-step 3.14). Its
audit trail then lives in Git history and the closed PRs.

## Context

ADR-035 specifies a new proxy architecture and mandates a fresh
implementation: *"The implementation following this ADR is built fresh
against the specification, not as an incremental modification of the
previous code."*

The current state of the reactor, captured in a pre-plan audit on
2026-05-16 (commit `b3e34c3`):

- **A legacy proxy exists** in `eu.inqudium.core.pipeline.proxy` (inside
  `inqudium-core`). Entry point `InqProxyFactory`, eight classes,
  consumed by `inqudium-imperative`, `inqudium-bulkhead-integration-proxy`
  and a few tests.
- **`InqPipeline` is a concrete `public final class`** in
  `eu.inqudium.core.pipeline`, with a nested `Builder`. It does not
  carry any `protect(...)` method. Its consumers span seven reactor
  modules.
- **No `inqudium-pipeline` Maven module exists yet.** ADR-037 specifies
  one but the topology is not realised.
- **No `Detection*` classes exist** anywhere in the reactor.
- **The annotation evaluator** (ADR-036) lives in
  `eu.inqudium:inqudium-annotation:0.7.0-SNAPSHOT` and accepts the
  legacy `eu.inqudium.core.pipeline.InqPipeline` as its constructor
  argument.

## Strategy

**Greenfield parallel — total separation.** The legacy proxy code in
`eu.inqudium.core.pipeline.proxy` is **not touched**. The legacy
`InqPipeline` class in `eu.inqudium.core.pipeline` is **also not
touched** — neither converted to an interface, nor migrated, nor
deprecated. Both pieces of legacy code continue to exist and serve
their existing consumers (Spring, AspectJ, function-based wrappers, the
bulkhead-integration examples) without any modification.

A **new `inqudium-pipeline` Maven module** is created alongside
`inqudium-core`. It contains a **new `InqPipeline` interface**
(`eu.inqudium.pipeline.InqPipeline`) per ADR-040, a new
`InqPipelineBuilder` per ADR-040 §4, and the detection classes per
ADR-037 §4. This new pipeline interface is what the new proxy module
consumes.

A **new `inqudium-proxy` Maven module** is created alongside, depending
on `inqudium-pipeline`. It implements ADR-035 in full.

Both worlds coexist at the end of this refactor. The legacy world is
removed in a future refactor; out of scope here.

### Annotation evaluator bridge

The annotation evaluator from ADR-036 is tied to the legacy `InqPipeline`
class. The new proxy needs the evaluator's logic but operates on the new
`InqPipeline` interface. To avoid duplicating ADR-036's logic, the new
`inqudium-pipeline` module provides a **bridge class
`InqPipelineAnnotationEvaluator`** that:

1. Accepts the new `eu.inqudium.pipeline.InqPipeline` as input.
2. Internally constructs a legacy `eu.inqudium.core.pipeline.InqPipeline`
   from the new pipeline's `elements()` via the legacy builder's
   `shieldAll(...)` method.
3. Calls the existing `AnnotationEvaluator.forPipeline(legacy).evaluate(...)`.
4. Returns the existing `EvaluationResult`.

This bridge is a **transitional hack**, localised in one class. It is
clearly documented as such in the Javadoc, lives in `inqudium-pipeline`
alongside other transitional pieces, and is removed when the legacy
`InqPipeline` is eventually dropped. The new proxy module never directly
constructs a legacy `InqPipeline`; the bridge is its only point of
contact with the legacy world.

### ADR promotion strategy

At the end of the refactor (sub-step 3.14):

| ADR | Final status | Justification |
|-----|--------------|---------------|
| ADR-035 (proxy architecture) | **Accepted** | New proxy implements it in full. |
| ADR-039 (uniform stack introspection) | **Accepted** | `ProxyStackAdapter` lands in 3.12. |
| ADR-040 (pipeline composition model) | **Accepted** | New `InqPipeline` interface satisfies it. |
| ADR-041 (composition ordering) | **Accepted** | New builder respects the ordering strategies. |
| ADR-042 (pipeline contracts) | **Accepted** | Consumed unchanged from `inqudium-core`. |
| ADR-037 (module topology) | **Proposed** (unchanged) | New `inqudium-pipeline` exists, but legacy consumers in `inqudium-core` still reference the legacy `InqPipeline`. Full topology not yet realised. Promoted by the future legacy-removal refactor. |

ADR-036 (annotation model) is already Accepted; this refactor consumes
it via the bridge described above and does not touch it.

## Reference documents

- `inqudium-proxy/docs/ARCHITECTURE.md` — committed by sub-step 3.4. The
  normative reference for package layout, type relationships, and
  dispatch flow inside `inqudium-proxy`. Out-of-band until 3.4.
- Pre-plan audit report (`audit-3.0-report.md`, not committed) — the
  inventory dated 2026-05-16 against commit `b3e34c3` that informed
  this plan.
- ADRs 035, 036, 037, 039, 040, 041, 042 — the normative specifications.

## Sub-steps

### 3.1 — Plan finalisation

Commit `REFACTORING_PROXY_REWRITE.md` to the repository root. No code
changes; no new modules. The plan document becomes the live planning
artefact for the refactor; sub-step reviews and completion-log updates
reference it.

### 3.2 — Create `inqudium-pipeline` module + `InqPipeline` interface + builder

Create a new Maven module `inqudium-pipeline` as a sibling of
`inqudium-core`. The module depends on `inqudium-core` only (compile
scope, non-optional). At this sub-step:

- `eu.inqudium.pipeline.InqPipeline` — new `public sealed interface`,
  per ADR-040. Public surface: `List<InqElement> elements()` plus a
  static `InqPipeline.builder()` factory returning
  `InqPipelineBuilder`. No `protect(...)` methods yet; those land in
  3.3 (proxy stub) and a future refactor (functional dispatch).
- `eu.inqudium.pipeline.InqPipelineBuilder` — new top-level
  `public final class`, per ADR-040 §4. Public surface: `shield(...)`,
  `shieldAll(...)`, `order(...)`, `build()`. **Single-use enforced**: a
  `built` flag set in `build()`, subsequent calls to `shield(...)` or
  `build()` raise `IllegalStateException`.
- Build-time invariants per ADR-040 §3: non-empty pipeline, at most one
  element per `(InqElementType, ParadigmTag, Name)` triple, immutable
  after `build()`. Violations raise descriptive exceptions at
  `build()` time.

Tests cover: builder happy path, single-use enforcement, invariant
violations, `elements()` returns an immutable list.

### 3.3 — `DetectionProxy` + `InqPipeline.protect(Class<T>, T)` stub + `InqPipelineAnnotationEvaluator` bridge

In the `inqudium-pipeline` module:

- `eu.inqudium.pipeline.DetectionProxy` — per ADR-037 §4. Probes for
  `eu.inqudium.proxy.ProxyDispatcher` via
  `Class.forName(name, false, loader)`. Always-loaded; returns `false`
  at this sub-step because `inqudium-proxy` does not yet exist.
- `default <T> T protect(Class<T> serviceInterface, T target)` on
  `InqPipeline` — per ADR-037 §3. Throws `IllegalStateException` with
  the descriptive ADR-037 message when `DetectionProxy.isPresent()`
  returns `false`. The real delegation to `ProxyDispatcher.protect(...)`
  is wired in 3.9 once `ProxyDispatcher` exists.
- `eu.inqudium.pipeline.InqPipelineAnnotationEvaluator` — the bridge
  described in §"Annotation evaluator bridge" above. Adds
  `inqudium-annotation` (the annotation-evaluator module) and
  `inqudium-core` (for the legacy `InqPipeline` constructor) as compile
  dependencies of `inqudium-pipeline`. Javadoc clearly marks the class
  as **transitional**.

Tests cover: `DetectionProxy.isPresent()` returns `false` when proxy
absent (always at this sub-step); `protect(Class<T>, T)` raises the
documented exception; bridge correctly forwards an empty pipeline and a
pipeline with one element to the evaluator.

### 3.4 — Create `inqudium-proxy` module skeleton + commit `ARCHITECTURE.md`

Create the new `inqudium-proxy` Maven module:

- `inqudium-proxy/pom.xml`: depends on `inqudium-core`,
  `inqudium-pipeline`, `inqudium-annotation` (the evaluator module) —
  all compile scope, non-optional. Plus `inqudium-imperative` as
  `<optional>true</optional>`.
- `inqudium-proxy/docs/ARCHITECTURE.md`: place the architecture
  document (attached to the prompt) verbatim. This document is the
  reference for sub-steps 3.5–3.13.
- Empty subpackage directories per ARCHITECTURE.md §4, each with a
  `package-info.java`.
- One module-presence placeholder test, removed in 3.9 when
  `ProxyDispatcher` arrives with real tests.

No production Java code beyond `package-info.java`.

### 3.5 — Foundation: invocation primitives, exception classification

In `inqudium-proxy`, the leaf-level pieces that have no dependency on
the rest of the proxy machinery:

- `InqUndeclaredCheckedException` (public, top-level).
- `invocation/MethodInvoker` (sealed strategy interface),
  `MethodHandleInvoker` (default), `ReflectiveInvoker` (fallback). JVM
  property `inqudium.proxy.invoker=mh|reflective` selects.
- `handler/ArgNormalizer` (null → empty array, per ADR-035 §11).
- `exception/ThrowableUnwrap` (recursive
  `InvocationTargetException` / `UndeclaredThrowableException`
  unwrapping).
- `exception/ExceptionClassifier` (ADR-035 §10 sync classification).

Each piece independently unit-tested. No integration with the rest of
the proxy.

### 3.6 — Dispatch entries part 1: sealed family + simple variants

In `inqudium-proxy`:

- `entries/MethodDispatchEntry` — sealed interface, initial permits
  `PassThroughEntry`, `DefaultMethodEntry`. Sub-steps 3.7, 3.10, 3.11
  add `SyncCacheEntry`, `ObjectMethodEntry`, `AsyncCacheEntry` to the
  `permits` clause in turn.
- `entries/PassThroughEntry` — single `MethodInvoker` call to the real
  target.
- `entries/DefaultMethodEntry` — delegates to
  `InvocationHandler.invokeDefault(...)` (Java 16+, per ADR-035 §7).

### 3.7 — Folding: `SyncChainFolder` + `FoldedSyncChain` + `SyncCacheEntry`

The correctness-sensitive piece. Per ARCHITECTURE.md §7.3:

- `folding/FoldedSyncChain` — `@FunctionalInterface` taking
  `(stackId, callId, Object[] args)`.
- `folding/SyncChainFolder` — recursive fold with closure-per-depth.
  The cast from storage typing `LayerAction<Void, Object>` to
  call-time typing `LayerAction<Object[], Object>` is contained in
  exactly this class and annotated with `@SuppressWarnings("unchecked")`.
  Storage vs. call-time typing rationale documented in the class's
  Javadoc with a back-pointer to ARCHITECTURE.md §7.3.
- `entries/SyncCacheEntry` — third permit of `MethodDispatchEntry`.

Tests must include a **retry-semantics correctness test**: a layer
that calls `next.execute(...)` multiple times must re-enter the inner
chain correctly each time. The closure-per-depth approach handles
this automatically; the test pins the behaviour so a future
"optimisation" cannot regress it. All test layer actions are plain
lambdas — no `InqBulkhead`, no `inqudium-imperative`.

### 3.8 — Construction (sync only): `ProxyBuilder` + collaborators

In `inqudium-proxy`:

- `construction/ElementResolver` — resolves element names from
  `MethodPlan.Decorated` to `InqElement` instances via
  `pipeline.elements()`.
- `construction/ParadigmValidator` — **sync half only** at this
  sub-step (requires `InqDecorator` for every resolved element). Async
  half lands in 3.11; the class must be structured so the
  `inqudium-imperative` reference stays off the sync loading path
  (split-class or polymorphic factory; implementation session decides).
- `construction/MethodDispatchEntryFactory` — the classification table
  from ARCHITECTURE.md §7: Object method? Default? PassThrough? Sync?
  Async is **rejected** at this sub-step
  (`UnsupportedOperationException` with "async support arrives in
  sub-step 3.11"). 3.11 replaces this.
- `construction/ProxyBuilder` — orchestrator. Calls
  `InqPipelineAnnotationEvaluator.evaluate(pipeline, iface, impl)` (the
  bridge from 3.3), runs classification, builds the
  `Map<Method, MethodDispatchEntry>`.

### 3.9 — Handler + `ProxyDispatcher` + wire `DetectionProxy` real delegation

In `inqudium-proxy`:

- `handler/PerProxyCache` — immutable
  `Map<Method, MethodDispatchEntry>`, built by `ProxyBuilder`.
- `handler/InqInvocationHandler` — `InvocationHandler` implementation,
  holds `stackId` and `callIdSource` per ADR-034.
- `ProxyDispatcher` — public, single static method
  `protect(InqPipeline, Class<T>, T)`.

In `inqudium-pipeline`:

- Replace the `IllegalStateException`-throwing body of
  `InqPipeline.protect(Class<T>, T)` from 3.3 with real delegation to
  `ProxyDispatcher.protect(...)`, gated on `DetectionProxy.isPresent()`.

The placeholder test from 3.4 is removed. First real end-to-end proxy
tests exercise sync flows via `pipeline.protect(...)`. Async methods on
a service interface still throw at construction (raised by
`MethodDispatchEntryFactory` per 3.8); 3.11 fixes that.

### 3.10 — Object methods: `ObjectMethodHandler` + `ObjectMethodEntry`

Per ADR-035 §8:

- `dispatch/ObjectMethodHandler` — equals symmetry, hashCode delegation,
  toString format, wait/notify on the proxy, getClass returns the
  proxy class.
- `entries/ObjectMethodEntry` — fourth permit; dispatch tag
  `Kind { EQUALS, HASH_CODE, TO_STRING, WAIT, NOTIFY, NOTIFY_ALL, GET_CLASS }`
  avoids per-call string comparison.
- `MethodDispatchEntryFactory` routes `Object`-declared methods to
  `ObjectMethodEntry`.

### 3.11 — Async dispatch path

In `inqudium-proxy`, under the class-loading discipline of ADR-037 §6:

- `dispatch/DetectionAsync` — probes for `inqudium-imperative` presence.
- `folding/FoldedAsyncChain` + `folding/AsyncChainFolder` — analogous
  to sync; references `inqudium-imperative` types and is class-loaded
  only via `DetectionAsync.isPresent()`-guarded paths.
- `entries/AsyncCacheEntry` — fifth permit of `MethodDispatchEntry`.
- `construction/ParadigmValidator` async half: requires
  `InqAsyncDecorator` for every resolved element on an async method.
- `construction/MethodDispatchEntryFactory` updated to produce
  `AsyncCacheEntry` for `CompletionStage`-returning methods, gated on
  `DetectionAsync.isPresent()`.

The split-class vs. polymorphic-factory choice from 3.8 is realised
here. Whichever is chosen must keep the
`inqudium-imperative`-referencing classes off the sync-only loading
path.

### 3.12 — Introspection adapter: `ProxyStackAdapter` + `ProxyStackInfo`

Per ADR-039:

- `introspection/ProxyStackInfo` — sealed-permitted DTO subtype.
  Carries `stackId`, `serviceInterface`, `elements()` snapshot, one
  `MethodLayers` per cache entry.
- `introspection/ProxyStackAdapter` — `supports(Object)`,
  `inspect(Object)`.
- A package-private read API on `InqInvocationHandler` for the adapter
  (mechanism choice: public accessors vs. sealed-interface bridge —
  implementation session decides).

The adapter is hooked into the central `InqIntrospector` (location
decided at this sub-step — likely `inqudium-pipeline`, possibly
`inqudium-core`; depends on where the introspector ends up living)
following the hardwired-chain pattern of ADR-039.

### 3.13 — Module-loading discipline test + end-to-end smoke tests

Two test categories:

- **End-to-end smoke tests** in `inqudium-proxy` using a real
  `InqBulkhead` from `inqudium-imperative`. A service interface with
  one sync method and one async method; protect via
  `pipeline.protect(...)` from the new `inqudium-pipeline`; assert
  that calls flow through the bulkhead correctly; assert that
  introspection produces a coherent `ProxyStackInfo`.
- **Module-loading-discipline test**: a separate Maven module or
  profile that builds `inqudium-proxy` without `inqudium-imperative`
  on the classpath and verifies, reflectively
  (`ClassLoader.getInitiatedClasses` or equivalent), that no class
  from `inqudium-imperative` is loaded when only a sync-method-only
  service is proxied. Automated verification of ADR-037 §6.

### 3.14 — ADR promotion + plan document deletion

- ADR-035, 039, 040, 041, 042: change `**Status:**` from `Proposed` to
  `Accepted`, add the date.
- ADR-037: **leave as `Proposed`**. Add a brief note in its header
  pointing out that the topology is partially realised by this
  refactor and full realisation depends on the future legacy-removal
  refactor.
- ADR-036 is already Accepted; do not touch.
- Delete `REFACTORING_PROXY_REWRITE.md`. Verify
  `grep -r REFACTORING_PROXY_REWRITE` returns no hits after this
  sub-step.

## Risks and notes

- **The annotation-evaluator bridge is a transitional hack.** Sub-step
  3.3 introduces `InqPipelineAnnotationEvaluator`; its Javadoc and the
  3.3 sub-step report must clearly mark it as transitional. Code
  review must catch any attempt to make it permanent.
- **Module-loading discipline is not compiler-enforced.** A
  class-literal reference to `inqudium-imperative` in a class loaded on
  the sync-only path silently breaks the optional-dependency contract.
  Mitigated by sub-step 3.13's automated test and by code-review
  attention in every sub-step that touches the async path (3.8, 3.11).
- **Storage typing vs. call-time typing.** ARCHITECTURE.md §7.3
  documents the distinction explicitly; the unchecked cast lives in
  exactly one place (`SyncChainFolder.fold`). Sub-step 3.7's review
  must verify the `@SuppressWarnings("unchecked")` annotation is on
  exactly that cast and not propagated to other call sites.
- **The annotation evaluator returns `MethodPlan.PassThrough` for
  unoverridden default methods** as well as for "normal" pass-through
  methods. The proxy must distinguish the two to route correctly to
  `DefaultMethodEntry` vs. `PassThroughEntry`. Sub-step 3.8 carries
  this logic; sub-step 3.9 tests both routes.
- **Two `InqPipeline` types coexist** at the end of this refactor:
  the legacy class `eu.inqudium.core.pipeline.InqPipeline` and the
  new interface `eu.inqudium.pipeline.InqPipeline`. Code in
  `inqudium-pipeline` and `inqudium-proxy` deals only with the new
  interface; the legacy class is touched only inside
  `InqPipelineAnnotationEvaluator` (3.3).

## Out-of-scope findings from the pre-plan audit

The audit surfaced several issues that are **not** addressed by this
refactor. They are recorded here so they are not lost; the maintainer
decides whether to file them as `TODO.md` entries or address them in a
later refactor:

- `inqudium-bom` references five non-existent artefact IDs
  (`inqudium-circuitbreaker`, `inqudium-retry`, `inqudium-ratelimiter`,
  `inqudium-bulkhead`, `inqudium-timelimiter`). The BOM will not
  install/deploy cleanly for downstream consumers. Not in scope here.
- `PipelineOrdering.shouldValidateOnBuild()` is defined but never
  consulted by `InqPipeline.Builder.build()`. Half-finished wiring.
  Not in scope (affects only the legacy `InqPipeline`).
- The legacy `InqPipeline.Builder` is implicitly reusable (no
  single-use guard, despite ADR-040 stating single-use intent). Not in
  scope (legacy). The new `InqPipelineBuilder` in 3.2 enforces
  single-use.
- The legacy proxy stack carries back-compat shims
  (`DispatchExtension.linkInner` default delegating to the one-arg
  variant). Will be removed together with the legacy proxy in the
  future legacy-removal refactor.

## Completion log

* [x] 3.1 — Plan finalisation (2026-05-16, PR #62)
* [x] 3.2 — Create inqudium-pipeline module + InqPipeline interface + builder (2026-05-16, PR #63)
* [x] 3.3 — DetectionProxy + protect(Class<T>, T) stub + InqPipelineAnnotationEvaluator bridge (2026-05-16, PR #64)
* [x] 3.4 — Create inqudium-proxy module skeleton + commit ARCHITECTURE.md (2026-05-16, PR #65)
* [x] 3.5 — Foundation: invocation primitives, exception classification (2026-05-16, PR #66)
* [x] 3.6 — Dispatch entries part 1: sealed family + simple variants (2026-05-17, PR #67)
* [x] 3.7 — Folding: SyncChainFolder + FoldedSyncChain + SyncCacheEntry (2026-05-17, PR #68)
* [x] 3.8 — Construction (sync only): ProxyBuilder + collaborators (2026-05-17, PR #69)
* [x] 3.9 — Handler + ProxyDispatcher + wire DetectionProxy real delegation (2026-05-17, PR #70)
* [x] 3.10 — Object methods: ObjectMethodHandler + ObjectMethodEntry (2026-05-17, PR #71)
* [x] 3.11 — Async dispatch (DetectionAsync + AsyncChainFolder + AsyncCacheEntry + AsyncParadigmValidator) (2026-05-17, PR #72)
* [ ] 3.12 — Introspection adapter: ProxyStackAdapter + ProxyStackInfo
* [ ] 3.13 — Module-loading-discipline test + end-to-end smoke tests
* [ ] 3.14 — ADR promotion + plan document deletion
