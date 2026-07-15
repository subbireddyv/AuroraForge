package io.auroraforge.keymgmt.infrastructure.azure;

import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.DecryptParameters;
import com.azure.security.keyvault.keys.cryptography.models.EncryptParameters;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import io.auroraforge.core.application.port.out.KeyManagementPort;
import io.auroraforge.core.config.cloud.AzureProperties;
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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Azure Key Vault adapter — envelope encryption using RSA-OAEP-256 key wrapping.
 *
 * Encryption flow:
 *  1. Check DEK cache for (tenantId, classification).
 *  2. On miss: generate a random AES-256 DEK locally.
 *  3. Wrap the DEK with RSA-OAEP-256 via Key Vault CryptographyClient.encrypt().
 *  4. Cache both plaintextDEK + wrappedDEK.
 *  5. Encrypt payload locally: AES-256-GCM(plaintext, plaintextDEK, random-IV).
 *  6. Pack output: [4B wrappedKeyLen | wrappedKey | 12B IV | GCM ciphertext + 16B auth-tag].
 *
 * Decryption flow:
 *  1. Unpack wrappedKey, IV, ciphertext.
 *  2. CryptographyClient.decrypt(wrappedKey) → plaintextDEK (RSA-OAEP-256 unwrap).
 *  3. AES-256-GCM decrypt with tenantId+classification AAD.
 *  4. Zero plaintextDEK.
 *
 * Algorithm choices:
 *  - RSA-OAEP-256 (not RSA-1.5): OAEP is semantically secure; PKCS#1 v1.5 is vulnerable
 *    to padding oracle attacks and has been deprecated in NIST guidance.
 *  - AES-256-GCM: authenticated encryption; the 16-byte auth-tag detects tampering.
 *
 * Key Vault Premium with HSM-backed RSA keys (4096-bit) means the private key material
 * never leaves the HSM, satisfying FIPS 140-2 Level 3 / PCI-DSS requirements.
 *
 * Activated when: auroraforge.cloud.provider=AZURE
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "auroraforge.cloud.provider", havingValue = "AZURE")
public class AzureKeyVaultAdapter implements KeyManagementPort, RotatableKeyAdapter {

    private static final int AES_KEY_BYTES = 32;   // AES-256
    private static final int IV_BYTES      = 12;   // 96-bit GCM IV
    private static final int GCM_TAG_BITS  = 128;

    private final CryptographyClient cryptographyClient;
    private final KeyClient          keyClient;
    private final AzureProperties    azureProps;
    private final DataKeyCache       dataKeyCache;

    public AzureKeyVaultAdapter(CryptographyClient cryptographyClient,
                                KeyClient keyClient,
                                AzureProperties azureProps,
                                DataKeyCache dataKeyCache) {
        this.cryptographyClient = cryptographyClient;
        this.keyClient          = keyClient;
        this.azureProps         = azureProps;
        this.dataKeyCache       = dataKeyCache;
    }

    // ── Encryption ────────────────────────────────────────────────────────────

    @Override
    @Timed(value = "auroraforge.keyvault.encrypt", description = "Azure Key Vault envelope-encrypt latency")
    @CircuitBreaker(name = "key-management")
    @Retry(name = "key-management")
    public EncryptionResult encrypt(byte[] plaintext, DataClassification classification,
                                    String tenantId) {
        byte[] aesKey;
        byte[] wrappedKey;
        String keyVersion;

        Optional<DataKeyCache.CachedDataKey> cached = dataKeyCache.get(tenantId, classification);
        if (cached.isPresent()) {
            aesKey     = cached.get().plaintextKey();
            wrappedKey = cached.get().encryptedKey();
            keyVersion = cached.get().cmkVersion();
            log.debug("DEK cache hit tenantId={} classification={}", tenantId, classification);
        } else {
            aesKey     = generateAesKey();
            keyVersion = currentKeyVersion(classification);

            // RSA-OAEP-256 wrap: the AES key bytes are treated as the "plaintext" for RSA.
            // Key Vault Premium RSA-HSM keys support RSA_OAEP_256 for wrapping.
            wrappedKey = cryptographyClient
                    .encrypt(EncryptParameters.createRsaOaep256Parameters(aesKey))
                    .getCipherText();

            dataKeyCache.put(tenantId, classification, aesKey, wrappedKey, keyVersion);
            log.debug("Wrapped new DEK via Azure Key Vault tenantId={} classification={}",
                    tenantId, classification);
        }

        try {
            byte[] iv         = generateIv();
            byte[] ciphertext = aesGcmEncrypt(plaintext, aesKey, iv,
                                              buildAad(classification, tenantId));
            log.debug("Encrypted {} bytes via Azure Key Vault envelope tenantId={}",
                    plaintext.length, tenantId);
            return new EncryptionResult(pack(wrappedKey, iv, ciphertext), keyVersion);
        } finally {
            Arrays.fill(aesKey, (byte) 0);
        }
    }

