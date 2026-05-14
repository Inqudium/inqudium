package eu.inqudium.annotation.evaluator;

import java.lang.reflect.Method;

/**
 * Resolves an {@link AnnotationSource} for a given interface method on an
 * implementation class, applying the inheritance semantics of ADR-036 §6.
 *
 * <p>Composes a {@link MethodResolver} to locate per-class signatures
 * (including bridges) and walks the implementation class hierarchy to
 * find the outermost method-level resilience annotation. If none is
 * found, falls back to class-level annotations on the implementation
 * class.</p>
 *
 * @since 0.8.0
 */
interface InheritanceResolver {

    /**
     * Resolves the annotation source for the given interface method on
     * the implementation class.
     *
     * @param interfaceMethod     the interface method whose signature
     *                            drives dispatch; must not be {@code null}
     * @param implementationClass the concrete implementation class; must
     *                            not be {@code null}
     * @return one of {@link AnnotationSource.MethodLevel},
     *         {@link AnnotationSource.ClassLevelOnly}, or
     *         {@link AnnotationSource.PassThrough}
     * @throws IllegalArgumentException             if either argument is
     *                                              {@code null}
     * @throws InqAnnotationConfigurationException  if bridge resolution
     *                                              fails for any class in
     *                                              the walked hierarchy
     */
    AnnotationSource resolve(Method interfaceMethod, Class<?> implementationClass);
}
