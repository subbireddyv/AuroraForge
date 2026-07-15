package io.auroraforge.core.application.port.out;

import java.util.Map;
import java.util.Optional;

/**
 * Output port for cloud-agnostic secret management.
 *
 * Implementations:
 *  - AwsSecretsManagerAdapter  (aws profile)
 *  - AzureKeyVaultSecretAdapter (azure profile)
 *  - LocalVaultSecretAdapter (local / docker profile — HashiCorp Vault)
 *
 * Secret names should follow the convention:
 *  auroraforge/{environment}/{service}/{secret-name}
 */
public interface CloudSecretPort {

    /**
     * Retrieves the current secret value as a plaintext string.
     * Implementations cache the value per the cloud SDK's built-in TTL
     * to avoid per-request latency.
     *
     * @throws SecretNotFoundException if the secret does not exist
     */
    String getSecret(String secretName);

    /**
     * Retrieves all key-value pairs from a JSON secret (structured secret).
     * Implementations parse the JSON string returned by the cloud provider.
     *
     * @throws SecretNotFoundException if the secret does not exist
     */
    Map<String, String> getSecretMap(String secretName);

    /**
     * Creates or updates a secret. The implementation determines whether
     * to create or update based on existence check.
     */
    void putSecret(String secretName, String value);

    /**
     * Creates or updates a structured (JSON key-value) secret.
     */
    void putSecretMap(String secretName, Map<String, String> values);

    /**
     * Initiates immediate secret rotation. The actual rotation mechanism
     * (Lambda for AWS, Key Vault rotation policy for Azure) is configured
     * in Terraform and triggered here.
     */
    void rotateSecret(String secretName);

    /**
     * Returns the ARN (AWS) or secret identifier (Azure) for audit logging.
     */
    Optional<String> getSecretArn(String secretName);

    /** Thrown when a requested secret does not exist in the backing store. */
    class SecretNotFoundException extends RuntimeException {
        public SecretNotFoundException(String name) {
            super("Secret not found: " + name);
        }
    }
}
