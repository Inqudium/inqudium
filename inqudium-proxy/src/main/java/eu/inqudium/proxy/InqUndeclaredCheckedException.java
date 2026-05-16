package eu.inqudium.proxy;

import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Objects;

/**
 * Thrown by the proxy when a method on the proxied target threw a
 * checked exception that the service interface's method signature
 * does not declare. The cause carries the original throwable; the
 * {@link #method()} accessor exposes which service method was being
 * called when the violation occurred.
 *
 * <p>Per ADR-035 §10, this exception extends
 * {@link UndeclaredThrowableException} so application code can catch
 * either the JDK supertype (the conventional approach) or this
 * Inqudium-specific subtype.</p>
 *
 * <p>The {@code method} field is {@code transient} because
 * {@link Method} is not serialisable. After deserialisation,
 * {@link #method()} returns {@code null}. The cause and message
 * survive serialisation via the supertype's standard machinery.</p>
 */
public final class InqUndeclaredCheckedException extends UndeclaredThrowableException {

    private static final long serialVersionUID = 1L;

    private final transient Method method;

    /**
     * Creates a new exception wrapping {@code cause}, attributed to
     * {@code method}.
     *
     * @param method  the service method whose invocation produced an
     *                undeclared checked exception; must not be
     *                {@code null}
     * @param cause   the underlying checked exception; must not be
     *                {@code null}
     */
    public InqUndeclaredCheckedException(Method method, Throwable cause) {
        super(Objects.requireNonNull(cause, "cause"),
                "Undeclared checked exception thrown by " + method);
        this.method = Objects.requireNonNull(method, "method");
    }

    /**
     * Returns the service method whose invocation produced the
     * undeclared checked exception, or {@code null} if this
     * exception was deserialised (the field is transient).
     */
    public Method method() {
        return method;
    }
}
