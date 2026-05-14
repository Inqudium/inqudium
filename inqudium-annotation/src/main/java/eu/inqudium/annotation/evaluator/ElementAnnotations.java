package eu.inqudium.annotation.evaluator;

import java.lang.annotation.Annotation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRateLimiter;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.InqTimeLimiter;
import eu.inqudium.annotation.InqTrafficShaper;
import eu.inqudium.core.element.InqElementType;

/**
 * Single source of truth for the Inqudium element-annotation metadata
 * consumed by the evaluator's resolvers. The descriptor list is ordered for
 * deterministic iteration so that diagnostic messages naming annotations
 * refer to them in this sequence regardless of which consumer produced the
 * message.
 *
 * @since 0.8.0
 */
final class ElementAnnotations {

    static final List<ElementAnnotationDescriptor<?>> DESCRIPTORS = List.of(
            new ElementAnnotationDescriptor<>(
                    InqCircuitBreaker.class, InqElementType.CIRCUIT_BREAKER, InqCircuitBreaker::value),
            new ElementAnnotationDescriptor<>(
                    InqRetry.class,          InqElementType.RETRY,           InqRetry::value),
            new ElementAnnotationDescriptor<>(
                    InqBulkhead.class,       InqElementType.BULKHEAD,        InqBulkhead::value),
            new ElementAnnotationDescriptor<>(
                    InqRateLimiter.class,    InqElementType.RATE_LIMITER,    InqRateLimiter::value),
            new ElementAnnotationDescriptor<>(
                    InqTimeLimiter.class,    InqElementType.TIME_LIMITER,    InqTimeLimiter::value),
            new ElementAnnotationDescriptor<>(
                    InqTrafficShaper.class,  InqElementType.TRAFFIC_SHAPER,  InqTrafficShaper::value));

    static final Map<Class<? extends Annotation>, InqElementType> ANNOTATION_TO_TYPE;

    static {
        Map<Class<? extends Annotation>, InqElementType> map = new LinkedHashMap<>();
        for (ElementAnnotationDescriptor<?> descriptor : DESCRIPTORS) {
            map.put(descriptor.annotationType(), descriptor.elementType());
        }
        ANNOTATION_TO_TYPE = Map.copyOf(map);
    }

    private ElementAnnotations() {
        // utility class
    }
}
