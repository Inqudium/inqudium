# ADR-037: Module topology and integration dispatch

**Status:** Accepted  
**Date:** 2026-05-17  
**Deciders:** Core team

## Implementation status

**Accepted.** The module topology and class-loading discipline
mandated by this ADR are realised in `inqudium-proxy`:

- The module is split into `inqudium-pipeline` (mandatory),
  `inqudium-annotation` (mandatory), `inqudium-imperative`
  (optional `<optional>true</optional>` per §6), `inqudium-proxy`
  itself, and `inqudium-core` (transitive)
- `eu.inqudium.pipeline.DetectionProxy` and
  `eu.inqudium.proxy.dispatch.DetectionAsync` implement the
  presence-probe pattern (§4)
- `eu.inqudium.pipeline.ProxyDelegation` is the reflective bridge
  that avoids the Maven cycle between `inqudium-pipeline` and
  `inqudium-proxy` (§5)
- `eu.inqudium.proxy.construction.AsyncEntryBuilder` (introduced
  in sub-step 3.13a) is the class-loading firewall that ensures
  `MethodDispatchEntryFactory.class`'s `BootstrapMethods` attribute
  contains no `inqudium-imperative` type. This realises §6's
  "no async class loads on the sync-only path" invariant — a
  contract the JVMS §5.4 mechanics of `invokedynamic` resolution
  make subtle to satisfy (see `ARCHITECTURE.md` §13 for the
  mechanism)

Empirically verified by
`eu.inqudium.proxy.discipline.ModuleLoadingDisciplineTest` (two
URLClassLoader-isolated test methods) as permanent regression
guards.

## Context

The library applies resilience compositions through several integration technologies — functional decoration,
JDK dynamic proxies, AspectJ weaving, Spring AOP — and supports several dispatch paradigms within those
technologies (synchronous, asynchronous, with reactive types as a likely future addition). Each integration and
each paradigm comes with its own classes, dependencies, and runtime requirements.

A naïve approach would put everything into a single library artefact, forcing every user to pull in transitive
dependencies they may not need. A user with synchronous-only services would still receive `CompletionStage`
machinery; a user without Reactor on their classpath would still see Reactor classes in their dependency tree.

This ADR specifies the module topology and the optional-dependency mechanism the library uses to keep each
user's footprint minimal — only the modules actually needed for the chosen integration and paradigms end up on
the classpath. It also specifies how the user-facing `InqPipeline` API stays uniform across all integrations
while delegating to the appropriate integration-specific dispatch.

### Architectural principles

Three principles drive the topology:

- **`inqudium-core` does not depend on integration modules.** The core stays at the bottom of the dependency
  graph. A reverse dependency from core to proxy or aspect would create a fragile circular tendency.
- **`InqPipeline` is the uniform user-facing API.** Regardless of integration technology, users build a
  pipeline and call `pipeline.protect(...)` on it. The choice of integration is encoded in the method
  overload selected by the call's argument types, not in a separate factory class.
- **`inqudium-pipeline` is a thin interface module without dispatch responsibilities.** It defines the
  `InqPipeline` interface, its builder, and the per-integration detection classes — nothing more. The actual
  dispatch logic for any integration lives in that integration's own module.

## Decision

### 1. Module topology

The library is structured into a small core, a thin pipeline interface module, and a set of integration
modules. The structure is:

| Module                | Role                                                                                                                                                  | Depends on                                          |
|-----------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------|
| `inqudium-core`       | Shared interfaces (`LayerAction`, `InqDecorator`, `Wrapper`), correlation IDs (`PipelineIds`), the resilience-element infrastructure, *functional decoration* and *synchronous dispatch* | (nothing else in the library)                       |
| `inqudium-pipeline`   | The `InqPipeline` interface with default methods routing to integrations, the pipeline builder, per-integration detection classes                     | `inqudium-core`; optional deps to all integrations  |
| `inqudium-proxy`      | JDK dynamic proxy infrastructure, annotation evaluator (per ADR-036), proxy dispatcher                                                                | `inqudium-core`; optional dep to `inqudium-imperative` |
| `inqudium-imperative` | Asynchronous dispatch — `AsyncLayerAction`, `InqAsyncDecorator`, `InternalAsyncExecutor`, async dispatcher                                            | `inqudium-core`                                     |
| (future) `inqudium-aspect`   | AspectJ integration — weaver setup, advice classes                                                                                              | `inqudium-core`                                     |
| (future) `inqudium-spring`   | Spring AOP integration as a thin adapter over `inqudium-proxy` (see section 7)                                                                  | `inqudium-core`, `inqudium-proxy`, `inqudium-pipeline` |
| (future) `inqudium-reactor`  | Reactor-specific dispatch — recognition of `Mono`/`Flux` return types, reactive subscription chain                                              | `inqudium-core`                                     |

