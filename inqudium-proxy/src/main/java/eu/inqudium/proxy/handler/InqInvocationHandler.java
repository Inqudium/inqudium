package eu.inqudium.proxy.handler;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * The {@link InvocationHandler} installed on every JDK proxy
 * produced by {@code ProxyDispatcher.protect(...)}. Holds the
 * per-proxy state (cache, stack ID, call-ID source) and routes
 * every invocation through the per-method
 * {@code MethodDispatchEntry}.
 *
 * <p><strong>Internal API.</strong> This class is {@code public} so
 * the dispatch entries in {@code eu.inqudium.proxy.entries} can
 * reference it as a parameter type. It is not part of
 * {@code inqudium-proxy}'s stable public API and may change between
 * sub-steps.</p>
 *
 * <p><strong>Sub-step 3.6 state:</strong> stub form. The class
 * exists only as a type identity needed by
 * {@code MethodDispatchEntry.dispatch(...)}. {@link #invoke} throws
 * {@link UnsupportedOperationException}. The full implementation —
 * fields, cache lookup, dispatch loop — arrives in sub-step 3.9.
 * Sub-step 3.7 may add fields if {@code SyncCacheEntry} requires
 * them at that point.</p>
 *
 * @see eu.inqudium.proxy.entries.MethodDispatchEntry
 */
public final class InqInvocationHandler implements InvocationHandler {

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        throw new UnsupportedOperationException(
                "InqInvocationHandler.invoke arrives in sub-step 3.9. "
                        + "Sub-step 3.6 introduces only the type identity for "
                        + "MethodDispatchEntry.dispatch(...).");
    }
}
