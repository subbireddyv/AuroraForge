package io.auroraforge.keymgmt.application.port;

import io.auroraforge.core.domain.model.DataClassification;

import java.time.Instant;

/**
 * Internal port: cloud-specific rotation capability.
 *
 * Implemented by {@code AwsKmsAdapter} (enables KMS automatic rotation + on-demand re-keying)
 * and {@code AzureKeyVaultAdapter} (triggers Key Vault rotation policy immediately).
 *
 * The LOCAL no-op adapter does NOT implement this interface. Code that depends on rotation
 * must inject this with {@code @Autowired(required = false)} and guard against {@code null}.
 */
public interface RotatableKeyAdapter {

    /**
     * Trigger CMK rotation for the given classification.
     * For AWS: enables automatic annual rotation and evicts the DEK cache.
     * For Azure: calls {@code rotateKey()} immediately creating a new key version.
     *
     * @return metadata about the rotation event
     */
    RotationResult triggerRotation(DataClassification classification);

    /** Returns true if automatic rotation is enabled for this classification's CMK. */
    boolean isRotationEnabled(DataClassification classification);

    record RotationResult(
            String previousVersion,
            String newVersion,
            Instant rotatedAt,
            String cloudProvider
    ) {}
}
