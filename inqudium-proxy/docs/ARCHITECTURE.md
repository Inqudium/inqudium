# `inqudium-proxy` — Architecture Design (v2)

**Status:** Draft, intended as the design basis for the from-scratch rewrite of `inqudium-proxy` mandated by ADR-035.
**Date:** 2026-05-16 (initial); updated 2026-05-17 to reflect implementation through sub-step 3.9.
**Supersedes:** v1 of this document.

**Authoritative references:** ADR-035 (proxy architecture), ADR-036 (annotation model — implemented in `eu.inqudium.annotation.evaluator`), ADR-037 (module topology), ADR-039 (uniform stack introspection), ADR-040 (pipeline composition model), ADR-041 (pipeline composition ordering), ADR-042 (pipeline contracts), ADR-034 (correlation IDs), ADR-029 (lifecycle implementation pattern).

**Changes from v1:**

1. The annotation evaluator is an **external consumer**, not an internal subpackage. The `construction/annotation/` directory has been removed; phase 1 is reduced to a single call into `AnnotationEvaluator.forPipeline(pipeline).evaluate(...)` plus the proxy-specific classifications the evaluator does not perform (Object methods, default-method routing, async-paradigm validation, name-to-element resolution).
2. The **storage vs. call-time typing** distinction is now explicit. ADR-035 §4 mandates `LayerAction<Void, Object>` as the *storage* typing for the per-method cache. The hot-path dispatcher locally uses `LayerAction<Object[], Object>` so that arguments flow through the `A` parameter of `execute(...)` naturally, eliminating one closure allocation per call. The unchecked cast at the storage boundary is safe because the two parameterisations are the same erased type at runtime.
3. The interface to ADR-036's evaluator (`EvaluationResult`, `MethodPlan.PassThrough`, `MethodPlan.Decorated(List<String> elementNamesOuterToInner)`) is now correctly reflected in §6 and §7.

---

## 1. Scope and non-scope

### In scope

This module provides the runtime that ADR-035 specifies:

- The dispatcher class invoked by `InqPipeline.protect(Class<T>, T)` (per ADR-037).
- A JDK-dynamic-proxy `InvocationHandler` that classifies and dispatches every call on the constructed proxy.
- Construction-time orchestration: invoking the external `AnnotationEvaluator` (ADR-036), classifying methods the evaluator does not see (Object methods, unoverridden default methods), resolving element names to `InqElement` instances, validating async-paradigm compatibility, and folding the chain.
- Hybrid sync/async dispatch on a single proxy.
- The introspection adapter for the proxy paradigm (`ProxyStackAdapter`, `ProxyStackInfo`) per ADR-039.
- The library-specific exception `InqUndeclaredCheckedException` (per ADR-035 §10).

### Out of scope

- **Annotation evaluation itself.** ADR-036 is implemented in `eu.inqudium.annotation.evaluator` (existing module). The proxy consumes its API.
- The pipeline composition model and ordering (ADR-040, ADR-041 — already enforced by the evaluator and the pipeline builder).
- Bytecode generation, build-time weaving, AspectJ, Spring AOP (separate modules).
- Concrete-class proxying — interfaces only (ADR-035 §12).
- Proxy serialisation (ADR-035 §12).
- Stacked-proxy optimisation (ADR-035 §9 — supported structurally, not optimised).

---

## 2. Module boundaries (ADR-037)

```
inqudium-proxy
├── depends on (mandatory):
│   ├── inqudium-core                                  ← LayerAction, LayerTerminal, InqElement, ...
│   ├── inqudium-pipeline                              ← InqPipeline, InqPipeline.builder
│   └── inqudium-annotation                            ← AnnotationEvaluator, EvaluationResult, MethodPlan
│                                                        (housing package eu.inqudium.annotation.evaluator)
└── depends on (optional):
    └── inqudium-imperative                            ← AsyncLayerAction, InqAsyncDecorator, ...
                                                         only loaded if any method on the service
                                                         interface returns CompletionStage
```

The optional `inqudium-imperative` dependency is declared with `<optional>true</optional>`. Async dispatch is reached through a hard-wired branch on `DetectionAsync.isPresent()` at proxy-construction time. No class-literal references to `inqudium-imperative` types in any class that may load when `inqudium-imperative` is absent (per ADR-037 §6).

The `DetectionProxy` class itself lives in `inqudium-pipeline` per ADR-037 §4 — outside the scope of this module.

---

## 3. Public surface

```java
InqPipeline pipeline = InqPipeline.builder()
        .shield(bulkhead)
        .shield(circuitBreaker)
        .build();

OrderService service = pipeline.protect(OrderService.class, new DefaultOrderService());
```

`pipeline.protect(Class<T>, T)` is a default method on `InqPipeline` (in `inqudium-pipeline`). It delegates to `ProxyDispatcher.protect(pipeline, serviceInterface, target)` in this module. `ProxyDispatcher` is the single public entry point.

