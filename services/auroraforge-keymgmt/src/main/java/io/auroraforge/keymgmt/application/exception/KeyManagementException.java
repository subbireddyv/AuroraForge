package io.auroraforge.keymgmt.application.exception;

/**
 * Unchecked exception for key management failures (encryption, decryption, rotation).
 * Wraps provider-specific exceptions so callers are not coupled to AWS/Azure SDK types.
 */
public class KeyManagementException extends RuntimeException {

    public KeyManagementException(String message) {
        super(message);
    }

    public KeyManagementException(String message, Throwable cause) {
        super(message, cause);
    }
}
