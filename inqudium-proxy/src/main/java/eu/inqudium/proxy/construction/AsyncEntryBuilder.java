package eu.inqudium.proxy.construction;

import eu.inqudium.annotation.evaluator.MethodPlan;
import eu.inqudium.core.element.InqElement;
import eu.inqudium.imperative.core.pipeline.AsyncLayerAction;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;
import eu.inqudium.pipeline.InqPipeline;
import eu.inqudium.proxy.entries.MethodDispatchEntry;
import eu.inqudium.proxy.folding.AsyncChainFolder;
import eu.inqudium.proxy.folding.FoldedAsyncChain;
import eu.inqudium.proxy.invocation.MethodInvoker;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Builds a {@link MethodDispatchEntry} for a service method that
 * returns {@link java.util.concurrent.CompletionStage}. Extracted
 * from {@code MethodDispatchEntryFactory} to satisfy the ADR-037 §6
 * class-loading discipline.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Per the finding documented in
 * {@code ADR-037-DISCIPLINE-FINDING.md} (sub-step 3.13's PR), the
 * HotSpot bytecode verifier resolves the return type of every
 * {@code MethodHandle} in a class's {@code BootstrapMethods}
 * attribute as soon as the class's first {@code invokedynamic} site
 * links. When the async-build helpers lived inside
 * {@code MethodDispatchEntryFactory}, the
 * {@code ::toAsyncLayerAction} method reference in
 * {@code buildAsyncDecorated} caused the JVM to eagerly load
 * {@link AsyncLayerAction} at the first
 * {@code MethodDispatchEntryFactory.createEntry(...)} call &mdash;
 * even for sync-only services.</p>
 *
 * <p>By moving the async-build flow into a separate class referenced
 * only by an {@code invokestatic} call (which IS lazy per JVMS
 * &sect;5.4), {@code MethodDispatchEntryFactory} no longer has any
 * async type in its {@code BootstrapMethods} attribute. The
 * imperative module's classes load only when an async service method
 * is actually present.</p>
 *
 * <h2>Class-loading discipline</h2>
 *
 * <p>This class must be referenced only from
 * {@code MethodDispatchEntryFactory.buildAsyncDecorated(...)}, which
 * is itself reached only after
 * {@code DetectionAsync.isPresent() == true}. The discipline tests in
 * {@code ModuleLoadingDisciplineTest} pin this invariant.</p>
 *
 * <p><strong>Internal API.</strong> Public for cross-package
 * reference from {@code MethodDispatchEntryFactory} (which is also
 * public). Not part of the stable user-facing API.</p>
 */
public final class AsyncEntryBuilder {

    private AsyncEntryBuilder() {
        // utility class
    }

    /**
     * Builds a {@link MethodDispatchEntry} for an async
     * (CompletionStage-returning) service method decorated by one or
     * more {@link InqAsyncDecorator} elements.
     *
     * <p>Precondition: the caller has already verified that
     * {@code DetectionAsync.isPresent() == true}.
     * {@link AsyncParadigmValidator} is invoked here to verify that
     * every resolved element implements {@link InqAsyncDecorator}.</p>
     */
    public static MethodDispatchEntry build(
            Method method,
            MethodPlan.Decorated decorated,
            InqPipeline pipeline,
            Object target) {

        List<InqElement> elements = ElementResolver.resolveNames(
                decorated.elementNamesOuterToInner(), pipeline);
        AsyncParadigmValidator.validate(elements, method);

        List<AsyncLayerAction<Void, Object>> asyncLayers = elements.stream()
                .map(AsyncEntryBuilder::toAsyncLayerAction)
                .toList();

        MethodInvoker invoker = MethodInvoker.create(target, method);
        FoldedAsyncChain chain = AsyncChainFolder.fold(asyncLayers, invoker);

        List<String> layerDescriptions = elements.stream()
                .map(InqElement::name)
                .toList();

        return MethodDispatchEntry.asyncCache(chain, layerDescriptions);
    }

    /**
     * Re-types an element to the storage parameterisation
     * {@code AsyncLayerAction<Void, Object>}. Since
     * {@link AsyncParadigmValidator} ran first, every element here is
     * an {@link InqAsyncDecorator}, which extends
     * {@link AsyncLayerAction}.
     *
     * <p>The wildcard intermediate cast bridges from the compile-time
     * {@link InqElement} view to the storage view. The cast routes
     * through wildcards, which the compiler accepts without an
     * unchecked warning &mdash; same pattern as the sync helper in
     * {@code MethodDispatchEntryFactory}.</p>
     */
    private static AsyncLayerAction<Void, Object> toAsyncLayerAction(InqElement element) {
        InqAsyncDecorator<?, ?> decorator = (InqAsyncDecorator<?, ?>) element;
        return (AsyncLayerAction<Void, Object>) (AsyncLayerAction<?, ?>) decorator;
    }
}
