package io.auroraforge.core.application.port.out;

import io.auroraforge.core.domain.model.DataClassification;

/**
 * Output port: encryption and decryption operations.
 * Implemented by the KeyManagementService's cloud adapters (KMS / Key Vault).
 * The application layer never calls AWS or Azure APIs directly.
 */
public interface KeyManagementPort {

    /**
     * Encrypt plaintext using the CMK appropriate for the given classification.
     *
     * @return {@link EncryptionResult} containing ciphertext and the key version used
     */
    EncryptionResult encrypt(byte[] plaintext, DataClassification classification,
                              String tenantId);

    /** Decrypt ciphertext using the key version stored alongside the encrypted payload. */
    byte[] decrypt(byte[] ciphertext, String keyVersion, DataClassification classification,
                   String tenantId);

    /** Describes the current active key version for a given classification. */
    String currentKeyVersion(DataClassification classification);

    record EncryptionResult(byte[] ciphertext, String keyVersion) {}
}
