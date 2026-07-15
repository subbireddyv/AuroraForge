package io.auroraforge.ingestion.infrastructure.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.secrets.SecretClient;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import io.auroraforge.core.config.cloud.AzureProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Azure SDK client beans, activated only when running on Azure.
 *
 * {@link DefaultAzureCredentialBuilder} chains: environment → Workload Identity →
 * Managed Identity → Azure CLI → Visual Studio Code. On AKS, Workload Identity
 * (OIDC federated credential bound to the pod's K8s service account) is used.
 * No client secrets or certificates are stored in the cluster.
 *
 * The {@link CryptographyClient} is scoped to the specific key version used for
 * encryption. During key rotation, a new client bound to the new version is needed;
 * the old version remains available for decryption of existing ciphertext.
 */
@Configuration
@ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "AZURE")
public class AzureClientConfig {

    private final AzureProperties azureProps;

    public AzureClientConfig(AzureProperties azureProps) {
        this.azureProps = azureProps;
    }

    /**
     * Key Vault Cryptography client for envelope encryption.
     * If keyVersion is empty, uses the current version (latest).
     */
    @Bean
    public CryptographyClient cryptographyClient() {
        String keyIdentifier = azureProps.keyvault().uri()
                + "/keys/" + azureProps.keyvault().keyName();
        if (!azureProps.keyvault().keyVersion().isBlank()) {
            keyIdentifier += "/" + azureProps.keyvault().keyVersion();
        }
        return new CryptographyClientBuilder()
                .keyIdentifier(keyIdentifier)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }

    /** Key Vault Secrets client — used by AzureKeyVaultSecretAdapter. */
    @Bean
    public SecretClient secretClient() {
        return new SecretClientBuilder()
                .vaultUrl(azureProps.keyvault().uri())
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }

    /**
     * Azure Blob Storage client.
     * The storage account uses Managed Identity auth (not shared key) as required
     * by the Terraform deny-shared-key-access policy on the storage account.
     */
    @Bean
    public BlobServiceClient blobServiceClient() {
        String endpoint = "https://" + azureProps.blob().accountName() + ".blob.core.windows.net";
        return new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }
}
