/**
 * Introspection adapter per ADR-039: {@code ProxyStackAdapter} plugs
 * into the central {@code InqIntrospector} so that
 * {@code inspect(proxyInstance)} works uniformly across stack kinds,
 * and {@code ProxyStackInfo} is the sealed-permitted DTO subtype that
 * carries the proxy-specific view (stack id, service interface,
 * elements snapshot, per-method layer summary).
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.introspection;
