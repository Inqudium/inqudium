# Chain-id allocation across stack-construction mechanisms — analysis report

## Question 1 — Other stack-construction mechanisms?

**Answer: No additional mechanism that fits all three criteria (multi-element input, callable output, documented as a stack).** The library exposes exactly two:

1. **`InqPipeline` + terminal/factory** — built via `InqPipeline.builder().shield(…).build()`, consumed by:
   - `InqProxyFactory.of(InqPipeline)` — `inqudium-core/src/main/java/eu/inqudium/core/pipeline/proxy/InqProxyFactory.java:144,179`
   - `InqAsyncProxyFactory.of(InqPipeline)` — `inqudium-imperative/src/main/java/eu/inqudium/imperative/core/pipeline/InqAsyncProxyFactory.java`
   - `HybridAspectPipelineTerminal.of(InqPipeline)` — `inqudium-aspect/src/main/java/eu/inqudium/aspect/pipeline/HybridAspectPipelineTerminal.java`
   - `SyncPipelineTerminal.of(InqPipeline)` — `inqudium-core/src/main/java/eu/inqudium/core/pipeline/SyncPipelineTerminal.java`
   - `InqShieldAspect` (Spring AOP) — internally builds `InqPipeline` via `PipelineFactory.create(scan, registry)` at `inqudium-spring/src/main/java/eu/inqudium/spring/InqShieldAspect.java:337`.

2. **Nested wrapper decoration** via `InqDecorator.decorateRunnable/Supplier/Callable/Function/JoinPoint` (default methods at `inqudium-core/src/main/java/eu/inqudium/core/pipeline/InqDecorator.java:67,83,99,113,129`) and the async siblings in `InqAsyncDecorator`. Stacks are formed by hand-nesting (`outer.decorateXxx(inner.decorateXxx(...))`).

Adjacent surfaces examined and rejected as separate stack mechanisms:

- `InqExecutor.executeRunnable/Supplier/Callable/Function/JoinPoint` at `inqudium-core/.../InqExecutor.java:60,84,110,142,168` — single-element immediate invocation, does not accept multiple elements.
- `SyncPipelineTerminal.decorateJoinPoint/decorateSupplier` (`SyncPipelineTerminal.java:248,267`) — these are folds *on top of* an already-built `InqPipeline`; not a separate construction mechanism.
- `InqShieldAspect.inspectPipeline(...)` (`InqShieldAspect.java:270,302`) — diagnostic only, builds a `JoinPointWrapper` chain off a pre-resolved per-method pipeline.
- AspectJ aspect (`HybridAspectPipelineTerminal`) and Spring aspect (`InqShieldAspect`) both ultimately delegate to `InqPipeline`.

## Question 2 — Module-by-module mechanism choice

| Module | Mechanism | Construction site |
|---|---|---|
| `inqudium-bulkhead-integration-function` | Nested `decorateXxx` | `…/function/OrderService.java:88-91` — `syncBulkhead.decorateFunction(this::placeOrderImpl)` etc. |
| `inqudium-bulkhead-integration-proxy` | `InqAsyncProxyFactory.of(InqPipeline)` | `…/proxy/Main.java:66-69` — `InqPipeline.builder().shield(bulkhead).build()` then `InqAsyncProxyFactory.of(pipeline).protect(...)` |
| `inqudium-bulkhead-integration-aspectj` | `HybridAspectPipelineTerminal.of(InqPipeline)` | `…/aspectj/OrderBulkheadAspect.java:229-230` — `HybridAspectPipelineTerminal.of(InqPipeline.builder().shield(bh).build())` cached per bulkhead name |
| `inqudium-bulkhead-integration-spring-framework` | Annotation (`@InqBulkhead`) → `InqShieldAspect` → `InqPipeline` | `…/spring/OrderService.java:59,80,102,123` (the `@InqBulkhead(BULKHEAD_NAME)` placements); pipeline built at `InqShieldAspect.java:337` |
| `inqudium-bulkhead-integration-spring-boot` | Annotation (`@InqBulkhead`) → `InqShieldAspect` → `InqPipeline` | `…/springboot/OrderService.java:62,84,106,127` (the `@InqBulkhead(BULKHEAD_NAME)` placements); pipeline built at `InqShieldAspect.java:337` |

## Question 3 — Chain-id allocation point per mechanism

**Source.** `PipelineIds.nextChainId()` is defined once at `inqudium-core/src/main/java/eu/inqudium/core/pipeline/PipelineIds.java:92`. It is the only chainId source. It is invoked from these call sites in main code (verified by grep over `*.java`, excluding `/target/` and `/test/`):

