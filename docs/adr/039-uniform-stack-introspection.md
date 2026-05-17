# ADR-039: Uniform stack introspection across wrapping paradigms

**Status:** Proposed
**Date:** 2026-05-13
**Deciders:** Core team
**Related:** ADR-003 (event system), ADR-022 (call identity propagation),
ADR-035 (proxy architecture), ADR-037 (module topology and paradigm
recognition).

## Implementation status

**Proposed.** Sub-step 3.12 of the proxy rewrite implements the
proxy-side adapter and DTOs as standalone artefacts in
`eu.inqudium.proxy.introspection`:

- `ProxyStackAdapter` (with `supports(Object)` and `inspect(Object)`)
- `ProxyStackInfo`, `MethodLayers` — standalone records whose
  shapes exactly match the ADR-039 specification, ready to fold
  into a sealed `InqStackInfo` hierarchy without contract change
- `MethodSignatureFormatter` — the canonical signature format
  defined by this ADR

**Deferred** to a separate library-wide refactor of comparable
scope to the proxy rewrite itself:

- The central `InqIntrospector` adapter chain in
  `inqudium-pipeline`, including the `DetectionAspectJ` and
  `DetectionSpringAop` probes
- The `InqStackInfo` sealed interface and its four permits
  (`FunctionStackInfo`, `ProxyStackInfo`, `AspectJStackInfo`,
  `SpringAspectStackInfo`)
- `FunctionStackAdapter`, `AspectJStackAdapter`,
  `SpringAspectStackAdapter`
- `InqStackRenderer` (`toTree`, `toJson`)
- The library-wide `chainId` → `stackId` rename
  (`BulkheadOnAcquireEvent`, `BulkheadEventPublishFailureException`,
  `InqRuntimeException`, etc.)
- The `SerializedLambda` tier-2 method resolution

ADR-039 will be promoted to "Accepted" only after the deferred
work lands. Until then, the proxy adapter is consumed directly
(no central dispatch).

## Context

The `Wrapper<S extends Wrapper<S>>` interface in `inqudium-core/pipeline`
was designed for one shape of resilience attachment: a function decorated
by a single chain of layers. Its public surface — `chainId()`, `inner()`,
`layerDescription()`, `toStringHierarchy()` — is single-chain by
construction. Every concrete wrapper today (function-based wrappers and
the JDK-proxy-based `ProxyWrapper`) is forced into that shape.

Resilience is, in practice, attached to programs through four
mechanisms:

1. **Function-based wrappers** — `RunnableWrapper`, `SupplierWrapper`,
   `FunctionWrapper`, `CallableWrapper`. One function, one chain, one
   logical method (the SAM). The current `Wrapper` interface fits this
   case without friction.
2. **JDK dynamic proxies** — `ProxyWrapper` with one or more
   `DispatchExtension` instances. Today, a `PipelineDispatchExtension`
   holds one `InqPipeline` and applies it uniformly to every service
   method (`canHandle(Method) == true`). The proxy exposes N methods but
   has one chain.
3. **AspectJ-woven targets** — bytecode-woven aspects around methods of
   a plain class. There is no wrapper instance and no `InvocationHandler`;
   the resilience layers are compiled into the class itself.
4. **Spring AOP-based aspects** — singleton bean acting as around-advice
   for matching method executions, attached via the Spring AOP
   infrastructure (CGLIB proxy or JDK proxy with `Advised` interface).

Cases 3 and 4 are not even nominally "wrappers" — `Wrapper` as an
interface name does not fit. Yet they all share the same observable
property: a method invocation passes through an ordered sequence of
resilience layers before reaching the target.

The current design has three coupled problems:

- **Conceptual mismatch.** `Wrapper` for a proxy means "this proxy
  layer in the proxy stack", not "this chain of resilience layers
  around a function". The two senses share an interface only because
  the proxy today happens to be single-pipeline.
- **No common API across paradigms.** AspectJ and Spring AOP cannot
  share a parent type with inqudium's own wrappers — they originate
  in unrelated frameworks with unrelated runtime contracts. Anything
  that wants to introspect "the resilience structure around this
  object" needs a paradigm-specific path today.
- **Recursive self-type generic leaks.** `Wrapper<S extends Wrapper<S>>`
  is an implementation detail of how the chain is walked safely
  without unchecked casts. It has no business being part of the
  user-facing introspection API.

The library is in 0.x with no external users, so backwards-compatibility
is not a constraint on this ADR.

## Considered options

### Option A — Keep `Wrapper`, document the single-pipeline limitation

`ProxyWrapper` stays single-pipeline. Users requiring per-method
resilience differentiation split their service interfaces (often
desirable for DDD reasons anyway) or stack multiple proxies. AspectJ
and Spring AOP integrations get separate introspection paths.

