package eu.inqudium.proxy.exception;

import eu.inqudium.proxy.InqUndeclaredCheckedException;

import java.lang.reflect.Method;

/**
 * Classifies a throwable raised inside a synchronous proxy dispatch
 * per ADR-035 §10. The handler catches all {@code Throwable}s and
 * delegates here; the return value is the throwable the handler
 * actually throws.
 *
 * <p>Algorithm (ADR-035 §10):</p>
 * <ol>
 *   <li>Unwrap reflective wrappers via {@link ThrowableUnwrap}.</li>
 *   <li>If the unwrapped throwable is a {@link RuntimeException} or
 *       {@link Error}, return it as-is.</li>
 *   <li>If the unwrapped throwable is an instance of any type
 *       declared in {@code method.getExceptionTypes()}, return it
 *       as-is.</li>
 *   <li>Otherwise wrap in {@link InqUndeclaredCheckedException}.</li>
 * </ol>
 *
 * <p>This classifier handles synchronous dispatch only. Failures
 * inside the {@code CompletionStage} returned by an async method are
 * not reclassified — they propagate via the standard JDK conventions.</p>
 *
 * <p><strong>Internal API.</strong> This class is {@code public} so
 * it can be invoked from {@code eu.inqudium.proxy.handler}; it is
 * not part of {@code inqudium-proxy}'s stable public API.</p>
 */
public final class ExceptionClassifier {

    private ExceptionClassifier() {
        // utility class
    }

    /**
     * Classifies {@code raw} for propagation out of the service
     * method's call. The handler's typical use is
     * {@code throw ExceptionClassifier.classify(t, method);}.
     */
    public static Throwable classify(Throwable raw, Method method) {
        Throwable unwrapped = ThrowableUnwrap.unwrap(raw);

        if (unwrapped instanceof RuntimeException) {
            return unwrapped;
        }
        if (unwrapped instanceof Error) {
            return unwrapped;
        }
        for (Class<?> declared : method.getExceptionTypes()) {
            if (declared.isInstance(unwrapped)) {
                return unwrapped;
            }
        }
        return new InqUndeclaredCheckedException(method, unwrapped);
    }
}
