package eu.inqudium.annotation.evaluator;

import eu.inqudium.core.element.InqElement;
import eu.inqudium.core.element.InqElementType;
import eu.inqudium.core.pipeline.InqPipeline;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link AnnotationEvaluator}. Composes the
 * package-private resolvers from sub-steps 3 and 4 and projects their
 * outputs onto a {@link MethodPlan} per method of the service interface.
 *
 * @since 0.8.0
 */
final class DefaultAnnotationEvaluator implements AnnotationEvaluator {

    private final InqPipeline pipeline;
    private final InheritanceResolver inheritanceResolver;
    private final OrderingResolver orderingResolver;

    DefaultAnnotationEvaluator(InqPipeline pipeline) {
        if (pipeline == null) {
            throw new IllegalArgumentException("pipeline must not be null");
        }
        this.pipeline = pipeline;
        MethodResolver methodResolver = new DefaultMethodResolver();
        this.inheritanceResolver = new DefaultInheritanceResolver(methodResolver);
        this.orderingResolver = new DefaultOrderingResolver();
    }

    @Override
    public <T> EvaluationResult evaluate(Class<T> serviceInterface, Class<? extends T> implementationClass) {
        if (serviceInterface == null) {
            throw new IllegalArgumentException("serviceInterface must not be null");
        }
        if (implementationClass == null) {
            throw new IllegalArgumentException("implementationClass must not be null");
        }
        if (!serviceInterface.isInterface()) {
            throw new IllegalArgumentException(
                    "serviceInterface must be an interface, but was: " + serviceInterface.getName());
        }
        if (!serviceInterface.isAssignableFrom(implementationClass)) {
            throw new IllegalArgumentException(
                    "implementationClass " + implementationClass.getName()
                            + " does not implement " + serviceInterface.getName());
        }

        Map<Method, MethodPlan> plans = new LinkedHashMap<>();
        for (Method interfaceMethod : serviceInterface.getMethods()) {
            MethodPlan plan = planFor(serviceInterface, interfaceMethod, implementationClass);
            plans.put(interfaceMethod, plan);
        }
        return new EvaluationResult(plans);
    }

    private MethodPlan planFor(
            Class<?> serviceInterface, Method interfaceMethod, Class<?> implementationClass) {

        AnnotationSource source = inheritanceResolver.resolve(interfaceMethod, implementationClass);

        AnnotatedElement annotatedElement = switch (source) {
            case AnnotationSource.PassThrough ignored -> null;
            case AnnotationSource.MethodLevel methodLevel -> methodLevel.method();
            case AnnotationSource.ClassLevelOnly classLevel -> classLevel.annotationSourceClass();
        };

        if (annotatedElement == null) {
            return new MethodPlan.PassThrough();
        }

        Map<InqElementType, String> elementNames = collectElementNames(
                serviceInterface, interfaceMethod, annotatedElement);

        List<InqElementType> ordering = orderingResolver.resolveOrder(annotatedElement);

        List<String> orderedNames = new ArrayList<>(ordering.size());
        for (InqElementType type : ordering) {
            String name = elementNames.get(type);
            if (name == null) {
                // Defensive: the OrderingResolver validates set-equality between customOrder
                // and the annotated element types, so reaching this branch indicates a
                // programming error in one of the resolvers rather than a user-facing
                // configuration problem.
                throw new IllegalStateException(
                        "Ordering for " + describe(serviceInterface, interfaceMethod)
                                + " references element type " + type
                                + " that is not annotated on " + annotatedElement);
            }
            orderedNames.add(name);
        }

        return new MethodPlan.Decorated(orderedNames);
    }

    /**
     * Reads every Inqudium element annotation present on {@code annotatedElement}
     * and projects it onto an {@code (element type, instance name)} map. Each
     * referenced name is then verified against the pipeline; the first
     * unknown name aborts the evaluation.
     */
    private Map<InqElementType, String> collectElementNames(
            Class<?> serviceInterface, Method interfaceMethod, AnnotatedElement annotatedElement) {

        EnumMap<InqElementType, String> result = new EnumMap<>(InqElementType.class);
        for (ElementAnnotationDescriptor<?> descriptor : ElementAnnotations.DESCRIPTORS) {
            String name = descriptor.readName(annotatedElement);
            if (name == null) {
                continue;
            }
            requirePipelineHasElement(
                    serviceInterface, interfaceMethod, descriptor.annotationType(), name);
            result.put(descriptor.elementType(), name);
        }
        return result;
    }

    private void requirePipelineHasElement(
            Class<?> serviceInterface,
            Method interfaceMethod,
            Class<? extends Annotation> annotationType,
            String elementName) {

        for (InqElement element : pipeline.elements()) {
            if (elementName.equals(element.name())) {
                return;
            }
        }
        throw new InqAnnotationConfigurationException(
                "@" + annotationType.getSimpleName() + " on "
                        + describe(serviceInterface, interfaceMethod)
                        + " references element name '" + elementName
                        + "' which is not present in the pipeline");
    }

    private static String describe(Class<?> serviceInterface, Method interfaceMethod) {
        return serviceInterface.getName() + "#" + interfaceMethod.getName();
    }
}
