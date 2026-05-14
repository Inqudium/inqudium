package eu.inqudium.annotation.evaluator;

/**
 * Thrown when the annotation evaluator detects an invalid configuration
 * during construction. Carries the offending class, method, annotation,
 * or attribute value as part of its message per ADR-036 §9.
 *
 * <p>The exception is an {@link IllegalStateException} subtype so that
 * existing callers that handle generic configuration failures continue
 * to behave correctly; consumers that want to react to evaluator-specific
 * problems can catch this subtype explicitly.</p>
 *
 * @since 0.8.0
 */
public class InqAnnotationConfigurationException extends IllegalStateException {

    /**
     * Constructs a new exception with the given diagnostic message.
     *
     * @param message a description of the configuration problem, including
     *                the offending class, method, annotation, or attribute
     *                value where applicable
     */
    public InqAnnotationConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a new exception with the given diagnostic message and
     * underlying cause.
     *
     * @param message a description of the configuration problem
     * @param cause   the underlying cause, or {@code null} if none
     */
    public InqAnnotationConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