Each integration module is a standalone artefact. A user with synchronous-only functional decoration depends
only on `inqudium-core`. A user with proxy-based resilience additionally depends on `inqudium-pipeline` and
`inqudium-proxy`. A user with hybrid sync/async proxies adds `inqudium-imperative` on top.

### 2. Optional dependencies

`inqudium-pipeline` declares each integration module as an *optional* dependency:

- Maven: `<optional>true</optional>` on the dependency declaration.
- Gradle: `compileOnly` configuration.

The optional declaration has two effects:

- The integration module is on the compile classpath of `inqudium-pipeline`. The pipeline interface's default
  methods can reference integration-specific dispatcher classes at compile time.
- The integration module is *not* transitively propagated to consumers of `inqudium-pipeline`. A user who
  depends on `inqudium-pipeline` does not automatically receive `inqudium-proxy` or other integration modules.

Users declare the integration modules they need explicitly in their own build configuration. This makes the
classpath footprint a deliberate user choice, not a transitive byproduct.

The same pattern applies inside integration modules. `inqudium-proxy` declares `inqudium-imperative` as
optional, so that proxy users who do not need async dispatch are not forced to include async machinery.

### 3. `InqPipeline` as the uniform user-facing API

`InqPipeline` is declared as an interface in `inqudium-pipeline`. It carries one method overload per
integration; the overload an application invokes selects the integration:

```java
public interface InqPipeline {

    List<InqElement> elements();

    // Functional decoration — synchronous, always available.
    default <T> Supplier<T> protect(Supplier<T> target) {
        return FunctionalDispatcher.protect(this, target);
    }

    // Proxy-based — requires inqudium-proxy on the classpath.
    default <T> T protect(Class<T> serviceInterface, T target) {
        if (!DetectionProxy.isPresent()) {
            throw new IllegalStateException(
                "Method protect(Class<T>, T) requires inqudium-proxy on the classpath. " +
                "Add the dependency to your build configuration.");
        }
        return ProxyDispatcher.protect(this, serviceInterface, target);
    }

    // AspectJ-based — requires inqudium-aspect on the classpath.
    // default <T> T protect(Object aspect, Object target) { ... }
}
```

The overload is selected at compile time from the argument types: a `Supplier<T>` routes to functional
decoration; a `Class<T>` paired with a target routes to proxy; future overloads route to other integrations.
The user does not name an integration explicitly; the call site's argument types do.

This pattern preserves the uniform API (`pipeline.protect(...)` regardless of context) while keeping
`inqudium-pipeline` free of dispatch logic — each default method delegates to a dispatcher class in the
relevant integration module. The body of each default method is small: a presence check plus a delegation
call.

### 4. Detection classes in `inqudium-pipeline`

For each integration module, `inqudium-pipeline` carries a detection class. The detection class exposes:

- `isPresent()` — is the integration module's class on the runtime classpath?

Detection classes are small and live alongside `InqPipeline`. Each provides the presence check that the
corresponding default method invokes. There is no `canHandle(Method)` predicate at the integration level —
that is a paradigm-level concern (sync vs. async vs. Reactor) handled inside the integration module, not at
the `InqPipeline` level.

A typical detection class:

