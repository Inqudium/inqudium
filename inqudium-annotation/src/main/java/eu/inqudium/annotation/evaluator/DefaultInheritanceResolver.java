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
 * <p>The hierarchy walk runs before any pass-through decision: a
 * method-level resilience annotation anywhere in the chain wins
 * immediately (Spring-strict §6), and pass-through is only chosen once
 * the walk yields no annotation. As a side effect of the walk, the
 * lowest class in the hierarchy that declares the signature method is
 * recorded so the class-level fallback can name a signature method even
 * when the concrete implementation does not declare the method
 * itself.</p>
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

        Method lowestDeclaringMethod = null;

        // Phase 1: walk the hierarchy. First method-level annotation wins.
        for (Class<?> current = implementationClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            Optional<Method> candidate = methodResolver.resolveAnnotationSourceMethod(
                    interfaceMethod, current);
            if (candidate.isEmpty()) {
                continue;
            }
            Method method = candidate.get();
            if (hasResilienceAnnotation(method)) {
                return new AnnotationSource.MethodLevel(method);
            }
            if (lowestDeclaringMethod == null) {
                lowestDeclaringMethod = method;
            }
        }

        // Phase 2: no method-level annotation anywhere. If no class declares the signature method,
        // there is nothing to protect. For an interface default method this is the "default not
        // overridden" case from ADR-036 §7; for an abstract interface method this is a malformed
        // implementation that the evaluator does not protect.
        if (lowestDeclaringMethod == null) {
            return new AnnotationSource.PassThrough();
        }

        // Phase 3: class-level fallback. isAnnotationPresent on the implementation class
        // transparently picks up @Inherited class-level annotations from superclasses.
        if (hasClassLevelResilienceAnnotation(implementationClass)) {
            return new AnnotationSource.ClassLevelOnly(lowestDeclaringMethod, implementationClass);
        }

        return new AnnotationSource.PassThrough();
    }

    private static boolean hasResilienceAnnotation(AnnotatedElement element) {
        for (Class<? extends Annotation> annotationType : RESILIENCE_ELEMENT_ANNOTATIONS) {
            if (element.isAnnotationPresent(annotationType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClassLevelResilienceAnnotation(Class<?> clazz) {
        return hasResilienceAnnotation(clazz);
    }
}