The delegation goes through a small package-private helper `ProxyDelegation` in `inqudium-pipeline` that performs `Class.forName("eu.inqudium.proxy.ProxyDispatcher", ...)` plus a cached `Method.invoke(...)` at class-init time. This reflection bridge exists because a direct class-literal reference would require `inqudium-pipeline` to compile-depend on `inqudium-proxy` — impossible since `inqudium-proxy` already compile-depends on `inqudium-pipeline` (a Maven cycle). Construction is a cold path; the per-call reflection overhead is negligible (one `Method.invoke` per `pipeline.protect(...)` invocation, never per service method call). The `DetectionProxy.isPresent()` check (ADR-037 §4) gates entry — if `inqudium-proxy` is absent from the classpath, `protect(...)` throws `IllegalStateException` with a descriptive message before reaching the bridge.

The only other public types in this module are `InqUndeclaredCheckedException` (surfaces to user code through `catch`), and the ADR-039 DTO `ProxyStackInfo` plus its adapter `ProxyStackAdapter`.

---

## 4. Package structure

```
eu.inqudium.proxy
│
├── ProxyDispatcher                    // Public — entry point, called via ProxyDelegation reflection bridge
├── InqUndeclaredCheckedException      // Public — surfaced to user code
│
├── handler/                           // Public/package-private mix — the InvocationHandler machinery
│   ├── InqInvocationHandler           //   public — the handler installed on every proxy
│   ├── PerProxyCache                  //   package-private — method-to-entry lookup
│   ├── ArgNormalizer                  //   package-private — null Object[] → empty array
│   └── ObjectMethodHandler            //   public — equals / hashCode / toString  (planned 3.10)
│
├── construction/                      // Public/package-private mix — phase 1 + 2 orchestration
│   ├── ProxyBuilder                   //   public — orchestrates evaluator call + entry construction
│   ├── ElementResolver                //   public — maps element names to InqElement instances
│   ├── SyncParadigmValidator          //   package-private — sync paradigm check
│   ├── AsyncParadigmValidator         //   package-private — async paradigm check  (planned 3.11)
│   └── MethodDispatchEntryFactory     //   public — classifies methods and builds entries
│
├── entries/                           // Public sealed interface + package-private records
│   ├── MethodDispatchEntry            //   public sealed interface + static factories
│   ├── SyncCacheEntry                 //   package-private record — folded sync chain
│   ├── PassThroughEntry               //   package-private record — direct target invocation
│   ├── DefaultMethodEntry             //   package-private record — InvocationHandler.invokeDefault
│   ├── ObjectMethodEntry              //   package-private record — dispatches to ObjectMethodHandler  (planned 3.10)
│   └── AsyncCacheEntry                //   package-private record — folded async chain  (planned 3.11)
│
├── folding/                           // Public — chain materialisation
│   ├── SyncChainFolder                //   public — builds FoldedSyncChain (closures-per-depth)
│   ├── FoldedSyncChain                //   public @FunctionalInterface — the per-method invocation closure
│   ├── AsyncChainFolder               //   public — builds FoldedAsyncChain  (planned 3.11)
│   └── FoldedAsyncChain               //   public @FunctionalInterface — async counterpart  (planned 3.11)
│
├── dispatch/                          // Public — paradigm classification
│   ├── ParadigmDetector               //   public — isAsyncMethod(Method); JDK types only
│   └── DetectionAsync                 //   public — probes for inqudium-imperative  (planned 3.11)
│
├── invocation/                        // Public sealed interface + package-private implementations
│   ├── MethodInvoker                  //   public sealed interface + create() factory
│   ├── MethodHandleInvoker            //   package-private — MethodHandle-based (default)
│   └── ReflectiveInvoker              //   package-private — Method.invoke fallback
│
├── exception/                         // Public/package-private — exception path
│   ├── ExceptionClassifier            //   public — ADR-035 §10 algorithm
│   └── ThrowableUnwrap                //   package-private — InvocationTargetException etc.
│
└── introspection/                     // Public — ADR-039 adapter  (planned 3.12)
    ├── ProxyStackAdapter              //   inspects an instance for proxy structure
    └── ProxyStackInfo                 //   sealed-permitted DTO subtype
```

Class visibility follows a consistent rule: **public types are the cross-package contact surface**, even when marked "Internal API" in their Javadoc (i.e., not part of the stable user-facing API). The strictly-package-private types are records and helpers used only within one package. The `(planned N.NN)` markers indicate which sub-step of the proxy rewrite plan (`REFACTORING_PROXY_REWRITE.md`) introduces each class; everything without a marker is implemented as of sub-step 3.9.

The `construction/annotation/` subpackage that v1 proposed is **removed** — that work is done in `eu.inqudium.annotation.evaluator` (existing module).

---

## 5. Type hierarchy

### 5.1 Consumed framework types

From `inqudium-core` (per ADR-042):

```java
interface LayerAction<A, R> {
    R execute(long stackId, long callId, A argument, LayerTerminal<A, R> next) throws Throwable;
}
interface LayerTerminal<A, R> {
    R execute(long stackId, long callId, A argument) throws Throwable;
}
interface InqElement {
    String name();
    InqElementType elementType();
    InqEventPublisher eventPublisher();
}
interface InqDecorator<A, R> extends InqElement, LayerAction<A, R> { ... }
```

From `inqudium-imperative` (optional, per ADR-042):

```java
interface AsyncLayerAction<A, R> {
    CompletionStage<R> executeAsync(long stackId, long callId, A argument, AsyncLayerTerminal<A, R> next);
}
interface InqAsyncDecorator<A, R> extends InqElement, AsyncLayerAction<A, R> { ... }
```

