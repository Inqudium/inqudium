package eu.inqudium.annotation.evaluator;

import eu.inqudium.annotation.InqShield;
import eu.inqudium.core.element.InqElementType;

import java.lang.reflect.AnnotatedElement;
import java.util.List;

/**
 * Reads the {@link InqShield} annotation from a given annotated element and
 * returns the ordered list of Inqudium resilience element types present on
 * that element, outermost first.
 *
 * <p>The input is an {@link AnnotatedElement} so the same resolver handles
 * both the method-level and class-level paths from {@link AnnotationSource}
 * without overloads: {@code AnnotatedElement} is the common supertype of
 * {@link java.lang.reflect.Method} and {@link Class}, and
 * {@code isAnnotationPresent} / {@code getAnnotation} behave consistently
 * for both.</p>
 *
 * <p>The resolver validates exactly those rules from ADR-036 §9 that can be
 * decided from {@code @InqShield} alone — mutual exclusion of {@code order}
 * and {@code customOrder}, set equality between {@code customOrder} and the
 * present element annotations, and the well-formedness of {@code order}.
 * Validation that requires the pipeline (e.g. that referenced element names
 * exist) is the caller's responsibility.</p>
 *
 * @since 0.8.0
 */
interface OrderingResolver {

    /**
     * Returns the ordered list of resilience element types present on
     * {@code annotationSource}, outermost first.
     *
     * @param annotationSource the annotated element to inspect; must not be
     *                         {@code null}
     * @return the ordered element types; an empty list if no Inqudium
     *         element annotation is present
     * @throws IllegalArgumentException             if {@code annotationSource}
     *                                              is {@code null}
     * @throws InqAnnotationConfigurationException  if {@code @InqShield} on
     *                                              {@code annotationSource}
     *                                              violates any of the §9
     *                                              rules that are decidable
     *                                              at the annotation layer
     */
    List<InqElementType> resolveOrder(AnnotatedElement annotationSource);
}
