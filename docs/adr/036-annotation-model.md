# ADR-036: Annotation model

**Status:** Proposed  
**Date:** 2026-05-05  
**Deciders:** Core team

## Context

Application code declares which methods are protected by which resilience elements through annotations on the
implementation class. Annotations such as `@InqBulkhead`, `@InqCircuitBreaker`, `@InqRetry`, and the umbrella
`@InqShield` carry the per-method protection configuration.

Annotations alone do nothing; they need an *evaluator* — a component that reads the annotations at the relevant
moment and translates them into a concrete protection strategy for each method. The library carries its own
evaluator for some integration technologies, while delegating the evaluation to an external framework for others.

This ADR specifies the rules the library's own evaluator follows. It is the normative reference for the annotation
semantics in those contexts.

### Scope

This ADR applies wherever the library evaluates annotations itself. Currently this covers the proxy integration
(see ADR-035). It does not cover:

- **Spring AOP integration.** Spring evaluates annotations using its own mechanism (`AnnotationUtils`,
  `AnnotatedElementUtils`, the `@Inherited` interpretation that differs from JVM defaults, and Spring's
  bridge-method handling). The library does not specify or constrain Spring's evaluation; tying the library's
  semantics to Spring's would also tie the library to Spring's evolution.
- **AspectJ integration today.** The current AspectJ integration does not evaluate annotations at all. Pointcuts
  are written explicitly by the application author. If a future revision introduces an annotation-driven AspectJ
  mode, this ADR will apply there too — the rule is "wherever the library evaluates annotations, this ADR
  governs", not "for proxy only".

The annotations themselves (`@InqBulkhead` etc.) are declared so that they are usable by all of these contexts.
The shared declaration is the common surface; the evaluation rules diverge.

## Decision

The library's annotation evaluator follows the rules in this section.

### 1. Application model

Each resilience element type has a corresponding annotation. The annotation carries a single string attribute that
references the element by name:

```java
@InqBulkhead("orderBh")
@InqCircuitBreaker("orderCb")
public Order placeOrder(Cart cart) { ... }
```

A name referenced in an annotation must match the name of an element in the relevant `InqPipeline` (or, in the
future, in the `InqRegistry` once that is introduced). Otherwise the configuration is invalid.

The optional `@InqShield` annotation controls how multiple element annotations are composed for a given method.
Without `@InqShield`, the default ordering rules apply.

### 2. Annotation source

Annotations are declared on the *implementation class*, not on the interface. This follows the convention
established by Spring and similar frameworks:

```java
public interface OrderService {
    Order placeOrder(Cart cart);
}

public class DefaultOrderService implements OrderService {
    @InqBulkhead("orderBh")
    @Override
    public Order placeOrder(Cart cart) { ... }
}
```

The interface is left clean. Resilience is an implementation concern; the implementation declares it.

This decision has practical consequences. Test doubles and alternative implementations of the same service
interface must each declare their own annotations if they are to be protected. This is by design — different
implementations may have different protection requirements (e.g. a remote-service implementation needs a circuit
breaker; an in-memory test stub does not).

Annotations declared on interface methods are not consulted by the evaluator. Authors who annotate the interface
should not expect the annotations to take effect; the implementation is the only source.

### 3. Composition order

When a method carries multiple resilience-element annotations, the order in which the elements wrap the method
call is determined by the optional `@InqShield` annotation. The annotation has two mutually exclusive attributes:

- **`order`** — selects a named ordering strategy. Recognised values:
  - `"INQUDIUM"` (default) — composition follows the canonical order defined by the `InqElementType` enum. Each
    element type carries an ordering weight (e.g. `BULKHEAD = 400`); the evaluator sorts the annotations by their
    element type's weight, ascending. The current `InqElementType` enum defines the order
    `TimeLimiter → TrafficShaper → RateLimiter → Bulkhead → CircuitBreaker → Retry`, outermost to innermost
    (per ADR-017). Future element types are added to the enum with their ordering weight and are integrated
    into the canonical sequence at their assigned position.
  - `"RESILIENCE4J"` — composition follows the canonical order used by the Resilience4j library:
    `Retry → CircuitBreaker → TrafficShaper → RateLimiter → TimeLimiter → Bulkhead`, outermost to innermost.
    This is offered for users migrating from or interoperating with Resilience4j.
