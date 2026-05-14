# ADR-041: Pipeline composition ordering

**Status:** Proposed  
**Date:** 2026-05-14  
**Deciders:** Core team  
**Related:** ADR-017 (superseded by this ADR), ADR-036 (annotation model — specifies how ordering is selected
from annotations), ADR-040 (`InqPipeline` composition model), ADR-024 (Spring interceptor ordering).

## Context

A pipeline (per ADR-040) is a collection of resilience elements. Before the pipeline can be applied to a
target, the elements have to be arranged in some sequence — an order that determines which element wraps the
target most closely, which element observes the call last, and how the elements' behaviours interact across
the chain.

The order is not a stylistic choice. Different orderings produce different system behaviours, and some
combinations expose specific failure modes that others avoid. A circuit breaker outside a retry sees every
individual attempt; a circuit breaker inside a retry sees only the final outcome. A time limiter outside a
retry bounds the total caller wait; a time limiter inside a retry bounds each attempt separately. These are
not equivalent. Choosing one over the other is an architectural decision with operational consequences.

Two questions follow. First: what ordering should the library default to, and why? Second: when other
orderings are useful, how should users specify them?

ADR-017 originally answered both questions, but did so before the annotation model was settled. The
ordering specifications and the annotation-attribute mechanics were intertwined in that ADR. The annotation
work was later moved to ADR-036, leaving ADR-017 with content that overlapped with ADR-036's specification.
This ADR consolidates the ordering content into a focused reference and supersedes ADR-017. Its scope is the
ordering strategies and their rationale, not how those strategies are selected from annotations (which
remains the territory of ADR-036).

## Decision

### 1. Three ordering strategies

The library specifies three ordering strategies that can be applied to a pipeline:

- **`INQUDIUM`** — the canonical Inqudium ordering. Default. Optimised for fast failure detection and bounded
  caller wait time.
- **`RESILIENCE4J`** — the ordering used by the Resilience4j library. Offered for users migrating from
  Resilience4j or interoperating with systems whose behaviour is already calibrated against that ordering.
- **Custom (`customOrder`)** — an explicit sequence of `InqElementType` values supplied by the user. Used
  when neither standard ordering captures the desired behaviour.

The strategy selection mechanism (annotation attributes, programmatic configuration on the pipeline builder)
is specified by ADR-036 and is not the concern of this ADR. What this ADR specifies is *what each strategy
means* — the actual element sequence each produces and the rationale behind those sequences.

### 2. The `INQUDIUM` canonical ordering

The Inqudium canonical ordering, outermost to innermost:

```
Call → TimeLimiter → TrafficShaper → RateLimiter → Bulkhead → CircuitBreaker → Retry → [target]
       ①             ②                ③             ④           ⑤                ⑥
```

The six positions and their rationale:

**① TimeLimiter (outermost).** Bounds the total caller wait time, including shaping delays, rate-limit waits,
and all retry attempts. A retry sequence of three attempts at three seconds each, plus a one-second shaping
delay, takes up to ten seconds without an outer time limiter — the caller perceives this as the service
having frozen. Putting the time limiter outermost gives the caller a guaranteed maximum wait regardless of
what happens internally.

**② TrafficShaper.** Smooths bursts into a steady flow before rate-limit tokens are consumed. Sits inside
the time limiter so that shaping delays are covered by the caller's time budget: a slow-drip shaper cannot
cause unbounded waiting because the outer time limiter cuts it short. Sits outside the rate limiter so
that the smoothed flow consumes tokens at a predictable rate rather than in bursts.

**③ RateLimiter.** Controls the rate at which calls enter the pipeline. Sits outside the bulkhead and
circuit breaker so that rate-limit enforcement happens before either concurrency control or failure
detection. Retries are inside the rate limiter — they do not consume additional rate-limit permits, which
matches the operational intuition that a single logical user request should cost one permit regardless of
how many internal attempts it requires.

