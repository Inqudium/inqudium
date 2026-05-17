/**
 * Exception classification for the synchronous dispatch path per
 * ADR-035 §10: {@code ExceptionClassifier} decides what propagates
 * synchronously to the caller, while {@code ThrowableUnwrap}
 * recursively peels reflective wrappers ({@code InvocationTargetException},
 * {@code UndeclaredThrowableException}) off a thrown cause.
 *
 * @see inqudium-proxy/docs/ARCHITECTURE.md
 */
package eu.inqudium.proxy.exception;
