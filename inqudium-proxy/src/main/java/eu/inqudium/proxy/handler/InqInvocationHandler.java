package eu.inqudium.proxy.handler;

import eu.inqudium.proxy.entries.MethodDispatchEntry;
import eu.inqudium.proxy.exception.ExceptionClassifier;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
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
 */
public final class InqInvocationHandler implements InvocationHandler {

    private final long stackId;
    private final LongSupplier callIdSource;
    private final PerProxyCache cache;

    public InqInvocationHandler(
            long stackId,
            LongSupplier callIdSource,
            Map<Method, MethodDispatchEntry> entries) {
        this.stackId = stackId;
        this.callIdSource = Objects.requireNonNull(callIdSource, "callIdSource");
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
