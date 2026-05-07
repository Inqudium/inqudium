# ADR-037: Module topology and optional integration modules

**Status:** Proposed  
**Date:** 2026-05-05  
**Deciders:** Core team

## Context

The library applies resilience compositions through several integration technologies (proxy, function-based,
AspectJ, Spring AOP) and supports several dispatch paradigms (synchronous, asynchronous, with reactive types as a
likely future addition). Each integration and each paradigm comes with its own classes, dependencies, and
runtime requirements.

A naïve approach would put everything into a single library artefact, forcing every user to pull in transitive
dependencies they may not need. A user with synchronous-only services would still receive `CompletionStage`
machinery; a user without Reactor on their classpath would still see Reactor classes in their dependency tree.

This ADR specifies the module topology and the optional-dependency mechanism the library uses to keep each
user's footprint minimal — only the modules actually needed for the chosen integration and paradigms end up on
the classpath.

## Decision

### 1. Module topology

The library is structured into a small core plus paradigm-specific modules. The structure relevant to the proxy
integration (ADR-035) and likely future paradigms is:

| Module                | Role                                                                                                                                    |
|-----------------------|-----------------------------------------------------------------------------------------------------------------------------------------|
| `inqudium-core`       | Shared interfaces (`LayerAction`, `InqDecorator`, `Wrapper`), correlation IDs (`PipelineIds`), and the resilience-element infrastructure. |
| `inqudium-pipeline`   | `InqPipeline` itself, the `protect(...)` API, the synchronous dispatch logic, and the dispatch-mode recognition for all known paradigms. |
| `inqudium-imperative` | Asynchronous dispatch — `AsyncLayerAction`, `InqAsyncDecorator`, `InternalAsyncExecutor`, the async dispatch logic.                      |
| (future) `inqudium-reactor`  | Reactor-specific dispatch — recognition of `Mono`/`Flux` return types, the reactive subscription chain.                                |

Each paradigm-specific module is a standalone artefact. A user with synchronous-only services depends on
`inqudium-pipeline` (and transitively `inqudium-core`). A user with hybrid sync/async services additionally
declares `inqudium-imperative`. A future user with Reactor services additionally declares `inqudium-reactor`.

### 2. Optional dependencies for paradigm modules

`inqudium-pipeline` declares each paradigm-specific module as an *optional* dependency:

- Maven: `<optional>true</optional>` on the dependency declaration.
- Gradle: `compileOnly` configuration.

The optional declaration has two effects:

- The paradigm module is on the compile classpath of `inqudium-pipeline`. The pipeline can reference paradigm
  classes (such as `CompletionStage` for async or `Mono` for Reactor) at compile time without further
  configuration.
- The paradigm module is *not* transitively propagated to consumers of `inqudium-pipeline`. A user who depends
  on `inqudium-pipeline` does not automatically receive `inqudium-imperative` or `inqudium-reactor`.

Users who need a paradigm declare its module explicitly in their own build configuration. This makes the
classpath footprint a deliberate user choice, not a transitive byproduct.

### 3. Dispatch-mode recognition lives in `inqudium-pipeline`

`inqudium-pipeline` knows about every paradigm. It contains the recognition logic — the predicates that examine
a `Method` and decide which paradigm applies. The form of the predicate depends on whether the paradigm's
marker type is part of the JDK or comes from an external module:

```java
// In inqudium-pipeline:

// JDK type — direct class-literal reference is safe.
private static boolean isAsyncMethod(Method method) {
    return CompletionStage.class.isAssignableFrom(method.getReturnType());
}

// (future) External type — string-based comparison required.
private static boolean isReactorMonoMethod(Method method) {
    return isAssignableTo(method.getReturnType(), "reactor.core.publisher.Mono");
}
```

