package io.auroraforge.keymgmt.infrastructure.aws;

import io.auroraforge.core.application.port.out.KeyManagementPort;
import io.auroraforge.core.config.cloud.AwsProperties;
import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.keymgmt.application.exception.KeyManagementException;
import io.auroraforge.keymgmt.application.port.RotatableKeyAdapter;
import io.auroraforge.keymgmt.infrastructure.cache.DataKeyCache;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * AWS KMS adapter — envelope encryption using the GenerateDataKey pattern.
 *
 * Encryption flow:
 *  1. Check DEK cache for (tenantId, classification).
 *  2. On miss: GenerateDataKey(CMK alias, AES_256) → {plaintextDEK, encryptedDEK}.
 *  3. Cache both forms. Zero the local plaintextDEK after use.
 *  4. Encrypt payload locally: AES-256-GCM(plaintext, plaintextDEK, random-IV).
 *  5. Pack output: [4B encDekLen | encryptedDEK | 12B IV | GCM ciphertext + 16B auth-tag].
 *
 * Decryption flow:
 *  1. Unpack encryptedDEK, IV, ciphertext.
 *  2. KmsClient.Decrypt(encryptedDEK) → plaintextDEK (KMS finds the CMK from the blob).
 *  3. AES-256-GCM decrypt with tenantId+classification AAD (prevents cross-tenant swap).
 *  4. Zero plaintextDEK.
 *
 * Why envelope encryption instead of direct KMS Encrypt?
 *  - KMS plaintext limit is 4096 bytes; envelope encryption removes this restriction.
 *  - GenerateDataKey results are cached — KMS API calls happen once per DEK TTL window,
 *    not once per record (dramatically reduces KMS throttling under high throughput).
 *  - Data bytes never leave the JVM; only the 32-byte DEK touches KMS.
 *
 * Activated when: auroraforge.cloud.provider=AWS
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "AWS")
public class AwsKmsAdapter implements KeyManagementPort, RotatableKeyAdapter {

    private static final int IV_BYTES     = 12;   // 96-bit IV (NIST SP 800-38D recommendation)
    private static final int GCM_TAG_BITS = 128;  // 128-bit authentication tag

    private final KmsClient    kmsClient;
    private final AwsProperties awsProps;
    private final DataKeyCache  dataKeyCache;

    public AwsKmsAdapter(KmsClient kmsClient, AwsProperties awsProps, DataKeyCache dataKeyCache) {
        this.kmsClient   = kmsClient;
        this.awsProps    = awsProps;
        this.dataKeyCache = dataKeyCache;
    }

    // ── Encryption ────────────────────────────────────────────────────────────

    @Override
    @Timed(value = "auroraforge.kms.encrypt", description = "AWS KMS envelope-encrypt latency")
    @CircuitBreaker(name = "key-management")
    @Retry(name = "key-management")
    public EncryptionResult encrypt(byte[] plaintext, DataClassification classification,
                                    String tenantId) {
        byte[] plaintextDek;
        byte[] encryptedDek;
        String cmkVersion;

        Optional<DataKeyCache.CachedDataKey> cached = dataKeyCache.get(tenantId, classification);
        if (cached.isPresent()) {
            plaintextDek = cached.get().plaintextKey();
            encryptedDek = cached.get().encryptedKey();
            cmkVersion   = cached.get().cmkVersion();
            log.debug("DEK cache hit tenantId={} classification={}", tenantId, classification);
        } else {
            GenerateDataKeyResponse dkResp = kmsClient.generateDataKey(
                    GenerateDataKeyRequest.builder()
                            .keyId(resolveKeyAlias())
                            .keySpec(DataKeySpec.AES_256)
                            .encryptionContext(buildContext(classification, tenantId))
                            .build());

            plaintextDek = dkResp.plaintext().asByteArray();
            encryptedDek = dkResp.ciphertextBlob().asByteArray();
            cmkVersion   = dkResp.keyId();

            dataKeyCache.put(tenantId, classification, plaintextDek, encryptedDek, cmkVersion);
            log.debug("Generated new DEK tenantId={} classification={} cmkVersion={}",
                    tenantId, classification, cmkVersion);
        }

        try {
            byte[] iv         = generateIv();
            byte[] ciphertext = aesGcmEncrypt(plaintext, plaintextDek, iv,
                                              buildAad(classification, tenantId));
            log.debug("Encrypted {} bytes via AWS KMS envelope tenantId={}", plaintext.length, tenantId);
            return new EncryptionResult(pack(encryptedDek, iv, ciphertext), cmkVersion);
        } finally {
            Arrays.fill(plaintextDek, (byte) 0); // zero local copy; cache holds its own copy
        }
    }

    // ── Decryption ────────────────────────────────────────────────────────────

