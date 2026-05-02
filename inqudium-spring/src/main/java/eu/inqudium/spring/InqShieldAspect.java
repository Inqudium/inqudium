package eu.inqudium.spring;

import eu.inqudium.annotation.support.InqAnnotationScanner;
import eu.inqudium.annotation.support.PipelineFactory;
import eu.inqudium.core.element.InqElementRegistry;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.InqPipeline;
import eu.inqudium.core.pipeline.JoinPointExecutor;
import eu.inqudium.core.pipeline.JoinPointWrapper;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Spring AOP aspect that intercepts methods annotated with Inqudium
 * element annotations and routes them through a resilience pipeline.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>The pointcut matches any method or class annotated with
 *       {@code @InqCircuitBreaker}, {@code @InqRetry}, {@code @InqBulkhead},
 *       {@code @InqRateLimiter}, {@code @InqTimeLimiter}, or
 *       {@code @InqTrafficShaper}.</li>
 *   <li>On first invocation of a method, the aspect scans the annotations
 *       (METHOD + TYPE merge), builds an {@link InqPipeline} via
 *       {@link PipelineFactory}, and caches the result as a
 *       {@link ResolvedShieldPipeline}.</li>
 *   <li>Subsequent invocations reuse the cached pipeline — only the
 *       terminal lambda ({@code pjp::proceed}) is created per call.</li>
 *   <li>Methods returning {@link CompletionStage} are dispatched through
 *       the async chain (correct permit lifecycle). All others go through
 *       the sync chain.</li>
 * </ol>
 *
 * <h3>Per-Method caching</h3>
 * <pre>
 *   First call:
 *     1. MethodSignature.getMethod()               ← per call
 *     2. InqAnnotationScanner.scan(method)          ← once
 *     3. PipelineFactory.create(scan, registry)     ← once
 *     4. Build chain factory                         ← once
 *     5. factory.apply(pjp::proceed).proceed()      ← per call
 *
 *   Subsequent calls:
 *     1. MethodSignature.getMethod()               ← per call
 *     2. cache.get(method) → ResolvedShieldPipeline ← O(1)
 *     3. factory.apply(pjp::proceed).proceed()      ← per call
 * </pre>
 *
 * <h3>Diagnostic API</h3>
 * <p>Three introspection methods mirror the diagnostic surface of
 * {@code AbstractPipelineAspect} (in {@code inqudium-aspect}), with
 * documented deviations where the Spring-AOP cache shape differs:</p>
 * <ul>
 *   <li>{@link #getResolvedPipeline(Method)} — hot-path diagnostic that
 *       returns the cached {@link ResolvedShieldPipeline}.</li>
 *   <li>{@link #inspectPipeline(JoinPointExecutor, Method)} — cold-path
 *       diagnostic that builds a {@link JoinPointWrapper} chain for the
 *       given method.</li>
 *   <li>{@link #inspectPipeline(JoinPointExecutor)} — cold-path diagnostic
 *       for the no-method case; returns a passthrough wrapper, since the
 *       Spring aspect's pipeline composition is fundamentally per-method.</li>
 * </ul>
 *
 * <h3>Not an AspectJ CTW aspect</h3>
 * <p>This aspect uses <strong>Spring AOP</strong> (proxy-based), not AspectJ
 * compile-time weaving. It requires {@code spring-boot-starter-aop} on the
 * classpath. For AspectJ CTW, use {@code inqudium-aspect} module instead.</p>
 *
 * <h3>Self-invocation caveat</h3>
 * <p><strong>Calls from within the same bean bypass the proxy and the
 * resilience pipeline.</strong> This is a fundamental property of Spring AOP's
 * proxy-based architecture — not specific to Inqudium. Example:</p>
 * <pre>{@code
 * @Service
 * public class OrderService {
 *
 *     @InqCircuitBreaker("orderCb")
 *     public String placeOrder(String item) {
 *         return remoteService.call(item);           // ← protected by CB
 *     }
 *
 *     public String bulkPlace(List<String> items) {
 *         return items.stream()
 *                 .map(this::placeOrder)              // ← BYPASSES the proxy!
 *                 .collect(joining(", "));             //    CB is NOT applied
 *     }
 * }
 * }</pre>
 *
 * <p>Workarounds:</p>
 * <ul>
 *   <li><strong>Inject self:</strong> {@code @Lazy @Autowired OrderService self;}
 *       and call {@code self.placeOrder(item)} instead of {@code this.placeOrder(item)}</li>
 *   <li><strong>Extract to another bean:</strong> move the annotated method to a
 *       separate {@code @Service} that is injected into this one</li>
 *   <li><strong>Use AspectJ CTW:</strong> switch to {@code inqudium-aspect} module
 *       which uses compile-time weaving and does not have this limitation</li>
 * </ul>
 *
 * @see PipelineFactory
 * @see ResolvedShieldPipeline
 * @since 0.8.0
 */
@Aspect
public class InqShieldAspect {

    private static final Logger log = LoggerFactory.getLogger(InqShieldAspect.class);

    private final InqElementRegistry registry;

    /**
     * Pre-composed pipelines, cached per Method.
     */
    private final ConcurrentHashMap<Method, ResolvedShieldPipeline> cache =
            new ConcurrentHashMap<>();

    public InqShieldAspect(InqElementRegistry registry) {
        this.registry = registry;
    }

    // ======================== Pointcuts ========================

    // --- Method-level: @annotation matches direct method annotations ---

    @Pointcut("@annotation(eu.inqudium.annotation.InqCircuitBreaker)")
    private void circuitBreakerMethod() {
    }

    @Pointcut("@annotation(eu.inqudium.annotation.InqRetry)")
    private void retryMethod() {
    }

    @Pointcut("@annotation(eu.inqudium.annotation.InqBulkhead)")
    private void bulkheadMethod() {
    }

    @Pointcut("@annotation(eu.inqudium.annotation.InqRateLimiter)")
    private void rateLimiterMethod() {
    }

    @Pointcut("@annotation(eu.inqudium.annotation.InqTimeLimiter)")
    private void timeLimiterMethod() {
    }

    @Pointcut("@annotation(eu.inqudium.annotation.InqTrafficShaper)")
    private void trafficShaperMethod() {
    }

    // --- Type-level: @within matches class-level annotations ---

    @Pointcut("@within(eu.inqudium.annotation.InqCircuitBreaker)")
    private void circuitBreakerType() {
    }

    @Pointcut("@within(eu.inqudium.annotation.InqRetry)")
    private void retryType() {
    }

    @Pointcut("@within(eu.inqudium.annotation.InqBulkhead)")
    private void bulkheadType() {
    }

    @Pointcut("@within(eu.inqudium.annotation.InqRateLimiter)")
    private void rateLimiterType() {
    }

    @Pointcut("@within(eu.inqudium.annotation.InqTimeLimiter)")
    private void timeLimiterType() {
    }

    @Pointcut("@within(eu.inqudium.annotation.InqTrafficShaper)")
    private void trafficShaperType() {
    }

    // --- Combined: any Inq annotation on method or class ---

    @Pointcut("circuitBreakerMethod() || retryMethod() || bulkheadMethod() || " +
            "rateLimiterMethod() || timeLimiterMethod() || trafficShaperMethod() || " +
            "circuitBreakerType() || retryType() || bulkheadType() || " +
            "rateLimiterType() || timeLimiterType() || trafficShaperType()")
    private void inqProtected() {
    }

    // ======================== Advice ========================

    /**
     * Around advice — intercepts every method matched by the
     * {@code inqProtected()} pointcut and routes it through the
     * cached pipeline.
     */
    @Around("inqProtected()")
    @SuppressWarnings("unchecked")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        ResolvedShieldPipeline cached = resolvePipeline(method);

        if (cached.isPassthrough()) {
            return pjp.proceed();
        }

        if (cached.isAsync()) {
            try {
                JoinPointExecutor<CompletionStage<Object>> terminal =
                        () -> (CompletionStage<Object>) pjp.proceed();
                return cached.asyncFactory().apply(terminal).proceed();
            } catch (Throwable e) {
                return CompletableFuture.failedFuture(e);
            }
        } else {
            JoinPointExecutor<Object> terminal = pjp::proceed;
            return cached.syncFactory().apply(terminal).proceed();
        }
    }

    // ======================== Diagnostics ========================

    /**
     * Returns the cached {@link ResolvedShieldPipeline} for the given method,
     * resolving it on first access.
     *
     * <p>Hot-path diagnostic: the returned instance is the same one used by
     * the {@code @Around} advice and is reused across invocations. Repeated
     * calls for the same {@link Method} return the same instance — useful
     * for tooling that correlates topology with runtime behaviour.</p>
     *
     * <p>Differs from {@code AbstractPipelineAspect.getResolvedPipeline(Method)}
     * in the return type: Spring-AOP caches an {@link InqPipeline}-backed
     * value, not a layer-action array, so {@link ResolvedShieldPipeline} does
     * not carry a stable {@code chainId()}. See the
     * {@link ResolvedShieldPipeline} JavaDoc for the rationale.</p>
     *
     * @param method the target method
     * @return the pre-composed, cached pipeline
     */
    public ResolvedShieldPipeline getResolvedPipeline(Method method) {
        return resolvePipeline(method);
    }

    /**
     * Builds a full {@link JoinPointWrapper} chain without executing it.
     *
     * <p>Cold-path diagnostic with full
     * {@link eu.inqudium.core.pipeline.Wrapper} introspection
     * ({@code inner()}, {@code chainId()}, {@code toStringHierarchy()}).
     * Intended for diagnostics and testing — not for hot-path execution.</p>
     *
     * <p>Differs from {@code AbstractPipelineAspect.inspectPipeline(JoinPointExecutor)}
     * in that it returns a passthrough wrapper rather than an unfiltered
     * provider chain. The Spring aspect resolves its pipeline per
     * {@link Method} via annotation scanning; outside of a method context
     * there is no pipeline composition to inspect. Callers that need a
     * fully-decorated chain should use
     * {@link #inspectPipeline(JoinPointExecutor, Method)} instead.</p>
     *
     * @param coreExecutor the join point execution
     * @return a passthrough wrapper around the executor
     */
    public JoinPointWrapper<Object> inspectPipeline(JoinPointExecutor<Object> coreExecutor) {
        if (coreExecutor == null) {
            throw new IllegalArgumentException("Core executor must not be null");
        }
        return new JoinPointWrapper<>("passthrough", coreExecutor);
    }

    /**
     * Builds a full {@link JoinPointWrapper} chain for the given method
     * without executing it.
     *
     * <p>Cold-path diagnostic with full
     * {@link eu.inqudium.core.pipeline.Wrapper} introspection. The chain
     * mirrors the structure of the cached pipeline — same elements, same
     * ordering — but is materialised as a {@code JoinPointWrapper} chain
     * rather than the chain factory used on the hot path.</p>
     *
     * <p>For methods that dispatch through the async chain at runtime, this
     * method still produces a synchronous {@link JoinPointWrapper} view of
     * the layer structure. The view is informational: the actual async
     * execution path uses
     * {@link InqAsyncDecorator#decorateAsyncJoinPoint} and produces
     * {@code AsyncJoinPointWrapper} instances. Layer ordering and naming
     * are identical between the two views.</p>
     *
     * <p>If the method has no effective Inqudium annotations after the
     * {@code TYPE+METHOD} merge, a passthrough wrapper is returned.</p>
     *
     * @param coreExecutor the join point execution
     * @param method       the target method
     * @return the outermost wrapper of the assembled chain
     */
    public JoinPointWrapper<Object> inspectPipeline(JoinPointExecutor<Object> coreExecutor,
                                                    Method method) {
        if (coreExecutor == null) {
            throw new IllegalArgumentException("Core executor must not be null");
        }
        if (method == null) {
            throw new IllegalArgumentException("Method must not be null");
        }

        ResolvedShieldPipeline cached = resolvePipeline(method);
        if (cached.isPassthrough() || cached.depth() == 0) {
            return new JoinPointWrapper<>("passthrough", coreExecutor);
        }

        return buildJoinPointChain(cached.pipeline(), coreExecutor);
    }

    // ======================== Internal: caching ========================

    private ResolvedShieldPipeline resolvePipeline(Method method) {
        ResolvedShieldPipeline cached = cache.get(method);
        if (cached != null) {
            return cached;
        }
        return cache.computeIfAbsent(method, this::buildCachedPipeline);
    }

    private ResolvedShieldPipeline buildCachedPipeline(Method method) {
        InqAnnotationScanner.ScanResult scan = InqAnnotationScanner.scan(method);

        if (scan.isEmpty()) {
            // Method matched via @within but has no effective annotations after merge
            return ResolvedShieldPipeline.PASSTHROUGH;
        }

        InqPipeline pipeline = PipelineFactory.create(scan, registry);

        if (pipeline.isEmpty()) {
            return ResolvedShieldPipeline.PASSTHROUGH;
        }

        log.debug("Built pipeline for {}.{}(): {} element(s), ordering={}",
                method.getDeclaringClass().getSimpleName(),
                method.getName(),
                pipeline.depth(),
                scan.ordering());

        boolean async = CompletionStage.class.isAssignableFrom(method.getReturnType());

        if (async) {
            return ResolvedShieldPipeline.async(pipeline, buildAsyncChainFactory(pipeline));
        } else {
            return ResolvedShieldPipeline.sync(pipeline, buildSyncChainFactory(pipeline));
        }
    }

    @SuppressWarnings("unchecked")
    private Function<JoinPointExecutor<Object>, JoinPointExecutor<Object>>
    buildSyncChainFactory(InqPipeline pipeline) {
        return pipeline.chain(
                Function.<JoinPointExecutor<Object>>identity(),
                (accFn, element) -> executor ->
                        ((InqDecorator<Void, Object>) element)
                                .decorateJoinPoint(accFn.apply(executor)));
    }

    @SuppressWarnings("unchecked")
    private Function<JoinPointExecutor<CompletionStage<Object>>,
            JoinPointExecutor<CompletionStage<Object>>>
    buildAsyncChainFactory(InqPipeline pipeline) {
        return pipeline.chain(
                Function.<JoinPointExecutor<CompletionStage<Object>>>identity(),
                (accFn, element) -> executor ->
                        ((InqAsyncDecorator<Void, Object>) element)
                                .decorateAsyncJoinPoint(accFn.apply(executor)));
    }

    /**
     * Walks the pipeline elements and folds them into a {@link JoinPointWrapper}
     * chain rooted at the given core executor. Used only by the cold-path
     * {@link #inspectPipeline(JoinPointExecutor, Method)} method — never on
     * the hot path.
     */
    @SuppressWarnings("unchecked")
    private JoinPointWrapper<Object> buildJoinPointChain(
            InqPipeline pipeline, JoinPointExecutor<Object> coreExecutor) {
        JoinPointExecutor<Object> chain = pipeline.chain(
                coreExecutor,
                (downstream, element) ->
                        ((InqDecorator<Void, Object>) element).decorateJoinPoint(downstream));
        // The fold guarantees a JoinPointWrapper at the top once the pipeline
        // has at least one element. The depth==0 case is short-circuited by
        // the caller.
        return (JoinPointWrapper<Object>) chain;
    }
}
