# ADR-037 §6 module-loading discipline finding

**Status:** Resolved
**Surfaced:** 2026-05-17 (sub-step 3.13 of REFACTORING_PROXY_REWRITE.md)
**Resolved:** 2026-05-17 (sub-step 3.13a of REFACTORING_PROXY_REWRITE.md, PR #N)
**Severity:** Architectural violation; deployment-breaking when
inqudium-imperative is intentionally absent.

## Summary

The proxy module promises in ADR-037 §6 and `ARCHITECTURE.md` §13 that
a deployment without `inqudium-imperative` on the classpath remains
functional for **sync-only** services. Sub-step 3.13's
`ModuleLoadingDisciplineTest` empirically verifies this contract and
finds it broken: building a sync-only proxy loads
`eu.inqudium.imperative.core.pipeline.AsyncLayerAction`, and a
classpath that excludes `inqudium-imperative` fails with

```
java.lang.NoClassDefFoundError:
  eu/inqudium/imperative/core/pipeline/AsyncLayerAction
```

at the moment `pipeline.protect(...)` is first called.

## Reproduction

`inqudium-proxy/src/test/java/eu/inqudium/proxy/discipline/ModuleLoadingDisciplineTest.java`
contains two `@Disabled`-marked tests:

- `should_protect_sync_only_service_without_inqudium_imperative` —
  builds a `URLClassLoader` whose classpath excludes any entry
  matching `inqudium-imperative`, then constructs and exercises a
  sync-only proxy. Currently throws
  `NoClassDefFoundError: AsyncLayerAction`.
- `should_not_load_async_classes_when_building_a_sync_only_proxy` —
  builds a sync-only proxy on a full classpath and asserts that none
  of the seven async-related class names appear in the
  URLClassLoader's `findLoadedClass` map. Six of seven (the proxy
  module's `AsyncChainFolder`, `FoldedAsyncChain`, `AsyncCacheEntry`,
  `AsyncParadigmValidator`; the imperative module's
  `InqAsyncDecorator`, `AsyncLayerTerminal`) are correctly absent.
  `AsyncLayerAction` is loaded; the test fails on that class.

Re-enable the tests by removing the `@Disabled` annotations once the
production-code fix below has landed.

## Diagnosis

`MethodDispatchEntryFactory` is the central type-switch on
`MethodPlan` and contains two private static helpers,
`toLayerAction(InqElement) → LayerAction` and
`toAsyncLayerAction(InqElement) → AsyncLayerAction`. Both helpers are
exposed as `MethodHandle`s inside the class's `BootstrapMethods`
attribute: each is the lambda target for a
`elements.stream().map(MethodDispatchEntryFactory::toLayerAction)` /
`...::toAsyncLayerAction` site inside `buildSyncDecorated(...)` /
`buildAsyncDecorated(...)` respectively. The `BootstrapMethods` table
has an entry of the form

```
4: REF_invokeStatic LambdaMetafactory.metafactory(...)
    #260 REF_invokeStatic
        MethodDispatchEntryFactory.toAsyncLayerAction:
            (InqElement) → AsyncLayerAction
    #263 (InqElement) → AsyncLayerAction
```

HotSpot's resolver appears to eagerly load the return-type class
(`AsyncLayerAction`) as soon as the **enclosing class's** first
`invokedynamic` site (the `switch` on `MethodPlan` in `createEntry`)
is linked, even though the specific bootstrap entry that mentions
`AsyncLayerAction` belongs to a different `invokedynamic` site that
the sync path never reaches.

`-verbose:class` confirms the load order: `MethodPlan$Decorated`,
`EvaluationResult`, then `AsyncLayerAction`, then
`MethodPlan$PassThrough`, then `MethodDispatchEntryFactory$$TypeSwitch`,
then `ParadigmDetector`. The four imperative async classes
(`AsyncChainFolder`, `AsyncCacheEntry`, `FoldedAsyncChain`,
`AsyncParadigmValidator`) do **not** load on the sync path —
discipline holds for those; the leak is specifically the
`AsyncLayerAction` return-type of the `toAsyncLayerAction` bootstrap.

## Proposed fix

Move the async-only helpers off `MethodDispatchEntryFactory` into a
separate class that lives only on the async branch. Concretely:

1. Create `eu.inqudium.proxy.construction.AsyncLayerActionExtractor`
   (or similar) carrying the `toAsyncLayerAction` helper and the
   `AsyncParadigmValidator.validate(...)` call. Move the
   `List<AsyncLayerAction<Void, Object>> asyncLayers = ...` stream
   into that class.
2. Have `buildAsyncDecorated(...)` in `MethodDispatchEntryFactory`
   delegate to it. The class-loading discipline then becomes: the new
   class is touched only inside `buildAsyncDecorated`, and the
   `DetectionAsync.isPresent()` gate above ensures
   `buildAsyncDecorated` is reachable only on the async path.
3. Verify by re-enabling the two tests in
   `ModuleLoadingDisciplineTest`.

This is purely a refactor of private static helpers; no public API,
no behavioural change. It belongs in a follow-up sub-step (suggested
3.13a) because sub-step 3.13's prompt explicitly forbids production-
code changes.

## Why this matters

The promise of an optional dependency is binary: either the
deployment works without it, or the dependency is mandatory. ADR-037
§6 takes the former position. The empirical test shows we are
currently in the latter regime. Users who exclude
`inqudium-imperative` from a sync-only deployment will see
`NoClassDefFoundError` at the moment their first proxy is built — a
class of failure that is hard to diagnose without exactly this kind
of test.

## Resolution

Sub-step 3.13a extracted the async-build flow out of
`MethodDispatchEntryFactory` into a new
`eu.inqudium.proxy.construction.AsyncEntryBuilder` class. Because
`MethodDispatchEntryFactory.buildAsyncDecorated(...)` now reaches the
async path via an `invokestatic` call (which is lazy at first
invocation per JVMS §5.4) instead of via the `BootstrapMethods`
attribute, `MethodDispatchEntryFactory.class`'s verifier no longer
triggers eager loading of `AsyncLayerAction`.

Empirically verified by `ModuleLoadingDisciplineTest` (both methods
re-enabled):

- `should_not_load_async_classes_when_building_a_sync_only_proxy`
  passes — none of the seven async-related class names appears in
  the URLClassLoader's `findLoadedClass` map after a sync-only proxy
  is constructed.
- `should_protect_sync_only_service_without_inqudium_imperative`
  passes — a classpath that excludes the entire `inqudium-imperative`
  JAR can still build and exercise a sync-only proxy without any
  `NoClassDefFoundError`.