- `inqudium-core/.../AbstractBaseWrapper.java:108`
- `inqudium-core/.../ResolvedPipelineState.java:83`
- `inqudium-core/.../InqExecutor.java:62, 86, 113, 144, 171`
- `inqudium-imperative/.../InqAsyncExecutor.java:42, 58, 74, 104, 120`

There is no other chainId-producing primitive — no second counter, no `UUID`-based path, no separate Spring or AspectJ allocator.

### (a) Nested wrapper decoration (`decorateXxx`)

- **Allocated at:** `AbstractBaseWrapper.java:108`, in the wrapper constructor's `else` branch when the delegate is *not* already an `AbstractBaseWrapper`. If the delegate is a wrapper, the chainId is *inherited* (`AbstractBaseWrapper.java:101`).
- **Holder:** `AbstractBaseWrapper.chainId` field, exposed via `Wrapper.chainId()`.
- **Lifetime:** per construction of the *innermost* wrapper, then shared up the entire nested chain. A `decorateFunction(...)` called once produces one chainId; repeated re-decoration of the same delegate would produce a new chainId only on the innermost call.
- **Public exposure:** Yes, `Wrapper.chainId()` on every wrapper in the chain returns the same value.

### (b) `InqPipeline` + terminals (`SyncPipelineTerminal`, `HybridAspectPipelineTerminal`, `AsyncPipelineTerminal`)

- **Allocated at:** `ResolvedPipelineState.java:83`, inside `ResolvedPipelineState.create(layerNames)`, called once per terminal construction.
- **Holder:** `ResolvedPipelineState.chainId` field (line 61), held by the terminal instance.
- **Lifetime:** per-terminal instance. All invocations through the same terminal share one chainId.
- **Public exposure:** Yes, via `ResolvedPipelineState.chainId()` (line 106) and any terminal-level accessor that exposes the resolved state.

### (c) `InqProxyFactory.of(InqPipeline)` and `InqAsyncProxyFactory.of(InqPipeline)`

- **Allocated at:** `AbstractBaseWrapper.java:108`, when the proxy instance is constructed by `ProxyWrapper.createProxy(serviceInterface, target, name, extension)` (`InqProxyFactory.java:94, 161`). The proxy itself implements `Wrapper` and inherits the `AbstractBaseWrapper` constructor logic.
- **Holder:** the proxy instance's `chainId` field; exposed via `Wrapper.chainId()` on the proxy.
- **Lifetime:** per-proxy instance. All method invocations on a given proxy go through `ProxyWrapper.java:265` which reads `chainId()` from the proxy itself and forwards to `extension.dispatch(chainId, callId, ...)`. Stacking proxies via `protect(...)` of an already-proxied target inherits the inner chainId via the same `AbstractBaseWrapper` constructor path.
- **Public exposure:** Yes, via `Wrapper.chainId()` on the proxy.

### (d) `HybridAspectPipelineTerminal.of(InqPipeline)`

Same as (b) — uses `ResolvedPipelineState.create(...)`. ChainId is allocated once per terminal and held for that terminal's lifetime. Confirmed by the aspectj integration example caching `HybridAspectPipelineTerminal` per bulkhead name (`OrderBulkheadAspect.java:126`).

### (e) `InqShieldAspect` (Spring AOP)

- **Allocated at:** `AbstractBaseWrapper.java:108`, reached by the chain factory invocation in `InqShieldAspect.java:223` (sync path: `cached.syncFactory().apply(terminal).proceed()`) or `:217` (async path). The factory (`buildSyncChainFactory` at `:359-366`) folds each pipeline element via `element.decorateJoinPoint(accFn.apply(executor))`; the innermost `JoinPointWrapper` triggers fresh chainId allocation in the `AbstractBaseWrapper` constructor, and outer layers inherit it.
- **Holder:** the freshly built `JoinPointWrapper` chain — discarded after the invocation completes.
- **Lifetime:** **per invocation**. The `ResolvedShieldPipeline` cached per `Method` (in `InqShieldAspect.cache`, line 126) holds the `InqPipeline` and the chain factory `Function`, but no chainId; the chainId is allocated each time `factory.apply(terminal)` materialises a new wrapper chain.
- **Public exposure:** Not via the cache. `ResolvedShieldPipeline` has no `chainId()` accessor (verified by the field list at lines 59-65 and by the absence of any `chainId` mention in the file). Each invocation's chainId only surfaces externally if observed mid-execution by the layers themselves (e.g. through the `LayerAction.execute(chainId, callId, …)` parameters).

**Verifying the `ResolvedShieldPipeline` Javadoc claim** (`ResolvedShieldPipeline.java:27-32`):

