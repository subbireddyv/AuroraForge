package io.auroraforge.keymgmt.infrastructure.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.KeyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import io.auroraforge.core.application.port.out.KeyManagementPort;
import io.auroraforge.core.config.cloud.AwsProperties;
import io.auroraforge.core.config.cloud.AzureProperties;
import io.auroraforge.core.config.cloud.CloudProperties;
import io.auroraforge.core.domain.model.DataClassification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

import java.time.Duration;

/**
 * Wires cloud-provider SDK clients conditionally on {@code auroraforge.cloud.provider}.
 *
 * Design:
 *  - The adapters ({@code AwsKmsAdapter}, {@code AzureKeyVaultAdapter}) are Spring
 *    {@code @Component}s with their own {@code @ConditionalOnProperty}. They auto-register
 *    as both {@code KeyManagementPort} and {@code RotatableKeyAdapter} beans.
 *
 *  - This class provides only the SDK client beans (KmsClient, CryptographyClient, KeyClient)
 *    that the adapters auto-wire. {@code @ConditionalOnMissingBean} prevents double-registration
 *    when the ingestion module's {@code AwsClientConfig} is also on the classpath.
 *
 *  - The LOCAL no-op adapter is an inner class here (not a {@code @Component} file),
 *    instantiated as a {@code @Bean} when cloud.provider=LOCAL.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({
        CloudProperties.class,
        AwsProperties.class,
        AzureProperties.class,
        KeyRotationProperties.class
})
public class KeyManagementConfig {

    // ── AWS SDK clients ───────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "AWS")
    @ConditionalOnMissingBean(KmsClient.class)
    public KmsClient kmsClient(AwsProperties awsProps) {
        log.info("Initialising AWS KmsClient (region={})", awsProps.region());
        return KmsClient.builder()
                .region(Region.of(awsProps.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(c -> c
                        .apiCallTimeout(Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)))
                .build();
    }

    // ── Azure SDK clients ─────────────────────────────────────────────────────

    /**
     * CryptographyClient: used for encrypt/decrypt operations against a specific key version.
     * Pinned to the configured key version (blank = latest).
     */
    @Bean
    @ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "AZURE")
    @ConditionalOnMissingBean(CryptographyClient.class)
    public CryptographyClient cryptographyClient(AzureProperties azureProps) {
        String keyIdentifier = azureProps.keyvault().uri()
                + "/keys/" + azureProps.keyvault().keyName();
        if (azureProps.keyvault().keyVersion() != null
                && !azureProps.keyvault().keyVersion().isBlank()) {
            keyIdentifier += "/" + azureProps.keyvault().keyVersion();
        }
        log.info("Initialising Azure CryptographyClient (keyId={})", keyIdentifier);
        return new CryptographyClientBuilder()
                .keyIdentifier(keyIdentifier)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }

    /**
     * KeyClient: used for key management operations (getKey, rotateKey, etc.).
     * Vault-scoped, not key-version-scoped.
     */
    @Bean
    @ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "AZURE")
    @ConditionalOnMissingBean(KeyClient.class)
    public KeyClient azureKeyClient(AzureProperties azureProps) {
        log.info("Initialising Azure KeyClient (vault={})", azureProps.keyvault().uri());
        return new KeyClientBuilder()
                .vaultUrl(azureProps.keyvault().uri())
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }

    // ── LOCAL no-op ───────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "LOCAL")
    public KeyManagementPort localKeyManagementPort() {
        log.warn("Activating NO-OP local key management adapter — NOT suitable for production");
        return new LocalNoOpKeyManagementAdapter();
    }

    /**
     * Passthrough adapter for docker-compose / unit-test environments.
     * Returns plaintext unchanged — encryption is a no-op. Do not use in staging or production.
     */
    private static class LocalNoOpKeyManagementAdapter implements KeyManagementPort {

        @Override
        public EncryptionResult encrypt(byte[] plaintext,
                                        DataClassification classification,
                                        String tenantId) {
            return new EncryptionResult(plaintext, "local-noop-v1");
        }

        @Override
        public byte[] decrypt(byte[] ciphertext, String keyVersion,
                              DataClassification classification, String tenantId) {
            return ciphertext;
        }

        @Override
        public String currentKeyVersion(DataClassification classification) {
            return "local-noop-v1";
        }
    }
}