From `inqudium-pipeline` (per ADR-040):

```java
interface InqPipeline {
    List<InqElement> elements();
    default <T> T protect(Class<T> iface, T target) { ... }
}
```

From `eu.inqudium.annotation.evaluator` (per ADR-036):

```java
public interface AnnotationEvaluator {
    static AnnotationEvaluator forPipeline(InqPipeline pipeline);
    <T> EvaluationResult evaluate(Class<T> serviceInterface, Class<? extends T> implementationClass);
}
public record EvaluationResult(Map<Method, MethodPlan> plans) { }
public sealed interface MethodPlan {
    record PassThrough() implements MethodPlan { }
    record Decorated(List<String> elementNamesOuterToInner) implements MethodPlan { }
}
public class InqAnnotationConfigurationException extends IllegalStateException { ... }
```

### 5.2 Proxy-internal types

#### `ProxyDispatcher` (public)

```java
public final class ProxyDispatcher {
    private ProxyDispatcher() { }

    public static <T> T protect(InqPipeline pipeline, Class<T> serviceInterface, T target) {
        // 1. Validate inputs (interface check, non-null).
        // 2. Run construction via ProxyBuilder.
        // 3. Instantiate the InvocationHandler with the per-method cache.
        // 4. Return a JDK proxy that implements serviceInterface.
    }
}
```

#### `InqInvocationHandler` (public — "Internal API")

```java
public final class InqInvocationHandler implements InvocationHandler {
    private final long stackId;                  // per ADR-034: one stackId per proxy
    private final LongSupplier callIdSource;     // per ADR-035 §6: one source per proxy
    private final Object realTarget;             // for ObjectMethodHandler equals (§10)
    private final PerProxyCache cache;

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object[] normalisedArgs = ArgNormalizer.normalise(args);
        MethodDispatchEntry entry = cache.entryFor(method);
        try {
            return entry.dispatch(proxy, this, normalisedArgs);
        } catch (Throwable t) {
            throw ExceptionClassifier.classify(t, method);
        }
    }
}
```

`stackId` is allocated from `PipelineIds.nextChainId()` (mechanism specified by ADR-034). `callIdSource` is a `LongSupplier` from `PipelineIds.newInstanceCallIdSource()` — backed internally by an `AtomicLong` private to this handler, with no contention between proxies. The class is `public` for cross-package reference (e.g. from `eu.inqudium.proxy.entries.ObjectMethodEntry`) but is labelled "Internal API" in its Javadoc; it is not part of the stable user-facing API.

#### `MethodDispatchEntry` (sealed interface)

```java
public sealed interface MethodDispatchEntry permits
        SyncCacheEntry,
        AsyncCacheEntry,
        PassThroughEntry,
        DefaultMethodEntry,
        ObjectMethodEntry {

    Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args) throws Throwable;

    // Static factories on the interface keep the permitted records package-private
    // while allowing cross-package construction from ProxyBuilder /
    // MethodDispatchEntryFactory:
    static MethodDispatchEntry passThrough(MethodInvoker invoker);
    static MethodDispatchEntry defaultMethod(Method defaultMethod);
    static MethodDispatchEntry syncCache(FoldedSyncChain chain, List<String> layerDescriptions);
    static MethodDispatchEntry objectMethod(ObjectMethodHandler.Kind kind);    // 3.10
    static MethodDispatchEntry asyncCache(FoldedAsyncChain chain, List<String> layerDescriptions);  // 3.11
}
```

The introspection adapter (§12) reads `layerDescriptions` directly from `SyncCacheEntry` / `AsyncCacheEntry` record components via the sealed pattern-match — no interface-level accessor is needed. `PassThroughEntry`, `DefaultMethodEntry`, and `ObjectMethodEntry` carry no layer information; the introspection adapter returns an empty list for them.

#### `PerProxyCache` (package-private)

```java
final class PerProxyCache {
    private final Map<Method, MethodDispatchEntry> entries;
    // Built at construction; never mutated. No synchronization on dispatch.
}
```

Keyed by `java.lang.reflect.Method`. Bridge methods are not a problem for the cache itself: the JDK proxy mechanism only ever delivers the interface's own (non-bridge) `Method` to the `InvocationHandler`, so the cache's key set is exactly `serviceInterface.getMethods()`. Bridge resolution happens upstream in the evaluator, on the implementation class side.

---

## 6. Phase 1 — Annotation evaluation (consuming ADR-036)

### 6.1 The single call

`ProxyBuilder.build(pipeline, serviceInterface, target)`:

1. Validate inputs: `serviceInterface.isInterface()`, both non-null, `serviceInterface.isAssignableFrom(target.getClass())`.
2. Call the external evaluator:
   ```java
   @SuppressWarnings("unchecked")
   Class<? extends T> implClass = (Class<? extends T>) target.getClass();
   EvaluationResult evaluation = AnnotationEvaluator
           .forPipeline(pipeline)
           .evaluate(serviceInterface, implClass);
   Map<Method, MethodPlan> plans = evaluation.plans();
   ```
3. The evaluator either succeeds (returning a plan per interface method) or throws `InqAnnotationConfigurationException` per ADR-036 §9. The proxy lets that exception propagate to the caller of `pipeline.protect(...)` — it is part of the public construction-error contract.

