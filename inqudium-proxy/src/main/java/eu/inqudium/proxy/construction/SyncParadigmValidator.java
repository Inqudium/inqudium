package eu.inqudium.proxy.construction;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.pipeline.InqDecorator;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * Validates that every element in a resolved list implements
 * {@link InqDecorator} (the sync paradigm contract). Raised by the
 * factory before building a {@code SyncCacheEntry}.
 *
 * <p>Per ADR-035 §6 / ARCHITECTURE.md §7.2: the evaluator does not
 * know which paradigm a service method uses, so this check is the
 * proxy's responsibility.</p>
 *
 * <p>Async validation (requiring {@code InqAsyncDecorator}) is
 * implemented separately in sub-step 3.11 as
 * {@code AsyncParadigmValidator} — split-class structure keeps the
 * {@code inqudium-imperative} reference off the sync class-loading
 * path (ARCHITECTURE.md §13).</p>
 */
final class SyncParadigmValidator {

    private SyncParadigmValidator() {
        // utility class
    }

    /**
     * Validates that every element implements {@link InqDecorator}.
     *
     * @param elements the resolved elements (outermost-first)
     * @param method   the service method being validated, for the
     *                 error message
     * @throws IllegalStateException if any element does not implement
     *                               {@link InqDecorator}
     */
    static void validate(List<InqElement> elements, Method method) {
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(method, "method");
        for (InqElement element : elements) {
            if (!(element instanceof InqDecorator<?, ?>)) {
                throw new IllegalStateException(
                        "Method " + method + " is synchronous but element '"
                                + element.name() + "' (type "
                                + element.elementType()
                                + ") does not implement InqDecorator");
            }
        }
    }
}
