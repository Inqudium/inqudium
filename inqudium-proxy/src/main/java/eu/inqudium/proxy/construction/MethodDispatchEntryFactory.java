package eu.inqudium.proxy.construction;

import eu.inqudium.annotation.evaluator.MethodPlan;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.proxy.dispatch.ParadigmDetector;
import eu.inqudium.proxy.entries.MethodDispatchEntry;
import eu.inqudium.proxy.folding.FoldedSyncChain;
import eu.inqudium.proxy.folding.SyncChainFolder;
import eu.inqudium.proxy.invocation.MethodInvoker;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Classifies a {@code (method, plan)} pair and builds the
 * appropriate {@link MethodDispatchEntry}. Per ARCHITECTURE.md §7:
 *
 * <pre>
 * classify(method, plan, implClass):
 *   if method.declaringClass == Object.class                      &rarr; ObjectMethodEntry
 *   elif plan instanceof PassThrough:
 *     if method.isDefault() &amp;&amp; !overriddenByImpl(method, implClass) &rarr; DefaultMethodEntry
 *     else                                                        &rarr; PassThroughEntry
 *   else (plan instanceof Decorated):
 *     elements = resolveNames(plan.elementNamesOuterToInner)
 *     mode     = isAsyncMethod(method) ? ASYNC : SYNC
 *     validate paradigm; fold and produce SyncCacheEntry or AsyncCacheEntry
 * </pre>
 *
 * <p><strong>Sub-step 3.8 deviations from the architecture:</strong></p>
 * <ul>
 *   <li>{@code Object}-declared methods route to
 *       {@link MethodDispatchEntry#passThrough(MethodInvoker) PassThroughEntry}
 *       — see {@code TODO(3.10)} below. The correct
 *       {@code ObjectMethodEntry} routing arrives in sub-step 3.10.</li>
 *   <li>Async methods raise
 *       {@link UnsupportedOperationException} with the documented
 *       message; sub-step 3.11 lands the async path.</li>
 * </ul>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from {@code ProxyBuilder}; not part of the stable
 * public surface.</p>
 */
public final class MethodDispatchEntryFactory {

    private MethodDispatchEntryFactory() {
        // utility class
    }

    /**
     * Builds the entry for one service method.
     *
     * @param method    the service-interface method
     * @param plan      the evaluator's plan for this method
     * @param pipeline  the pipeline (for element resolution)
     * @param target    the real target (for binding the
     *                  {@link MethodInvoker})
     * @param implClass the implementation class (for the
     *                  "overridden default" check)
     */
    public static MethodDispatchEntry createEntry(
            Method method,
            MethodPlan plan,
            InqPipeline pipeline,
            Object target,
            Class<?> implClass) {

        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(implClass, "implClass");

        // Object-declared methods: temporary PassThrough routing.
        // TODO(3.10): route to ObjectMethodEntry via ObjectMethodHandler.
        if (method.getDeclaringClass() == Object.class) {
            MethodInvoker invoker = MethodInvoker.create(target, method);
            return MethodDispatchEntry.passThrough(invoker);
        }

        return switch (plan) {
            case MethodPlan.PassThrough passThrough ->
                    buildPassThrough(method, target, implClass);
            case MethodPlan.Decorated decorated ->
                    buildDecorated(method, decorated, pipeline, target);
        };
    }

    private static MethodDispatchEntry buildPassThrough(
            Method method, Object target, Class<?> implClass) {
        if (method.isDefault() && !overriddenByImpl(method, implClass)) {
            return MethodDispatchEntry.defaultMethod(method);
        }
        MethodInvoker invoker = MethodInvoker.create(target, method);
        return MethodDispatchEntry.passThrough(invoker);
    }

    private static MethodDispatchEntry buildDecorated(
            Method method,
            MethodPlan.Decorated plan,
            InqPipeline pipeline,
            Object target) {

        if (ParadigmDetector.isAsyncMethod(method)) {
            throw new UnsupportedOperationException(
                    "Method " + method + " returns CompletionStage; "
                            + "async support arrives in sub-step 3.11. "
                            + "Use a synchronous method signature in the "
                            + "meantime or wait for the async dispatch path "
                            + "to land.");
        }

        List<InqElement> elements = ElementResolver.resolveNames(
                plan.elementNamesOuterToInner(), pipeline);
        SyncParadigmValidator.validate(elements, method);

        List<LayerAction<Void, Object>> layerActions = elements.stream()
                .map(MethodDispatchEntryFactory::toLayerAction)
                .toList();

        MethodInvoker invoker = MethodInvoker.create(target, method);
        FoldedSyncChain chain = SyncChainFolder.fold(layerActions, invoker);

        List<String> layerDescriptions = elements.stream()
                .map(InqElement::name)
                .toList();

        return MethodDispatchEntry.syncCache(chain, layerDescriptions);
    }

    /**
     * Re-types an element to the storage parameterisation
     * {@code LayerAction<Void, Object>}. Since
     * {@link SyncParadigmValidator} ran first, every element here is
     * an {@link InqDecorator}, which extends {@link LayerAction}.
     *
     * <p>Storage typing vs. call-time typing (per ADR-035 §4): the
     * element implements {@code InqDecorator<A, R>} for some
     * {@code A}, {@code R}. At storage time we erase to
     * {@code LayerAction<Void, Object>}; the
     * {@link SyncChainFolder} re-parameterises the list at fold time.
     * The wildcard intermediate cast bridges from the compile-time
     * {@link InqElement} view (which the compiler does not statically
     * see as a {@link LayerAction}) to the storage view.</p>
     *
     * <p>This is the second of two unchecked-cast sites in
     * {@code inqudium-proxy}'s main sources (the first lives in
     * {@link SyncChainFolder#fold} and casts the whole list; this one
     * casts a single element). See ARCHITECTURE.md §7.2.</p>
     */
    @SuppressWarnings("unchecked")
    private static LayerAction<Void, Object> toLayerAction(InqElement element) {
        InqDecorator<?, ?> decorator = (InqDecorator<?, ?>) element;
        return (LayerAction<Void, Object>) (LayerAction<?, ?>) decorator;
    }

    /**
     * Returns {@code true} if the implementation class declares a
     * non-default method that overrides the given interface default
     * method.
     */
    private static boolean overriddenByImpl(Method defaultMethod, Class<?> implClass) {
        try {
            Method implMethod = implClass.getMethod(
                    defaultMethod.getName(),
                    defaultMethod.getParameterTypes());
            return !implMethod.isDefault();
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
