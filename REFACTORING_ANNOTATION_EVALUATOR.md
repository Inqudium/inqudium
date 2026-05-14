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
   default-method pass-through rule and the class-hierarchy walk from
   ADR-036 §5 and §6.
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
  class, method, annotation, and attribute value where applicable. It is
  introduced by the first sub-step that needs to throw it (currently
  sub-step 2).
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

## Sub-step 3: `MethodResolver` and `InheritanceResolver`

### Goal

Implement the two collaborators that, given an interface method and an
implementation class, return either the annotation-source method on the
implementation (or one of its superclasses) or the pass-through marker.
Combined, they realise ADR-036 §5 (method resolution) and §6
(inheritance).

### Spec

`MethodResolver` is a package-private interface:

```java
interface MethodResolver {
    Optional<Method> resolveAnnotationSourceMethod(
            Method interfaceMethod, Class<?> implementationClass);
}
```

An empty `Optional` means pass-through — either the interface method is a
default method and the implementation does not override it, or the resolved
method (after applying inheritance rules) carries no resilience
annotations.

The default implementation:

1. If the interface method has a default body and the implementation does
   not override it, returns `Optional.empty()`.
2. Otherwise, looks up the method on the implementation class. If the
   returned method `isBridge()`, delegates to `BridgeMethodResolver`.
3. Returns the non-bridge typed method as the candidate annotation source.

`InheritanceResolver` wraps `MethodResolver` to apply ADR-036 §6:

- Method-level annotations override class-level annotations completely.
  If the resolved method carries any Inqudium element annotation, those
  annotations are the sole source.
- If the resolved method carries no element annotation, the resolver
  walks the class hierarchy upward (`getSuperclass()`) looking for an
  overridden method with annotations.
- Class-level annotations apply only when no method-level annotation is
  found anywhere in the chain. Class-level annotations are picked up via
  the JVM's `@Inherited` mechanism, because the Inqudium annotations are
  already declared `@Inherited`.

The return contract: `InheritanceResolver` returns a `Method` whose
annotations (either method-level or via class-level fallback) constitute
the effective resilience configuration, or `Optional.empty()` for
pass-through.

### Tests

The fixtures exercise:

- Default method, implementation does not override → pass-through.
- Default method, implementation overrides → implementation method.
- Generic interface produces a bridge on the implementation → typed
  method returned.
- Method-level annotation on impl overrides class-level annotation
  (different element type on class vs. method).
- Method without annotation on impl, class-level annotation present →
  effective configuration is the class-level annotation.
- Method without annotation on impl, superclass method has annotation →
  superclass annotation is the source.
- Method without annotation anywhere in chain → pass-through.

### What this sub-step does NOT do

- Validate annotation contents (names, mutual exclusion of `@InqShield`
  attributes). That is sub-step 4.
- Check that referenced element names exist in the pipeline. That is
  sub-step 5.
- Order the annotations. That is sub-step 4.

### Verification gates

- Full reactor `mvn verify` is green.
- Each test fixture is a static nested class within the test class (no
  scattered top-level fixtures).
- Sub-step 2's `BridgeMethodResolver` is exercised by at least one
  fixture that produces a real compiler-generated bridge method.

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

1. Resolve the annotation-source method via `InheritanceResolver`.
2. If pass-through → `MethodPlan.PassThrough`.
3. Otherwise:
   - Read all element annotations from the source method.
   - For each annotation, verify the referenced name exists in the
     pipeline. Otherwise, throw `InqAnnotationConfigurationException`.
   - Resolve the ordering via `OrderingResolver`.
   - Project each ordered element type onto its corresponding element
     name from the annotations.
   - Wrap in `MethodPlan.Decorated`.

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
  effective plan derived from class-level.
- Method-level annotation overrides class-level annotation of a different
  type — the class-level annotation does not contribute to the plan.
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
- [x] 2 — `BridgeMethodResolver` and `InqAnnotationConfigurationException` (2026-05-14, PR #TBD)
- [ ] 3 — `MethodResolver` and `InheritanceResolver`
- [ ] 4 — `OrderingResolver`
- [ ] 5 — `AnnotationEvaluator`
