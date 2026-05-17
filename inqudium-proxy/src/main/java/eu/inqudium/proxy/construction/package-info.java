/**
 * Proxy construction pipeline: {@code ProxyBuilder} orchestrates,
 * {@code ElementResolver} maps element names to {@code InqElement}
 * instances drawn from the {@code InqPipeline},
 * {@code ParadigmValidator} verifies sync/async-decorator compatibility
 * per ADR-035 §6, and {@code MethodDispatchEntryFactory} classifies
 * each method of the service interface into the matching
 * {@code MethodDispatchEntry} variant.
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.construction;