> "The Spring aspect's hot path produces a fresh `JoinPointWrapper` chain per invocation (with its own chain identifier from `PipelineIds`); there is no stable per-method chain identifier in the cache."

**Confirmed.** The per-invocation allocation site is `AbstractBaseWrapper.java:108`, reached via `InqShieldAspect.java:223` (sync) and `:217` (async), through the `decorateJoinPoint` fold defined at `:363-365`. The cache (`InqShieldAspect.cache`, line 126) holds only the immutable `InqPipeline` and chain-factory `Function`; neither carries a chainId. `ResolvedShieldPipeline` has no `chainId()` method.

### (f) `InqExecutor.executeXxx` and `InqAsyncExecutor.executeXxx`

Not requested by Q1 as a stack mechanism, but listed here because it is a separate chainId-allocation surface that any element implementing `InqExecutor` (or wrapper composed of decorators that ultimately implement it) inherits.

- **Allocated at:** `InqExecutor.java:62, 86, 113, 144, 171` and `InqAsyncExecutor.java:42, 58, 74, 104, 120`.
- **Holder:** none — the chainId is passed as a parameter into `LayerAction.execute(...)` and dies with the call.
- **Lifetime:** per `executeXxx` call.
- **Public exposure:** none — only flows through the layer-action `chainId` parameter.

Note that `executeXxx` invoked on a wrapper would allocate a *fresh* chainId at the executor level rather than reusing the wrapper's `chainId()`, because `executeXxx` is a default method on the `InqExecutor`/`LayerAction` interface and ignores wrapper state.

## Question 4 — Does a unifying "Stack" type exist?

**Answer: No. The two paths are conceptually parallel but structurally disjoint.** The library has no class or interface that abstractly represents "a stack of resilience patterns wrapping an execution unit" with `InqPipeline` *and* the `decorateXxx` outputs as instances of it.

**Evidence:**

- **`InqPipeline`** (`inqudium-core/src/main/java/eu/inqudium/core/pipeline/InqPipeline.java`) is a passive, immutable composition value object: an ordered list of `InqElement`s plus a `chain(seed, fold)` fold combinator (`InqPipeline.java:192-200`). It does *not* implement `Wrapper`, does *not* implement `LayerAction`, has no `chainId()`, and is never produced by `decorateXxx`.

- **`Wrapper<S>`** (`inqudium-core/src/main/java/eu/inqudium/core/pipeline/Wrapper.java`) is the type produced by `decorateXxx` (concrete subclasses `FunctionWrapper`, `SupplierWrapper`, `CallableWrapper`, `RunnableWrapper`, `JoinPointWrapper`, plus the proxy via `AbstractProxyWrapper`). It exposes `chainId()`, `inner()`, `toStringHierarchy()`. It is never produced by `InqPipeline.builder()`.

- **No common supertype with stack semantics.** `InqDecorator` (the element type) extends `LayerAction` and does not abstract over "the whole stack" — only "one layer." `LayerAction` is a layer-level around-advice contract, not a stack representation.

- **No conversion API.** There is no `InqPipeline.toWrapperChain()`, no `Wrapper.toPipeline()`. The only adapter is the *fold direction*: a terminal consumes an `InqPipeline` and *produces* a `Wrapper`-or-functional-interface chain (e.g. `SyncPipelineTerminal.decorateJoinPoint` at `SyncPipelineTerminal.java:248-250`, or `InqShieldAspect.buildSyncChainFactory` at `InqShieldAspect.java:359-366`). The reverse direction does not exist.

**Divergence point.** The terminal/factory layer — where `InqPipeline` is consumed — is where each construction path commits to its representation:

- `InqPipeline` path commits to a *cached `ResolvedPipelineState` + chain-factory `Function`* (terminal-side; `ResolvedPipelineState.java:65-86`, `InqShieldAspect.java:354,361`).
- `decorateXxx` path commits to a *nested `Wrapper` chain* directly at the call site, with no intermediate value object.

The two artefacts share `InqElement`/`InqDecorator` at the *layer* level (the same bulkhead instance can be an element of an `InqPipeline` *and* the receiver of `bulkhead.decorateFunction(...)`), but they do not share a "stack" representation.

**Ambiguity flagged.** The answer above takes "Stack" to mean a single type that both paths produce or both paths reference as their assembled artefact. If the audit instead defines "Stack" more loosely as "any abstraction over multiple `InqElement`s in order," then `InqPipeline` alone fits — but only the pipeline path produces it; the `decorateXxx` path neither creates nor consumes it. This distinction is left to the reviewer.
