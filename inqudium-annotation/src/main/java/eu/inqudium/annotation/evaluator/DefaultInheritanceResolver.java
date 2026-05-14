package eu.inqudium.annotation.evaluator;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRateLimiter;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.InqTimeLimiter;
import eu.inqudium.annotation.InqTrafficShaper;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * Default implementation of {@link InheritanceResolver}. Applies the
 * ADR-036 §6 algorithm by composing a {@link MethodResolver} for per-class
 * signature lookups and walking the superclass chain from the
 * implementation class up to (but not including) {@link Object}.
 *
 * <p>The Spring-strict rule is encoded in step 2: as soon as a
 * method-level resilience annotation is found anywhere in the walk, the
 * search stops and class-level annotations are ignored entirely.</p>
 *
 * @since 0.8.0
 */
final class DefaultInheritanceResolver implements InheritanceResolver {

    private static final List<Class<? extends Annotation>> RESILIENCE_ELEMENT_ANNOTATIONS = List.of(
            InqCircuitBreaker.class,
            InqRetry.class,
            InqBulkhead.class,
            InqRateLimiter.class,
            InqTimeLimiter.class,
            InqTrafficShaper.class);

    private final MethodResolver methodResolver;

    DefaultInheritanceResolver(MethodResolver methodResolver) {
        if (methodResolver == null) {
            throw new IllegalArgumentException("methodResolver must not be null");
        }
        this.methodResolver = methodResolver;
    }

    @Override
    public AnnotationSource resolve(Method interfaceMethod, Class<?> implementationClass) {
        if (interfaceMethod == null) {
            throw new IllegalArgumentException("interfaceMethod must not be null");
        }
        if (implementationClass == null) {
            throw new IllegalArgumentException("implementationClass must not be null");
        }

        Optional<Method> implMethod = methodResolver.resolveAnnotationSourceMethod(
                interfaceMethod, implementationClass);

        // Pass-through gate: the implementation class does not override an interface default method.
        // DefaultMethodResolver returns empty in exactly that case (independent of the unrelated case
        // where a method lives on a superclass) when the interface method is default.
        if (implMethod.isEmpty() && interfaceMethod.isDefault()) {
            return new AnnotationSource.PassThrough();
        }

        // Method-level hierarchy walk: stop at the first method that carries any resilience annotation.
        for (Class<?> current = implementationClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Optional<Method> methodAtLevel = methodResolver.resolveAnnotationSourceMethod(
                    interfaceMethod, current);
            if (methodAtLevel.isPresent() && carriesAnyResilienceAnnotation(methodAtLevel.get())) {
                return new AnnotationSource.MethodLevel(methodAtLevel.get());
            }
        }

        // Class-level fallback: only consult the implementation class. @Inherited makes class-level
        // annotations on superclasses transparently visible via isAnnotationPresent on the subclass.
        if (carriesAnyResilienceAnnotation(implementationClass)) {
            if (implMethod.isPresent()) {
                return new AnnotationSource.ClassLevelOnly(implMethod.get(), implementationClass);
            }
        }

        return new AnnotationSource.PassThrough();
    }

    private static boolean carriesAnyResilienceAnnotation(AnnotatedElement element) {
        for (Class<? extends Annotation> annotationType : RESILIENCE_ELEMENT_ANNOTATIONS) {
            if (element.isAnnotationPresent(annotationType)) {
                return true;
            }
        }
        return false;
    }
}
