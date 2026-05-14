package eu.inqudium.annotation.evaluator;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.function.Function;

import eu.inqudium.core.element.InqElementType;

/**
 * Pairs an Inqudium element annotation type with the pipeline element type it
 * represents and the function that extracts the element instance name from an
 * annotation instance. Consumed by the three evaluator collaborators that
 * need to enumerate, classify, or read names from element annotations.
 *
 * @param <A>             the annotation type (e.g. {@link
 *                        eu.inqudium.annotation.InqCircuitBreaker})
 * @param annotationType  the annotation class
 * @param elementType     the {@link InqElementType} this annotation maps onto
 * @param nameExtractor   reads the {@code value()} attribute of an annotation
 *                        instance to obtain the referenced element name
 * @since 0.8.0
 */
record ElementAnnotationDescriptor<A extends Annotation>(
        Class<A> annotationType,
        InqElementType elementType,
        Function<A, String> nameExtractor) {

    /**
     * Reads the element name from {@code element}'s instance of this
     * descriptor's annotation type, or returns {@code null} if the
     * annotation is not present on {@code element}.
     */
    String readName(AnnotatedElement element) {
        A annotation = element.getAnnotation(annotationType);
        return annotation == null ? null : nameExtractor.apply(annotation);
    }
}
