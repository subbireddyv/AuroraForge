package io.auroraforge.core.config.cloud;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AWS-specific runtime configuration.
 *
 * Bound from: auroraforge.aws.*
 *
 * All values are injected via environment variables in EKS via the Secrets Store
 * CSI Driver — no plaintext credentials in the config file. The IAM role bound
 * via IRSA provides access to KMS, S3, RDS, and Secrets Manager.
 */
@Validated
@ConfigurationProperties(prefix = "auroraforge.aws")
public record AwsProperties(

        @NotBlank String region,

        String secondaryRegion,

        KmsConfig kms,

        S3Config s3,

        RdsConfig rds,

        EksConfig eks

) {
    public AwsProperties {
        if (secondaryRegion == null) secondaryRegion = "us-west-2";
        if (kms  == null) kms  = KmsConfig.defaults();
        if (s3   == null) s3   = S3Config.defaults();
        if (rds  == null) rds  = RdsConfig.defaults();
        if (eks  == null) eks  = EksConfig.defaults();
    }

    /**
     * KMS Customer Managed Key aliases — must match what Terraform created.
     * Never embed key IDs directly; aliases are stable across key rotation.
     */
    public record KmsConfig(
            String appDataKeyAlias,
            String rdsKeyAlias,
            String s3KeyAlias,
            String cloudwatchKeyAlias
    ) {
        static KmsConfig defaults() {
            return new KmsConfig(
                "alias/auroraforge-app-data",
                "alias/auroraforge-rds",
                "alias/auroraforge-s3",
                "alias/auroraforge-cloudwatch"
            );
        }
    }

    /** S3 bucket names supplied via Terraform output → K8s ConfigMap. */
    public record S3Config(
            String rawBucket,
            String processedBucket,
            String sparkCheckpointsBucket,
            String backupBucket,
            int multipartThresholdMb,
            int multipartPartSizeMb
    ) {
        static S3Config defaults() {
            return new S3Config("", "", "", "", 100, 50);
        }
    }

    /** RDS connection details injected from Secrets Manager at pod startup. */
    public record RdsConfig(
            String endpoint,
            String readerEndpoint,
            String secretArn,
            String dbName,
            int maxPoolSize,
            int minIdle
    ) {
        static RdsConfig defaults() {
            return new RdsConfig("", "", "", "auroraforge", 20, 5);
        }
    }

    /** EKS cluster metadata — used for k8s client configuration. */
    public record EksConfig(
            String clusterName,
            String serviceAccountName,
            String irsaRoleArn
    ) {
        static EksConfig defaults() {
            return new EksConfig("auroraforge-eks", "auroraforge-sa", "");
        }
    }
}