    // ── Decryption ────────────────────────────────────────────────────────────

    @Override
    @Timed(value = "auroraforge.keyvault.decrypt", description = "Azure Key Vault envelope-decrypt latency")
    @CircuitBreaker(name = "key-management")
    @Retry(name = "key-management")
    public byte[] decrypt(byte[] packed, String keyVersion,
                          DataClassification classification, String tenantId) {
        PackedBlob blob = unpack(packed);

        // RSA-OAEP-256 unwrap via the injected CryptographyClient.
        // If the keyVersion differs from the current client's pinned version (post-rotation),
        // update AZURE_KEY_VAULT_KEY_VERSION and redeploy, or create a versioned client here.
        byte[] aesKey = cryptographyClient
                .decrypt(DecryptParameters.createRsaOaep256Parameters(blob.wrappedKey()))
                .getPlainText();

        try {
            return aesGcmDecrypt(blob.ciphertext(), aesKey, blob.iv(),
                                 buildAad(classification, tenantId));
        } finally {
            Arrays.fill(aesKey, (byte) 0);
        }
    }

    // ── Key metadata ──────────────────────────────────────────────────────────

    @Override
    public String currentKeyVersion(DataClassification classification) {
        KeyVaultKey key = keyClient.getKey(azureProps.keyvault().keyName());
        return key.getId();   // full Key Vault URI: https://vault.../keys/<name>/<version>
    }

    // ── RotatableKeyAdapter ───────────────────────────────────────────────────

    @Override
    public RotationResult triggerRotation(DataClassification classification) {
        String previousVersion = currentKeyVersion(classification);

        // Creates a new key version immediately per the Key Vault rotation policy.
        KeyVaultKey rotated = keyClient.rotateKey(azureProps.keyvault().keyName());

        // Evict all cached DEKs: next encrypt call generates a new DEK wrapped with the
        // new RSA key version. Old ciphertexts can still be decrypted via the old version
        // (Key Vault retains all previous key versions).
        dataKeyCache.evictAll();

        log.info("Azure Key Vault rotation completed: key={} newVersion={}",
                azureProps.keyvault().keyName(), rotated.getId());

        return new RotationResult(previousVersion, rotated.getId(), Instant.now(), "AZURE");
    }

    @Override
    public boolean isRotationEnabled(DataClassification classification) {
        KeyVaultKey key = keyClient.getKey(azureProps.keyvault().keyName());
        // Key Vault rotation policy is managed via Terraform; treat key existence as enabled
        return key != null && key.getKey() != null;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private byte[] buildAad(DataClassification classification, String tenantId) {
        return (tenantId + ":" + classification.name()).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] generateAesKey() {
        byte[] key = new byte[AES_KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
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
     * Binary pack format (big-endian):
     *   [4B wrappedKeyLen | wrappedKey | 12B IV | GCM ciphertext + 16B auth tag]
     *
     * Identical structure to AwsKmsAdapter — simplifies cross-cloud migration tooling.
     * Azure RSA-OAEP-256 wrapped keys for 4096-bit RSA are 512 bytes (fits in 4B length).
     */
    private byte[] pack(byte[] wrappedKey, byte[] iv, byte[] ciphertext) {
        byte[] out = new byte[4 + wrappedKey.length + IV_BYTES + ciphertext.length];
        out[0] = (byte) (wrappedKey.length >>> 24);
        out[1] = (byte) (wrappedKey.length >>> 16);
        out[2] = (byte) (wrappedKey.length >>> 8);
        out[3] = (byte)  wrappedKey.length;
        System.arraycopy(wrappedKey, 0, out, 4,                              wrappedKey.length);
        System.arraycopy(iv,         0, out, 4 + wrappedKey.length,          IV_BYTES);
        System.arraycopy(ciphertext, 0, out, 4 + wrappedKey.length + IV_BYTES, ciphertext.length);
        return out;
    }

    private PackedBlob unpack(byte[] packed) {
        int wkLen     = ((packed[0] & 0xFF) << 24)
                      | ((packed[1] & 0xFF) << 16)
                      | ((packed[2] & 0xFF) << 8)
                      |  (packed[3] & 0xFF);
        int ivOffset  = 4 + wkLen;
        int ctOffset  = ivOffset + IV_BYTES;
        return new PackedBlob(
                Arrays.copyOfRange(packed, 4,        ivOffset),
                Arrays.copyOfRange(packed, ivOffset, ctOffset),
                Arrays.copyOfRange(packed, ctOffset, packed.length));
    }

    private record PackedBlob(byte[] wrappedKey, byte[] iv, byte[] ciphertext) {}
}
