package eu.inqudium.proxy;

import eu.inqudium.core.pipeline.PipelineIds;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.proxy.construction.ProxyBuilder;
import eu.inqudium.proxy.entries.MethodDispatchEntry;
import eu.inqudium.proxy.handler.InqInvocationHandler;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Public entry point for proxy-based protection. Called by
 * {@code InqPipeline.protect(Class, Object)} via the
 * {@code ProxyDelegation} reflection bridge in
 * {@code inqudium-pipeline} (the reflection avoids the Maven cycle
 * that a direct class-literal reference would create).
 *
 * <p>Single public method: {@link #protect(InqPipeline, Class, Object)}.
 * Everything else in this module is internal.</p>
 */
public final class ProxyDispatcher {

    private ProxyDispatcher() {
        // utility class
    }

    /**
     * Builds a JDK proxy of {@code serviceInterface} that routes
     * every method invocation through the resilience pipeline.
     *
     * @param pipeline         the resilience pipeline (must contain
     *                         every element referenced by annotations
     *                         on the service-interface impl)
     * @param serviceInterface the interface the proxy will implement;
     *                         must be a Java interface
     * @param target           the real implementation the proxy
     *                         delegates to after applying the
     *                         pipeline; must implement
     *                         {@code serviceInterface}
     * @return a proxy of {@code serviceInterface}
     * @throws IllegalArgumentException  if inputs are invalid
     * @throws IllegalStateException     if a pipeline element is missing
     *                                   the right paradigm interface
     *                                   for any method's mode, or if
     *                                   the service interface declares
     *                                   a {@link java.util.concurrent.CompletionStage}-
     *                                   returning method but
     *                                   {@code inqudium-imperative} is
     *                                   absent from the classpath
     *                                   (ADR-037 §3)
     * @throws eu.inqudium.annotation.evaluator.InqAnnotationConfigurationException
     *                                   if the annotation evaluator
     *                                   detects a configuration
     *                                   problem (per ADR-036 §9)
     */
    public static <T> T protect(InqPipeline pipeline, Class<T> serviceInterface, T target) {
        Map<Method, MethodDispatchEntry> entries =
                ProxyBuilder.build(pipeline, serviceInterface, target);

        long stackId = PipelineIds.nextChainId();
        LongSupplier callIdSource = PipelineIds.newInstanceCallIdSource();

        InqInvocationHandler handler = new InqInvocationHandler(
                stackId,
                callIdSource,
                target,
                serviceInterface,
                pipeline.elements(),
                entries);

        return serviceInterface.cast(
                Proxy.newProxyInstance(
                        serviceInterface.getClassLoader(),
                        new Class<?>[]{serviceInterface},
                        handler));
    }
}
