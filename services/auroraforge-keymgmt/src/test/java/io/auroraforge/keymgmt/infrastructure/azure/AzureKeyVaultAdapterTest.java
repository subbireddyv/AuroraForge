package io.auroraforge.keymgmt.infrastructure.azure;

import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.DecryptResult;
import com.azure.security.keyvault.keys.cryptography.models.EncryptResult;
import com.azure.security.keyvault.keys.cryptography.models.EncryptionAlgorithm;
import com.azure.security.keyvault.keys.models.JsonWebKey;
import com.azure.security.keyvault.keys.models.KeyType;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import io.auroraforge.core.config.cloud.AzureProperties;
import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.keymgmt.application.exception.KeyManagementException;
import io.auroraforge.keymgmt.application.port.RotatableKeyAdapter;
import io.auroraforge.keymgmt.infrastructure.cache.DataKeyCache;
import io.auroraforge.keymgmt.infrastructure.config.KeyRotationProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AzureKeyVaultAdapter}.
 *
 * CryptographyClient and KeyClient are mocked. The RSA "wrap" is simulated by
 * returning a fixed byte array so the AES-GCM round-trip can be verified end-to-end.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AzureKeyVaultAdapter")
class AzureKeyVaultAdapterTest {

    @Mock private CryptographyClient cryptographyClient;
    @Mock private KeyClient          keyClient;
    @Mock private AzureProperties    azureProps;
    @Mock private AzureProperties.KeyVaultConfig keyvaultConfig;

    private DataKeyCache         dataKeyCache;
    private AzureKeyVaultAdapter adapter;

    private static final String KEY_NAME    = "auroraforge-app-key";
    private static final String KEY_VERSION = "https://vault.azure.net/keys/auroraforge-app-key/v1";

    @BeforeEach
    void setUp() {
        when(azureProps.keyvault()).thenReturn(keyvaultConfig);
        when(keyvaultConfig.keyName()).thenReturn(KEY_NAME);

        KeyRotationProperties props = new KeyRotationProperties(
                null, null, null, null,
                300, 300, 10, 10,
                90);
        dataKeyCache = new DataKeyCache(props);
        adapter = new AzureKeyVaultAdapter(cryptographyClient, keyClient, azureProps, dataKeyCache);
    }

    // ── Encrypt / Decrypt round-trip ─────────────────────────────────────────

    @Nested
    @DisplayName("Envelope encryption")
    class EnvelopeEncryptionTests {

        @Test
        @DisplayName("encrypt() wraps DEK via CryptographyClient and AES-GCM encrypts payload")
        void encryptWrapsAesKeyAndEncryptsPayload() {
            byte[] plaintext = "Sensitive record".getBytes();
            stubCurrentKeyVersion();
            stubKeyVaultEncrypt();

            var result = adapter.encrypt(plaintext, DataClassification.CONFIDENTIAL, "tenant-1");

            assertThat(result.ciphertext()).isNotNull().isNotEmpty();
            assertThat(result.keyVersion()).isEqualTo(KEY_VERSION);
            verify(cryptographyClient, times(1)).encrypt(any());
        }

        @Test
        @DisplayName("decrypt(encrypt(plaintext)) recovers the original plaintext")
        void encryptDecryptRoundTrip() {
            byte[] plaintext = "PII payload to protect".getBytes();
            stubCurrentKeyVersion();

            // Capture the AES key that is wrapped during encrypt
            ArgumentCaptor<byte[]> aesKeyCaptor = ArgumentCaptor.forClass(byte[].class);

            when(cryptographyClient.encrypt(any()))
                    .thenAnswer(inv -> {
                        // Return a mock EncryptResult wrapping the raw AES key bytes
                        // (simulates Key Vault RSA-OAEP-256 wrapping)
                        byte[] aesKey = inv.getArgument(0,
                                com.azure.security.keyvault.keys.cryptography.models.EncryptParameters.class)
                                .getPlainText();
                        return buildEncryptResult(aesKey);
                    });

            var encrypted = adapter.encrypt(plaintext, DataClassification.RESTRICTED, "tenant-2");

            // For decryption, the mock CryptographyClient.decrypt() must return the same AES key.
            // Since the "wrappedKey" in the packed blob IS the raw AES key (our mock passthrough),
            // we just echo back the ciphertext bytes as the "decrypted" plaintext.
            when(cryptographyClient.decrypt(any()))
                    .thenAnswer(inv -> {
                        byte[] wrappedKey = inv.getArgument(0,
                                com.azure.security.keyvault.keys.cryptography.models.DecryptParameters.class)
                                .getCipherText();
                        return buildDecryptResult(wrappedKey);
                    });

            byte[] recovered = adapter.decrypt(
                    encrypted.ciphertext(), encrypted.keyVersion(),
                    DataClassification.RESTRICTED, "tenant-2");

            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("decrypt() throws on AES-GCM authentication failure (tampered ciphertext)")
        void decryptFailsOnTamperedCiphertext() {
            byte[] plaintext = "data".getBytes();
            stubCurrentKeyVersion();
            stubKeyVaultEncrypt();

            var encrypted = adapter.encrypt(plaintext, DataClassification.CONFIDENTIAL, "t");

            // Return different (wrong) AES key on decrypt — GCM tag check will fail
            when(cryptographyClient.decrypt(any()))
                    .thenReturn(buildDecryptResult(new byte[32])); // all-zero key = wrong

            assertThatThrownBy(() ->
                    adapter.decrypt(encrypted.ciphertext(), encrypted.keyVersion(),
                            DataClassification.CONFIDENTIAL, "t"))
                    .isInstanceOf(KeyManagementException.class);
        }
    }

