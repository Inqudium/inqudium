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
   ordered list of element types for a given method.
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
  `PipelineOrdering.Profiles.RESILIENCE4J` ordering. Added in sub-step 1.

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

`annotationSourceClass` in `ClassLevelOnly` is the implementation class
passed into `InheritanceResolver.resolve` — the caller reads class-level
annotations from it via standard reflection.

#### `InheritanceResolver`

Package-private interface:

```java
interface InheritanceResolver {
    AnnotationSource resolve(Method interfaceMethod, Class<?> implementationClass);
}
```

The default implementation composes a `MethodResolver` and applies the
following algorithm. The order is significant: the hierarchy walk runs
*before* any pass-through decision, so that a method-level annotation on
an intermediate class is found even when the concrete implementation does
not itself declare the method.

1. **Hierarchy walk for method-level annotations.** Walk the chain
   `implementationClass`, then `implementationClass.getSuperclass()`, and
   so on, stopping at (but not visiting) `Object.class`. At each class
   `currentClass`:
   - Invoke
     `methodResolver.resolveAnnotationSourceMethod(interfaceMethod, currentClass)`.
   - If the result is present:
     - If the method carries any Inqudium resilience element annotation
       (`@InqCircuitBreaker`, `@InqRetry`, `@InqBulkhead`,
       `@InqRateLimiter`, `@InqTimeLimiter`, `@InqTrafficShaper`), return
       `AnnotationSource.MethodLevel(method)` immediately.
     - Otherwise, if `lowestDeclaringMethod` has not yet been recorded,
       record this method as the lowest declaring method in the
       hierarchy. Continue the walk.
   - If the result is empty, continue.

   After the walk, no method-level annotation was found anywhere.
   `lowestDeclaringMethod` is either the lowest method in the hierarchy
   matching the signature, or `null` if no class in the hierarchy
   declares it.

2. **No-implementation gate.** If `lowestDeclaringMethod` is `null`:
   - The implementation has no method matching the interface signature
     anywhere. For a default interface method this is the classic
     "default not overridden" case (per ADR-036 §7); for an abstract
     interface method this is a malformed implementation that the
     evaluator does not protect.
   - Return `AnnotationSource.PassThrough`.

3. **Class-level fallback.** `lowestDeclaringMethod` is non-null; check
   whether `implementationClass` has any class-level resilience
   annotation (via standard `isAnnotationPresent`, which transparently
   picks up `@Inherited` from superclasses).
   - If yes, return
     `AnnotationSource.ClassLevelOnly(lowestDeclaringMethod, implementationClass)`.
   - If no, return `AnnotationSource.PassThrough`.

The Spring-strict rule is encoded in step 1: as soon as a method-level
annotation is found, the walk stops and class-level annotations are
ignored entirely. The reordering relative to a naive "pass-through
first, walk second" approach is what allows method-level annotations on
intermediate classes to be discovered when the concrete implementation
inherits the method without overriding it.

Defensive checks at entry: reject `null` for either parameter with
`IllegalArgumentException`.

### Tests

Two test classes — one per resolver — at:

- `inqudium-annotation/src/test/java/eu/inqudium/annotation/evaluator/MethodResolverTest.java`
- `inqudium-annotation/src/test/java/eu/inqudium/annotation/evaluator/InheritanceResolverTest.java`

Test fixtures are static nested classes within the test classes.

**`MethodResolverTest` — at minimum:**

- Default method that the target class does not override anywhere in its
  hierarchy → `Optional.empty()`.
- Default method overridden on the queried target class itself →
  returns the override.
- Default method overridden on an ancestor; queried against the leaf
  class → `Optional.empty()` (the resolver is per-class; the method is
  not declared on the leaf).
- Default method overridden on an ancestor; queried against the
  ancestor class → returns the override.
- Non-default interface method, target class declares a matching method
  directly → returns it.
- Non-default interface method, target class does *not* declare a
  matching method → `Optional.empty()`.
- Generic interface, target class declares a bridge plus the typed
  method → returns the typed method via `BridgeMethodResolver`.
- Defensive: null `interfaceMethod` or null `targetClass` →
  `IllegalArgumentException`.

**`InheritanceResolverTest` — at minimum:**

All three `AnnotationSource` variants must be reached, with the
intermediate-class cases explicitly covered.

- **`PassThrough` — default method not overridden:** Default method, no
  override anywhere, no annotations. → `PassThrough`.
- **`PassThrough` — unannotated chain:** Concrete method, no resilience
  annotation anywhere. → `PassThrough`.
- **`MethodLevel` — direct on impl:** Impl method carries `@InqRetry`.
  → `MethodLevel(implMethod)`.