The asymmetry is intentional and required by the JVM's class-loading semantics. A direct class-literal
reference such as `Mono.class` forces the JVM to resolve `reactor.core.publisher.Mono` when the enclosing
class (e.g. `DetectionReactor`) is loaded — *before* any of its methods is invoked. If Reactor is absent from
the runtime classpath, the JVM raises `NoClassDefFoundError` at the load of `DetectionReactor` itself, never
reaching the recognition logic that would otherwise return `false`. The lazy-loading guarantees discussed in
section 4 protect *method bodies* that are not executed; they do not protect *class-literal references* inside
a class that is loaded.

JDK types do not need this protection because they are always present at runtime. `CompletionStage` is a
member of `java.util.concurrent` and is guaranteed available; a direct class-literal reference to it cannot
fail. The library uses the more robust `isAssignableFrom` form for JDK types because it correctly handles
subtypes — a method returning a subtype of `CompletionStage` is recognised as async.

For external types, the library provides a helper that walks the type hierarchy by name, avoiding any
class-literal references:

```java
// Helper in inqudium-pipeline, used by recognition predicates that target external types.
public static boolean isAssignableTo(Class<?> type, String targetTypeName) {
    if (type == null || type == Object.class) {
        return false;
    }
    if (targetTypeName.equals(type.getName())) {
        return true;
    }
    // Walk superclasses.
    if (isAssignableTo(type.getSuperclass(), targetTypeName)) {
        return true;
    }
    // Walk implemented interfaces.
    for (Class<?> iface : type.getInterfaces()) {
        if (isAssignableTo(iface, targetTypeName)) {
            return true;
        }
    }
    return false;
}
```

