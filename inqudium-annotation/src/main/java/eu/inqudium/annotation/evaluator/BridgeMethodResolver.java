package eu.inqudium.annotation.evaluator;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Resolves a compiler-generated bridge method to its corresponding typed
 * method on the same declaring class, using the reflection-only algorithm
 * specified in ADR-036 §5.
 *
 * <p>This is a phase-internal utility — package-private, no public API
 * surface beyond the single static method.</p>
 */
final class BridgeMethodResolver {

    private BridgeMethodResolver() {
        // utility class
    }

    /**
     * Resolves the bridge method to its unique typed counterpart on the
     * same declaring class.
     *
     * <p>The scan is restricted to {@code bridge.getDeclaringClass()
     * .getDeclaredMethods()}. Cross-class bridge resolution is the
     * responsibility of the inheritance resolver.</p>
     *
     * @param bridge the bridge method to resolve; must satisfy
     *               {@link Method#isBridge()}
     * @return the unique non-bridge typed method on the same declaring class
     *         that matches the bridge per the algorithm of ADR-036 §5
     * @throws IllegalArgumentException             if {@code bridge} is
     *                                              {@code null} or not a
     *                                              bridge method
     * @throws InqAnnotationConfigurationException  if zero or more than one
     *                                              typed counterpart matches
     */
    static Method resolveBridge(Method bridge) {
        if (bridge == null) {
            throw new IllegalArgumentException("bridge method must not be null");
        }
        if (!bridge.isBridge()) {
            throw new IllegalArgumentException(
                    "method " + describeMethod(bridge) + " is not a bridge method");
        }

        Class<?>[] bridgeParameterTypes = bridge.getParameterTypes();
        Class<?> bridgeReturnType = bridge.getReturnType();

        List<Method> candidates = new ArrayList<>();
        for (Method candidate : bridge.getDeclaringClass().getDeclaredMethods()) {
            if (candidate.isBridge()) {
                continue;
            }
            if (!candidate.getName().equals(bridge.getName())) {
                continue;
            }
            if (candidate.getParameterCount() != bridge.getParameterCount()) {
                continue;
            }
            if (!parameterTypesAssignable(bridgeParameterTypes, candidate.getParameterTypes())) {
                continue;
            }
            if (!bridgeReturnType.isAssignableFrom(candidate.getReturnType())) {
                continue;
            }
            candidates.add(candidate);
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw new InqAnnotationConfigurationException(
                    "Bridge method " + describeMethod(bridge)
                            + " has no typed counterpart on its declaring class "
                            + bridge.getDeclaringClass().getName() + ".");
        }
        throw new InqAnnotationConfigurationException(
                "Bridge method " + describeMethod(bridge)
                        + " is ambiguous: multiple typed counterparts match on declaring class "
                        + bridge.getDeclaringClass().getName() + ". Candidates: "
                        + describeCandidates(candidates) + ".");
    }

    private static boolean parameterTypesAssignable(Class<?>[] bridgeTypes, Class<?>[] candidateTypes) {
        for (int i = 0; i < bridgeTypes.length; i++) {
            if (!bridgeTypes[i].isAssignableFrom(candidateTypes[i])) {
                return false;
            }
        }
        return true;
    }

    private static String describeMethod(Method method) {
        StringJoiner parameters = new StringJoiner(", ", "(", ")");
        for (Class<?> parameterType : method.getParameterTypes()) {
            parameters.add(parameterType.getTypeName());
        }
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + parameters + " -> " + method.getReturnType().getTypeName();
    }

    private static String describeCandidates(List<Method> candidates) {
        StringJoiner joiner = new StringJoiner(", ", "[", "]");
        for (Method candidate : candidates) {
            joiner.add(describeMethod(candidate));
        }
        return joiner.toString();
    }
}
