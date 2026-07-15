package io.auroraforge.auth.presentation;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import io.auroraforge.auth.infrastructure.config.JwtProperties;
import io.auroraforge.auth.infrastructure.jwt.RsaKeyProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.RSAPublicKey;
import java.util.Map;

/**
 * Exposes the public key as a JSON Web Key Set (JWKS) so any service that receives
 * AuroraForge JWTs can independently verify their RS256 signatures without calling
 * the auth service on each request.
 *
 * <pre>
 *   GET /.well-known/jwks.json
 * </pre>
 *
 * The {@code kid} in each JWK matches the {@code kid} header in issued tokens so
 * verifiers can select the correct key during key rotation (multiple keys in the set).
 *
 * Only active when {@code auroraforge.auth.server.enabled=true}.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "auroraforge.auth.server.enabled", havingValue = "true", matchIfMissing = true)
public class JwksController {

    private final RsaKeyProvider  rsaKeyProvider;
    private final JwtProperties   jwtProps;

    /** Cached JWKS serialization — the public key is loaded once at startup. */
    private volatile String cachedJwksJson;

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        if (cachedJwksJson == null) {
            cachedJwksJson = buildJwkSet().toPublicJWKSet().toJSONObject().toString();
        }

        // Parse to Map so Jackson can re-serialise with standard formatting
        JWKSet jwkSet = buildJwkSet().toPublicJWKSet();
        return ResponseEntity.ok(jwkSet.toJSONObject());
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private JWKSet buildJwkSet() {
        RSAPublicKey pub = rsaKeyProvider.getPublicKey();

        JWK key = new RSAKey.Builder(pub)
                .keyUse(KeyUse.SIGNATURE)
                .keyID(jwtProps.rsaKey().keyId())
                .algorithm(com.nimbusds.jose.JWSAlgorithm.RS256)
                .build();

        return new JWKSet(key);
    }
}
