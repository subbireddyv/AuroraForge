package io.auroraforge.core.config.cloud;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Azure-specific runtime configuration.
 *
 * Bound from: auroraforge.azure.*
 *
 * Credentials are provided via Workload Identity (federated credential on the
 * AKS pod's service account) — no client secrets stored in the cluster.
 * The managed identity has Key Vault "Crypto User" + "Secrets User" RBAC.
 */
@Validated
@ConfigurationProperties(prefix = "auroraforge.azure")
public record AzureProperties(

        @NotBlank String subscriptionId,

        @NotBlank String resourceGroup,

        @NotBlank String location,

        String secondaryLocation,

        KeyVaultConfig keyvault,

        CosmosConfig cosmos,

        BlobConfig blob,

        AksConfig aks

) {
    public AzureProperties {
        if (secondaryLocation == null) secondaryLocation = "westus2";
        if (keyvault == null) keyvault = KeyVaultConfig.defaults();
        if (cosmos   == null) cosmos   = CosmosConfig.defaults();
        if (blob     == null) blob     = BlobConfig.defaults();
        if (aks      == null) aks      = AksConfig.defaults();
    }

    /**
     * Azure Key Vault configuration.
     * The {@code keyName} is the RSA-HSM 4096-bit key used for envelope encryption.
     */
    public record KeyVaultConfig(
            String uri,
            String keyName,
            String keyVersion,
            boolean useHsm
    ) {
        static KeyVaultConfig defaults() {
            return new KeyVaultConfig("", "auroraforge-app-key", "", true);
        }
    }

    /**
     * Cosmos DB multi-region configuration.
     * {@code consistencyLevel} mirrors the Terraform BoundedStaleness setting.
     */
    public record CosmosConfig(
            String endpoint,
            String databaseName,
            String containerName,
            String consistencyLevel,
            boolean enableAnalyticalStorage,
            int requestUnitsPerSecond
    ) {
        static CosmosConfig defaults() {
            return new CosmosConfig(
                "", "auroraforge", "events",
                "BoundedStaleness", true, 4000
            );
        }
    }

    /** Azure Blob Storage — mirrors the Crossplane ObjectStore composition. */
    public record BlobConfig(
            String accountName,
            String rawContainer,
            String processedContainer,
            String backupContainer,
            int blockSizeMb
    ) {
        static BlobConfig defaults() {
            return new BlobConfig("", "raw", "processed", "backup", 50);
        }
    }

    /** AKS workload identity — used when constructing managed identity token requests. */
    public record AksConfig(
            String clusterName,
            String managedIdentityClientId,
            String tenantId,
            String oidcIssuerUrl
    ) {
        static AksConfig defaults() {
            return new AksConfig("auroraforge-aks", "", "", "");
        }
    }
}