The evaluator handles entirely:

- Source method resolution (ADR-036 §5: bridge-method handling, the default-method-overridden-or-not check on the impl side).
- Class-level vs. method-level inheritance (ADR-036 §6).
- Composition order via `@InqShield(order=...)` or `@InqShield(customOrder={...})` (ADR-036 §3) — the returned `Decorated.elementNamesOuterToInner` is **already** in the correct outermost-first composition order.
- Validation of: missing element names in the pipeline, malformed `@InqShield`, ambiguous bridges (ADR-036 §9).

### 6.2 What the proxy must add on top

The evaluator does not know about:

| Concern                                            | Why the proxy handles it                                                                                  |
|----------------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| `Object` methods (`equals`, `hashCode`, `toString`) | The evaluator iterates `serviceInterface.getMethods()`, which on an **interface** excludes `Object` methods (a JDK reflection quirk: `getMethods()` on an interface returns the interface's own methods and inherited superinterface methods, but not `Object`'s methods). The evaluator therefore never returns a plan for `equals`, `hashCode`, or `toString`. `ProxyBuilder` seeds entries for these three Object methods after the evaluator pass, routing them directly to `ObjectMethodEntry`. |
| Default-method routing                             | An unoverridden default method receives `PassThrough` from the evaluator. The proxy must distinguish this from a normal pass-through to call `InvocationHandler.invokeDefault(...)` rather than `realTarget.method(...)`. |
| Sync vs. async dispatch mode                       | The evaluator returns only names; the proxy decides sync/async from the return type (`isAsyncMethod`).    |
| Async-decorator paradigm compatibility (§6 of ADR-035) | The evaluator does not know whether the resolved elements support async. Async methods whose referenced elements lack `InqAsyncDecorator` must fail at construction. |
| Element-name → `InqElement` resolution             | The evaluator returns names; the proxy looks them up by `name()` from `pipeline.elements()`.              |
| Build a `LayerAction<Void, Object>` chain          | The proxy folds the resolved elements into the per-method dispatcher.                                     |

Each of these is straightforward, but they all happen at proxy-construction time, never at dispatch.

---

## 7. Phase 2 — Per-method materialisation

For each `Method` in the keyset of `plans`, the proxy produces exactly one `MethodDispatchEntry`. Classification is a small decision table:

```
classify(method, plan, implClass):
    if method.declaringClass == Object.class                       → ObjectMethodEntry
    elif plan instanceof PassThrough:
        if method.isDefault() && !overriddenByImpl(method, implClass) → DefaultMethodEntry
        else                                                       → PassThroughEntry
    else (plan instanceof Decorated):
        elements   = resolveNames(plan.elementNamesOuterToInner)
        mode       = isAsyncMethod(method) ? ASYNC : SYNC
        validate paradigm compatibility (mode, elements)
        fold and produce SyncCacheEntry or AsyncCacheEntry
```

`overriddenByImpl(method, implClass)` is a small reflective check on whether the implementation class declares the same signature as a non-default method. The evaluator already does the same check internally for its own purposes; the proxy repeats it because it consumes `MethodPlan.PassThrough` opaquely and needs the bit independently.

### 7.1 Element name resolution

```java
List<InqElement> resolveNames(List<String> names, InqPipeline pipeline, ...) {
    Map<String, InqElement> byName = pipeline.elements().stream()
            .collect(Collectors.toMap(InqElement::name, Function.identity()));
    // The evaluator already validated existence; this lookup will not miss.
    return names.stream().map(byName::get).toList();
}
```

The pipeline-elements list is small (typically ≤ 6 per ADR-040/041), so the `Map` construction is acceptable per proxy. The lookup is on cold-path code; no optimisation needed.

### 7.2 Paradigm validation

```java
void validateParadigm(DispatchMode mode, List<InqElement> elements, Method method) {
    for (InqElement element : elements) {
        switch (mode) {
            case SYNC -> requireDecorator(element, method);
            case ASYNC -> requireAsyncDecorator(element, method);
        }
    }
}

void requireAsyncDecorator(InqElement element, Method method) {
    // No class-literal reference here unless DetectionAsync.isPresent() — see §13.
    if (!(element instanceof InqAsyncDecorator<?, ?>)) {
        throw new IllegalStateException(
            "Method " + method + " returns CompletionStage but element '" + element.name()
            + "' (type " + element.elementType() + ") does not implement InqAsyncDecorator");
    }
}
```

This is the §6/ADR-035 check the evaluator does not perform.

### 7.3 Storage vs. call-time typing — the corrected story

ADR-035 §4 mandates that **the per-method cache stores layer actions as `LayerAction<Void, Object>`** — a uniform storage type that accepts any element regardless of its declared `<A, R>`. This is the storage-side contract.

The dispatcher's hot-path code is local to this module and may use a different static parameterisation. Concretely: the chain folder treats each layer as `LayerAction<Object[], Object>` so that the proxy's `args:Object[]` flows through the `A` parameter of `execute(...)` naturally. Because Java generics are erased at runtime, `LayerAction<Void, Object>` and `LayerAction<Object[], Object>` are the same `LayerAction` after erasure; the cast at the storage boundary is unchecked but safe. The cast happens once per chain at fold time, never per call.

The folded sync chain is therefore a functional interface that takes the args directly:

