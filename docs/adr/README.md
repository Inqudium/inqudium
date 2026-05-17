# Architecture Decision Records

This index groups all ADRs in this directory by topic so the architectural
intent of the project can be navigated by area rather than by chronological
number. Within each cluster, ADRs are listed in numerical order. Status is
shown in parentheses; supersession relationships are noted inline.

For the cross-cutting design narrative, see [`../architecture.md`](../architecture.md).

---

## 1. Foundation and module architecture

The shape of the project: how the code is split into modules, which paradigms
are first-class, and where the shared kernel lives.

- [ADR-001: Modular architecture](001-modular-architecture.md) — Accepted
- [ADR-004: Native implementation per paradigm](004-native-per-paradigm.md) — Accepted
- [ADR-005: Shared contracts in `inqudium-core`](005-shared-contracts.md) — Accepted
- [ADR-037: Module topology and integration dispatch](037-module-topology.md) — Proposed

## 2. Public API and compatibility

How users interact with the library, how third-party integrations plug in,
and how the project handles change over time.

- [ADR-002: Functional decoration API](002-functional-api.md) — Accepted
- [ADR-006: Resilience4J drop-in compatibility](006-resilience4j-compat.md) — Accepted
- [ADR-013: Breaking change management](013-breaking-change-management.md) — Accepted
- [ADR-014: ServiceLoader conventions](014-serviceloader-conventions.md) — Accepted

## 3. Configuration, runtime, and registries

The runtime container, the registry pattern, and how configurations are
described and validated.

- [ADR-015: Registry pattern](015-registry-pattern.md) — Accepted
- [ADR-025: Configuration architecture](025-configuration-architecture.md) — Accepted
- [ADR-026: `InqRuntime` and the component registry](026-runtime-and-component-registry.md) — Accepted
- [ADR-027: Validation strategy](027-validation-strategy.md) — Accepted

## 4. Component lifecycle and hot-swap

The state machine each component obeys, how it is implemented in concrete
classes, and how live updates propagate between coupled components.

- [ADR-028: Component lifecycle contract](028-component-lifecycle-contract.md) — Accepted
- [ADR-029: Component lifecycle implementation pattern](029-component-lifecycle-implementation-pattern.md) — Accepted
- [ADR-033: Pipeline integration of lifecycle-aware components](033-pipeline-integration-of-lifecycle-aware-components.md) — Proposed
- [ADR-043: Update propagation and veto negotiation](043-update-propagation-and-veto-negotiation.md) — Proposed

## 5. Pipeline composition

How resilience elements are stacked into a single execution pipeline, what
contracts each layer must honour, and the order in which they nest.

- [ADR-017: Pipeline composition order](017-pipeline-composition-order.md) — Superseded by ADR-041
- [ADR-040: `InqPipeline` composition model](040-pipeline-composition-model.md) — Proposed
- [ADR-041: Pipeline composition ordering](041-pipeline-composition-ordering.md) — Proposed
- [ADR-042: Pipeline contracts](042-pipeline-contracts.md) — Proposed

## 6. Concurrency and threading

The threading model the kernel and paradigm modules are built on.

- [ADR-008: Virtual-thread-ready imperative primitives](008-virtual-thread-readiness.md) — Accepted
- [ADR-023: Always return the decorated copy, never the original `CompletionStage`](023-completable-future-copy-over-original.md) — Accepted

## 7. Resilience elements

Per-element design decisions for the seven resilience primitives.

- [ADR-010: `TimeLimiter` semantics](010-timelimiter-semantics.md) — Accepted
- [ADR-012: Timeout value hierarchy](012-timeout-value-hierarchy.md) — Accepted
- [ADR-016: Sliding window design](016-sliding-window-design.md) — Accepted *(circuit breaker)*
- [ADR-018: Retry behavior and backoff strategies](018-retry-behavior-backoff.md) — Accepted
- [ADR-019: Rate limiter design](019-ratelimiter-design.md) — Accepted
- [ADR-020: Bulkhead design](020-bulkhead-design.md) — Superseded by ADR-044 + ADR-045
- [ADR-032: Bulkhead strategy hot-swap and strategy-config DSL](032-bulkhead-strategy-hot-swap.md) — Superseded by ADR-044 + ADR-045
- [ADR-044: Bulkhead strategies and hot-swap](044-bulkhead-strategies-and-hot-swap.md) — Proposed
- [ADR-045: Bulkhead configuration, handle, and diagnostics](045-bulkhead-configuration-handle-diagnostics.md) — Proposed

## 8. Observability, events, and diagnostics

How the library reports what is happening at runtime — the event system, JFR
emission, log/audit exporters, and stack-introspection contracts.

- [ADR-003: Event-driven observability](003-event-system.md) — Accepted
- [ADR-007: JFR events over log-based tracing](007-jfr-over-logging.md) — Accepted
- [ADR-030: Component event publisher scope](030-component-event-publisher-scope.md) — Accepted
- [ADR-031: Per-runtime event registry isolation](031-per-runtime-event-registry-isolation.md) — Proposed
- [ADR-038: Diagnostic logging and audit as event exporters](038-diagnostic-logging-and-audit-as-event-exporters.md) — Proposed
- [ADR-039: Uniform stack introspection across wrapping paradigms](039-uniform-stack-introspection.md) — Proposed

## 9. Errors and exceptions

Exception taxonomy, structured error codes, and how failure modes are
surfaced to callers.

- [ADR-009: Exception strategy](009-exception-strategy.md) — Accepted
- [ADR-021: Structured error codes](021-error-codes.md) — Accepted

## 10. Context, identity, and correlation

How per-call identity, correlation IDs, and contextual data flow through the
pipeline and across paradigm boundaries.

- [ADR-011: Context propagation](011-context-propagation.md) — Accepted
- [ADR-022: Call identity propagation](022-call-identity-propagation.md) — Accepted
- [ADR-034: Correlation identifiers `stackId` and `callId`](034-correlation-identifiers.md) — Proposed

## 11. Annotation model and Spring integration

The `@InqShield` annotation surface, proxy generation, and Spring-specific
integration concerns.

- [ADR-024: Spring interceptor ordering](024-spring-interceptor-ordering.md) — Proposed
- [ADR-035: Proxy integration architecture](035-proxy-architecture.md) — Proposed
- [ADR-036: Annotation model](036-annotation-model.md) — Accepted

---

## Status legend

- **Accepted** — adopted and considered authoritative for the codebase.
- **Proposed** — under active design; the direction is committed but details
  may still shift before acceptance.
- **Superseded** — retained for historical context; the linked successor ADR
  carries the current decision.
