package eu.inqudium.proxy.exception;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

/**
 * Recursively unwraps reflective wrapper exceptions
 * ({@link InvocationTargetException} and
 * {@link UndeclaredThrowableException}) to expose the real cause.
 * Stops at the first non-wrapper throwable, or at a wrapper with
 * a {@code null} cause (which is then returned as-is).
 *
 * <p>Per ARCHITECTURE.md §9 / ADR-035 §10 step 1.</p>
 */
final class ThrowableUnwrap {

    private ThrowableUnwrap() {
        // utility class
    }

    /**
     * Walks the cause chain through any sequence of
     * {@code InvocationTargetException} and
     * {@code UndeclaredThrowableException} wrappers, returning the
     * first throwable that is neither.
     *
     * <p>If a wrapper has a {@code null} cause (theoretically
     * possible, practically rare), the wrapper itself is returned —
     * there is nothing further to unwrap.</p>
     *
     * @param t the throwable to unwrap; must not be {@code null}
     * @throws NullPointerException if {@code t} is {@code null}
     */
    static Throwable unwrap(Throwable t) {
        if (t == null) {
            throw new NullPointerException("t");
        }
        Throwable current = t;
        while (current instanceof InvocationTargetException
                || current instanceof UndeclaredThrowableException) {
            Throwable cause = current.getCause();
            if (cause == null) {
                return current;
            }
            current = cause;
        }
        return current;
    }
}
