/**
 * Runtime dispatch surface installed on every proxy instance: the
 * {@code InvocationHandler} implementation, the immutable per-proxy
 * method cache, and the argument normaliser that turns a {@code null}
 * varargs array into an empty {@code Object[]}.
 *
 * <p>Every call on a proxy enters here and is routed to the
 * appropriate {@code MethodDispatchEntry} chosen at proxy construction
 * time.</p>
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.handler;