```java
public final class DetectionProxy {

    private static final boolean PRESENT = checkPresence();

    public static boolean isPresent() {
        return PRESENT;
    }

    private static boolean checkPresence() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = DetectionProxy.class.getClassLoader();
        }
        try {
            Class.forName(
                "eu.inqudium.proxy.ProxyDispatcher",
                false,
                loader);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
```

The presence check runs once, when the detection class is first loaded. The result is cached. Subsequent
invocations return the cached value.

`Class.forName(name, false, loader)` is the form the library uses: it locates the class by name without
triggering its static initialisers.

The classloader chosen for the lookup is the *context classloader* of the current thread, falling back to the
classloader of the detection class itself if the context classloader is `null`. This is the established
pattern for libraries that may run in container environments (Spring Boot fat JARs, servlet containers, OSGi)
where the context classloader sees the user's optional dependencies, but the classloader of a library class
loaded by a parent loader may not. Implementations may extend this with additional defensive fallback paths
(e.g. trying both classloaders explicitly) without changing the spec; the spec mandates only that the context
classloader is consulted first and that a `null` context classloader does not cause a failure.

### 5. Paradigm-level dispatch inside integration modules

Within an integration module, paradigm selection (sync vs. async vs. Reactor) is the integration module's
own concern. The proxy module is the canonical example.

`inqudium-proxy` knows about its supported paradigms (sync, async) and contains paradigm-specific dispatch
classes (`SyncDispatcher`, `AsyncDispatcher`). When a proxy is constructed, the dispatcher in `inqudium-proxy`
classifies each method by its return type and routes to the appropriate per-paradigm dispatcher:

```java
// In inqudium-proxy, during proxy construction:
public Object dispatch(Method method, Object[] args) {
    if (isAsyncMethod(method)) {
        if (!DetectionAsync.isPresent()) {
            throw new IllegalStateException(
                "Method " + method + " requires async dispatch (return type " +
                method.getReturnType().getName() + "), " +
                "but inqudium-imperative is not on the classpath.");
        }
        return AsyncDispatcher.dispatch(method, args);
    }
    // Sync default — always available, no detection needed.
    return SyncDispatcher.dispatch(method, args);
}
```

The detection of paradigms inside `inqudium-proxy` follows the same pattern as the detection of integrations
inside `inqudium-pipeline`: a detection class with `isPresent()`, a hard-wired if-else chain, no shared
collection of dispatchers.

#### Class-literal references and the type-reference trap

Detection-related code must avoid class-literal references to types from optional modules. A direct reference
such as `Mono.class` in `DetectionReactor` would force the JVM to resolve `reactor.core.publisher.Mono` when
`DetectionReactor` itself is loaded — before the recognition logic ever runs — raising
`NoClassDefFoundError` if Reactor is absent.

JDK types are exempt from this concern because they are guaranteed to be present at runtime:

```java
// JDK type — direct class-literal reference is safe.
private static boolean isAsyncMethod(Method method) {
    return CompletionStage.class.isAssignableFrom(method.getReturnType());
}
```

External types require string-based comparison, which the library provides through a helper that walks the
type hierarchy without class-literal references:

