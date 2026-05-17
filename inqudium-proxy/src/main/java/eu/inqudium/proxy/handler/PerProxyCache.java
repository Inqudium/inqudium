package eu.inqudium.proxy.handler;

import eu.inqudium.proxy.entries.MethodDispatchEntry;
import eu.inqudium.proxy.introspection.MethodLayers;
import eu.inqudium.proxy.introspection.MethodSignatureFormatter;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable per-proxy lookup from {@link Method} to
 * {@link MethodDispatchEntry}. Constructed once by
 * {@link InqInvocationHandler}'s constructor; queried on every
 * proxied method call.
 *
 * <p>Package-private — only the handler in the same package uses it.</p>
 */
final class PerProxyCache {

    private final Map<Method, MethodDispatchEntry> entries;

    PerProxyCache(Map<Method, MethodDispatchEntry> entries) {
        this.entries = Map.copyOf(Objects.requireNonNull(entries, "entries"));
    }

    /**
     * Returns the entry for {@code method}, or throws
     * {@link IllegalStateException} if no entry exists. The latter
     * indicates an evaluator/factory bug — every method on the
     * service interface should be in the cache.
     */
    MethodDispatchEntry entryFor(Method method) {
        MethodDispatchEntry entry = entries.get(method);
        if (entry == null) {
            throw new IllegalStateException(
                    "No dispatch entry for method " + method
                            + " in this proxy. This indicates a bug "
                            + "in the proxy construction phase.");
        }
        return entry;
    }

    /**
     * Builds one {@link MethodLayers} per cached entry, materialising
     * the canonical signature and the entry's layer descriptions on
     * demand. Cold-path API for introspection (ADR-039).
     */
    List<MethodLayers> methodLayers() {
        return entries.entrySet().stream()
                .map(e -> new MethodLayers(
                        MethodSignatureFormatter.format(e.getKey()),
                        e.getValue().layerDescriptions(),
                        Optional.of(e.getKey())))
                .toList();
    }
}