**④ Bulkhead.** Limits concurrent calls at the pipeline level. Even calls that the circuit breaker permits
through are bounded. Concurrency control sits at this level rather than at the innermost position
(Resilience4j's choice) because the goal is to bound concurrency for the *whole pipeline*, not just the
final call. A bulkhead at the innermost position would let an arbitrary number of retries and recovery
operations run concurrently as long as the actual call is bounded — which defeats the purpose of
concurrency limiting in a system designed to protect downstream resources.

**⑤ CircuitBreaker.** Records each individual attempt in its sliding window. Sits outside the retry so
that retries on a failing service each count as separate failures — the breaker opens fast, before the
retry sequence has burned all its attempts. The alternative (breaker inside retry) records only the final
outcome of the retry sequence as a single observation, which slows breaker convergence.

**⑥ Retry (innermost).** Retries only the actual call to the target. Because retry is innermost, the
circuit breaker counts each attempt individually, the time limiter bounds the total time across all
attempts, and the rate limiter does not penalise retries. Each of these properties follows from putting
retry at this position.

### 3. The `RESILIENCE4J` ordering

The Resilience4j-compatible ordering, outermost to innermost:

```
Call → Retry → CircuitBreaker → TrafficShaper → RateLimiter → TimeLimiter → Bulkhead → [target]
```

This ordering exists for migration scenarios and for users who explicitly want Resilience4j's behaviour. The
ordering preserves Resilience4j's documented sequence and its operational characteristics — including
characteristics that Inqudium considers suboptimal in its own canonical ordering. Selecting this strategy
is an explicit opt-in to those characteristics.

The `TrafficShaper` is not part of the Resilience4j library; Inqudium places it at the same relative
position (before `RateLimiter`) regardless of the chosen strategy, because that placement is independent of
the surrounding ordering: shaping bursts before token consumption is a property of the shaper-limiter pair,
not of the broader composition.

### 4. Differences between `INQUDIUM` and `RESILIENCE4J`

The differences and Inqudium's rationale for deviating:

| Element        | Resilience4j position | Inqudium position          | Rationale for the Inqudium choice                                                          |
|----------------|-----------------------|----------------------------|--------------------------------------------------------------------------------------------|
| Retry          | Outermost             | Innermost                  | Inqudium: breaker sees each attempt; faster failure detection                              |
| CircuitBreaker | Inside Retry          | Outside Retry              | Inqudium: each attempt counted individually for faster convergence                         |
| RateLimiter    | Inside CircuitBreaker | Outside CircuitBreaker     | Inqudium: rate limit gates before the breaker's sliding window; consistent global limit    |
| TimeLimiter    | Inside RateLimiter    | Outermost                  | Inqudium: bounds total caller wait across all retries                                      |
| Bulkhead       | Innermost             | Outside CircuitBreaker     | Inqudium: concurrency bounded at pipeline level, not just call level                       |
| TrafficShaper  | Not present           | Between TimeLimiter and RL | Inqudium adds shaping; placement is independent of the surrounding ordering                |

The right-hand column is the Inqudium architectural reasoning. The left-hand column is documented for
migration purposes: users moving from Resilience4j who want behavioural parity can select
`RESILIENCE4J`-ordering and inherit the trade-offs Resilience4j made.

### 5. Custom ordering

Users who want neither standard ordering specify the order explicitly:

```java
@InqShield(customOrder = {
        InqElementType.CIRCUIT_BREAKER,
        InqElementType.RETRY,
        InqElementType.BULKHEAD
})
@InqCircuitBreaker("cb")
@InqRetry("rt")
@InqBulkhead("bh")
public OrderResult placeOrder(Order order) { ... }
```

The array lists element types in outermost-first order. The annotation evaluator (per ADR-036) sorts the
method's annotation set according to this array.

**`customOrder` must be complete.** Every element type that appears on the method must be listed in
`customOrder`. An element type present on the method but absent from `customOrder` is an error: the
annotation evaluator fails at phase 1 (proxy construction) with a descriptive message identifying the
unlisted type. There is no implicit fallback to a standard ordering for unlisted types; the user who
chooses a custom ordering takes full responsibility for the sequence.

This strict rule reflects the architectural reasoning behind custom orderings: a user who selects
`customOrder` is making a deliberate composition choice. Silently appending unlisted types in some default
order would mix the explicit choice with an implicit decision, producing behaviour that is hard to predict
from the annotation alone. Failing fast at construction time surfaces the inconsistency where it can be
fixed — in the annotation, before any call is made.

Within a single method, each annotation resolves to exactly one element via the `(InqElementType,
ParadigmTag, Name)` triple specified in ADR-040 (a method dispatching synchronously and annotated
`@InqBulkhead("orderBh")` resolves to the imperative bulkhead named `orderBh`). The ordering operates on
the resulting set of resolved elements, sorted by their `InqElementType` according to the `customOrder`
array. There is no ambiguity within one method: each element type appears at most once in the method's
resolved set, and `customOrder` orders those distinct types.

Custom orderings can express compositions that neither standard strategy captures — for instance, putting
retry outermost but circuit breaker outside retry (an inversion of both standard strategies). The library
does not validate custom orderings against architectural principles; the user takes responsibility for the
behaviour the chosen ordering produces.

### 6. Startup validation for known anti-patterns

When a pipeline is built, the library checks for ordering combinations that are documented anti-patterns
and emits warnings (not errors — the user may have chosen the ordering deliberately). Three combinations
trigger warnings today:

**Retry outside CircuitBreaker.** The retry attempts may run against an open circuit breaker, receiving
`InqCallNotPermittedException` on each attempt. Useful only when the retry is configured to not retry on
this exception. Warning suggests reviewing the retry's exception filter.

**TimeLimiter inside Retry.** Each retry attempt gets a fresh timeout, but the total caller wait time is
unbounded (up to `timeout × maxAttempts`). Matches Resilience4j's behaviour by default. Warning makes the
caller aware that total wait is not bounded.

**TrafficShaper outside TimeLimiter.** Shaping delays are not covered by the caller's time budget; a
slow-drip shaper could cause unbounded caller waiting. Warning suggests moving the shaper inside the time
limiter.

Warnings are emitted at pipeline build time, before the pipeline serves its first call. They are
informational; a user who has selected `RESILIENCE4J` ordering or a custom ordering that triggers the
warning has already chosen the trade-off.

### 7. Cache is not a pipeline element

Cache (typically Spring's `@Cacheable` or a similar interceptor) is fundamentally different from the other
resilience elements. Retry, CircuitBreaker, Bulkhead, and the rest provide additional behaviour *around* a
method call — they decide whether and how the call proceeds, but the method body always contains the actual
target invocation. Cache, on the other hand, *replaces* the method execution entirely on a hit: the method
body, and therefore the entire resilience pipeline, is never entered.

Because of this difference, cache does not participate in pipeline ordering. It is not assigned a position
between TimeLimiter and Bulkhead, nor is it sortable by `INQUDIUM` or `RESILIENCE4J` strategy. Instead,
cache is a separate interceptor whose priority relative to the Inqudium aspect interceptor is governed by
the framework integration layer (ADR-024).

The practical consequence: on a cache hit, the Inqudium pipeline does not run. On a cache miss, the pipeline
runs as if the cache were absent. The cache's presence does not affect any pipeline ordering rule
specified in this ADR.

### 8. Element-pair decision matrix

The pipeline ordering produces specific behavioural pairings between elements. The following matrix
summarises the most consequential pairs, the effect each pairing produces, and the scenarios in which each
is appropriate. The matrix is a user-facing reference; it does not introduce new rules beyond what the
strategy specifications above already imply.

| Outer          | Inner          | Effect                                                | When to use                                            |
|----------------|----------------|-------------------------------------------------------|--------------------------------------------------------|
| CircuitBreaker | Retry          | Breaker counts each attempt, opens fast               | Inqudium default — fastest failure detection           |
| Retry          | CircuitBreaker | Breaker counts retry sequences, opens slow            | Resilience4j default — retries the whole sequence      |
| TimeLimiter    | Retry          | Total time bounded across all attempts                | Inqudium default — guaranteed max wait                 |
| Retry          | TimeLimiter    | Each attempt bounded, total time = timeout × attempts | Resilience4j default — each attempt has its own budget |
| TimeLimiter    | TrafficShaper  | Shaping delays covered by caller's time budget        | Inqudium default — shaper cannot cause unbounded wait  |
| TrafficShaper  | TimeLimiter    | Shaping delays not time-bounded                       | Only when shaping should run regardless of caller wait |
| TrafficShaper  | RateLimiter    | Bursts smoothed before tokens consumed                | Both strategies — steady flow into rate limiter        |
| RateLimiter    | TrafficShaper  | Tokens consumed before shaping                        | Rarely useful — defeats the purpose of shaping         |
| RateLimiter    | Retry          | Retries don't consume rate limit permits              | Inqudium default — retries don't penalise the rate     |
| Retry          | RateLimiter    | Each retry consumes a permit                          | When retries should be rate-limited                    |
| Bulkhead       | CircuitBreaker | Concurrency bounded even when breaker is closed       | Inqudium default — consistent concurrency control      |
| CircuitBreaker | Bulkhead       | Bulkhead only active when breaker allows calls        | Resilience4j default — breaker gates concurrency       |

The matrix tells a user: *"if I have these two elements, and one is outer, what behaviour do I get?"* The
default strategies in the third-from-right column show which pairing each strategy produces. A user
selecting `INQUDIUM` ordering gets the pairings labelled "Inqudium default"; a user selecting
`RESILIENCE4J` ordering gets those labelled "Resilience4j default".

## Consequences

**Positive:**

- The library's recommended ordering is documented with its full rationale. Users adopting the
  `INQUDIUM` default can see why each element sits where it does and what the trade-off is against
  alternative placements.
- The Resilience4j ordering is preserved as a first-class option. Migration projects do not need to
  rebuild their operational intuition; they select `RESILIENCE4J` and continue with the trade-offs they
  already know.
- The decision matrix is a teaching aid for new users. Reading it tells the user what each strategy
  produces without having to derive the behaviour from the ordering specification.
- Startup warnings catch deliberate or accidental anti-patterns without blocking the pipeline from
  running. Users keep control; the library informs.
- Cache's exclusion from pipeline ordering is made explicit. Users who would otherwise wonder where
  `@Cacheable` fits in the pipeline have the answer in one sentence: it does not.

**Negative:**

- Three strategies (`INQUDIUM`, `RESILIENCE4J`, custom) add conceptual surface. Users have to choose
  one, and the choice has behavioural consequences. The library mitigates this with a documented
  default (`INQUDIUM`) and a teaching matrix, but the surface cannot be hidden.
- The `INQUDIUM` deviation from Resilience4j's order is documented but unfamiliar to users with
  Resilience4j background. The library accepts this cost because the architectural reasoning behind the
  Inqudium ordering is, in the library's view, stronger than parity with prior art.
- Anti-pattern warnings depend on the ordering choice and the elements present. A pipeline with only
  two elements may not trigger any warning even if the ordering is suboptimal; the warning set is not
  exhaustive. Users who want comprehensive analysis must read the decision matrix.

**Neutral:**

- The ordering of cache relative to the Inqudium aspect is governed by ADR-024, not by this ADR.
  Users who need to coordinate cache, security, and Inqudium interceptors find the relevant guidance
  there.
- The strategies (`INQUDIUM`, `RESILIENCE4J`, custom) are addressable from annotations through
  `@InqShield(order = "...")` and `@InqShield(customOrder = {...})`, as specified by ADR-036. The
  annotation-attribute mechanism is not in scope for this ADR; the strategy meanings are.
- ADR-017 originally combined ordering with annotation mechanics. This ADR retains the ordering content
  and refers users seeking the annotation mechanics to ADR-036. ADR-017 is marked Superseded by this
  ADR in its own header update.
- Future strategies (e.g., a hypothetical `HIGH_THROUGHPUT` strategy that places the bulkhead outside
  the rate limiter) can be added without modifying this ADR's existing strategies. The strategy set is
  open; the three present strategies cover the cases the library prioritises today.