```java
// External type — string-based comparison required.
private static boolean isReactorMonoMethod(Method method) {
    return isAssignableTo(method.getReturnType(), "reactor.core.publisher.Mono");
}

public static boolean isAssignableTo(Class<?> type, String targetTypeName) {
    if (type == null || type == Object.class) {
        return false;
    }
    if (targetTypeName.equals(type.getName())) {
        return true;
    }
    if (isAssignableTo(type.getSuperclass(), targetTypeName)) {
        return true;
    }
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
(via the method's actual return type, which is necessarily loaded if the user invokes the method), it does
not introduce any new class-literal references. If Reactor is absent, `Mono` is never loaded, the helper
never encounters it in the hierarchy walk, and the recognition correctly returns `false`.

### 6. Lazy class loading: why separation works

The JVM combines two mechanisms that, together, make the optional-dependency pattern reliable:

- **Lazy class loading.** The JVM loads a class only when its first runtime use is reached — a `new`
  expression, a static field access, a method invocation. Merely referencing a class name in bytecode (e.g.
  in a method that is never executed) does not trigger loading.
- **Lazy verification.** Modern JVMs do not eagerly resolve every type referenced in a class's bytecode at
  load time. When a class is loaded, the JVM defers verification of its referenced types until the first
  branch that needs them is executed.

A concrete trace. Suppose `pipeline.protect(myClass, myTarget)` is called with `inqudium-proxy` absent:

1. The JVM enters the default method `protect(Class<T>, T)` on `InqPipeline`. The interface is already
   loaded; no proxy-related loading has occurred yet.
2. The JVM evaluates `DetectionProxy.isPresent()`. `DetectionProxy` is in `inqudium-pipeline` (always
   present); it is loaded if not already loaded. Its `isPresent` returns `false` because `ProxyDispatcher`
   is not on the classpath.
3. The `if`-branch fires the `IllegalStateException`. The reference to `ProxyDispatcher.protect(...)` is
   *never resolved*; the JVM does not attempt to load `ProxyDispatcher` or any class it transitively
   references.

If the same call is made with `inqudium-proxy` present, step 2 returns `true`, the `if`-branch is skipped,
and the JVM proceeds to `ProxyDispatcher.protect(...)`. `ProxyDispatcher` is loaded on first execution of
this line; the proxy's own paradigm-detection logic runs from there.

#### What lazy loading does not protect

The lazy-loading guarantee operates between *method bodies that are not executed*. It does not operate within
a class that is being loaded. When the JVM loads a class, it must resolve every type referenced in the class's
constant pool — including types referenced by class-literal expressions (`SomeType.class`) and by field
declarations whose types are mentioned directly. If any of those referenced types is absent from the
classpath, the JVM raises `NoClassDefFoundError` at the load of the referencing class itself, not when a
particular method is invoked.

This is why detection classes for integrations or paradigms whose marker types are *not* part of the JDK
must use string-based references for their target types (see section 5). JDK types are exempt because they
are guaranteed to be present at runtime.

#### Architectural condition: no mixed dispatch structures

The mechanism is also defeated by code that touches multiple integration-specific classes in a shared
structure. For instance, populating an array `Dispatcher[] dispatchers` with instances of every known
dispatcher forces the JVM to load every dispatcher class at array-initialisation time, regardless of which
one is later selected. The optional-dependency benefit is lost; users would suddenly need every integration
module on their classpath, because the array's initialisation references all of them.

The default methods in `InqPipeline` must therefore select an integration *before* touching
integration-specific classes — a hard-wired branch chain that names each integration explicitly. This
discipline, together with the avoidance of class-literal references to external types in detection classes,
is not enforced by the compiler; it is a code-review responsibility.

### 7. Spring AOP as a thin adapter over `inqudium-proxy`

The library's Spring AOP integration is planned (but not implemented in the initial release) as a thin
adapter over `inqudium-proxy` rather than as an independent dispatch implementation. A Spring aspect bean
holds a JDK proxy per target (Spring bean); the aspect's `@Around` advice locates the proxy for the current
target and delegates to it.

The conceptual sketch:

```java
// In inqudium-spring, future:
public class InqShieldAspect {
    private final InqPipeline pipeline;
    private final Map<Object, Object> proxyCache = new ConcurrentHashMap<>();

