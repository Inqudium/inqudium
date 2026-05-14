package eu.inqudium.annotation.evaluator;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

/**
 * Reflection-only default implementation of {@link MethodResolver}.
 *
 * <p>The algorithm follows steps 1–4 of ADR-036 §5 and §7 (pass-through
 * for unoverridden interface default methods). It is per-class: walking
 * the hierarchy across superclasses is the responsibility of
 * {@link InheritanceResolver}.</p>
 *
 * @since 0.8.0
 */
final class DefaultMethodResolver implements MethodResolver {

    @Override
    public Optional<Method> resolveAnnotationSourceMethod(
            Method interfaceMethod, Class<?> targetClass) {
        if (interfaceMethod == null) {
            throw new IllegalArgumentException("interfaceMethod must not be null");
        }
        if (targetClass == null) {
            throw new IllegalArgumentException("targetClass must not be null");
        }

        if (interfaceMethod.isDefault()
                && !defaultMethodOverriddenAnywhereInHierarchy(interfaceMethod, targetClass)) {
            return Optional.empty();
        }

        Method declared;
        try {
            declared = targetClass.getDeclaredMethod(
                    interfaceMethod.getName(), interfaceMethod.getParameterTypes());
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        }

        if (declared.isBridge()) {
            return Optional.of(BridgeMethodResolver.resolveBridge(declared));
        }
        return Optional.of(declared);
    }

    private static boolean defaultMethodOverriddenAnywhereInHierarchy(
            Method interfaceMethod, Class<?> targetClass) {
        String name = interfaceMethod.getName();
        Class<?>[] parameterTypes = interfaceMethod.getParameterTypes();
        for (Class<?> current = targetClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (declaresMatchingConcreteMethod(current, name, parameterTypes)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresMatchingConcreteMethod(
            Class<?> declaringClass, String name, Class<?>[] parameterTypes) {
        for (Method method : declaringClass.getDeclaredMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (!Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                continue;
            }
            return true;
        }
        return false;
    }
}
