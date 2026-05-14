package eu.inqudium.annotation.evaluator;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Resolves the method declared on a target class whose signature matches a
 * given interface method. Bridges are resolved to their typed counterparts
 * via {@link BridgeMethodResolver}.
 *
 * <p>An empty {@link Optional} means either that the interface method is a
 * default method which the target class (and its hierarchy) does not
 * override, or that the target class does not declare a method matching
 * the interface method's signature. The caller — typically
 * {@link InheritanceResolver} — interprets which of the two applies.</p>
 *
 * <p>The interface is named {@code targetClass} rather than
 * {@code implementationClass} because the resolver is reused at every
 * level of the hierarchy walk performed by {@link InheritanceResolver}.</p>
 *
 * @since 0.8.0
 */
interface MethodResolver {

    /**
     * Returns the method on {@code targetClass} whose signature matches
     * {@code interfaceMethod}.
     *
     * @param interfaceMethod the interface method whose signature drives
     *                        the lookup; must not be {@code null}
     * @param targetClass     the class to inspect; must not be {@code null}
     * @return the matching declared method, with bridges resolved to their
     *         typed counterparts, or {@link Optional#empty()} if the
     *         interface method is a default method that the target class
     *         (and its hierarchy) does not override, or if the target class
     *         does not declare a method matching the signature
     * @throws IllegalArgumentException             if either argument is
     *                                              {@code null}
     * @throws InqAnnotationConfigurationException  if a bridge method on the
     *                                              target class has no
     *                                              unique typed counterpart
     */
    Optional<Method> resolveAnnotationSourceMethod(
            Method interfaceMethod, Class<?> targetClass);
}
