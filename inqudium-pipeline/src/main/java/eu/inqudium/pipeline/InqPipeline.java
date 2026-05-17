package eu.inqudium.pipeline;

import eu.inqudium.core.element.InqElement;

import java.util.List;

/**
 * Composition primitive: a finite, ordered list of resilience elements
 * shared across all paradigms (sync, async, future reactive). The
 * pipeline is the unit of composition that integrations (proxy,
 * functional decoration, AspectJ, Spring) consume to apply resilience
 * around target code.
 *
 * <p>An {@code InqPipeline} is constructed exclusively via
 * {@link #builder()} and the {@link InqPipelineBuilder} it returns.
 * The pipeline is structurally immutable once built — no element can
 * be added, replaced, or reordered after {@link InqPipelineBuilder#build()}
 * returns.</p>
 *
 * <p>Per ADR-040, the interface intentionally allows multiple
 * implementations. The default builder produces one
 * {@code DefaultInqPipeline}; integration modules may wrap a pipeline
 * in additional behaviour (e.g. a diagnostic wrapper that records all
 * applied elements) by implementing this interface.</p>
 *
 * @see InqPipelineBuilder
 */
public interface InqPipeline {

    /**
     * Returns the pipeline's elements in canonical composition order
     * (outermost first). The list is unmodifiable; modification
     * attempts throw {@link UnsupportedOperationException}.
     *
     * <p>Ordering follows ADR-041 — the builder reorders elements at
     * {@code build()} time according to the configured ordering
     * strategy. The {@code shield(...)} call order in the builder is
     * not the composition order.</p>
     *
     * @return the ordered, unmodifiable element list
     */
    List<InqElement> elements();

    /**
     * Returns a JDK dynamic proxy that implements
     * {@code serviceInterface} and routes every method invocation
     * through the resilience elements declared in this pipeline. The
     * proxy applies the elements according to the per-method plan
     * computed by the annotation evaluator (ADR-036).
     *
     * <p>This default method requires {@code inqudium-proxy} on the
     * classpath. The probe is performed by {@link DetectionProxy}; if
     * the module is absent, an {@link IllegalStateException} is raised
     * with a message pointing at the required dependency. When the
     * proxy module is present, the call is delegated to
     * {@code eu.inqudium.proxy.ProxyDispatcher.protect(...)} via the
     * {@link ProxyDelegation} reflective bridge (string-name lookup
     * avoids the Maven cycle that a direct class-literal reference
     * would create).</p>
     *
     * @param serviceInterface  the interface the proxy will implement;
     *                          must be an interface, not a concrete
     *                          class
     * @param target            the real implementation to which the
     *                          proxy delegates after applying the
     *                          pipeline
     * @param <T>               the service interface type
     * @return                  a proxy of {@code serviceInterface}
     * @throws IllegalStateException        if the proxy module is not
     *                                      on the classpath
     */
    default <T> T protect(Class<T> serviceInterface, T target) {
        if (!DetectionProxy.isPresent()) {
            throw new IllegalStateException(
                    "ProxyDispatcher is not on the classpath. Add "
                            + "inqudium-proxy as a runtime dependency to enable "
                            + "proxy-based protection of service interfaces.");
        }
        return ProxyDelegation.delegateProtect(this, serviceInterface, target);
    }

    /**
     * Creates a new, single-use {@link InqPipelineBuilder}. Each call
     * returns a fresh builder; builders are not reusable after their
     * {@code build()} method returns.
     *
     * @return a fresh builder
     */
    static InqPipelineBuilder builder() {
        return new InqPipelineBuilder();
    }
}
