/**
 * Phase-three dispatch helpers: {@code ParadigmDetector} answers
 * {@code isAsyncMethod(Method)} using only JDK types,
 * {@code ObjectMethodHandler} implements {@code equals} / {@code hashCode}
 * / {@code toString} and the {@code wait} / {@code notify} family on
 * proxies per ADR-035 §8, and {@code DetectionAsync} probes for
 * {@code inqudium-imperative} on the classpath per ADR-037 §4.
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.dispatch;
