package eu.inqudium.proxy.introspection;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Produces the canonical human-readable signature string for a
 * {@link Method} per ADR-039:
 *
 * <pre>
 * &lt;DeclaringClassSimpleName&gt;.&lt;methodName&gt;(&lt;P1&gt;, &lt;P2&gt;, ...)
 * </pre>
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>{@code Supplier.get()}</li>
 *   <li>{@code OrderService.processOrder(Order)}</li>
 *   <li>{@code BiFunction.apply(String, Integer)}</li>
 *   <li>{@code Repository.findAll(Predicate, Pageable)}</li>
 * </ul>
 *
 * <p>Per ADR-039, the format depends only on the declared method
 * and is stable across stacks. Parameter simple-name collisions
 * (two methods on different classes sharing simple names of
 * declaring class and all parameters) produce identical strings;
 * disambiguation is a renderer-side responsibility, not a
 * formatter responsibility. {@link MethodLayers#method()} carries
 * the canonical identity in all cases.</p>
 *
 * <p>Arrays are rendered with trailing brackets ({@code Foo[]});
 * multi-dimensional arrays repeat the brackets ({@code int[][]}).
 * Varargs collapse to array form at the
 * {@link Method#getParameterTypes()} level already. Anonymous
 * classes (whose {@link Class#getSimpleName()} returns the empty
 * string) fall back to the last {@code $}-segment of the binary
 * name.</p>
 *
 * <p><strong>Public API.</strong> Stable signature format; ADR-039.</p>
 */
public final class MethodSignatureFormatter {

    private MethodSignatureFormatter() {
        // utility class
    }

    /**
     * Formats {@code method} into the ADR-039 canonical signature.
     */
    public static String format(Method method) {
        String declaringClassName = simpleNameOrFallback(method.getDeclaringClass());
        String methodName = method.getName();
        String params = Arrays.stream(method.getParameterTypes())
                .map(MethodSignatureFormatter::typeName)
                .collect(Collectors.joining(", "));
        return declaringClassName + "." + methodName + "(" + params + ")";
    }

    private static String simpleNameOrFallback(Class<?> clazz) {
        String simple = clazz.getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        String binary = clazz.getName();
        int dollar = binary.lastIndexOf('$');
        return dollar >= 0 ? binary.substring(dollar + 1) : binary;
    }

    private static String typeName(Class<?> type) {
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        return simpleNameOrFallback(type);
    }
}