```java
@FunctionalInterface
interface FoldedSyncChain {
    Object run(long stackId, long callId, Object[] args) throws Throwable;
}

final class SyncChainFolder {

    /**
     * Folds the list of layer actions plus the terminal invoker into a single FoldedSyncChain.
     * The input layers are stored as LayerAction<Void, Object>; this method casts them to
     * LayerAction<Object[], Object> for the proxy's internal call mechanics. The cast is
     * an unchecked cast that is safe because the two parameterisations share the same
     * erased type at runtime (per ADR-035 §4 — storage typing vs. call-time typing).
     */
    static FoldedSyncChain fold(List<LayerAction<Void, Object>> storageLayers, MethodInvoker invoker) {
        @SuppressWarnings("unchecked")
        List<LayerAction<Object[], Object>> layers =
                (List<LayerAction<Object[], Object>>) (List<?>) storageLayers;
        return foldRecursive(layers, 0, invoker);
    }

    private static FoldedSyncChain foldRecursive(
            List<LayerAction<Object[], Object>> layers, int idx, MethodInvoker invoker) {
        if (idx == layers.size()) {
            return (stackId, callId, args) -> invoker.invoke(args);
        }
        LayerAction<Object[], Object> head = layers.get(idx);
        FoldedSyncChain tail = foldRecursive(layers, idx + 1, invoker);
        return (stackId, callId, args) -> {
            LayerTerminal<Object[], Object> nextForHead =
                    (s, c, a) -> tail.run(s, c, a);
            return head.execute(stackId, callId, args, nextForHead);
        };
    }
}
```

The per-call dispatch becomes:

```java
final class SyncCacheEntry implements MethodDispatchEntry {
    private final FoldedSyncChain chain;
    private final List<String> layerDescriptions;

    @Override
    public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args) throws Throwable {
        long stackId = handler.stackId();
        long callId  = handler.nextCallId();
        return chain.run(stackId, callId, args);
    }
}
```

**Per-call allocations.** N intermediate `LayerTerminal` closures, one per chain transition. Each closure captures only the `tail` reference — args flow through the function parameter, not through the closure. Compared to the v1 design, this saves one allocation per call (the outer args-capturing closure) and produces a cleaner closure topology that the JIT escape-analysis can more easily eliminate.

**Why not a stateful walker.** A single per-call walker that walks the layer array via `idx++` would break retry semantics. A retry layer calls `next.execute(...)` multiple times; with a stateful walker, the second invocation would start where the first ended (past the inner chain), causing inner layers to be skipped on retry. Closures-per-depth capture the correct re-entry point automatically; they are the simplest correct fold.

### 7.4 Async folding

Structurally analogous, with `AsyncLayerAction<Object[], Object>` and `AsyncLayerTerminal<Object[], Object>`. The classes `AsyncChainFolder`, `AsyncCacheEntry`, and the folder-internal types live in this module but reference `inqudium-imperative` types. They are loaded only via the `DetectionAsync.isPresent()` branch in `ProxyBuilder`. If `DetectionAsync.isPresent()` is `false` and any async method exists on the service interface, construction fails with the descriptive `IllegalStateException` from ADR-037 §3.

The `AsyncCacheEntry.dispatch(...)` separates the two error paths per ADR-035 §10:

```java
@Override
public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args) throws Throwable {
    long stackId = handler.stackId();
    long callId  = handler.nextCallId();
    // chain.run may throw synchronously (e.g. permit-acquire failure before the async op starts),
    // or return a CompletionStage which itself may complete exceptionally.
    return chain.run(stackId, callId, args);
}
```

Synchronous throws from `chain.run` are classified by `ExceptionClassifier` in the `InqInvocationHandler.invoke`'s catch-block (§9). Async failures rolled into the returned `CompletionStage` are not reclassified — they propagate as the JDK conventions specify.

### 7.5 The trivial entry types

```java
final class PassThroughEntry implements MethodDispatchEntry {
    private final MethodInvoker invoker;
    @Override public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args) throws Throwable {
        return invoker.invoke(args);
    }
}

final class DefaultMethodEntry implements MethodDispatchEntry {
    private final Method defaultMethod;
    @Override public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args) throws Throwable {
        return InvocationHandler.invokeDefault(proxy, defaultMethod, args);  // Java 16+
    }
}

final class ObjectMethodEntry implements MethodDispatchEntry {
    private final ObjectMethodHandler.Kind kind;
    @Override public Object dispatch(Object proxy, InqInvocationHandler handler, Object[] args) throws Throwable {
        return ObjectMethodHandler.dispatch(kind, proxy, handler, args);
    }
}
```

`InvocationHandler.invokeDefault` (Java 16+) handles JPMS module-boundary concerns transparently per ADR-035 §7 — no `MethodHandles.privateLookupIn` is needed.

---

## 8. Phase 3 — Dispatch

`InqInvocationHandler.invoke(...)` looks up the entry, normalises args, and delegates. The entry encapsulates the dispatch logic.

The hot path for a protected sync call:

