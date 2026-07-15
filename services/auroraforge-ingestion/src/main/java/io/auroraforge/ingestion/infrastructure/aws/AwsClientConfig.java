package io.auroraforge.ingestion.infrastructure.aws;

import io.auroraforge.core.config.cloud.AwsProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.multipart.MultipartConfiguration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import java.time.Duration;

/**
 * AWS SDK v2 client beans, activated only when running on AWS.
 *
 * All clients use {@link DefaultCredentialsProvider} which resolves credentials
 * in order: environment vars → system properties → AWS profile → ECS task role →
 * EKS IRSA (via OIDC token file). In production on EKS, IRSA supplies the role.
 *
 * The S3AsyncClient is configured with CRT (Common Runtime) multipart support
 * for efficient large object uploads from the Spark processing pipeline.
 */
@Configuration
@ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "AWS")
public class AwsClientConfig {

    private final AwsProperties awsProps;

    public AwsClientConfig(AwsProperties awsProps) {
        this.awsProps = awsProps;
    }

    @Bean
    public KmsClient kmsClient() {
        return KmsClient.builder()
                .region(Region.of(awsProps.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(c -> c
                        .apiCallTimeout(Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)))
                .build();
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsProps.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(c -> c
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(10)))
                .build();
    }

    /**
     * Async S3 client with multipart upload configured.
     * Used by {@link S3TransferManager} for large Spark output files.
     */
    @Bean
    public S3AsyncClient s3AsyncClient() {
        return S3AsyncClient.crtBuilder()
                .region(Region.of(awsProps.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .multipartConfiguration(MultipartConfiguration.builder()
                        .minimumPartSizeInBytes((long) awsProps.s3().multipartPartSizeMb() * 1024 * 1024)
                        .thresholdInBytes((long) awsProps.s3().multipartThresholdMb() * 1024 * 1024)
                        .build())
                .build();
    }

    @Bean
    public S3TransferManager s3TransferManager(S3AsyncClient s3AsyncClient) {
        return S3TransferManager.builder()
                .s3Client(s3AsyncClient)
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.of(awsProps.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsProps.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(c -> c
                        .apiCallTimeout(Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)))
                .build();
    }
}