- **`MethodLevel` — on direct parent:** Impl inherits method from
  parent; parent's method carries `@InqRetry`. →
  `MethodLevel(parentMethod)`.
- **`MethodLevel` — on deep ancestor:** Three-level hierarchy; only
  grandparent's method carries the annotation. →
  `MethodLevel(grandparentMethod)`.
- **`MethodLevel` — default-method overridden by intermediate with
  annotation:** Interface default method; intermediate class overrides
  it with `@InqRetry`; concrete class extends the intermediate without
  re-overriding. → `MethodLevel(intermediateMethod)`. This is the
  scenario that the JC2 spec correction in this sub-step explicitly
  addresses.
- **`MethodLevel` via bridge:** Generic interface; impl class has bridge
  + typed method; typed method carries `@InqRetry`. →
  `MethodLevel(typedMethod)`.
- **`MethodLevel` overrides class-level:** Impl class has class-level
  `@InqBulkhead`; impl method has method-level `@InqRetry` (different
  element type). → `MethodLevel(implMethod)` — class-level is *not*
  mixed in.
- **`ClassLevelOnly` — direct on impl:** Impl class has class-level
  annotation; method has none. →
  `ClassLevelOnly(implMethod, implClass)`.
- **`ClassLevelOnly` — via `@Inherited` from superclass:** Parent class
  carries class-level annotation; impl inherits it. Method has no
  method-level annotation. →
  `ClassLevelOnly(implMethod, implClass)`.
- **`ClassLevelOnly` — default-method overridden by intermediate without
  annotation, impl has class-level:** Interface default method;
  intermediate class overrides without annotation; impl extends and
  declares class-level annotation. → `ClassLevelOnly(intermediateMethod,
  implClass)`. The signature method points at the intermediate's
  override; the annotation source is the impl class.
- **`PassThrough` — default-method overridden by intermediate without
  annotation, no class-level anywhere:** Interface default method;
  intermediate class overrides without annotation; impl extends without
  class-level. → `PassThrough`.
- Defensive: null `interfaceMethod` or null `implementationClass` →
  `IllegalArgumentException`.

### What this sub-step does NOT do

- Validate annotation contents (names, mutual exclusion of `@InqShield`
  attributes). That is sub-step 4.
- Check that referenced element names exist in the pipeline. That is
  sub-step 5.
- Order the annotations. That is sub-step 4.
- Read the actual annotation values — the resolvers locate where
  annotations live; reading them is the caller's job.

### Verification gates

- Full reactor `mvn verify` is green.
- All three `AnnotationSource` variants are reached by at least one test
  each. `ClassLevelOnly` has separate tests for the direct-on-impl path,
  the `@Inherited`-from-superclass path, and the intermediate-override
  path.
- At least one test in `InheritanceResolverTest` exercises the
  bridge-method path through the inheritance walk.
- At least one test pins the JC2 scenario explicitly: default-method
  overridden by intermediate class with annotation, concrete impl
  inherits without re-overriding, → `MethodLevel` on the intermediate's
  method.

## Sub-step 4: `OrderingResolver`

### Goal

Read `@InqShield` from the annotation-source method, validate the
`@InqShield`-layer rules from ADR-036 §9, and return the ordered list of
element types (outermost first) for that method.

### Spec

```java
interface OrderingResolver {
    List<InqElementType> resolveOrder(Method annotationSource);
}
```

Logic:

1. Read all Inqudium element annotations from `annotationSource` and
   determine the set of present element types.
2. Read `@InqShield` from `annotationSource`. If absent, use the
   `"INQUDIUM"` default with no further `@InqShield` validation.
3. If `@InqShield` is present:
   - If `customOrder.length > 0` and `order` is not the default
     `"INQUDIUM"`, throw `InqAnnotationConfigurationException` (mutual
     exclusion).
   - If `customOrder.length > 0`:
     - Every element type present on the method must appear in
       `customOrder`. Otherwise, throw.
     - Every entry in `customOrder` must correspond to a present element
       type on the method. Otherwise, throw.
     - The ordering is the `customOrder` array, taken as-is.
   - Else if `order` is `"INQUDIUM"` → sort by
     `InqElementType.defaultPipelineOrder()` ascending.
   - Else if `order` is `"RESILIENCE4J"` → sort by the existing
     `PipelineOrdering.Profiles.RESILIENCE4J` ordering.
   - Else → throw `InqAnnotationConfigurationException` (unknown `order`).
4. Return the ordered list.

### Tests