    @Around("...")
    public Object around(ProceedingJoinPoint pjp) {
        Object target = pjp.getTarget();
        Object proxy = proxyCache.computeIfAbsent(target, t ->
            pipeline.protect(/* iface */ t.getClass().getInterfaces()[0], t));
        // ...delegate to the corresponding method on proxy...
    }
}
```

This planned architecture has two benefits. First, it preserves a single proxy implementation for the
library — Spring users transparently benefit from the same dispatch logic, the same correlation-ID handling
(ADR-034), the same annotation evaluation (ADR-036). Second, it preserves the uniform user-facing API: a
Spring application configures an `InqPipeline` bean and an `InqShieldAspect` bean over it; the rest is
indistinguishable from the standalone proxy use case.

The detailed specification for the Spring adapter is deferred to a future ADR. This ADR records the
architectural intent so the choice is preserved through the initial implementation.

## Consequences

**Positive:**

- A user's classpath footprint matches the user's actual needs. Synchronous-only functional-decoration users
  depend on `inqudium-core` alone; proxy users add `inqudium-pipeline` and `inqudium-proxy`; async users
  additionally include `inqudium-imperative`.
- Adding a new integration is a clean operation: a new module is created with its own dispatcher, a detection
  class is added to `inqudium-pipeline`, and an overload of `protect(...)` is added to the `InqPipeline`
  interface. No existing module needs structural changes.
- The `pipeline.protect(...)` API is the single user-facing entry point regardless of integration. The
  method overload chosen by argument types is the implicit integration selector; users do not need to learn
  factory classes or configuration knobs.
- Configuration errors are diagnosed clearly. A method overload that requires an absent integration module
  raises a library-specific `IllegalStateException` with a message identifying the missing module — at the
  point of `protect(...)` invocation, not silently or as a JVM-level `NoClassDefFoundError`.
- The `inqudium-pipeline` module is small and stable. It deals only with pipeline composition and
  integration routing; it does not embed integration-specific complexity. Changes to integration internals
  do not ripple into the pipeline module.
- The pattern is established and battle-tested. Slf4j, Jackson, and similar libraries use the same
  optional-dependency approach with success.

**Negative:**

- The separation between detection (in `inqudium-pipeline`) and dispatch (in integration-specific modules)
  creates a coupling between modules that authors of new integrations must understand. A new integration
  requires changes in three places: the integration module itself, a detection class in `inqudium-pipeline`,
  and a new overload on the `InqPipeline` interface.
- The lazy-loading mechanism is an architectural condition that authors must respect. Two patterns can
  defeat it. First, mixing dispatchers in a shared structure (an array of `Dispatcher` instances iterated
  indiscriminately) forces eager class loading of all integrations. Second, using class-literal references
  to external types (e.g. `Mono.class`) inside a detection class forces the JVM to resolve those types when
  the detection class itself is loaded — before the recognition logic ever runs. Both conditions are not
  enforced by the compiler; they must be caught at code-review time.
- Each detection class carries the responsibility of correctly probing for its target. Authors must
  implement `isPresent` consistently and choose a target class whose presence reliably indicates the
  integration's availability. Probing the wrong class would lead to false negatives (rejecting a present
  module) or false positives (accepting a partial module). The risk is small but real.
- The hard-wired overload chain on `InqPipeline` is closed for third-party extension. Adding a new
  integration — for instance, an internal `inqudium-kotlin-coroutines` module developed within a user's
  organisation — requires modifying the `inqudium-pipeline` source code. The library does not provide a
  plugin mechanism (such as `ServiceLoader`) for runtime extension, because integration interfaces
  deliberately do not share a common type — the `protect(...)` overloads have different argument types and
  return types that cannot be reduced to a single shared interface without losing type safety. Authors who
  need a new integration should propose its addition to the upstream project rather than treating the
  library as an open extension surface.

**Neutral:**

- The split between `inqudium-core` (shared abstractions plus functional decoration plus synchronous
  dispatch) and `inqudium-pipeline` (interface plus routing) reflects a separation of concerns rather than a
  forced one. Function-based decoration users who do not need pipeline composition can depend on
  `inqudium-core` alone. The split is invisible to users who only use the pipeline-based API.
- Future integration modules (Reactor, RxJava, Kotlin coroutines, etc.) follow the same template. Each
  contributes a module with its dispatcher; each receives a detection class in `inqudium-pipeline` and an
  overload on `InqPipeline`. The architecture is open by design, scaled by adding modules rather than by
  modifying existing ones.
- The Spring AOP adapter strategy (section 7) means the eventual Spring integration becomes a small adapter
  rather than a full re-implementation. This is recorded as architectural intent to be preserved through
  the initial implementation; detailed specification follows in a future ADR.
- The relationship to ADR-035 (proxy integration) is foundational: ADR-035 specifies the proxy as one
  user-visible integration; ADR-037 specifies how the proxy is reached through `pipeline.protect(...)` and
  how its module relates to the rest of the topology.