    @Override
    @Timed(value = "auroraforge.kms.decrypt", description = "AWS KMS envelope-decrypt latency")
    @CircuitBreaker(name = "key-management")
    @Retry(name = "key-management")
    public byte[] decrypt(byte[] packed, String keyVersion,
                          DataClassification classification, String tenantId) {
        PackedBlob blob = unpack(packed);

        // KMS extracts the CMK from the encryptedDEK ciphertext blob — keyId not required.
        byte[] plaintextDek = kmsClient.decrypt(
                        DecryptRequest.builder()
                                .ciphertextBlob(SdkBytes.fromByteArray(blob.encryptedDek()))
                                .encryptionContext(buildContext(classification, tenantId))
                                .build())
                .plaintext().asByteArray();

        try {
            return aesGcmDecrypt(blob.ciphertext(), plaintextDek, blob.iv(),
                                 buildAad(classification, tenantId));
        } finally {
            Arrays.fill(plaintextDek, (byte) 0);
        }
    }

    // ── Key metadata ──────────────────────────────────────────────────────────

    @Override
    public String currentKeyVersion(DataClassification classification) {
        return kmsClient.describeKey(
                        DescribeKeyRequest.builder().keyId(resolveKeyAlias()).build())
                .keyMetadata()
                .keyId();
    }

    // ── RotatableKeyAdapter ───────────────────────────────────────────────────

    @Override
    public RotationResult triggerRotation(DataClassification classification) {
        String previousVersion = currentKeyVersion(classification);

        // Idempotent: enables AWS-managed annual automatic rotation on this CMK
        kmsClient.enableKeyRotation(
                EnableKeyRotationRequest.builder().keyId(resolveKeyAlias()).build());

        // Evict all DEKs: next encrypt call will generate fresh DEKs whose key material
        // is protected by the rotated CMK's new backing key.
        dataKeyCache.evictAll();

        String newVersion = currentKeyVersion(classification);
        log.info("AWS KMS rotation enabled: cmk={} prev={}", resolveKeyAlias(), previousVersion);

        return new RotationResult(previousVersion, newVersion, Instant.now(), "AWS");
    }

    @Override
    public boolean isRotationEnabled(DataClassification classification) {
        return kmsClient.getKeyRotationStatus(
                        GetKeyRotationStatusRequest.builder().keyId(resolveKeyAlias()).build())
                .keyRotationEnabled();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** All data classifications share the app-data CMK (operators may split by classification). */
    private String resolveKeyAlias() {
        return awsProps.kms().appDataKeyAlias();
    }

    private Map<String, String> buildContext(DataClassification classification, String tenantId) {
        // Encryption context is cryptographically bound: the EXACT same map is required on decrypt.
        // Changing context values makes existing ciphertext permanently undecryptable.
        return Map.of(
                "tenantId",       tenantId,
                "classification", classification.name(),
                "service",        "auroraforge-keymgmt",
                "contextVersion", "v1"
        );
    }

    private byte[] buildAad(DataClassification classification, String tenantId) {
        // AAD for AES-GCM: binds ciphertext to its owner. Prevents a CONFIDENTIAL blob
        // from being submitted for decryption under a different tenant or classification.
        return (tenantId + ":" + classification.name()).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] generateIv() {
        byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    private byte[] aesGcmEncrypt(byte[] plaintext, byte[] key, byte[] iv, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new KeyManagementException("AES-GCM encryption failed", e);
        }
    }

    private byte[] aesGcmDecrypt(byte[] ciphertext, byte[] key, byte[] iv, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new KeyManagementException(
                    "AES-GCM decryption failed — wrong key, wrong context, or tampered ciphertext", e);
        }
    }

    /**
     * Binary pack format (all integers big-endian):
     *   [4B encDekLen | encDek bytes | 12B IV | GCM ciphertext + 16B auth tag]
     *
     * The 4-byte length prefix accommodates DEK blobs up to ~4 GB.
     * AWS KMS ciphertext blobs for AES_256 GenerateDataKey are typically ~185 bytes.
     */
    private byte[] pack(byte[] encDek, byte[] iv, byte[] ciphertext) {
        byte[] out = new byte[4 + encDek.length + IV_BYTES + ciphertext.length];
        out[0] = (byte) (encDek.length >>> 24);
        out[1] = (byte) (encDek.length >>> 16);
        out[2] = (byte) (encDek.length >>> 8);
        out[3] = (byte)  encDek.length;
        System.arraycopy(encDek,     0, out, 4,                            encDek.length);
        System.arraycopy(iv,         0, out, 4 + encDek.length,            IV_BYTES);
        System.arraycopy(ciphertext, 0, out, 4 + encDek.length + IV_BYTES, ciphertext.length);
        return out;
    }

    private PackedBlob unpack(byte[] packed) {
        int encDekLen = ((packed[0] & 0xFF) << 24)
                      | ((packed[1] & 0xFF) << 16)
                      | ((packed[2] & 0xFF) << 8)
                      |  (packed[3] & 0xFF);
        int ivOffset  = 4 + encDekLen;
        int ctOffset  = ivOffset + IV_BYTES;
        return new PackedBlob(
                Arrays.copyOfRange(packed, 4,         ivOffset),
                Arrays.copyOfRange(packed, ivOffset,  ctOffset),
                Arrays.copyOfRange(packed, ctOffset,  packed.length));
    }

    private record PackedBlob(byte[] encryptedDek, byte[] iv, byte[] ciphertext) {}
}
