package eu.inqudium.proxy.handler;

/**
 * Normalises the {@code Object[] args} parameter of
 * {@link java.lang.reflect.InvocationHandler#invoke(Object,
 * java.lang.reflect.Method, Object[])} so that downstream code can
 * assume a non-{@code null} array (the JDK passes {@code null} for
 * no-arg methods).
 *
 * <p>Per ADR-035 §11 the normaliser does <strong>no defensive
 * copy</strong> — JDK proxies guarantee the array is not mutated by
 * the runtime, and downstream code does not mutate it either.</p>
 */
final class ArgNormalizer {

    private static final Object[] EMPTY = new Object[0];

    private ArgNormalizer() {
        // utility class
    }

    /**
     * Returns the given array as-is if non-{@code null}, otherwise a
     * shared empty array. No defensive copy.
     */
    static Object[] normalise(Object[] args) {
        return args == null ? EMPTY : args;
    }
}
