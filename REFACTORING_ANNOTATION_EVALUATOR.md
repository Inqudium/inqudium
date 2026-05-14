# REFACTORING_ANNOTATION_EVALUATOR.md

**Status:** In progress
**Start date:** 2026-05-14
**Driving ADR:** ADR-036 (Annotation model)

This document plans the library-internal annotation evaluator described in
ADR-036. The evaluator turns the resilience-element annotations on a service
implementation into a per-method protection plan that downstream integrations
(future proxy integration, future annotation-driven AspectJ integration) can
consume.

## Scope

### In scope

1. Align `@InqShield` with ADR-036 §3 — replace the `"CUSTOM"` string-valued
   `order` mode with a typed `customOrder` attribute of type
   `InqElementType[]`. Keep the named-strategy `order` attribute, restricted
   to `"INQUDIUM"` and `"RESILIENCE4J"`.
2. A bridge-method resolver that implements the reflection-only algorithm
   from ADR-036 §5.
3. A method resolver that combines the bridge-method resolver with the
   default-method pass-through rule, and an inheritance resolver that
   applies the §6 inheritance semantics on top.
4. An ordering resolver that reads `@InqShield`, validates the rules from
   ADR-036 §9 that are decidable at the `@InqShield` layer, and returns the
   ordered list of element types for a given annotated element (either a
   method, for the method-level path, or a class, for the class-level
   path).
5. A top-level annotation evaluator that composes the above and, for a given
   pipeline + service interface + implementation class, produces a
   per-method `MethodPlan`. The plan is either pass-through or a decorated
   ordered list of element names.

### Out of scope

- Wiring the evaluator into `InqAsyncProxyFactory`, or producing a new
  factory that builds per-method dispatch paths from the evaluator's
  output. That is the next phase, in a separate module.
- `fallbackMethod` semantics. ADR-036 §10 explicitly defers this; the
  attribute is declared on the element annotations but is not interpreted.
- Meta-annotation support. ADR-036 §10 explicitly defers this.
- Annotation lookup on interface methods. ADR-036 §2 excludes this by
  design.
- **Migration of `inqudium-annotation-support` to use the new evaluator.**
  That module is the legacy annotation consumer (predates ADR-036) and
  currently uses `@InqShield(order = "CUSTOM")`. Its replacement by the new
  evaluator is the responsibility of a separate session and is explicitly
  out of scope here. See "Existing module landscape" below for the
  consequence.

## Module and package layout

- **Module:** `inqudium-annotation` (existing).
- **Existing package:** `eu.inqudium.annotation` — the annotation classes
  themselves continue to live here.
- **New package:** `eu.inqudium.annotation.evaluator` — the evaluator and
  its internal collaborators, plus the dedicated configuration exception
  type.
- **Module dependency:** `inqudium-annotation` depends on `inqudium-core`
  for `InqElementType`, `InqPipeline`, and the existing
  `PipelineOrdering.resilience4j()` ordering accessor. Added in sub-step 1.

## Existing module landscape

The repository contains an existing module `inqudium-annotation-support`
that predates ADR-036. It carries a legacy annotation scanner and a
pipeline factory that consume `@InqShield(order = "CUSTOM")` directly.

This phase **does not modify** that module. The phase produces a new
evaluator in `inqudium-annotation`; the migration of any caller from the
legacy support module to the new evaluator is the responsibility of a
separate session.

A practical consequence: after sub-step 1, the legacy module's `"CUSTOM"`
code path remains in place and the module continues to build green, but
no new `@InqShield` caller can target that path (the value is no longer
recognised by the evaluator-to-be). This is expected, transient drift; it
resolves when the separate migration session lands. At phase closure, a
`TODO.md` entry should remain to flag the deprecation pending so the
migration session has a clear pointer.

## Conventions for all sub-steps

These apply to every prompt issued for this phase and are not restated in
each individual prompt:

- Tests are JUnit 5 + AssertJ, with `@Nested` grouping by category,
  Given/When/Then comment markers, and full-sentence test method names in
  snake_case. No mocking libraries.