```
JDK proxy → InqInvocationHandler.invoke(...)
        → ArgNormalizer.normalise(args)
        → cache.entryFor(method)                  // HashMap lookup, cache immutable
        → SyncCacheEntry.dispatch(...)            // in a try-block
        → chain.run(stackId, callId, args)        // args threaded through the function parameter
        → layer-action chain                      // pre-folded; closures-per-depth handle re-entry
        → MethodInvoker.invoke(args)              // real target call
        ─────────────────────────────────────────
        (on Throwable from dispatch:
         → ExceptionClassifier.classify(t, method)) // re-thrown by invoke()
```

No reflection lookups on the hot path beyond the cache. No `Class.forName`. No annotation reading.

---

## 9. Exception classification (ADR-035 §10)

Sync only. Lives in `eu.inqudium.proxy.exception.ExceptionClassifier`. The handler wraps the entry dispatch in `try/catch (Throwable)`. For `AsyncCacheEntry`, the catch only fires on the synchronous prefix of an async call; failures inside the returned `CompletionStage` are not subject to classification.

Algorithm:

1. Unwrap `InvocationTargetException` and `UndeclaredThrowableException` recursively to expose the real cause.
2. If `RuntimeException`, `Error`, or a checked exception declared in `method.getExceptionTypes()` — propagate as-is.
3. Otherwise wrap in `InqUndeclaredCheckedException` (extends `java.lang.reflect.UndeclaredThrowableException`), with the `Method` reference as a property alongside the cause.

The `InqUndeclaredCheckedException` is `public` and lives at the top-level of `eu.inqudium.proxy`. Application code may catch it explicitly or rely on the JDK supertype.

---

## 10. `Object` method handling (ADR-035 §8)

`ObjectMethodHandler` is the single dispatcher for all `Object` methods. It is invoked from `ObjectMethodEntry`, which carries an enum tag `Kind { EQUALS, HASH_CODE, TO_STRING, WAIT, NOTIFY, NOTIFY_ALL, GET_CLASS }` to avoid per-call method-name string comparison.

The rules (verbatim from ADR-035 §8):

| Method        | Behaviour                                                                            |
|---------------|--------------------------------------------------------------------------------------|
| `equals`      | Proxies equal iff both are JDK proxies with `InvocationHandler`s of the same concrete type whose real targets are equal. |
| `hashCode`    | Delegates to the real target.                                                        |
| `toString`    | Descriptive: proxy class simple name + `[` + real target's `toString` + `]`.         |

`equals` symmetry is enforced by the "both must be JDK proxies with our handler type" test.

---

## 11. Hot-path performance (ADR-035 §11)

`MethodInvoker` is a sealed strategy interface:

```java
public sealed interface MethodInvoker permits MethodHandleInvoker, ReflectiveInvoker {
    Object invoke(Object[] args) throws Throwable;

    /**
     * Creates a {@link MethodInvoker} for {@code target.method(...)}. The JVM property
     * {@code inqudium.proxy.invoker=mh|reflective} selects between the two implementations
     * (default: {@code mh}). Read once per call — set on JVM startup for global selection.
     */
    static MethodInvoker create(Object target, Method method);
}
```

Default choice: `MethodHandleInvoker`. The JVM property `inqudium.proxy.invoker=mh|reflective` lets us run side-by-side benchmarks without code changes. The two implementations differ in exception propagation: `MethodHandleInvoker` propagates the underlying throwable unwrapped, while `ReflectiveInvoker` wraps in `InvocationTargetException` per JDK convention — both routes are correctly handled by `ExceptionClassifier` / `ThrowableUnwrap` in §9.

