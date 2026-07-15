package io.auroraforge.auth.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * JWT token settings.
 *
 * <pre>
 * auroraforge:
 *   jwt:
 *     issuer: https://auth.auroraforge.io
 *     access-token-expiry-seconds: 900         # 15 min
 *     refresh-token-expiry-seconds: 86400      # 24 h
 *     rsa-key:
 *       private-key-base64: <PEM base64>        # LOCAL only
 *       public-key-base64:  <PEM base64>        # LOCAL only
 *       secrets-manager-arn: arn:aws:...        # AWS profile
 *       key-vault-secret-name: aurora-jwt-key  # Azure profile
 *       key-id: auroraforge-auth-key-1          # JWK "kid" header
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "auroraforge.jwt")
public record JwtProperties(

        @NotBlank
        String issuer,

        /** Access token lifetime in seconds. Default: 900 (15 min). */
        @Positive
        long accessTokenExpirySeconds,

        /** Refresh token lifetime in seconds. Default: 86400 (24 h). */
        @Positive
        long refreshTokenExpirySeconds,

        @Valid
        RsaKeyConfig rsaKey

) {
    public JwtProperties {
        if (issuer == null)                        issuer = "https://auth.auroraforge.io";
        if (accessTokenExpirySeconds  <= 0)        accessTokenExpirySeconds  = 900L;
        if (refreshTokenExpirySeconds <= 0)        refreshTokenExpirySeconds = 86400L;
        if (rsaKey == null)                        rsaKey = new RsaKeyConfig(null, null, null, null, "auroraforge-auth-key-1", false);
    }

    /**
     * RSA key pair configuration.
     *
     * Priority (first non-null wins):
     *  1. AWS Secrets Manager  (secrets-manager-arn)
     *  2. Azure Key Vault      (key-vault-secret-name)
     *  3. PEM base64 in config (private-key-base64 / public-key-base64)
     *  4. Auto-generate on startup (all fields null → dev/test only)
     */
    public record RsaKeyConfig(
            /** PEM-encoded PKCS#8 private key, base64 (no line breaks). LOCAL/test only. */
            String privateKeyBase64,

            /** PEM-encoded X.509 SubjectPublicKeyInfo, base64 (no line breaks). LOCAL/test only. */
            String publicKeyBase64,

            /** AWS Secrets Manager ARN storing the PEM private key. Used on the "aws" profile. */
            String secretsManagerArn,

            /** Azure Key Vault secret name storing the PEM private key. Used on the "azure" profile. */
            String keyVaultSecretName,

            /**
             * JWK "kid" header value included in every token and returned by the JWKS endpoint.
             * Other services use it to select the correct verification key during rotation.
             */
            @NotBlank
            String keyId,

            /**
             * When true, a fresh RSA-2048 key pair is generated on every startup and logged at INFO.
             * Set to false in all non-dev environments.
             */
            boolean generateOnStartup
    ) {}
}
