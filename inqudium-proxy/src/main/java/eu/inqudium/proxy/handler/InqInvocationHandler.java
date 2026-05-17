package eu.inqudium.proxy.handler;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.proxy.entries.MethodDispatchEntry;
import eu.inqudium.proxy.exception.ExceptionClassifier;
import eu.inqudium.proxy.introspection.MethodLayers;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The {@link InvocationHandler} installed on every JDK proxy
 * produced by {@code ProxyDispatcher.protect(...)}.
 *
 * <p>Holds the per-proxy state and routes every method invocation
 * through the right {@link MethodDispatchEntry} from the
 * {@link PerProxyCache}.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference; not part of the stable public surface.</p>
 *
 * <h2>Hot-path</h2>
 * <p>Per ARCHITECTURE.md §8:</p>
 * <pre>
 * JDK proxy → InqInvocationHandler.invoke(...)
 *         → ArgNormalizer.normalise(args)
 *         → cache.entryFor(method)            // HashMap lookup
 *         → entry.dispatch(...)
 * </pre>
 * <p>Synchronous failures are routed through
 * {@link ExceptionClassifier} per ADR-035 §10. Failures inside an
 * async method's {@code CompletionStage} return value are not
 * reclassified — they propagate via the JDK conventions.</p>
 *
 * <h2>Introspection (ADR-039)</h2>
 * <p>The handler exposes the per-proxy introspection data directly
 * via {@link #serviceInterface()}, {@link #elements()}, and
 * {@link #methodLayers()}. The accessors are cold-path APIs intended
 * for {@code ProxyStackAdapter}; they do not participate in the
 * dispatch hot path.</p>
 */
public final class InqInvocationHandler implements InvocationHandler {

    private final long stackId;
    private final LongSupplier callIdSource;
    private final Object realTarget;
    private final Class<?> serviceInterface;
    private final List<InqElement> elements;
    private final PerProxyCache cache;

    public InqInvocationHandler(
            long stackId,
            LongSupplier callIdSource,
            Object realTarget,
            Class<?> serviceInterface,
            List<InqElement> elements,
            Map<Method, MethodDispatchEntry> entries) {
        this.stackId = stackId;
        this.callIdSource = Objects.requireNonNull(callIdSource, "callIdSource");
        this.realTarget = Objects.requireNonNull(realTarget, "realTarget");
        this.serviceInterface = Objects.requireNonNull(serviceInterface, "serviceInterface");
        this.elements = List.copyOf(Objects.requireNonNull(elements, "elements"));
        this.cache = new PerProxyCache(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * Returns the proxy stack's stable ID per ADR-034. Constant for
     * the lifetime of this handler.
     */
    public long stackId() {
        return stackId;
    }

    /**
     * Pulls the next call ID from the source. Each call returns a
     * fresh value.
     */
    public long nextCallId() {
        return callIdSource.getAsLong();
    }

    /**
     * The real implementation the proxy wraps. Exposed so
     * {@link ObjectMethodHandler#dispatch} can read it from other
     * proxies during {@code equals} comparison (ARCHITECTURE.md §10).
     */
    public Object realTarget() {
        return realTarget;
    }

    /**
     * Returns the service interface the proxy implements. Used by
     * {@code ProxyStackAdapter} to populate {@code ProxyStackInfo.targetType}
     * per ADR-039.
     */
    public Class<?> serviceInterface() {
        return serviceInterface;
    }

    /**
     * Returns an immutable snapshot of the pipeline's elements at
     * the time of proxy construction (ADR-039). The list is decoupled
     * from the original {@code pipeline.elements()} list; later
     * pipeline mutations (none are permitted by ADR-040, but the
     * decoupling is defensive) would not be observable through this
     * accessor.
     */
    public List<InqElement> elements() {
        return elements;
    }

    /**
     * Returns one {@link MethodLayers} per service method (including
     * Object methods, default methods, and pass-through methods —
     * all with empty layer lists). Cold-path API; the list is built
     * on every call.
     */
    public List<MethodLayers> methodLayers() {
        return cache.methodLayers();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object[] normalisedArgs = ArgNormalizer.normalise(args);
        MethodDispatchEntry entry = cache.entryFor(method);
        try {
            return entry.dispatch(proxy, this, normalisedArgs);
        } catch (Throwable t) {
            throw ExceptionClassifier.classify(t, method);
        }
    }
}