    // ── DEK cache ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DEK cache")
    class DekCacheTests {

        @Test
        @DisplayName("second encrypt() reuses cached DEK (no second Key Vault wrap call)")
        void secondEncryptUsesCachedDek() {
            byte[] plaintext = "payload".getBytes();
            stubCurrentKeyVersion();
            stubKeyVaultEncrypt();

            adapter.encrypt(plaintext, DataClassification.INTERNAL, "tenant-c");
            adapter.encrypt(plaintext, DataClassification.INTERNAL, "tenant-c");

            // getKey() called once for currentKeyVersion; encrypt() called once for wrapping
            verify(keyClient, times(1)).getKey(KEY_NAME);
            verify(cryptographyClient, times(1)).encrypt(any());
        }
    }

    // ── Rotation ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Key rotation")
    class RotationTests {

        @Test
        @DisplayName("triggerRotation() calls KeyClient.rotateKey and returns RotationResult")
        void triggerRotationCallsRotateKey() {
            stubCurrentKeyVersion();

            String newVersion = "https://vault.azure.net/keys/auroraforge-app-key/v2";
            KeyVaultKey rotatedKey = buildKeyVaultKey(newVersion);
            when(keyClient.rotateKey(KEY_NAME)).thenReturn(rotatedKey);

            RotatableKeyAdapter.RotationResult result =
                    adapter.triggerRotation(DataClassification.RESTRICTED);

            assertThat(result.cloudProvider()).isEqualTo("AZURE");
            assertThat(result.previousVersion()).isEqualTo(KEY_VERSION);
            assertThat(result.newVersion()).isEqualTo(newVersion);
            verify(keyClient).rotateKey(KEY_NAME);
        }

        @Test
        @DisplayName("triggerRotation() evicts the entire DEK cache")
        void triggerRotationEvictsDekCache() {
            stubCurrentKeyVersion();
            stubKeyVaultEncrypt();
            adapter.encrypt("data".getBytes(), DataClassification.CONFIDENTIAL, "t");
            assertThat(dataKeyCache.size()).isGreaterThan(0);

            KeyVaultKey rotatedKey = buildKeyVaultKey(KEY_VERSION + "-v2");
            when(keyClient.rotateKey(KEY_NAME)).thenReturn(rotatedKey);
            adapter.triggerRotation(DataClassification.CONFIDENTIAL);

            assertThat(dataKeyCache.size()).isZero();
        }
    }

    // ── currentKeyVersion ────────────────────────────────────────────────────

    @Test
    @DisplayName("currentKeyVersion() returns full Key Vault key URI")
    void currentKeyVersionReturnsKeyId() {
        stubCurrentKeyVersion();
        assertThat(adapter.currentKeyVersion(DataClassification.PUBLIC)).isEqualTo(KEY_VERSION);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubCurrentKeyVersion() {
        when(keyClient.getKey(KEY_NAME)).thenReturn(buildKeyVaultKey(KEY_VERSION));
    }

    private void stubKeyVaultEncrypt() {
        when(cryptographyClient.encrypt(any())).thenAnswer(inv -> {
            byte[] aesKey = inv.getArgument(0,
                    com.azure.security.keyvault.keys.cryptography.models.EncryptParameters.class)
                    .getPlainText();
            return buildEncryptResult(aesKey);
        });
    }

    private KeyVaultKey buildKeyVaultKey(String id) {
        return new KeyVaultKey(id,
                new JsonWebKey().setId(id).setKeyType(KeyType.RSA_HSM));
    }

    private EncryptResult buildEncryptResult(byte[] cipherText) {
        return new EncryptResult(cipherText, EncryptionAlgorithm.RSA_OAEP_256, KEY_VERSION);
    }

    private DecryptResult buildDecryptResult(byte[] plainText) {
        return new DecryptResult(plainText, EncryptionAlgorithm.RSA_OAEP_256, KEY_VERSION);
    }
}
