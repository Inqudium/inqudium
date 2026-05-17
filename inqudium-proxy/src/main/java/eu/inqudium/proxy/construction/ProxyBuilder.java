package eu.inqudium.proxy.construction;

import eu.inqudium.annotation.evaluator.EvaluationResult;
import eu.inqudium.annotation.evaluator.MethodPlan;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.pipeline.InqPipelineAnnotationEvaluator;
import eu.inqudium.proxy.entries.MethodDispatchEntry;
import eu.inqudium.proxy.handler.ObjectMethodHandler.Kind;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Orchestrator for the proxy's construction phase. Per
 * ARCHITECTURE.md §6:
 *
 * <ol>
 *   <li>Validate inputs.</li>
 *   <li>Call the annotation evaluator via the
 *       {@link InqPipelineAnnotationEvaluator} bridge (ADR-036 plus
 *       the transitional bridge from sub-step 3.3).</li>
 *   <li>For each method in the evaluator's plan, classify and build
 *       a {@link MethodDispatchEntry} via
 *       {@link MethodDispatchEntryFactory}.</li>
 * </ol>
 *
 * <p>Returns the per-proxy entries map. Sub-step 3.9's
 * {@code ProxyDispatcher} wires this into an
 * {@code InqInvocationHandler} and a JDK proxy.</p>
 *
 * <p><strong>Construction-time errors:</strong></p>
 * <ul>
 *   <li>{@link IllegalArgumentException} for invalid inputs
 *       (non-interface, target not implementing the interface,
 *       nulls).</li>
 *   <li>{@code InqAnnotationConfigurationException} from the
 *       evaluator (propagated unchanged).</li>
 *   <li>{@link IllegalStateException} from
 *       {@link SyncParadigmValidator} or
 *       {@link AsyncParadigmValidator} for paradigm mismatches, or
 *       from {@link MethodDispatchEntryFactory} when an async method
 *       is declared but {@code inqudium-imperative} is absent from
 *       the classpath (ADR-037 §3).</li>
 * </ul>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from sub-step 3.9's {@code ProxyDispatcher}; not part
 * of the stable public surface.</p>
 */
public final class ProxyBuilder {

    private static final Map<Method, Kind> OBJECT_METHOD_KINDS = buildObjectMethodKinds();

    private ProxyBuilder() {
        // utility class
    }

    private static Map<Method, Kind> buildObjectMethodKinds() {
        try {
            return Map.of(
                    Object.class.getMethod("equals", Object.class), Kind.EQUALS,
                    Object.class.getMethod("hashCode"), Kind.HASH_CODE,
                    Object.class.getMethod("toString"), Kind.TO_STRING);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Builds the per-method dispatch map for a proxy of
     * {@code serviceInterface} around {@code target}, decorated by
     * {@code pipeline}.
     */
    public static <T> Map<Method, MethodDispatchEntry> build(
            InqPipeline pipeline,
            Class<T> serviceInterface,
            T target) {

        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(serviceInterface, "serviceInterface");
        Objects.requireNonNull(target, "target");

        if (!serviceInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "serviceInterface must be an interface, was "
                            + serviceInterface.getName());
        }
        if (!serviceInterface.isInstance(target)) {
            throw new IllegalArgumentException(
                    "target of type " + target.getClass().getName()
                            + " does not implement service interface "
                            + serviceInterface.getName());
        }

        @SuppressWarnings("unchecked")
        Class<? extends T> implClass = (Class<? extends T>) target.getClass();

        EvaluationResult evaluation = InqPipelineAnnotationEvaluator
                .evaluate(pipeline, serviceInterface, implClass);

        Map<Method, MethodPlan> plans = evaluation.plans();
        Map<Method, MethodDispatchEntry> entries = new HashMap<>(plans.size());

        for (Map.Entry<Method, MethodPlan> entry : plans.entrySet()) {
            Method method = entry.getKey();
            MethodPlan plan = entry.getValue();
            MethodDispatchEntry dispatchEntry = MethodDispatchEntryFactory.createEntry(
                    method, plan, pipeline, target, implClass);
            entries.put(method, dispatchEntry);
        }

        // Object-declared methods (equals, hashCode, toString) are not
        // enumerated by serviceInterface.getMethods() on an interface,
        // so the annotation evaluator never emits plans for them. The
        // JDK proxy still routes equals/hashCode/toString to the
        // InvocationHandler, so we seed entries that route them to
        // ObjectMethodHandler per ARCHITECTURE.md §10.
        for (Map.Entry<Method, Kind> objectEntry : OBJECT_METHOD_KINDS.entrySet()) {
            entries.put(objectEntry.getKey(),
                    MethodDispatchEntry.objectMethod(objectEntry.getValue()));
        }

        return Map.copyOf(entries);
    }
}
