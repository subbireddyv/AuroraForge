package io.auroraforge.keymgmt.infrastructure.cache;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.keymgmt.infrastructure.config.KeyRotationProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory cache of plaintext data-encryption keys (DEKs).
 *
 * Why cache DEKs?
 * Without a cache, every {@code encrypt()} call invokes {@code GenerateDataKey} (AWS)
 * or {@code CryptographyClient.encrypt(aesKey)} (Azure). At scale (thousands of
 * records/second per tenant) this saturates KMS/Key Vault throttling limits fast.
 *
 * The cache stores BOTH the plaintext DEK (for local AES-GCM) AND its cloud-wrapped
 * form (so subsequent encryptions pack the same encrypted DEK into the ciphertext blob,
 * allowing the decrypt path to call KMS/Key Vault with the correct ciphertext).
 *
 * Security notes:
 *  - Plaintext DEK bytes live in JVM heap. An OS-level attacker with read access to
 *    the JVM heap can extract them (as with any in-process secret).
 *  - TTLs are short (60–300 s) to limit the exposure window.
 *  - On eviction the byte arrays are zeroed to reduce residual-memory risk.
 *  - In a FIPS-140-2 environment, replace with HSM-resident key handles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataKeyCache {

    private final KeyRotationProperties props;
    private final ConcurrentHashMap<String, CachedDataKey> store = new ConcurrentHashMap<>();
    private final AtomicInteger hitCounter  = new AtomicInteger();
    private final AtomicInteger missCounter = new AtomicInteger();

    public record CachedDataKey(
            byte[] plaintextKey,
            byte[] encryptedKey,   // packed alongside ciphertext for decryption
            String cmkVersion,
            Instant expiresAt
    ) {
        public boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Returns a cached DEK if one exists and has not expired.
     */
    public Optional<CachedDataKey> get(String tenantId, DataClassification classification) {
        String key = cacheKey(tenantId, classification);
        CachedDataKey entry = store.get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null) {
                evictAndZero(key, entry);
            }
            missCounter.incrementAndGet();
            return Optional.empty();
        }
        hitCounter.incrementAndGet();
        return Optional.of(entry);
    }

    /**
     * Store a freshly generated DEK with its TTL for the given tenant + classification.
     * Copies the byte arrays defensively so the caller can safely zero its locals.
     */
    public void put(String tenantId, DataClassification classification,
                    byte[] plaintextKey, byte[] encryptedKey, String cmkVersion) {
        int ttl = ttlFor(classification);
        store.put(cacheKey(tenantId, classification), new CachedDataKey(
                Arrays.copyOf(plaintextKey, plaintextKey.length),
                Arrays.copyOf(encryptedKey, encryptedKey.length),
                cmkVersion,
                Instant.now().plusSeconds(ttl)));
    }

    /** Evict all cached DEKs for every classification of a specific tenant. */
    public void evict(String tenantId) {
        store.entrySet().removeIf(e -> {
            boolean match = e.getKey().startsWith(tenantId + ":");
            if (match) zeroKey(e.getValue());
            return match;
        });
        log.debug("DEK cache evicted for tenantId={}", tenantId);
    }

    /** Evict a specific tenant + classification DEK. */
    public void evict(String tenantId, DataClassification classification) {
        CachedDataKey removed = store.remove(cacheKey(tenantId, classification));
        if (removed != null) zeroKey(removed);
        log.debug("DEK cache evicted tenantId={} classification={}", tenantId, classification);
    }

    /** Evict all DEKs across all tenants (called on global CMK rotation). */
    public void evictAll() {
        store.forEach((k, v) -> zeroKey(v));
        store.clear();
        log.info("DEK cache fully evicted (global CMK rotation triggered)");
    }

    public int size()      { return store.size(); }
    public int hitCount()  { return hitCounter.get(); }
    public int missCount() { return missCounter.get(); }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void evictAndZero(String key, CachedDataKey entry) {
        store.remove(key, entry);
        zeroKey(entry);
    }

    private void zeroKey(CachedDataKey entry) {
        if (entry != null) {
            Arrays.fill(entry.plaintextKey(), (byte) 0);
        }
    }

    private String cacheKey(String tenantId, DataClassification classification) {
        return tenantId + ":" + classification.name();
    }

    private int ttlFor(DataClassification classification) {
        return switch (classification) {
            case PUBLIC       -> props.publicDataKeyTtlSeconds();
            case INTERNAL     -> props.internalDataKeyTtlSeconds();
            case CONFIDENTIAL -> props.confidentialDataKeyTtlSeconds();
            case RESTRICTED   -> props.restrictedDataKeyTtlSeconds();
        };
    }
}