This helper preserves the semantic equivalent of `isAssignableFrom` (subtype recognition) while using only
strings to identify the target type. Because the helper accesses `Class` objects already loaded into the JVM
(via `getSuperclass`/`getInterfaces` on the method's actual return type), it does not introduce any new
class-literal references. If Reactor is absent, `Mono` is never loaded, the helper never encounters it in the
hierarchy walk, and the recognition correctly returns `false`.

The recognition logic is small, pure, and either JDK-only or string-based in its references. It does not
invoke paradigm-specific behaviour itself; it merely identifies which paradigm a method belongs to. The
actual paradigm-specific dispatch is delegated to the paradigm module.

### 4. Separated dispatch paths per paradigm

The dispatch paths for different paradigms must be separated in the code, never mixed in a shared structure.
This is the architectural condition that makes optional dependencies work without surprise failures.

#### Why separation works: lazy class loading and lazy verification

The JVM combines two mechanisms that, together, make the optional-dependency pattern reliable:

- **Lazy class loading.** The JVM loads a class only when its first runtime use is reached — a `new`
  expression, a static field access, a method invocation. Merely referencing a class name in bytecode (e.g.
  in a method that is never executed) does not trigger loading.
- **Lazy verification.** Modern JVMs do not eagerly resolve every type referenced in a class's bytecode at
  load time. When a class is loaded, the JVM defers verification of its referenced types until the first
  branch that needs them is executed.

The combination means that a method like:

```java
public Object dispatch(Method method, Object[] args) {
    if (DetectionAsync.canHandle(method)) {
        return AsyncDispatcher.dispatch(method, args);   // refers to async classes
    }
    return SyncDispatcher.dispatch(method, args);        // refers to sync classes only
}
```

works correctly even when the async classes are absent from the runtime classpath, *provided* the async branch
is never entered. The bytecode references to async classes do not trigger loading; only the actual execution of
`AsyncDispatcher.dispatch(...)` would.

A concrete trace. Suppose `dispatch` is invoked with a synchronous method (e.g. `String findById(String id)`):

1. The JVM enters `dispatch`. The method is already loaded; no async-related loading has occurred.
2. The JVM evaluates `DetectionAsync.canHandle(method)`. `DetectionAsync` is in `inqudium-pipeline` (always
   present); it is loaded if not already loaded. Its `canHandle` returns `false` for the sync method.
3. The `if`-branch is skipped. The reference to `AsyncDispatcher.dispatch(...)` is *never resolved*; the JVM
   does not attempt to load `AsyncDispatcher` or any class it transitively references.
4. The JVM proceeds to `SyncDispatcher.dispatch(...)`, loads `SyncDispatcher` (in `inqudium-pipeline`), and
   executes synchronously. The `inqudium-imperative` classes remain unloaded throughout.

The same trace with an async method (e.g. `CompletionStage<Order> placeOrderAsync(...)`) loads
`AsyncDispatcher` and the async classes it transitively references, *only at the moment* the async branch is
entered. Sync-only users never reach this branch and never pay the loading cost.

#### What lazy loading does not protect

The lazy-loading guarantee operates between *method bodies that are not executed*. It does not operate within
a class that is being loaded. When the JVM loads a class, it must resolve every type referenced in the class's
constant pool — including types referenced by class-literal expressions (`SomeType.class`) and by field
declarations whose types are mentioned directly. If any of those referenced types is absent from the
classpath, the JVM raises `NoClassDefFoundError` at the load of the referencing class itself, not when a
particular method is invoked.

This is why detection classes for paradigms whose marker types are *not* part of the JDK must use string-based
references for their target types (see section 3). A `DetectionReactor` class that contains
`Mono.class.isAssignableFrom(...)` would force the JVM to resolve `reactor.core.publisher.Mono` at the load of
`DetectionReactor` — defeating the optional-dependency pattern at the very first step. The string-based
hierarchy walk avoids this; it only inspects classes that are already loaded into the JVM by virtue of being
present in the user's actual code.

JDK types are exempt from this concern because they are guaranteed to be present at runtime.
`CompletionStage.class.isAssignableFrom(...)` in `DetectionAsync` is safe because `CompletionStage` is part of
the JDK; the JVM can always resolve it.

#### Architectural condition: no mixed dispatch structures

The mechanism is also defeated by code that touches multiple paradigm-specific classes in a shared structure.
For instance, populating an array `DispatchExtension[] extensions` with instances of every known extension
forces the JVM to load every extension class at array-initialisation time, regardless of which one is later
selected. The optional-dependency benefit is lost; sync-only users would suddenly need `inqudium-imperative`
on their classpath, because the array's initialisation references it.

The dispatcher must therefore select a paradigm *before* it touches paradigm-specific classes — a hard-wired
if-else chain that names each paradigm explicitly, rather than a generic iteration over a collection. This
discipline, together with the avoidance of class-literal references to external types in detection classes, is
not enforced by the compiler; it is a code-review responsibility.

### 5. Dispatch resolution and configuration error semantics

The dispatcher in `inqudium-pipeline` resolves each method through a three-step procedure, in order:

**Step 1 — Annotation check.** The dispatcher first asks whether the method carries any resilience-element
annotations. If not, the method is classified as pass-through (per ADR-036 section 7), invoked directly on
the real target without any further consideration. Methods returning paradigm-specific types (such as
`CompletionStage`) but lacking annotations follow this path; they require no paradigm support and trigger no
classpath checks.

**Step 2 — Paradigm-specific detection.** For annotated methods, the dispatcher consults each paradigm-specific
detection class in turn. Each detection class exposes two static methods:

- `canHandle(Method method)` — does this paradigm apply to the method? Typically a return-type check, e.g.
  `CompletionStage.class.isAssignableFrom(method.getReturnType())`.
- `isPresent()` — is the paradigm module's class on the runtime classpath?

The two checks are deliberately separate. `canHandle` answers a conceptual question ("is this an async
method?"); `isPresent` answers a configuration question ("is the async module available?"). Mixing them
would conflate two different failure modes.

The detection chain is hard-wired in the dispatcher, naming each paradigm explicitly:

```java
// In inqudium-pipeline, hard-wired chain:
if (DetectionAsync.canHandle(method)) {
    if (!DetectionAsync.isPresent()) {
        throw new IllegalStateException(
            "Method " + method + " requires async dispatch (return type " +
            method.getReturnType().getName() + "), " +
            "but inqudium-imperative is not on the classpath. " +
            "Add it to your build configuration.");
    }
    return AsyncDispatcher.dispatch(method, args, ...);
}

// Future paradigms appear here as additional named branches:
// if (DetectionReactor.canHandle(method)) { ... }

// Step 3 — fall through to sync default (see below).
```

**Step 3 — Synchronous default.** If no paradigm-specific detection has returned `true`, the method is
classified as synchronous and dispatched through the synchronous chain. Synchronous dispatch is always
available — `inqudium-pipeline` ships its synchronous logic directly. There is no `canHandle` and no
`isPresent` check for synchronous dispatch; it is the default catch-all.

#### The presence check

Each paradigm-specific detection class (`DetectionAsync`, future `DetectionReactor`, etc.) implements
`isPresent` using `Class.forName` with class initialisation disabled:

```java
public final class DetectionAsync {

    private static final boolean PRESENT = checkPresence();

    public static boolean canHandle(Method method) {
        return CompletionStage.class.isAssignableFrom(method.getReturnType());
    }

    public static boolean isPresent() {
        return PRESENT;
    }

    private static boolean checkPresence() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = DetectionAsync.class.getClassLoader();
        }
        try {
            Class.forName(
                "eu.inqudium.imperative.AsyncLayerAction",
                false,                                  // do not initialise
                loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

The presence check runs once, when `DetectionAsync` itself is first loaded. The result is cached in the
`PRESENT` static field. Subsequent invocations of `isPresent()` return the cached value with no further
classpath inspection.

`Class.forName(name, false, loader)` is the form the library uses: it locates the class by name without
triggering its static initialisers. This is sufficient for a presence check (we only need to know the class
exists) and avoids any side effects from initialisation.

The classloader chosen for the lookup is the *context classloader* of the current thread, falling back to the
classloader of the detection class itself if the context classloader is `null`. This is the established
pattern for libraries that may run in container environments (Spring Boot fat JARs, servlet containers, OSGi)
where the context classloader sees the user's optional dependencies, but the classloader of a library class
loaded by a parent loader may not. Implementations may extend this with additional defensive fallback paths
(e.g. trying both classloaders explicitly) without changing the spec; the spec mandates only that the context
classloader is consulted first and that a `null` context classloader does not cause a failure.

#### Failure outcomes

The four cases that arise from the three-step resolution map cleanly:

- **Annotated, paradigm-specific detection matches, module present.** The paradigm is used; dispatch proceeds.
- **Annotated, paradigm-specific detection matches, module absent.** The dispatcher throws
  `IllegalStateException` at proxy construction time (during phase-1 validation, per ADR-035) with a
  descriptive message identifying the offending method and the missing module.
- **Annotated, no paradigm-specific detection matches.** The method is dispatched synchronously.
- **Not annotated.** The method is pass-through; classpath state of paradigm modules is irrelevant.

The fail-fast principle applies to the third bullet's annotation case: errors surface during
`pipeline.protect(...)`, not at first method invocation. Users who configure the build incorrectly receive
clear diagnostics immediately, before any application traffic touches the proxy.

### 6. Separation between `inqudium-core` and `inqudium-pipeline`

`inqudium-core` carries the shared abstractions (`LayerAction`, `InqDecorator`, `Wrapper`, `PipelineIds`, the
resilience-element infrastructure). It does not contain `InqPipeline` itself; the pipeline is a composition
artefact, and its construction and dispatch live in `inqudium-pipeline`.

This split is deliberate. A future module may use the shared abstractions without using `InqPipeline` — for
instance, a function-based decoration scenario where `InqDecorator.decorateSupplier` is invoked directly
without ever building a pipeline. Such a use case depends only on `inqudium-core`. Users who want pipeline
composition depend additionally on `inqudium-pipeline`.

The split also keeps `inqudium-core` free of any paradigm-specific logic. The recognition predicates and the
dispatch routing live in `inqudium-pipeline`, not in `inqudium-core`. Future paradigms add their support by
contributing a paradigm module and by extending the dispatch logic in `inqudium-pipeline`; they do not modify
`inqudium-core`.

## Consequences

**Positive:**

- A user's classpath footprint matches the user's actual needs. Synchronous-only users do not pull in
  `CompletionStage`-based machinery; non-Reactor users do not pull in Reactor.
- Adding a new paradigm (e.g. RxJava 3) is a clean operation: a new module is created, its dispatch logic is
  contained within it, and `inqudium-pipeline` adds a recognition predicate plus a dispatch-routing branch.
  No existing module needs structural changes.
- The `pipeline.protect(...)` API is the single user-facing entry point regardless of which paradigms are in
  play. Users do not need to choose between sync, async, or hybrid factories; the library handles the choice
  internally based on the method signatures it encounters.
- Configuration errors are diagnosed clearly. A method requiring an absent paradigm module raises a
  library-specific `IllegalStateException` with a message identifying the offending method and the missing
  module — at proxy construction time, not at first invocation. Users do not have to interpret a JVM-level
  `NoClassDefFoundError`.
- The pattern is established and battle-tested. Slf4j, Jackson, and similar libraries use the same
  optional-dependency approach with success.

**Negative:**

- The separation between recognition (in `inqudium-pipeline`) and dispatch (in paradigm-specific modules)
  creates a coupling between modules that authors of new paradigms must understand. A new paradigm requires
  changes in two places — the paradigm module itself, and the recognition routing in `inqudium-pipeline`.
- The lazy-loading mechanism is an architectural condition that authors must respect. Two patterns can defeat
  it. First, mixing dispatch paths in a shared structure (an array of `DispatchExtension` instances iterated
  indiscriminately) forces eager class loading of all paradigms. Second, using class-literal references to
  external types (e.g. `Mono.class`) inside a detection class forces the JVM to resolve those types when the
  detection class itself is loaded — before the recognition logic ever runs. Both conditions are not enforced
  by the compiler; they must be caught at code-review time.
- Each paradigm-specific detection class carries two responsibilities: a conceptual `canHandle` check and a
  classpath `isPresent` check. Authors of new paradigms must implement both consistently. Forgetting the
  `isPresent` check would lead to `NoClassDefFoundError` at first method invocation; forgetting the
  `canHandle` check would mean the paradigm is never selected. Both failure modes are obvious in testing but
  must be caught at code-review time.
- The hard-wired if-else chain that selects paradigms is closed for third-party extension. Adding a new
  paradigm — for instance, an internal `inqudium-rxjava3` module developed within a user's organisation —
  requires modifying the `inqudium-pipeline` source code to register a new branch in the chain. The library
  does not provide a plugin mechanism (such as `ServiceLoader`) for runtime extension, because paradigm-
  specific dispatch interfaces deliberately do not share a common type — `LayerAction` (synchronous),
  `AsyncLayerAction` (two-phase async with decorated-copy contract), and any future paradigm interface
  express semantically different around-advice contracts that cannot be reduced to a single shared interface
  without losing type safety or semantic precision. A `ServiceLoader<X>` mechanism requires `X` to be a
  single common type, which would force a problematic abstraction over heterogeneous dispatch styles.
  Authors who need a new paradigm should propose its addition to the upstream project rather than treating
  the library as an open extension surface.

**Neutral:**

- The split between `inqudium-core` (shared abstractions) and `inqudium-pipeline` (composition + dispatch)
  reflects a separation of concerns rather than a forced one. Function-based decoration and other use cases
  that do not need a pipeline can depend on `inqudium-core` alone. The split is invisible to users who only
  use the pipeline-based API.
- Future paradigm modules (Reactor, RxJava, Kotlin coroutines) follow the same template. Each contributes a
  module with its dispatch logic; each receives recognition support in `inqudium-pipeline`. The architecture
  is open by design; ADR-035 acknowledges this in its mention of future Reactor support.
- The relationship to ADR-035 (proxy integration) is foundational: ADR-035 specifies the proxy as the
  user-visible integration, but the dispatch-routing mechanism it describes is realised through the module
  topology in this ADR. The two ADRs are read together — ADR-035 for the user-facing semantics, ADR-037 for
  the module-level realisation.
