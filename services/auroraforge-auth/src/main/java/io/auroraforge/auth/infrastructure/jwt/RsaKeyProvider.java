package io.auroraforge.auth.infrastructure.jwt;

import io.auroraforge.auth.infrastructure.config.JwtProperties;
import io.auroraforge.core.config.cloud.CloudProperties;
import io.auroraforge.core.config.cloud.CloudProperties.CloudProviderType;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the RSA-2048 key pair used to sign and verify JWT tokens.
 *
 * Loading priority (first successful source wins):
 * <ol>
 *   <li>AWS Secrets Manager — when cloud provider = AWS and {@code rsaKey.secretsManagerArn} is set</li>
 *   <li>Azure Key Vault     — when cloud provider = AZURE and {@code rsaKey.keyVaultSecretName} is set</li>
 *   <li>Base64 PEM in config — when {@code rsaKey.privateKeyBase64} is set (LOCAL / test)</li>
 *   <li>Auto-generate       — when {@code rsaKey.generateOnStartup = true} (dev only)</li>
 * </ol>
 *
 * Auto-generated keys are logged at INFO so the public key can be embedded in service configs
 * for testing; never use auto-generation in production.
 */
@Slf4j
@Component
public class RsaKeyProvider {

    private final JwtProperties   jwtProps;
    private final CloudProperties cloudProps;

    // Optional cloud clients — may be null when the matching profile is inactive
    private final SecretsManagerClient awsSecretsClient;
    private final com.azure.security.keyvault.secrets.SecretClient azureSecretClient;

    @Getter private RSAPrivateKey privateKey;
    @Getter private RSAPublicKey  publicKey;

    public RsaKeyProvider(
            JwtProperties jwtProps,
            CloudProperties cloudProps,
            // @Nullable — injected only when AWS SDK is on classpath and configured
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            SecretsManagerClient awsSecretsClient,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.azure.security.keyvault.secrets.SecretClient azureSecretClient) {

        this.jwtProps          = jwtProps;
        this.cloudProps        = cloudProps;
        this.awsSecretsClient  = awsSecretsClient;
        this.azureSecretClient = azureSecretClient;
    }

    @PostConstruct
    public void init() throws Exception {
        JwtProperties.RsaKeyConfig cfg = jwtProps.rsaKey();

        if (cloudProps.provider() == CloudProviderType.AWS && cfg.secretsManagerArn() != null) {
            loadFromAwsSecretsManager(cfg.secretsManagerArn());
        } else if (cloudProps.provider() == CloudProviderType.AZURE && cfg.keyVaultSecretName() != null) {
            loadFromAzureKeyVault(cfg.keyVaultSecretName());
        } else if (cfg.privateKeyBase64() != null && cfg.publicKeyBase64() != null) {
            loadFromConfig(cfg.privateKeyBase64(), cfg.publicKeyBase64());
        } else if (cfg.generateOnStartup()) {
            generateKeyPair();
        } else {
            throw new IllegalStateException(
                "No RSA key source configured. Set one of: " +
                "auroraforge.jwt.rsa-key.private-key-base64, " +
                "auroraforge.jwt.rsa-key.secrets-manager-arn, " +
                "auroraforge.jwt.rsa-key.key-vault-secret-name, " +
                "or auroraforge.jwt.rsa-key.generate-on-startup=true (dev only)");
        }

        log.info("RSA key pair loaded. kid={} publicKey={}",
                 jwtProps.rsaKey().keyId(),
                 Base64.getEncoder().encodeToString(publicKey.getEncoded()).substring(0, 32) + "...");
    }

    // ── Private loaders ──────────────────────────────────────────────────────

    private void loadFromConfig(String privateBase64, String publicBase64) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");

        byte[] privateBytes = Base64.getDecoder().decode(stripPemHeaders(privateBase64));
        privateKey = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(privateBytes));

        byte[] publicBytes = Base64.getDecoder().decode(stripPemHeaders(publicBase64));
        publicKey = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(publicBytes));

        log.info("RSA key pair loaded from configuration.");
    }

    private void loadFromAwsSecretsManager(String secretArn) throws Exception {
        if (awsSecretsClient == null) {
            throw new IllegalStateException(
                "AWS Secrets Manager client not available — ensure the aws profile is active.");
        }
        String secretValue = awsSecretsClient
                .getSecretValue(GetSecretValueRequest.builder().secretId(secretArn).build())
                .secretString();

        // Secret format: "PRIVATE_KEY_B64=<base64>\nPUBLIC_KEY_B64=<base64>"
        String privatePem = parseSecretField(secretValue, "PRIVATE_KEY_B64");
        String publicPem  = parseSecretField(secretValue, "PUBLIC_KEY_B64");
        loadFromConfig(privatePem, publicPem);
        log.info("RSA key pair loaded from AWS Secrets Manager: {}", secretArn);
    }

    private void loadFromAzureKeyVault(String secretName) throws Exception {
        if (azureSecretClient == null) {
            throw new IllegalStateException(
                "Azure Key Vault secret client not available — ensure the azure profile is active.");
        }
        // Key Vault secret stores JSON: {"privateKey":"<b64>","publicKey":"<b64>"}
        String secretValue = azureSecretClient.getSecret(secretName).getValue();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode node = om.readTree(secretValue);
        loadFromConfig(node.get("privateKey").asText(), node.get("publicKey").asText());
        log.info("RSA key pair loaded from Azure Key Vault secret: {}", secretName);
    }

    private void generateKeyPair() throws NoSuchAlgorithmException {
        log.warn("Generating ephemeral RSA-2048 key pair — NOT suitable for production use!");
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, new SecureRandom());
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey  = (RSAPublicKey)  pair.getPublic();

        // Log for developer convenience so the public key can be captured and pinned
        String privB64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String pubB64  = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        log.info("=== Generated RSA private key (PKCS#8 DER base64) ===\n{}", privB64);
        log.info("=== Generated RSA public key (X.509 DER base64)  ===\n{}", pubB64);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Strips PEM armour (-----BEGIN ...-----) and whitespace, returning raw base64. */
    private static String stripPemHeaders(String pem) {
        return pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s+", "");
    }

    private static String parseSecretField(String secretString, String fieldName) {
        for (String line : secretString.split("\n")) {
            if (line.startsWith(fieldName + "=")) {
                return line.substring(fieldName.length() + 1).trim();
            }
        }
        throw new IllegalArgumentException("Field '" + fieldName + "' not found in secret value");
    }
}