Async invocation does not use a separate `invokeAsync(...)` method on `MethodInvoker`; the same synchronous `invoke(...)` returns a `CompletionStage` for async methods (the return type is decided by the target's method signature, not the invoker). The async dispatch logic lives in `AsyncCacheEntry` (planned 3.11), which calls `invoke(...)` and chains on the resulting `CompletionStage`.

Arity-specialised invokers (one cached `MethodHandle` per arity) are deferred until benchmarks identify the array-unpack cost.

---

## 12. Introspection (ADR-039)

`ProxyStackAdapter` lives in this module and is referenced from `InqIntrospector` in `inqudium-pipeline`.

```java
public final class ProxyStackAdapter {

    public static boolean supports(Object instance) {
        if (!Proxy.isProxyClass(instance.getClass())) return false;
        InvocationHandler h = Proxy.getInvocationHandler(instance);
        return h instanceof InqInvocationHandler;
    }

    public static InqStackInfo inspect(Object instance) {
        InqInvocationHandler h = (InqInvocationHandler) Proxy.getInvocationHandler(instance);
        return new ProxyStackInfo(
                h.stackId(),
                Optional.of(h.serviceInterface()),
                h.elements(),                      // snapshot copied from the pipeline at construction
                h.cache().methodLayersView()       // one MethodLayers per entry
        );
    }
}
```

`InqInvocationHandler` exposes a small package-private read API to the adapter. The `MethodLayers` records are built at construction time, with `Optional.of(method)` populated for every entry — the proxy paradigm always has a concrete `Method` (tier-1 of ADR-039's resolution).

---

## 13. Module-loading discipline (ADR-037 §6)

Two patterns must be respected by the implementation:

1. **No class-literal references to `inqudium-imperative` types in any `inqudium-proxy` class that may load when `inqudium-imperative` is absent.** Async-related classes (`AsyncChainFolder`, `AsyncCacheEntry`, `FoldedAsyncChain`, `AsyncParadigmValidator`) are reached only through `DetectionAsync.isPresent()`-guarded branches in `MethodDispatchEntryFactory`.
2. **No mixed dispatcher structures.** Sync-vs-async selection is a hard-wired `if (isAsyncMethod) { ... } else { ... }`, not an array of dispatchers iterated indiscriminately.

The paradigm-validator design deserves explicit documentation. The validator must perform an `instanceof InqAsyncDecorator` check, which is a class-literal reference to a type from `inqudium-imperative`. **Decision: split-class structure.** Two separate classes:

- `SyncParadigmValidator` (sub-step 3.8) — references only `InqDecorator` from `inqudium-core`; always loadable.
- `AsyncParadigmValidator` (planned sub-step 3.11) — references `InqAsyncDecorator` from `inqudium-imperative`; loaded only via the `DetectionAsync.isPresent()` branch in `MethodDispatchEntryFactory`.

Both are package-private static helpers in `eu.inqudium.proxy.construction`. The factory selects between them via the result of `ParadigmDetector.isAsyncMethod(method)`. No type hierarchy connects them — the relationship is via the factory's branching, not via polymorphism. This is simpler than an abstract `ParadigmValidator` interface with two implementations and equally satisfies the class-loading constraint.

---

## 14. Construction-time control flow

```
ProxyDispatcher.protect(pipeline, serviceInterface, target)
    │
    ├─ Validate inputs
    │
    ├─ Run AnnotationEvaluator.forPipeline(pipeline).evaluate(serviceInterface, target.getClass())
    │      → Map<Method, MethodPlan> plans
    │      (Throws InqAnnotationConfigurationException eagerly for any ADR-036 §9 violation.)
    │
    ├─ Determine async presence: any method in plans.keySet() returns CompletionStage?
    │      yes → require DetectionAsync.isPresent(); else throw IllegalStateException (ADR-037)
    │
    ├─ For each Method m in plans.keySet():
    │      ├─ if m.declaringClass == Object.class            → ObjectMethodEntry
    │      ├─ elif plans[m] instanceof PassThrough:
    │      │      ├─ if m.isDefault() && !overriddenByImpl  → DefaultMethodEntry
    │      │      └─ else                                    → PassThroughEntry(MethodInvoker)
    │      └─ else (plans[m] instanceof Decorated):
    │             ├─ resolve element names → List<InqElement>
    │             ├─ validate paradigm (sync ⇒ InqDecorator, async ⇒ InqAsyncDecorator)
    │             ├─ cast layers to LayerAction<Void, Object> for storage
    │             ├─ fold via SyncChainFolder or AsyncChainFolder
    │             └─ SyncCacheEntry or AsyncCacheEntry
    │
    ├─ Build PerProxyCache from the entries
    │
    ├─ Allocate stackId from PipelineIds (ADR-034)
    ├─ Construct InqInvocationHandler(realTarget, serviceInterface, cache, stackId, callIdSource)
    │
    └─ Return Proxy.newProxyInstance(loader, new Class[]{serviceInterface}, handler)
```

If any step from "Determine async presence" onward throws, construction fails before the proxy is returned. No partially-initialised proxy is ever observable to user code.

---

## 15. Testing strategy

Tests follow CLAUDE.md conventions: JUnit 5, AssertJ only, no mock libraries, `@Nested` groupings, deterministic time, full-English-sentence method names in `snake_case`.

Test class structure mirrors package structure. Major categories (with sub-step where each was introduced):

- **`ProxyDispatcherTest`** (3.9) — end-to-end construction tests, input validation, returned-instance type assertions.
- **`InqInvocationHandlerTest`** (3.6/3.7/3.9) — dispatch routing, classification correctness, correlation-ID semantics (`stackId` constant per proxy, `callId` monotonic per call).
- **`ProxyBuilderTest`** (3.8) — phase orchestration. Verifies that:
    - the evaluator's `InqAnnotationConfigurationException` propagates unchanged;
    - sync-decorator paradigm violations fail at construction with a descriptive message;
    - the immutable entries map carries one entry per service method plus `equals`/`hashCode`/`toString`;
    - **(planned 3.11)** async-decorator paradigm violations fail at construction;
    - **(planned 3.11)** missing `inqudium-imperative` for an async method fails with the ADR-037 §3 message.
- **`MethodDispatchEntryFactoryTest`** (3.8) — classification table per §7: PassThrough plans, Decorated plans, paradigm-validation propagation.
- **`SyncChainFolderTest`** (3.7) — folding correctness. Categories: empty chain, single layer, multi-layer, **retry semantics** (a layer that calls `next.execute(...)` multiple times correctly re-enters the inner chain each time), exception propagation through middle layers.
- **`ObjectMethodHandlerTest`** (planned 3.10) — `equals` symmetry, `hashCode` delegation, `toString` format.
- **`SyncParadigmValidatorTest`** (3.8) — sync method with non-`InqDecorator` element fails.
- **`AsyncParadigmValidatorTest`** (planned 3.11) — async method with non-`InqAsyncDecorator` element fails.
- **`AsyncChainFolderTest`** (planned 3.11) — folding correctness, async variant.
- **`ExceptionClassifierTest`** (3.5) — runtime, error, declared-checked, undeclared-checked classification; `InvocationTargetException` and `UndeclaredThrowableException` unwrapping.
- **`EndToEndPipelineProtectTest`** (3.9) — end-to-end through `pipeline.protect(...)`, exercising the `ProxyDelegation` reflection bridge.
- **`InqPipelineProtectWithoutProxyTest`** (3.3/3.9, in `inqudium-pipeline`'s test sources) — the proxy-absent branch (`DetectionProxy.isPresent() == false`).
- **`ProxyStackAdapterTest`** (planned 3.12) — the ADR-039 introspection adapter produces the right DTO; `MethodLayers.method()` is populated for every entry.
- **`ModuleLoadingDisciplineTest`** (planned 3.13) — a profile-controlled test verifies that running with `inqudium-imperative` absent does not load any async-related classes when no async methods exist on the service interface. Implementation: reflectively probe `getInitiatedClasses()` after a sync-only proxy is constructed and verify none of the async-related class names appear.

Tests are flat where the framework requires (none of the proxy tests is a Spring Boot test, so the `@Nested` caveat from CLAUDE.md does not apply here).

---

## 16. Open questions and TODOs

Each phase-tagged per CLAUDE.md's TODO discipline:

- **TODO(impl-1):** decide between `MethodHandleInvoker` and `ReflectiveInvoker` as the default based on JMH benchmarks (sync/async, varying arity, varying layer count). Architecture allows either; the JVM property `inqudium.proxy.invoker=mh|reflective` switches.
- **TODO(impl-2):** decide whether to introduce arity-specialised invokers. Defer until benchmarks identify the array-unpack cost.
- **TODO(impl-3):** investigate the per-call closure cost (N closures for N layers). Current design accepts this allocation as cheap; if benchmarks identify it as hot, an arena-based allocator or a stack-based walker with explicit depth state could replace closures. Retry semantics must be preserved (see §7.3).
- **TODO(intro-1):** finalise the package-private read API on `InqInvocationHandler` for `ProxyStackAdapter`. Choose between public accessor methods on the handler or a sealed-interface bridge. Resolved as part of sub-step 3.12.
- **TODO(jpms):** add a `module-info.java` that explicitly exports `eu.inqudium.proxy` and `eu.inqudium.proxy.introspection` and `requires` the right modules. Ensure no transitive exposure of internal packages.

**Resolved:**

- ~~TODO(evaluator-name)~~ — the annotation evaluator module is `eu.inqudium:inqudium-annotation` (Maven coordinate, package `eu.inqudium.annotation.evaluator`).
- ~~TODO(paradigm-split)~~ — split-class structure chosen, see §13.

---

## 17. Summary of structural choices

| Question                                       | Choice                                                                  | Justification |
|------------------------------------------------|-------------------------------------------------------------------------|---------------|
| Proxy mechanism                                | JDK `Proxy`                                                             | ADR-035 §2    |
| Public entry point                             | Single static method on `ProxyDispatcher`                               | ADR-037 §3    |
| Annotation evaluation                          | Delegated to `AnnotationEvaluator` in `eu.inqudium.annotation.evaluator`| ADR-036       |
| Per-method cache scope                         | Per `InvocationHandler` (per proxy)                                     | ADR-035 §11   |
| Cache storage typing                           | `LayerAction<Void, Object>` — uniform storage                            | ADR-035 §4    |
| Hot-path call-time typing                      | `LayerAction<Object[], Object>` locally — args thread through `A`        | Erasure-safe; one allocation fewer per call than args-in-closure |
| Folding model                                  | Recursive closure-per-depth via `FoldedSyncChain` / `FoldedAsyncChain`  | Retry correctness; cheap closures |
| Default-method dispatch                        | `InvocationHandler.invokeDefault` (Java 16+)                            | ADR-035 §7    |
| Object methods                                 | Dedicated `ObjectMethodHandler`, not in the layer chain                 | ADR-035 §8    |
| Hybrid sync/async                              | Per-method `DispatchMode`, separate cache-entry subtypes                | ADR-035 §6    |
| Optional `inqudium-imperative` dependency      | Lazy-loaded async classes, gated by `DetectionAsync.isPresent()`         | ADR-037 §6    |
| Exception classification                       | Sync only; async failures propagate via `CompletionStage`                | ADR-035 §10   |
| `stackId` / `callId` carriers                  | Handler holds `stackId` and per-handler `LongSupplier` `callIdSource` (from `PipelineIds.newInstanceCallIdSource()`) | ADR-034, ADR-035 §6 |
| Introspection                                  | `ProxyStackAdapter` in this module, surfaces `ProxyStackInfo`            | ADR-039       |
| Reflective invocation                          | `MethodInvoker` interface, default `MethodHandleInvoker`                 | ADR-035 §11   |
| Async-paradigm validation                      | Performed by the proxy at construction, not by the evaluator             | ADR-035 §6 (evaluator doesn't know paradigm) |
| Element name → element resolution              | Performed by the proxy at construction via `pipeline.elements()` lookup  | Evaluator returns names per its API |
| Proxy stacking                                 | Supported structurally, not optimised                                    | ADR-035 §9    |
| Concrete-class proxying                        | Not supported (interfaces only)                                          | ADR-035 §12   |
| Serialisable proxies                           | Not supported                                                            | ADR-035 §12   |
