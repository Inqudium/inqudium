package eu.inqudium.proxy.construction;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.imperative.core.pipeline.InqAsyncDecorator;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Validates that every element in a resolved list implements
 * {@link InqAsyncDecorator} (the async paradigm contract). The
 * factory raises this check before building an
 * {@link eu.inqudium.proxy.entries.AsyncCacheEntry}.
 *
 * <p>Per ADR-035 §6 / ARCHITECTURE.md §7.2: the evaluator does not
 * know which paradigm a service method uses; this check is the
 * proxy's responsibility.</p>
 *
 * <p>The sync counterpart {@link SyncParadigmValidator} (sub-step
 * 3.8) lives in a separate class to keep the
 * {@code inqudium-imperative} class-literal reference (the
 * {@code instanceof InqAsyncDecorator} test) off the sync class-
 * loading path. See ARCHITECTURE.md §13 — split-class structure.</p>
 *
 * <p><strong>Class-loading discipline</strong> (ADR-037 §6): this
 * class must be loaded only via the async branch of
 * {@code MethodDispatchEntryFactory}, AFTER
 * {@code DetectionAsync.isPresent()} has returned {@code true}.
 * Loading it when {@code inqudium-imperative} is absent would
 * result in {@code NoClassDefFoundError}.</p>
 */
final class AsyncParadigmValidator {

    private AsyncParadigmValidator() {
        // utility class
    }

    /**
     * Validates that every element implements
     * {@link InqAsyncDecorator}.
     *
     * @param elements the resolved elements (outermost-first)
     * @param method   the service method being validated, for the
     *                 error message
     * @throws IllegalStateException if any element does not implement
     *                               {@link InqAsyncDecorator}
     */
    static void validate(List<InqElement> elements, Method method) {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(method, "method");
        for (InqElement element : elements) {
            if (!(element instanceof InqAsyncDecorator<?, ?>)) {
                throw new IllegalStateException(
                        "Method " + method + " returns CompletionStage but element '"
                                + element.name() + "' (type "
                                + element.elementType()
                                + ") does not implement InqAsyncDecorator");
            }
        }
    }
}
