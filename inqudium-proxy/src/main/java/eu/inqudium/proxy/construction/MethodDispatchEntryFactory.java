package eu.inqudium.proxy.construction;

import eu.inqudium.annotation.evaluator.MethodPlan;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;
import eu.inqudium.core.pipeline.LayerAction;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.proxy.dispatch.DetectionAsync;
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
 *   if plan instanceof PassThrough:
 *     if method.isDefault() &amp;&amp; !overriddenByImpl(method, implClass) &rarr; DefaultMethodEntry
 *     else                                                        &rarr; PassThroughEntry
 *   else (plan instanceof Decorated):
 *     elements = resolveNames(plan.elementNamesOuterToInner)
 *     mode     = isAsyncMethod(method) ? ASYNC : SYNC
 *     validate paradigm; fold and produce SyncCacheEntry or AsyncCacheEntry
 * </pre>
 *
 * <p>{@code Object}-declared methods are <strong>not</strong> handled
 * by this factory. {@code serviceInterface.getMethods()} on an
 * interface excludes {@link Object} methods, so the evaluator's plans
 * never reference them; {@code ProxyBuilder} seeds Object-method
 * entries directly via
 * {@link MethodDispatchEntry#objectMethod(eu.inqudium.proxy.handler.ObjectMethodHandler.Kind)}
 * after the evaluator pass.</p>
 *
 * <p><strong>Class-loading discipline</strong> (ADR-037 §6 /
 * ARCHITECTURE.md §13). This class carries <strong>no</strong>
 * compile-time references to {@code inqudium-imperative} types.
 * The entire async-build flow lives in {@link AsyncEntryBuilder};
 * {@link #buildAsyncDecorated(Method, MethodPlan.Decorated,
 * InqPipeline, Object)} reaches it via a plain {@code invokestatic}
 * call, which is lazy per JVMS §5.4 — the JVM resolves
 * {@code AsyncEntryBuilder} (and through it
 * {@code AsyncLayerAction}, {@code InqAsyncDecorator},
 * {@code AsyncChainFolder}, {@code FoldedAsyncChain},
 * {@code AsyncParadigmValidator}) only when that branch is first
 * executed. The earlier in-class {@code toAsyncLayerAction} helper
 * caused the JVM's verifier to eagerly resolve
 * {@code AsyncLayerAction} via this class's {@code BootstrapMethods}
 * attribute — see {@code ADR-037-DISCIPLINE-FINDING.md} for the full
 * diagnosis. The entry point
 * {@link #buildDecorated(Method, MethodPlan.Decorated, InqPipeline, Object)}
 * gates the async branch on {@link DetectionAsync#isPresent()}, which
 * itself touches no async type literals.</p>
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
            if (!DetectionAsync.isPresent()) {
                throw new IllegalStateException(
                        "Method " + method + " returns CompletionStage but "
                                + "inqudium-imperative is not on the classpath. "
                                + "Add inqudium-imperative as a runtime dependency "
                                + "to enable async dispatch (ADR-037 §3).");
            }
            // Class-loading discipline: the async-build flow lives in
            // AsyncEntryBuilder, referenced only from
            // buildAsyncDecorated via a plain invokestatic call. The
            // JVM resolves AsyncEntryBuilder (and the imperative types
            // it touches) lazily per JVMS §5.4 — only when this branch
            // is first executed.
            return buildAsyncDecorated(method, plan, pipeline, target);
        }
        return buildSyncDecorated(method, plan, pipeline, target);
    }

    private static MethodDispatchEntry buildSyncDecorated(
            Method method,
            MethodPlan.Decorated plan,
            InqPipeline pipeline,
            Object target) {

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

    private static MethodDispatchEntry buildAsyncDecorated(
            Method method,
            MethodPlan.Decorated plan,
            InqPipeline pipeline,
            Object target) {
        return AsyncEntryBuilder.build(method, plan, pipeline, target);
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
     * <p>See ARCHITECTURE.md §7.2 for the full discussion.</p>
     */
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