Rejected: leaves heterogeneity unsolved, keeps the recursive self-type
in the public API, and conflates internal chain-walk mechanics with
external introspection.

### Option B — Make `Wrapper` method-aware

`Wrapper` grows a `Map<Method, Chain>` view. This forces every
wrapper subtype (including the function-based ones with exactly one
SAM method) into a map shape and still does not accommodate AspectJ
or Spring AOP, which do not implement `Wrapper`.

Rejected: muddies the abstraction without solving the heterogeneity
problem.

### Option C — Split `Wrapper` into function- and proxy-flavoured subinterfaces

Two interfaces with a shared supertype, each carrying its own
introspection shape. Cleaner than B but still requires AspectJ and
Spring AOP integrations to either implement an inqudium interface
they have no business knowing about, or sit outside the introspection
API entirely.

Rejected: improves the function/proxy split but does not unify across
wrapping paradigms.

### Option D — External introspector with adapter-resolved DTO (chosen)

An `InqIntrospector` utility takes any object and returns an
`InqStackInfo` describing its resilience structure. Adapter classes
inside the library know how to introspect each paradigm
(function-wrapper, proxy, AspectJ, Spring AOP). The DTO is a sealed
hierarchy of records. The wrapper's internal contract is freed from
the introspection responsibility and can stay package-private.

This is the chosen option.

## Decision

### Public API

```java
import java.util.Collections;

public final class InqIntrospector {
    public static Optional<InqStackInfo> inspect(Object instance);
}

public sealed interface InqStackInfo permits
        FunctionStackInfo, ProxyStackInfo,
        AspectJStackInfo, SpringAspectStackInfo {

    long stackId();

    Optional<Class<?>> targetType();

    List<InqElement> elements();

    List<MethodLayers> methodLayers();
}

public record MethodLayers(
        String methodSignature,
        List<String> layerDescriptions,
        Optional<Method> method) {
    public MethodLayers {
        layerDescriptions = Collections.unmodifiableLis(layerDescriptions);
    }
}

public record FunctionStackInfo(
        long stackId,
        Optional<Class<?>> targetType,
        List<InqElement> elements,
        List<MethodLayers> methodLayers) implements InqStackInfo {
    public FunctionStackInfo {
        elements = Collections.unmodifiableLis(elements);
        methodLayers = Collections.unmodifiableList(methodLayers);
    }
}

// ProxyStackInfo, AspectJStackInfo, SpringAspectStackInfo follow the
// same shape. Subtype-specific fields (e.g. Spring bean name) may be
// added as additional record components when concrete diagnostic
// value justifies them.
```

### Identifier rename: `chainId` → `stackId`

The single `long` identifier carried through a resilience invocation
is renamed from `chainId` to `stackId` library-wide. The semantics
are clarified:

- Function-based wrappers: one `stackId` per wrapper instance.
- Proxies: one `stackId` per proxy instance, shared across all
  intercepted methods.
- AspectJ-woven targets: one `stackId` per target instance, shared
  across all advised methods.
- Spring AOP aspects: one `stackId` per advised bean. Because Spring
  aspect beans are typically singletons, an adapter may opt not to
  synthesise a per-call identifier; the `stackId` then identifies
  the bean and not the call site.

The rename is in scope of this ADR end-to-end. Every occurrence of
`chainId` is renamed: `Wrapper.chainId()` → `Wrapper.stackId()`,
`BaseWrapper.chainId()` → `BaseWrapper.stackId()`, `InternalExecutor`
parameter `chainId` → `stackId`, event-record fields, Javadoc, test
names. The library is in 0.x; a single coherent rename is cheaper than
a phased one.

### Method signature format

`MethodLayers.methodSignature` is a single, stable, human-readable
string. The format is:

```
<DeclaringClassSimpleName>.<methodName>(<Param1SimpleName>, <Param2SimpleName>, ...)
```

Rules:

- Class names use `Class#getSimpleName()` (which is empty for
  anonymous classes — in that case the binary name's last
  `$`-segment is substituted).
- Parameter types are listed in declaration order, joined by `", "`.
- Array types are rendered as `Foo[]`, varargs collapse to the
  array form.
- The return type is omitted. It rarely disambiguates overloads
  (return type does not participate in Java overload resolution)
  and adds clutter for diagnostic logs.
- Generic type parameters are omitted (erased at the `Method`
  level anyway).

Examples:

- `Supplier.get()`
- `OrderService.processOrder(Order)`
- `BiFunction.apply(String, Integer)`
- `Repository.findAll(Predicate, Pageable)`

Stability: this format depends only on the declared method and is
independent of the stack it appears in. Adding or removing methods
from the same stack does not change any existing signature.

### Parameter simple-name collisions

