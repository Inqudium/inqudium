package eu.inqudium.annotation.evaluator;

import java.lang.reflect.Method;

/**
 * Structured result of the inheritance walk. Distinguishes the three
 * outcomes from ADR-036 §6: method-level annotations on some method in the
 * hierarchy, class-level annotations only, or no annotations at all.
 *
 * <p>This type is internal to the evaluator package — it carries the
 * semantics that the public {@code AnnotationEvaluator} needs to map onto
 * {@code MethodPlan}, but does not appear in any public API.</p>
 *
 * @since 0.8.0
 */
sealed interface AnnotationSource {

    /**
     * Method-level resilience annotations apply. The annotations live on
     * {@link #method()}, which may be the implementation's own method or
     * an inherited method on a superclass. Class-level annotations on the
     * implementation are ignored entirely, per ADR-036 §6 (method
     * overrides class completely).
     *
     * @param method the method whose annotations drive the protection plan
     */
    record MethodLevel(Method method) implements AnnotationSource {
    }

    /**
     * No method-level resilience annotations exist anywhere in the
     * hierarchy. The implementation class carries class-level resilience
     * annotations (possibly via {@code @Inherited} from a superclass). The
     * signature method drives dispatch; the annotations are read from
     * {@link #annotationSourceClass()}.
     *
     * <p>{@link #annotationSourceClass()} is the implementation class
     * passed into {@link InheritanceResolver#resolve}; it is present
     * explicitly rather than derived from
     * {@code signatureMethod.getDeclaringClass()}, which may point at a
     * superclass while class-level inheritance is rooted at the original
     * implementation class.</p>
     *
     * @param signatureMethod       the method whose signature drives
     *                              dispatch
     * @param annotationSourceClass the implementation class from which
     *                              class-level annotations are read
     */
    record ClassLevelOnly(Method signatureMethod, Class<?> annotationSourceClass)
            implements AnnotationSource {
    }

    /**
     * No resilience annotations apply to this method — either the
     * implementation does not override an interface default method, or no
     * method-level annotation exists in the hierarchy and the
     * implementation class has no class-level annotation either.
     */
    record PassThrough() implements AnnotationSource {
    }
}
