/**
 * The sealed {@code MethodInvoker} strategy used to call the real
 * target after the resilience chain has unwrapped:
 * {@code MethodHandleInvoker} caches a per-method {@code MethodHandle}
 * and is the default, {@code ReflectiveInvoker} is the
 * {@code Method.invoke}-based fallback. The system property
 * {@code inqudium.proxy.invoker=mh|reflective} selects between them.
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.invocation;