Two methods on different declaring classes may share both simple
name and simple parameter names — for example:

```java
class Foo {
    void process(java.util.function.Predicate p) {}
    void process(com.foo.Predicate p) {}
}
```

Both produce `Foo.process(Predicate)`. This is an accepted
limitation of the canonical signature format. Disambiguation is
**not** baked into the signature string; instead:

- `MethodLayers.method()` carries the cached `java.lang.reflect.Method`
  object and is the canonical identity in all cases.
- `methodLayers()` is a `List`, not a `Map` keyed by signature.
  Multiple entries with identical `methodSignature` but different
  `method()` values are valid and intended.
- Rendering layers (see "Renderer separation" below) are responsible
  for detecting duplicate signatures within an `InqStackInfo` and
  appending a display-time disambiguator if needed (e.g. an index
  suffix `#2` on the second occurrence). This keeps the canonical
  signature clean for the 99% of methods that do not collide and
  produces distinguishable output only when collision is real.

This is a deliberate separation: the DTO carries facts, the renderer
chooses how to display them.

### Method caching and lambda support

`MethodLayers.method` is computed once at adapter time and stored
inside the record. No reflection is performed on accessor calls. The
adapter strategy depends on the wrapper construction path:

1. **Explicit `Method` hint.** Wrapper factories expose an overload
   that accepts a `java.lang.reflect.Method` directly:
   `SupplierWrapper.of(supplier, MyService.class.getMethod("process"))`.
   When used, `method()` carries the supplied `Method` verbatim.
2. **Method reference via `SerializedLambda`.** When the wrapped value
   is a `Serializable` lambda or method reference, the adapter extracts
   the implementation method via the `writeReplace` /
   `SerializedLambda` mechanism (`getImplClass`,
   `getImplMethodName`, `getImplMethodSignature`) and reconstructs the
   `Method` object. This recovers the original method for users who
   pass `myService::process` against a `Serializable` functional
   interface.
3. **SAM fallback.** When neither of the above applies, the SAM
   method of the wrapped value's static functional type is used —
   e.g. `Supplier.class.getMethod("get")` for a `Supplier<T>`. The
   `methodSignature` then reads `Supplier.get()`.
4. **No recoverable identity.** For paradigms where the reflective
   `Method` is not recoverable (some AspectJ compile-time-weaving
   join points, some Spring configurations, lambda values whose
   static type cannot be determined), `method` is
   `Optional.empty()` and `methodSignature` falls back to
   `<unknown>.<unknown>()`.

The four-tier resolution is applied in this order; the first
applicable tier wins.

### Adapter discovery: hardwired chain (per ADR-037)

`InqIntrospector#inspect` walks a hardwired chain of known adapters
in declared order. The chain is closed for third-party extension —
new paradigms must be proposed upstream — consistent with the
rationale in ADR-037 for paradigm dispatch.

```java
public final class InqIntrospector {
    public static Optional<InqStackInfo> inspect(Object instance) {
        if (DetectionAspectJ.isPresent()
                && AspectJStackAdapter.supports(instance)) {
            return Optional.of(AspectJStackAdapter.inspect(instance));
        }
        if (DetectionSpringAop.isPresent()
                && SpringAspectStackAdapter.supports(instance)) {
            return Optional.of(SpringAspectStackAdapter.inspect(instance));
        }
        if (ProxyStackAdapter.supports(instance)) {
            return Optional.of(ProxyStackAdapter.inspect(instance));
        }
        if (FunctionStackAdapter.supports(instance)) {
            return Optional.of(FunctionStackAdapter.inspect(instance));
        }
        return Optional.empty();
    }
}
```

Each `Detection*` class uses `Class.forName(name, false, loader)` to
test for the paradigm module on the classpath — identical to the
pattern established in ADR-037. Paradigm-specific types stay
lazily loaded; an absent paradigm never causes class-loading of its
types.

`ServiceLoader` is deliberately not used. The set of supported
paradigms is closed; adapter authors who introduce new paradigms
must propose them upstream, matching the rationale in ADR-037 for
paradigm dispatch.

### Empty result, not exception

When no adapter matches, `inspect` returns `Optional.empty()` rather
than throwing. Introspecting an arbitrary object that happens not to
carry resilience structure is not an error condition; it is a
legitimate query whose answer is "no resilience structure was
detected on this object".

### Element resolution per adapter

Each adapter is responsible for populating `InqStackInfo.elements()`
with the actual `InqElement` objects underlying the stack. The path
varies by paradigm:

- **Function and Proxy adapters** access the `InqPipeline` carried
  by the wrapper instance (function wrappers store it directly;
  `PipelineDispatchExtension` holds it on the proxy side).
