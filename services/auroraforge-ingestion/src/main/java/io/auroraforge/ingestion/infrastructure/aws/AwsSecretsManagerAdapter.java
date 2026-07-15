package io.auroraforge.ingestion.infrastructure.aws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.auroraforge.core.application.port.out.CloudSecretPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;

import java.util.Map;
import java.util.Optional;

/**
 * AWS Secrets Manager implementation of {@link CloudSecretPort}.
 *
 * Responses are cached in the Spring cache abstraction (caffeine, Redis, etc.)
 * to avoid per-request Secrets Manager API calls (~1ms round trip on EKS,
 * but adds up at scale and incurs per-call cost). The cache is evicted on
 * {@link #rotateSecret} to force fresh retrieval after rotation.
 *
 * Cache name: "secrets" — configure TTL in application.yml:
 *   spring.cache.caffeine.spec=maximumSize=200,expireAfterWrite=300s
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "AWS")
public class AwsSecretsManagerAdapter implements CloudSecretPort {

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper          objectMapper;

    @Override
    @Cacheable(value = "secrets", key = "#secretName")
    public String getSecret(String secretName) {
        try {
            return secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder().secretId(secretName).build()
            ).secretString();
        } catch (ResourceNotFoundException e) {
            throw new SecretNotFoundException(secretName);
        }
    }

    @Override
    @Cacheable(value = "secrets", key = "'map:' + #secretName")
    public Map<String, String> getSecretMap(String secretName) {
        String json = getSecret(secretName);
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Secret '" + secretName + "' is not valid JSON", e);
        }
    }

    @Override
    public void putSecret(String secretName, String value) {
        try {
            secretsManagerClient.createSecret(
                    CreateSecretRequest.builder().name(secretName).secretString(value).build());
            log.info("Created secret: {}", secretName);
        } catch (ResourceExistsException e) {
            secretsManagerClient.putSecretValue(
                    PutSecretValueRequest.builder().secretId(secretName).secretString(value).build());
            log.info("Updated secret: {}", secretName);
        }
    }

    @Override
    public void putSecretMap(String secretName, Map<String, String> values) {
        try {
            putSecret(secretName, objectMapper.writeValueAsString(values));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize secret map for: " + secretName, e);
        }
    }

    @Override
    @CacheEvict(value = "secrets", allEntries = true)
    public void rotateSecret(String secretName) {
        secretsManagerClient.rotateSecret(
                RotateSecretRequest.builder().secretId(secretName).rotateImmediately(true).build());
        log.info("Initiated rotation for secret: {}", secretName);
    }

    @Override
    public Optional<String> getSecretArn(String secretName) {
        try {
            return Optional.of(secretsManagerClient.describeSecret(
                    DescribeSecretRequest.builder().secretId(secretName).build()).arn());
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }
}