- **`customOrder`** — an explicit ordering specified as an array of `InqElementType` values:

  ```java
  @InqShield(customOrder = {InqElementType.BULKHEAD, InqElementType.CIRCUIT_BREAKER})
  @InqBulkhead("orderBh")
  @InqCircuitBreaker("orderCb")
  public Order placeOrder(Cart cart) { ... }
  ```

  The evaluator wraps the method in the listed types' order, outermost first. The custom-order array is reusable
  across methods and across services — authors can define a constant `private static final InqElementType[]
  MY_ORDER = ...` and reference it from `@InqShield(customOrder = MY_ORDER)`. The array may list element types
  that a given annotated source does not actually carry; the evaluator silently filters such entries out during
  projection. The only requirement is that every element type that *is* present on the source must appear in
  `customOrder` — otherwise the resolved order would be ambiguous for that source. This makes a single shared
  constant usable across methods that select different subsets of the listed element types.

Without an explicit `@InqShield`, the `"INQUDIUM"` default applies. Authors do not need to write
`@InqShield(order = "INQUDIUM")` for the canonical case.

Source-order — the textual order in which annotations were written by the author — is not used as an ordering
mechanism. The Java reflection API does not guarantee that `Method.getDeclaredAnnotations()` returns annotations
in source-declaration order; relying on it would produce inconsistent behaviour across compilers and toolchains.
Authors who want a specific order must declare it explicitly via `customOrder`.

### 4. Pipeline-as-universe rule

The annotation evaluator selects elements from the pipeline that the integration was configured with (e.g. the
pipeline passed to `InqAsyncProxyFactory.of(pipeline)` for proxy integration). The selection is constrained:

- An annotation referencing a name not present in the pipeline causes a configuration error. The error surfaces
  at construction time (eager validation), not at first invocation.
- An annotation can only select elements; it cannot add elements that are not in the pipeline.
- An annotation cannot duplicate elements; the pipeline already enforces at most one element per element type.

The pipeline therefore defines the closed universe of available protection. Per-method variation is by *omission*
only — methods may select a subset of the pipeline's elements, but no method introduces elements outside the
pipeline.

### 5. Method resolution

The evaluator processes each method that the integration will dispatch. For the proxy integration, this is each
method on the service interface that the JDK proxy delivers to the invocation handler. The evaluator must
determine the *annotation source method* — the `Method` object on which annotations are looked up:

1. If the interface method is a *default method* and the implementation does not override it, the method is
   classified as pass-through (see section 7). No annotation lookup is performed.
2. Otherwise, the implementation class is queried for a method with the same name and parameter types as the
   interface method. If the returned method is not a bridge method, it is the annotation source.
3. If the returned method is a *bridge method* (`Method.isBridge() == true`) — typically generated by the Java
   compiler when generics on the interface are concretised on the implementation — the bridge method is *not*
   used as the annotation source. Bridge methods are synthetic and never carry user-written annotations. The
   evaluator must resolve the bridge to its corresponding typed method using the algorithm below.
4. The resolved annotation source method is used to read all `@InqShield`, `@InqBulkhead`, `@InqCircuitBreaker`,
   and other resilience annotations.
5. The cache key (or whatever the integration uses to store per-method protection metadata) is the *interface
   method*, because dispatch later receives the interface method via the JDK proxy mechanism. The annotation
   source method is used only for annotation discovery.

#### Bridge-method resolution algorithm

Given a bridge method `B` declared on the implementation class, the evaluator scans the declared methods of the
same class for a candidate typed method `T` such that:

1. `T.getName().equals(B.getName())`,
2. `T.getParameterCount() == B.getParameterCount()`,
3. For each parameter index `i`: `B.getParameterTypes()[i].isAssignableFrom(T.getParameterTypes()[i])` —
   `T`'s parameter type at position `i` is at least as specific as `B`'s,