- **AspectJ adapter** reads the element references woven into the
  aspect-compiled class. Concrete mechanism depends on the AspectJ
  integration design (out of scope for this ADR).
- **Spring AOP adapter** has the option, but not the obligation, to
  resolve elements via the `ApplicationContext` if the user's Spring
  configuration exposes an `InqRepository` bean. When it cannot,
  `elements()` returns an empty list. The DTO contract permits this
  partial result without becoming invalid.

The introspector itself takes no registry argument and performs no
global lookup. Resolution is fully encapsulated in the adapter.

### Renderer separation

The current `Wrapper#toStringHierarchy()` default method is removed.
Rendering becomes a separate concern:

```java
public final class InqStackRenderer {
    public static String toTree(InqStackInfo info);
    public static String toJson(InqStackInfo info);
}
```

Renderers take an `InqStackInfo` and produce paradigm-agnostic
output. Renderers are also responsible for display-time
disambiguation of duplicate method signatures (see "Parameter
simple-name collisions" above). New rendering formats are added
without touching the introspector or the DTO.

### DTO immutability

All record components that hold collections are wrapped with
`List.copyOf(...)` in the canonical constructor. This produces both
a defensive copy and an unmodifiable list in one step. The DTO is
therefore deeply immutable for collection fields; `Optional`,
`long`, and `Class<?>` components are immutable by their own
contracts.

## Consequences

**Positive:**

- Function-based wrappers, JDK proxies, AspectJ-woven targets, and
  Spring AOP aspects share one introspection API. Heterogeneous
  paradigms become uniform from the user's viewpoint.
- The recursive self-type generic `Wrapper<S extends Wrapper<S>>`
  is no longer part of the public introspection API. It can be
  reduced to a package-private chain-structure contract or removed
  entirely once the chain-walk mechanics are reorganised.
- Per-method introspection is structurally supported. The DTO carries
  a list of `MethodLayers` regardless of whether the underlying stack
  currently differentiates per method. Future per-method pipeline
  support requires no DTO change.
- Method references against `Serializable` functional interfaces
  recover their original method through the `SerializedLambda`
  extraction path. Users who care about identity get it without
  passing a `Method` hint explicitly.
- Renderer is a separate, replaceable concern. Tree output, JSON
  output, and any future format do not touch the DTO or the
  introspector. Rendering is also where signature disambiguation
  lives, keeping the canonical signature clean.
- The method signature format is stable per-method and human-readable.
  It is safe to embed in logs, dashboards, and JFR events.
- The `chainId` → `stackId` rename is coherent: every place that
  uses the identifier reads `stackId` after this ADR lands. No
  interim mixed naming.

**Negative:**

- The Spring AOP adapter may return `elements()` as an empty list
  when the user's configuration does not expose an `InqRepository`
  bean accessible to the adapter. The DTO is correct but
  diagnostically thinner. The rendered output for such stacks shows
  the method layers but not the element instances behind them.
- The closed adapter chain means new wrapping paradigms (e.g. a
  Quarkus interceptor model, a Helidon SE integration) require
  modifying `InqIntrospector` rather than dropping a JAR on the
  classpath. This is consistent with ADR-037's stance for paradigm
  dispatch and is the price of guaranteed classpath isolation.
- Parameter simple-name collisions across packages produce identical
  signature strings in the canonical format. The DTO remains
  unambiguous via `method()`, but rendered output requires
  renderer-side disambiguation logic. Renderers that ignore this
  responsibility produce duplicate-looking lines for the (rare)
  colliding cases.
- The `SerializedLambda` extraction path requires the wrapped lambda
  or method reference to be `Serializable`. Plain
  `Supplier<T>` / `Function<T, R>` references without a
  `Serializable` intersection type fall back to the SAM method of
  the static type, which may be less informative than the user
  expects.

**Neutral:**

- Lambda values produce a `FunctionStackInfo` whose
  `methodSignature` is `Supplier.get()` (or the equivalent SAM) when
  the lambda's static type is recoverable, the original
  implementation method when the lambda is `Serializable`, or
  `<unknown>.<unknown>()` when neither path applies. The `method()`
  Optional is correspondingly populated or empty.
- Subtype-specific fields (Spring bean name, AspectJ aspect name,
  proxy chain-link status) are not part of the initial DTO. They
  can be added later as record components on the relevant subtype
  without breaking other subtypes. The sealed hierarchy is flat,
  per the explicit decision; an intermediate
  `MethodStackInfo` is not introduced because no current consumer
  needs to pattern-match across "all method-based variants" as a
  group.
- The introspector is a cold-path API. No effort is made to optimise
  for high-frequency invocation. Repeated `inspect(...)` calls on
  the same instance re-walk the adapter chain; if a future use case
  needs caching, it is added on top, not inside.

