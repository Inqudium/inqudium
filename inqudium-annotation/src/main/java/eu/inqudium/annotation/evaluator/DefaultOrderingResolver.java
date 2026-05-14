package eu.inqudium.annotation.evaluator;

import eu.inqudium.annotation.InqBulkhead;
import eu.inqudium.annotation.InqCircuitBreaker;
import eu.inqudium.annotation.InqRateLimiter;
import eu.inqudium.annotation.InqRetry;
import eu.inqudium.annotation.InqShield;
import eu.inqudium.annotation.InqTimeLimiter;
import eu.inqudium.annotation.InqTrafficShaper;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.pipeline.PipelineOrdering;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default implementation of {@link OrderingResolver}. Reads
 * {@link InqShield} from the input element, applies the §9 validation
 * rules that are decidable at the annotation layer, and projects the set
 * of present resilience element types onto the chosen ordering.
 *
 * <p>The two named orderings — {@code "INQUDIUM"} and {@code "RESILIENCE4J"}
 * — are sourced from {@link InqElementType#defaultPipelineOrder()} and
 * {@link PipelineOrdering#resilience4j()} respectively, so the resolver does
 * not duplicate ordering knowledge that already lives in
 * {@code inqudium-core}.</p>
 *
 * @since 0.8.0
 */
final class DefaultOrderingResolver implements OrderingResolver {

    private static final String ORDER_INQUDIUM = "INQUDIUM";
    private static final String ORDER_RESILIENCE4J = "RESILIENCE4J";

    /**
     * Maps each Inqudium element annotation class to the element type it
     * represents. Iteration order is deterministic (insertion order), which
     * makes diagnostic messages predictable.
     */
    private static final Map<Class<? extends Annotation>, InqElementType> ANNOTATION_TO_TYPE;

    static {
        Map<Class<? extends Annotation>, InqElementType> map = new LinkedHashMap<>();
        map.put(InqCircuitBreaker.class, InqElementType.CIRCUIT_BREAKER);
        map.put(InqRetry.class, InqElementType.RETRY);
        map.put(InqBulkhead.class, InqElementType.BULKHEAD);
        map.put(InqRateLimiter.class, InqElementType.RATE_LIMITER);
        map.put(InqTimeLimiter.class, InqElementType.TIME_LIMITER);
        map.put(InqTrafficShaper.class, InqElementType.TRAFFIC_SHAPER);
        ANNOTATION_TO_TYPE = map;
    }

    @Override
    public List<InqElementType> resolveOrder(AnnotatedElement annotationSource) {
        if (annotationSource == null) {
            throw new IllegalArgumentException("annotationSource must not be null");
        }

        Set<InqElementType> present = collectPresentTypes(annotationSource);
        InqShield shield = annotationSource.getAnnotation(InqShield.class);

        if (shield == null) {
            return sortBy(present, InqElementType::defaultPipelineOrder);
        }

        String order = shield.order();
        InqElementType[] customOrder = shield.customOrder();

        if (customOrder.length > 0 && !ORDER_INQUDIUM.equals(order)) {
            throw new InqAnnotationConfigurationException(
                    "@InqShield on " + describe(annotationSource)
                            + " cannot set both order='" + order
                            + "' and customOrder=" + typesOf(customOrder)
                            + " simultaneously; the two attributes are mutually exclusive");
        }

        if (customOrder.length > 0) {
            return validateAndReturnCustomOrder(annotationSource, customOrder, present);
        }

        return switch (order) {
            case ORDER_INQUDIUM -> sortBy(present, InqElementType::defaultPipelineOrder);
            case ORDER_RESILIENCE4J -> {
                PipelineOrdering ordering = PipelineOrdering.resilience4j();
                yield sortBy(present, ordering::orderFor);
            }
            default -> throw new InqAnnotationConfigurationException(
                    "@InqShield on " + describe(annotationSource)
                            + " order='" + order + "' is not a recognised value; "
                            + "expected 'INQUDIUM' or 'RESILIENCE4J'");
        };
    }

    private static Set<InqElementType> collectPresentTypes(AnnotatedElement annotationSource) {
        EnumSet<InqElementType> present = EnumSet.noneOf(InqElementType.class);
        for (Map.Entry<Class<? extends Annotation>, InqElementType> entry : ANNOTATION_TO_TYPE.entrySet()) {
            if (annotationSource.isAnnotationPresent(entry.getKey())) {
                present.add(entry.getValue());
            }
        }
        return present;
    }

    private static List<InqElementType> validateAndReturnCustomOrder(
            AnnotatedElement annotationSource,
            InqElementType[] customOrder,
            Set<InqElementType> present) {

        EnumSet<InqElementType> customSet = EnumSet.noneOf(InqElementType.class);
        for (InqElementType type : customOrder) {
            customSet.add(type);
        }

        EnumSet<InqElementType> missingFromCustom = EnumSet.copyOf(present);
        missingFromCustom.removeAll(customSet);
        if (!missingFromCustom.isEmpty()) {
            throw new InqAnnotationConfigurationException(
                    "@InqShield on " + describe(annotationSource)
                            + " customOrder=" + typesOf(customOrder)
                            + " is missing element type(s) that are annotated on the source: "
                            + missingFromCustom);
        }

        EnumSet<InqElementType> notPresent = EnumSet.copyOf(customSet);
        notPresent.removeAll(present);
        if (!notPresent.isEmpty()) {
            throw new InqAnnotationConfigurationException(
                    "@InqShield on " + describe(annotationSource)
                            + " customOrder=" + typesOf(customOrder)
                            + " references element type(s) that are not annotated on the source: "
                            + notPresent);
        }

        return List.of(customOrder.clone());
    }

    private static List<InqElementType> sortBy(
            Set<InqElementType> present,
            java.util.function.ToIntFunction<InqElementType> orderFn) {
        List<InqElementType> sorted = new ArrayList<>(present);
        sorted.sort(Comparator.comparingInt(orderFn));
        return List.copyOf(sorted);
    }

    private static List<InqElementType> typesOf(InqElementType[] array) {
        return List.of(array.clone());
    }

    /**
     * Renders {@code annotationSource} for diagnostic messages. Methods get
     * their declaring class plus signature; classes get their fully qualified
     * name; any other {@link AnnotatedElement} falls back to {@code toString}.
     */
    private static String describe(AnnotatedElement annotationSource) {
        if (annotationSource instanceof Method method) {
            return method.getDeclaringClass().getName() + "#" + method.getName();
        }
        if (annotationSource instanceof Class<?> clazz) {
            return clazz.getName();
        }
        return annotationSource.toString();
    }
}