- Code, comments, Javadoc, commit messages, and PR descriptions are in
  English. Conversational explanations to the maintainer (in the
  implementation session's report) are in German.
- The evaluator and its collaborators are pure reflection. No bytecode
  analysis, no external utilities, no Spring imports.
- Fail-fast on validation errors: the first error aborts the evaluation
  with a descriptive exception. No error accumulation.
- A dedicated exception type — `InqAnnotationConfigurationException`,
  extending `IllegalStateException`, residing in
  `eu.inqudium.annotation.evaluator` — carries all evaluator-detected
  configuration errors. The exception message identifies the offending
  class, method, annotation, and attribute value where applicable.
  Introduced in sub-step 2.
- On any scope discrepancy between this plan and the code as it actually
  exists, the implementation session pauses and asks rather than silently
  correcting.
- Javadoc verification gates ignore warnings on files this sub-step did
  not touch (pre-existing warnings on the other element annotations are
  known and tracked separately).
- Every sub-step finishes by adding its own entry to the consolidated
  `## Completion log` section at the bottom of this document. The entry
  carries the sub-step ID, a short topic, the completion date, and the PR
  number.

## Sub-step 1: Align `@InqShield` with ADR-036 §3

### Goal

Change `@InqShield` from a single-attribute annotation with three string
modes (`"INQUDIUM" | "RESILIENCE4J" | "CUSTOM"`) to a two-attribute
annotation that uses a typed array for custom ordering.

### Spec

The annotation declares:

```java
public @interface InqShield {
    String order() default "INQUDIUM";          // "INQUDIUM" or "RESILIENCE4J" only
    InqElementType[] customOrder() default {};  // typed array, empty = "not set"
}
```

The Javadoc is updated to:

- Document the two attributes and their mutual-exclusion contract (a
  validation enforced later by the ordering resolver, not by the annotation
  itself).
- Remove all references to `"CUSTOM"` as a value for `order`.
- Replace the existing custom-order example with one that uses
  `customOrder = {InqElementType.RETRY, InqElementType.CIRCUIT_BREAKER}`.
- State that the empty default for `customOrder` means "not set" — Java
  annotations cannot represent null defaults.

Validation logic (mutual exclusion, well-formedness of `customOrder`) is
*not* part of this sub-step. It belongs to sub-step 4 (`OrderingResolver`).

### Module dependency

If `inqudium-annotation/pom.xml` does not yet declare a dependency on
`inqudium-core`, the implementation session adds it.

### What this sub-step does NOT do

- Implement any evaluator logic.
- Validate mutual exclusion of `order` and `customOrder`.
- Migrate any caller code that might use `@InqShield(order = "CUSTOM")`.

### Verification gates

- Full reactor `mvn verify` is green.
- Repository-wide grep for the literal `"CUSTOM"` in any `@InqShield(...)`
  context outside `inqudium-annotation` itself returns either zero matches
  or matches confined to `inqudium-annotation-support` (the legacy module,
  out of scope here).
- A new test class verifies via reflection that `@InqShield` declares
  exactly the two expected attributes with their expected default values.
- The Javadoc renders without new warnings on the changed file.

## Sub-step 2: `BridgeMethodResolver`

### Goal

Implement the reflection-only bridge-method resolution algorithm from
ADR-036 §5 as a package-private utility in
`eu.inqudium.annotation.evaluator`. Introduce the dedicated configuration
exception type `InqAnnotationConfigurationException` as part of this
sub-step.

### Spec

The resolver exposes a single static method:

```java
final class BridgeMethodResolver {
    static Method resolveBridge(Method bridge);
}
```

Input: a `Method` for which `isBridge() == true`. Output: the unique
non-bridge typed method on the same declaring class that satisfies the
algorithm from ADR-036 §5.

The three outcomes (single match, multiple matches, no match) are handled
exactly as the ADR specifies:

- Single match → return it.
- Multiple matches → throw `InqAnnotationConfigurationException` listing
  all candidates.
- No match → throw `InqAnnotationConfigurationException` indicating the
  bridge has no typed counterpart on its declaring class.

The implementation uses only `java.lang.reflect`. No bytecode analysis. No
external utilities.

### Tests

The test class produces test fixtures (small static nested classes with
generic interfaces) that exercise:

- Simple generic interface, one type parameter → one bridge, one match.
- Covariant return type → bridge with supertype return, typed method
  with subtype return; the assignability rule in step 4 of the algorithm
  resolves it.
- Multiple type parameters → multiple bridges, each resolves
  independently.
- Chained bridges within the same class — the typed method lives on the
  same class as the bridges.
- Ambiguity case — two typed candidates both satisfy the algorithm;
  expect `InqAnnotationConfigurationException` listing both.
- No-match case — synthetic constellation where no typed method matches
  the bridge; expect `InqAnnotationConfigurationException`.

### What this sub-step does NOT do

- Walk the class hierarchy. Cross-class bridge resolution is handled by
  the inheritance resolver in sub-step 3.
- Read annotations off the resolved method. That is the caller's
  responsibility.

### Verification gates

- Full reactor `mvn verify` is green.
- The test class covers each of the six listed cases with at least one
  test method.
- The resolver class itself has no public API surface beyond the single
  static method.

## Sub-step 3: `MethodResolver`, `AnnotationSource`, and `InheritanceResolver`

### Goal

Implement the two collaborators that, given an interface method and an
implementation class, identify both the method whose signature drives
dispatch and the location from which annotations should be read.
Combined, they realise ADR-036 §5 (method resolution) and §6
(inheritance), and they introduce the package-private structured result
type that downstream sub-steps consume.

### Spec

#### `MethodResolver`

Package-private interface in `eu.inqudium.annotation.evaluator`:

```java
interface MethodResolver {
    Optional<Method> resolveAnnotationSourceMethod(
            Method interfaceMethod, Class<?> targetClass);
}
```

Returns a `Method` declared on `targetClass` whose signature matches
`interfaceMethod`, with bridge methods resolved to their typed
counterparts. An empty `Optional` means either:

- The interface method is a default method and no class in the hierarchy
  starting from `targetClass` overrides it (per-class pass-through
  detection), or
- `targetClass` does not declare a method matching the interface method's
  signature.

The default implementation:

1. If `interfaceMethod.isDefault()`, check whether any class in the
   hierarchy starting from `targetClass` overrides the default. If none
   does, return `Optional.empty()`.
2. Otherwise, query `targetClass.getDeclaredMethods()` for a method
   whose name and parameter types match. If none is found on
   `targetClass` itself, return `Optional.empty()`.
3. If the returned method `isBridge()`, delegate to
   `BridgeMethodResolver.resolveBridge` and return its result wrapped in
   `Optional.of`.
4. Otherwise, return the method wrapped in `Optional.of`.

The two "empty" cases are not distinguished at this layer — that is
`InheritanceResolver`'s responsibility, which walks the hierarchy and
gathers the signature method from whichever class declares it.

#### `AnnotationSource`

A package-private sealed result type expressing the three outcomes of
the inheritance walk:

```java
sealed interface AnnotationSource {

    /**
     * Method-level resilience annotations apply. Annotations live on
     * {@code method}, which may be the implementation's own method or
     * an inherited method on a superclass. Class-level annotations on
     * the implementation are ignored entirely, per ADR-036 §6.
     */
    record MethodLevel(Method method) implements AnnotationSource {}

    /**
     * No method-level resilience annotations exist anywhere in the
     * hierarchy. The implementation class carries class-level resilience
     * annotations (possibly via {@code @Inherited} from a superclass).
     * The signature method drives dispatch; the annotations are read
     * from {@code annotationSourceClass}.
     */
    record ClassLevelOnly(Method signatureMethod,
                          Class<?> annotationSourceClass)
            implements AnnotationSource {}

    /**
     * No resilience annotations apply to this method.
     */
    record PassThrough() implements AnnotationSource {}
}
```

#### `InheritanceResolver`

Package-private interface:

```java
interface InheritanceResolver {
    AnnotationSource resolve(Method interfaceMethod, Class<?> implementationClass);
}
```

The default implementation composes a `MethodResolver` and applies the
following algorithm in this order: hierarchy walk first, pass-through
decision second.

1. **Hierarchy walk for method-level annotations.** Walk
   `implementationClass` and its superclass chain, stopping at (but not
   visiting) `Object.class`. At each `currentClass`, invoke
   `methodResolver.resolveAnnotationSourceMethod(interfaceMethod, currentClass)`.
   If the result is present and the method carries any Inqudium element
   annotation, return `MethodLevel(method)` immediately. Otherwise,
   record the first non-empty result as `lowestDeclaringMethod` and
   continue.
2. **No-implementation gate.** If no class in the chain declared the
   method (`lowestDeclaringMethod == null`), return `PassThrough`.
3. **Class-level fallback.** If `implementationClass` has any class-level
   resilience annotation (`isAnnotationPresent` transparently handles
   `@Inherited`), return
   `ClassLevelOnly(lowestDeclaringMethod, implementationClass)`. Otherwise
   return `PassThrough`.

Defensive checks at entry: reject `null` with `IllegalArgumentException`.

### Tests

See `MethodResolverTest` and `InheritanceResolverTest`. All three
`AnnotationSource` variants are reached by separate tests, with the
intermediate-class cases explicitly covered. At least one test pins
the bridge integration; at least one test pins the JC2 scenario
(default-method overridden by intermediate class with annotation, impl
inherits without re-overriding → `MethodLevel`).

### Verification gates

- Full reactor `mvn verify` is green.
- All three `AnnotationSource` variants are reached.
- `ClassLevelOnly` has separate tests for direct-on-impl,
  `@Inherited`-from-superclass, and intermediate-override paths.
- At least one test exercises the bridge-method path through the
  inheritance walk.

## Sub-step 4: `OrderingResolver`

### Goal

Read `@InqShield` from a given annotated element (a `Method` for the
method-level path, a `Class` for the class-level path), validate the
`@InqShield`-layer rules from ADR-036 §9, and return the ordered list of
element types (outermost first) for that element.

### Spec

Package-private interface in `eu.inqudium.annotation.evaluator`:

```java
interface OrderingResolver {
    List<InqElementType> resolveOrder(AnnotatedElement annotationSource);
}
```

`AnnotatedElement` is the `java.lang.reflect` interface implemented by
both `Method` and `Class<?>`. Using it directly lets the same resolver
handle both the `MethodLevel` and `ClassLevelOnly` paths from
`AnnotationSource` without overloads or wrappers.

Logic:

1. Read all Inqudium element annotations from `annotationSource` and
   determine the set of present element types.
2. Read `@InqShield` from `annotationSource`. If absent, treat as
   `order = "INQUDIUM"` with empty `customOrder` and no further
   `@InqShield` validation.
3. If `@InqShield` is present:
   - If `customOrder.length > 0` and `order` is not the default
     `"INQUDIUM"`, throw `InqAnnotationConfigurationException` (mutual
     exclusion).
   - If `customOrder.length > 0`:
     - Every element type present on the annotation source must appear
       in `customOrder`. Otherwise, throw.
     - Every entry in `customOrder` must correspond to a present element
       type on the annotation source. Otherwise, throw.
     - The ordering is the `customOrder` array, taken as-is.
   - Else if `order` is `"INQUDIUM"` → sort the present types by
     `InqElementType.defaultPipelineOrder()` ascending.
   - Else if `order` is `"RESILIENCE4J"` → sort the present types by the
     existing `PipelineOrdering.resilience4j()` ordering.
   - Else → throw `InqAnnotationConfigurationException` (unknown `order`).
4. Return the ordered list.

If the annotation source carries no element annotations, the resolver
returns an empty list (no exception).

Defensive checks at entry: reject `null` with `IllegalArgumentException`.

### Tests

See `OrderingResolverTest`. All three ordering modes are covered
positively, all four §9 validation rules are pinned negatively with
exception-message assertions, and at least one test uses a `Class<?>`
as input.

### Verification gates

- Full reactor `mvn verify` is green.
- Every validation rule from ADR-036 §9 that is decidable at the
  `@InqShield` layer has at least one negative test pinning the error
  message.
- Positive paths cover all three ordering modes (INQUDIUM default,
  RESILIENCE4J, custom).
- At least one test uses a `Class<?>` as the annotation source.

## Sub-step 5: `AnnotationEvaluator`

### Goal

Compose the previous sub-steps into the public top-level evaluator.
Given a pipeline plus a service interface and its implementation class,
produce a per-method `MethodPlan` map.

### Spec

Public types:

```java
public sealed interface MethodPlan {
    record PassThrough() implements MethodPlan {}
    record Decorated(List<String> elementNamesOuterToInner) implements MethodPlan {}
}

public record EvaluationResult(Map<Method, MethodPlan> plans) {
    public EvaluationResult {
        plans = Map.copyOf(plans);
    }
}

public interface AnnotationEvaluator {
    static AnnotationEvaluator forPipeline(InqPipeline pipeline);
    EvaluationResult evaluate(Class<?> serviceInterface, Class<?> implementationClass);
}
```

`MethodPlan` is a sealed interface with two record variants.
`EvaluationResult` is a record whose compact constructor defensively
copies the input map so the returned plan is immutable. The public API
surface is exactly four types: `MethodPlan`, `EvaluationResult`,
`AnnotationEvaluator`, and `InqAnnotationConfigurationException` (the
last one already public from sub-step 2). `AnnotationSource` and the
three resolver interfaces remain package-private.

Algorithm (per ADR-036 §1, §2, §4):

For each method on `serviceInterface`:

1. Invoke `InheritanceResolver.resolve(interfaceMethod, implementationClass)`,
   yielding an `AnnotationSource`.
2. Dispatch on the result. In the non-`PassThrough` cases, the relevant
   `AnnotatedElement` is either the method (for `MethodLevel`) or the
   annotation source class (for `ClassLevelOnly`).
   - `PassThrough` → `MethodPlan.PassThrough`.
   - `MethodLevel(method)` → the annotated element is `method`. The
     annotations are method-scope; class-level annotations on the
     implementation are ignored, per ADR-036 §6.
   - `ClassLevelOnly(signatureMethod, annotationSourceClass)` → the
     annotated element is `annotationSourceClass`. The annotations are
     class-scope; the method itself has none.
3. Read all Inqudium element annotations from the chosen annotated
   element. For each annotation's `value()` (the element instance name),
   verify that the pipeline contains an element with that name.
   Otherwise, throw `InqAnnotationConfigurationException` naming the
   service interface, the method, the annotation, and the missing
   element name.
4. Resolve the ordering via
   `OrderingResolver.resolveOrder(annotatedElement)`.
5. Project each ordered element type onto its element name from the
   annotations. Wrap the resulting `List<String>` in
   `MethodPlan.Decorated`.

Fail-fast: the first error in any method aborts the entire evaluation
with `InqAnnotationConfigurationException`. The cache key on the
resulting map is the interface method, per ADR-036 §5 step 5.

The default implementation `DefaultAnnotationEvaluator` is
package-private and wires up the three resolvers
(`DefaultMethodResolver`, `DefaultInheritanceResolver`,
`DefaultOrderingResolver`) inside its constructor. The static factory
`AnnotationEvaluator.forPipeline(InqPipeline)` returns an instance.

### Tests

End-to-end tests with synthetic service interfaces covering:

- Single-method interface, single element annotation, name in pipeline
  → `Decorated` with one entry.
- Single-method interface, multiple element annotations, default order
  → `Decorated` with INQUDIUM order.
- Default interface method, impl does not override → `PassThrough`.
- Method without resilience annotations anywhere in the chain →
  `PassThrough`.
- Annotation references an element name not in the pipeline → throws,
  message names the missing element.
- Class-level annotation, method without method-level annotation →
  `Decorated` derived from class-level annotations.
- Method-level annotation overrides class-level annotation of a
  different type — class-level not in the plan.
- Generic interface with a bridge on the impl → plan resolved against
  the typed method's annotations, cached against the interface method.
- Multiple methods on the same interface produce independent plans.
- Defensive: null input → `IllegalArgumentException`.

### Verification gates

- Full reactor `mvn verify` is green.
- The end-to-end tests cover all bullets above.
- The public API surface under
  `inqudium-annotation/src/main/java/eu/inqudium/annotation/evaluator/`
  consists of exactly the four types named in the Spec section.
  `AnnotationSource`, `MethodResolver`, `InheritanceResolver`,
  `OrderingResolver`, and their default impls all remain
  package-private.
- The `EvaluationResult` record's plan map is immutable — modifying
  the returned map via cast or reflection raises
  `UnsupportedOperationException` (this is a side effect of
  `Map.copyOf`, no explicit assertion required unless the test author
  wants one).

## Phase closure

When all sub-steps are approved:

1. ADR-036 transitions from "Proposed" to "Accepted" in a separate commit
   by the maintainer. The phase does not change ADR status itself.
2. A `TODO.md` entry remains for the deprecation of
   `inqudium-annotation-support`: the legacy `"CUSTOM"` code path is no
   longer reachable from new `@InqShield` callers, and the module needs
   replacement by a separate session.
3. This document is deleted. Its content has moved to the code (the
   evaluator and its tests), to the ADR (already there), and to `TODO.md`
   for any deferred items that surfaced during execution.
4. If any audit-style findings surfaced during the phase (e.g.
   constellations the bridge-method resolver cannot handle but that
   appear in real user code), they are routed: into `TODO.md` if they
   constitute deferred work, into a follow-up sub-step inside this phase
   if they block phase closure, into `IDEAS.md` if they are speculative.

## Completion log

- [x] 1 — `@InqShield` aligned with ADR-036 §3 (2026-05-14, PR #53)
- [x] 2 — `BridgeMethodResolver` and `InqAnnotationConfigurationException` (2026-05-14, PR #54)
- [x] 3 — `MethodResolver`, `AnnotationSource`, and `InheritanceResolver` (2026-05-14, PR #55)
- [x] 4 — `OrderingResolver` (2026-05-14, PR #56)
- [x] 5 — `AnnotationEvaluator` (2026-05-14, PR #TBD)