- No `@InqShield`, single element annotation → default order.
- No `@InqShield`, multiple element annotations → INQUDIUM order applied.
- Explicit `@InqShield(order = "RESILIENCE4J")` with multiple annotations
  → R4J order applied.
- `customOrder` with one annotation → that single type in the list.
- `customOrder` with multiple annotations in non-default order → that
  exact order returned.
- `@InqShield` with both `order = "RESILIENCE4J"` and a non-empty
  `customOrder` → fails with descriptive message.
- `customOrder` missing a type that is annotated on the method → fails.
- `customOrder` containing a type that is not annotated on the method →
  fails.
- `@InqShield(order = "BOGUS")` → fails.

### What this sub-step does NOT do

- Check that referenced element names exist in the pipeline (sub-step 5).
- Drive the method resolution itself — it assumes its input is already
  the correct annotation-source method.

### Verification gates

- Full reactor `mvn verify` is green.
- Every validation rule from ADR-036 §9 that is decidable at the
  `@InqShield` layer has at least one negative test pinning the error
  message.
- Positive paths cover all three ordering modes.

## Sub-step 5: `AnnotationEvaluator`

### Goal

Compose the previous sub-steps into the public top-level evaluator. Given
a pipeline plus a service interface and its implementation class, produce
a per-method `MethodPlan` map.

### Spec

```java
public sealed interface MethodPlan {
    record PassThrough() implements MethodPlan {}
    record Decorated(List<String> elementNamesOuterToInner) implements MethodPlan {}
}

public final class EvaluationResult {
    Map<Method, MethodPlan> plans();
}

public interface AnnotationEvaluator {
    static AnnotationEvaluator forPipeline(InqPipeline pipeline);
    EvaluationResult evaluate(Class<?> serviceInterface, Class<?> implementationClass);
}
```

For each method on `serviceInterface`:

1. Invoke `InheritanceResolver.resolve(interfaceMethod, implementationClass)`,
   yielding an `AnnotationSource`.
2. Dispatch on the result:
   - `PassThrough` → `MethodPlan.PassThrough`.
   - `MethodLevel(method)` → read all Inqudium element annotations from
     `method`. The annotations are method-scope; class-level annotations
     on the implementation are ignored, per ADR-036 §6.
   - `ClassLevelOnly(signatureMethod, annotationSourceClass)` → read
     all Inqudium element annotations from `annotationSourceClass`.
     The annotations are class-scope; the method itself has none.
3. Once annotations are gathered (in the method-level or class-level
   case), verify that every referenced element name exists in the
   pipeline. Otherwise, throw `InqAnnotationConfigurationException`.
4. Resolve the ordering via `OrderingResolver.resolveOrder` — invoked
   on the same `AnnotatedElement` whose annotations are in play
   (the method for `MethodLevel`, the class for `ClassLevelOnly`).
5. Project the ordered element types onto their corresponding element
   names from the annotations. Wrap in `MethodPlan.Decorated`.

Fail-fast: the first error in any method aborts the entire evaluation.

The cache key on the resulting map is the interface method, per ADR-036
§5 step 5.

### Tests

- Single-method interface, single element annotation, name exists in
  pipeline → `Decorated` with one entry.
- Single-method interface, multiple element annotations, default order →
  `Decorated` with INQUDIUM order.
- Default interface method, implementation does not override →
  `PassThrough`.
- Method without resilience annotations anywhere in the chain →
  `PassThrough`.
- Annotation references an element name not in the pipeline → fails with
  descriptive message.
- Class-level annotation, method without method-level annotation →
  `Decorated` derived from class-level annotations (`ClassLevelOnly`
  path).
- Method-level annotation overrides class-level annotation of a different
  type — the class-level annotation does not contribute to the plan
  (`MethodLevel` path; class-level is ignored).
- Generic interface with a bridge method on the implementation → plan is
  resolved against the typed method's annotations and cached against
  the interface method.
- Multiple methods on the same interface produce independent plans.

### What this sub-step does NOT do

- Build any per-method decorator stack. The plan is a data structure;
  consuming it is the next phase's responsibility.
- Touch `InqAsyncProxyFactory` or any proxy code.
- Interpret `fallbackMethod` — the attribute on the element annotations is
  read but its value is not stored or surfaced anywhere.

### Verification gates

- Full reactor `mvn verify` is green.
- End-to-end tests with synthetic service interfaces cover all rows in
  the test list above.
- The evaluator has no public collaborators beyond the static factory,
  the `evaluate` method, the result type, and the plan sealed hierarchy.
  `AnnotationSource` remains package-private.

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
- [ ] 4 — `OrderingResolver`
- [ ] 5 — `AnnotationEvaluator`