4. `B.getReturnType().isAssignableFrom(T.getReturnType())` — `T`'s return type is at least as specific as
   `B`'s,
5. `T.isBridge() == false`.

The outcome of the scan is one of three cases:

- **Exactly one match.** `T` is the resolved annotation source method.
- **Multiple matches.** The configuration is ambiguous. The evaluator fails with a descriptive error
  identifying the bridge method and all matching candidates. Resolution does not proceed.
- **No match.** This is unexpected for code produced by a standard Java compiler from a well-formed source. The
  evaluator fails with an error rather than silently falling back to the bridge method.

The algorithm is intentionally reflection-only. It does not perform bytecode analysis. In normal use this is
sufficient: the Java compiler generates bridge methods for a single purpose (bridging the erased and typed
signatures of an overridden generic method), and exactly one typed method satisfies the algorithm above.

The hard-fail behaviour on ambiguity is deliberate. Constellations that produce ambiguity — for instance, a
generic interface implementation that adds overloaded methods whose parameter types are subtypes of the bridge's
erased parameter types — are rare in practice and arguably an anti-pattern. Authors encountering the failure
should restructure their implementation rather than rely on a heuristic guess. Even sophisticated bytecode-based
resolvers (such as Spring's `BridgeMethodResolver`) need to fall back to heuristics in such constellations and
may produce results that diverge from the compiler's actual choice; the library's strict reflection-only approach
trades flexibility for predictability.

#### Bridge-method edge cases

The algorithm handles the following cases correctly:

- **Covariant return types.** A method that overrides an inherited method with a more-specific return type causes
  the compiler to generate a bridge with the supertype's return type. The bridge delegates to the typed method.
  The algorithm's rule 4 (return-type assignability) accommodates this case.
- **Multiple generic parameters.** A class implementing an interface with multiple type parameters
  (`class Foo implements Bar<Integer, String>`) may carry multiple bridge methods, one per type-parameter
  position. Each is resolved independently using the algorithm.
- **Chained bridges.** In deep inheritance hierarchies with intermediate generic refinements, a bridge method may
  exist on an intermediate class that refers to a typed method on a more derived class. The algorithm scans the
  declared methods of the class containing the bridge; if the typed method exists on the same class, resolution
  succeeds. Chained-bridge constellations where the typed method lives on a different class are handled by the
  inheritance traversal rules of section 6 — the evaluator traverses the class hierarchy and applies the
  resolution algorithm at each level.

The library's bridge-method resolver is implemented as a library-internal algorithm. The library does not depend
on external utilities (e.g. Spring's `BridgeMethodResolver`); existing public-domain implementations may be
consulted as references for the algorithm but the library carries its own.

### 6. Inheritance

The evaluator follows Spring's convention for combining method-level and class-level annotations:

- **Method-level annotations override class-level annotations completely.** If the annotation source method
  declares any resilience-element annotation, the evaluator uses only the method's annotations and ignores all
  class-level annotations for that method.
- **Class-level annotations apply only when the method declares no resilience annotations of its own.** A class
  annotated with `@InqBulkhead("classBh")` provides this protection for all methods that themselves carry no
  resilience annotations.
- **Method-level annotations are searched up the class hierarchy.** If the annotation source method on the direct
  implementation class carries no resilience annotations but an overridden method on a superclass does, the
  superclass method's annotations apply. The evaluator traverses the class hierarchy upward until a method with
  resilience annotations is found, or until the chain ends.
- **Class-level annotations are searched up the class hierarchy via the JVM's `@Inherited` mechanism.** Inquudium's
  annotations are declared with `@Inherited`, so a class-level annotation on a superclass automatically applies to
  subclasses unless overridden.
- **`@InqShield` falls under the same method-overrides-class rule.** When the evaluated method carries any
  method-level resilience annotation, the ordering is read from the method's own `@InqShield` (if declared) or
  defaulted to `"INQUDIUM"`; a class-level `@InqShield` declared on the implementation does **not** apply in this
  case. Conversely, when only class-level resilience annotations apply (the class-level-only path), the
  class-level `@InqShield` governs the ordering of those class-level annotations. In short, `@InqShield` is read
  from the same `AnnotatedElement` as the element annotations whose ordering it controls; the method-versus-class
  decision happens once for the whole annotation set, not separately for `@InqShield`.

Concrete consequences with examples:

```java
@InqBulkhead("classBh")
public class Service {

    public Order m1(...) { ... }
    // Effective: Bulkhead("classBh")
    // (Class-level applies because the method has no resilience annotations.)

    @InqRetry("methodRt")
    public Order m2(...) { ... }
    // Effective: Retry("methodRt")
    // (Method-level overrides class-level completely.)
}
```

This is the strict Spring convention. Authors who want to combine class-level defaults with method-level
additions must write all relevant annotations on the method explicitly.

### 7. Pass-through behaviour

A method without resilience annotations (after applying the inheritance rules of section 6) is classified as
*pass-through*. The integration invokes the method directly on the real target, with no resilience processing.

A default method on the service interface is also classified as pass-through, but only when the implementation
does not override it. The library treats unoverridden default methods as user-defined convenience methods that
delegate to abstract methods of the same interface. Protecting them would introduce double protection — the
default method itself wrapped, and the abstract method it internally calls wrapped again. The pass-through
classification avoids this. If the default method internally calls an abstract method of the same interface,
that internal call goes through the integration's normal dispatch and receives the abstract method's protection.

When the implementation *does* override the default method, the overriding method is treated as a normal
implementation method. Its annotations are evaluated per the rules in sections 5 and 6; pass-through
classification does not apply. From the evaluator's perspective, an overridden default method is
indistinguishable from any other implemented method.

### 8. Visibility and self-invocation

The evaluator only sees methods that flow through the integration's dispatch mechanism. For the proxy
integration, this means only public interface methods are visible to the evaluator and subject to annotation
processing.

- **Non-public methods of the implementation are silently ignored.** Annotations on protected, package-private,
  or private methods of the implementation class are never evaluated, because the proxy never receives such calls.
  The proxy only implements public interface methods.
- **Self-invocation bypasses the integration.** When an implementation method calls another method on `this`,
  the call invokes the implementation directly without flowing through the proxy. The resilience-stack does not
  apply to such internal calls. This is a known limitation of JDK dynamic proxies (and equally of Spring AOP).

Authors who require resilience on internal calls have two options. They may extract the protected logic into a
separate component that is itself proxied, then inject the proxied component as a dependency. Alternatively, they
may obtain the proxy reference (e.g. through Spring's self-injection pattern, or by holding a separate reference)
and route the internal call through the proxy reference rather than `this`. Neither workaround is provided by the
library; both follow established patterns from Spring AOP.

### 9. Validation

All annotation-related errors that can be detected from the configuration must surface at construction time —
when the integration is being assembled — not at first invocation. The errors include:

- An annotation referencing a name not present in the pipeline.
- A method whose annotations include element types that are not listed in its `@InqShield(customOrder = ...)`,
  if `customOrder` is set.
- A `@InqShield` declaration that sets both `order` and `customOrder` simultaneously.
- A class-level annotation referencing a name not present in the pipeline (the error surfaces for any method that
  inherits the class-level annotation; it is acceptable for the error to be reported per-method).
- An `order` value that is not one of the recognised strings.
- An ambiguous bridge-method resolution (multiple typed methods match the bridge — see section 5).
- A bridge method without any matching typed method (also section 5).

The error messages should identify the offending method (or class), the offending annotation, and the offending
attribute value. The evaluator is the choke point where these errors should be detected; downstream code can
assume the annotation graph is well-formed.

### 10. Out of scope

The following capabilities are explicitly not supported by this evaluator:

- **Meta-annotations.** A user-defined annotation that itself carries `@InqBulkhead`, `@InqRetry`, etc. — for
  instance, a custom `@MyResilientRemoteCall` declared as `@InqBulkhead("remoteBh") @InqRetry("remoteRt")
  @interface MyResilientRemoteCall {}` — is not recognised. Only annotations directly present on the method or
  class are evaluated. Recursive resolution of meta-annotations is a possible future extension; today it is
  intentionally absent to keep the evaluator simple.
- **Annotation inheritance from interface methods.** Resilience annotations on interface methods are not
  consulted, by design (see section 2).
- **The `fallbackMethod` attribute.** The element annotations (`@InqBulkhead`, `@InqCircuitBreaker`, etc.)
  declare a `fallbackMethod` attribute that names a method to invoke when the protected method fails or is
  rejected by the resilience element. This attribute is not interpreted by the evaluator. It is reserved for
  a future extension whose design — invocation semantics, signature requirements, behaviour on async
  dispatch, interaction with the resilience-stack — is not yet finalised. Authors may declare the attribute
  on their annotations; the evaluator silently ignores its value. A separate ADR will specify the fallback
  semantics when the design is settled.

## Consequences

**Positive:**

- The implementation-as-source convention matches Spring and similar frameworks. Authors familiar with Spring's
  `@Transactional` or `@Cacheable` find the model immediately readable.
- Class-level defaults plus method-level overrides give authors a familiar mechanism for keeping common protection
  configurations DRY.
- The `customOrder` attribute uses `InqElementType` values rather than names, making it compile-time checked and
  reusable across methods. Authors can define a shared `InqElementType[]` constant and reference it from many
  methods.
- Method resolution via bridge-method skipping handles generic interfaces transparently. Authors do not need to
  understand bridge methods or compiler-generated synthetics; the evaluator hides this complexity.
- Eager validation at construction time gives immediate feedback. Misnamed annotations, malformed `@InqShield`
  values, and missing pipeline elements all fail fast, where the configuration is being assembled.
- The pipeline-as-universe rule constrains the configuration surface. There is no way for an annotation to
  introduce protection that the integration is not aware of, which prevents whole classes of misconfiguration.
- Self-invocation and visibility limitations are documented explicitly, matching the reality of JDK dynamic
  proxies. Authors are forewarned and can adopt the established workaround patterns rather than discovering the
  limitation through unexpected behaviour.

**Negative:**

- The strict Spring convention (method-level overrides class-level completely) means class-level annotations lose
  their effect for any method that declares any resilience annotation. Authors who expect partial-override
  semantics will be surprised.
- Method-level inheritance up the class hierarchy means that an annotation on a deeply-inherited method may not
  be visible to authors reading only the concrete class. The behaviour is what authors expect from Spring, but it
  introduces non-locality.
- Annotations on interface methods are silently ignored by this evaluator. Authors who annotate the interface
  may be confused when nothing happens, especially if their previous experience is with frameworks where
  interface annotations have meaning.
- Self-invocation is a real-world trap. Authors who refactor a method out of an existing class without rerouting
  internal callers through the proxy lose protection on those internal calls without any warning from the
  library. This is an industry-wide limitation of proxy-based interception, not specific to the library.
- Meta-annotations are not supported. Authors who design DSL-style annotations for their domains must wait for a
  future evaluator extension or compose protection through direct annotations.
- The bridge-method resolver fails hard on ambiguous constellations rather than guessing. Authors who hit such
  constellations — typically by adding overloaded methods to a class that implements a generic interface — must
  restructure their implementation rather than rely on a heuristic. The trade-off favours predictability over
  flexibility; the alternative (reflection-based heuristics or bytecode analysis) is either unreliable or
  introduces an external dependency.

**Neutral:**

- The annotation declarations themselves are usable by external evaluators. Spring AOP, for instance, evaluates
  `@InqBulkhead` according to Spring's rules. The library's evaluator and Spring's evaluator may differ in
  edge-case interpretation; this is acceptable as long as each evaluator is internally consistent and the choice
  of evaluator is determined by the integration technology.
- The bridge-method resolver is a library-internal algorithm. It is implemented by the library, not delegated to
  external utilities, to keep the library independent of any specific external dependency. Existing public-domain
  implementations (for instance, Spring's `BridgeMethodResolver`) may be consulted as references for the
  algorithm but are not depended upon.
