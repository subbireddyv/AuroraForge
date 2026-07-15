package io.auroraforge.keymgmt.infrastructure.aws;

import io.auroraforge.core.config.cloud.AwsProperties;
import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.keymgmt.application.exception.KeyManagementException;
import io.auroraforge.keymgmt.application.port.RotatableKeyAdapter;
import io.auroraforge.keymgmt.infrastructure.cache.DataKeyCache;
import io.auroraforge.keymgmt.infrastructure.config.KeyRotationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.*;

import java.security.SecureRandom;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AwsKmsAdapter}.
 *
 * KmsClient is mocked: no real AWS credentials or network calls.
 * Tests verify the envelope encryption/decryption round-trip, DEK cache behaviour,
 * and rotation delegation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AwsKmsAdapter")
class AwsKmsAdapterTest {

    @Mock private KmsClient   kmsClient;
    @Mock private AwsProperties awsProps;
    @Mock private AwsProperties.KmsConfig kmsConfig;

    private DataKeyCache     dataKeyCache;
    private AwsKmsAdapter    adapter;

    // A realistic 32-byte plaintext DEK and its mock KMS-encrypted form
    private static final byte[] PLAINTEXT_DEK  = randomBytes(32);
    private static final byte[] ENCRYPTED_DEK  = randomBytes(185);  // typical KMS ciphertext size
    private static final String CMK_ALIAS      = "alias/auroraforge-app-data";
    private static final String CMK_ID         = "arn:aws:kms:us-east-1:123456789:key/test-key-id";

    @BeforeEach
    void setUp() {
        when(awsProps.kms()).thenReturn(kmsConfig);
        when(kmsConfig.appDataKeyAlias()).thenReturn(CMK_ALIAS);

        // Real DataKeyCache with short TTLs (doesn't need Spring context)
        KeyRotationProperties props = new KeyRotationProperties(
                "0 0 2 ? * SUN", "0 0 2 ? * SUN", "0 0 3 1 * ?", "0 0 4 1 * ?",
                300, 300, 10, 10,   // 10-second TTL for CONFIDENTIAL/RESTRICTED in tests
                90);
        dataKeyCache = new DataKeyCache(props);
        adapter = new AwsKmsAdapter(kmsClient, awsProps, dataKeyCache);
    }

    // ── Encrypt / Decrypt round-trip ─────────────────────────────────────────

    @Nested
    @DisplayName("Envelope encryption")
    class EnvelopeEncryptionTests {

        @Test
        @DisplayName("encrypt() calls GenerateDataKey and AES-GCM encrypts the payload")
        void encryptCallsGenerateDataKey() {
            byte[] plaintext = "Hello, AuroraForge!".getBytes();
            stubGenerateDataKey();

            var result = adapter.encrypt(plaintext, DataClassification.CONFIDENTIAL, "tenant-1");

            assertThat(result.ciphertext()).isNotNull().isNotEmpty();
            assertThat(result.keyVersion()).isEqualTo(CMK_ID);
            verify(kmsClient, times(1)).generateDataKey(any(GenerateDataKeyRequest.class));
        }

        @Test
        @DisplayName("decrypt(encrypt(plaintext)) recovers the original plaintext")
        void encryptDecryptRoundTrip() {
            byte[] plaintext = "Sensitive PII data".getBytes();
            stubGenerateDataKey();

            var encrypted = adapter.encrypt(plaintext, DataClassification.RESTRICTED, "tenant-2");

            // Stub KMS decrypt to return the same PLAINTEXT_DEK we generated
            stubDecrypt();

            byte[] recovered = adapter.decrypt(
                    encrypted.ciphertext(), encrypted.keyVersion(),
                    DataClassification.RESTRICTED, "tenant-2");

            assertThat(recovered).isEqualTo(plaintext);
        }

        @Test
        @DisplayName("decrypt() fails with tampered ciphertext (GCM auth tag mismatch)")
        void decryptRejectedTamperedCiphertext() {
            byte[] plaintext = "Original data".getBytes();
            stubGenerateDataKey();

            var encrypted = adapter.encrypt(plaintext, DataClassification.CONFIDENTIAL, "tenant-1");

            // Flip a byte in the ciphertext portion (after the DEK and IV)
            byte[] tampered = encrypted.ciphertext().clone();
            tampered[tampered.length - 1] ^= 0xFF;

            stubDecrypt();

            assertThatThrownBy(() ->
                    adapter.decrypt(tampered, encrypted.keyVersion(),
                            DataClassification.CONFIDENTIAL, "tenant-1"))
                    .isInstanceOf(KeyManagementException.class)
                    .hasMessageContaining("AES-GCM decryption failed");
        }

        @Test
        @DisplayName("decrypt() fails when AAD (tenantId) differs from encrypt time")
        void decryptRejectedWrongTenant() {
            byte[] plaintext = "Secret".getBytes();
            stubGenerateDataKey();

            // Encrypted for tenant-A
            var encrypted = adapter.encrypt(plaintext, DataClassification.RESTRICTED, "tenant-A");
            stubDecrypt();

            // Attempt to decrypt as tenant-B — GCM tag will fail
            assertThatThrownBy(() ->
                    adapter.decrypt(encrypted.ciphertext(), encrypted.keyVersion(),
                            DataClassification.RESTRICTED, "tenant-B"))
                    .isInstanceOf(KeyManagementException.class);
        }

        @Test
        @DisplayName("ciphertext is different each call (fresh IV per encryption)")
        void freshIvPerEncryption() {
            byte[] plaintext = "Same plaintext".getBytes();
            stubGenerateDataKey();

            var result1 = adapter.encrypt(plaintext, DataClassification.CONFIDENTIAL, "tenant-x");
            dataKeyCache.evictAll(); // force new DEK
            stubGenerateDataKey();
            var result2 = adapter.encrypt(plaintext, DataClassification.CONFIDENTIAL, "tenant-x");

            assertThat(result1.ciphertext()).isNotEqualTo(result2.ciphertext());
        }
    }

    // ── DEK cache behaviour ──────────────────────────────────────────────────

    @Nested
    @DisplayName("DEK cache")
    class DekCacheTests {

        @Test
        @DisplayName("second encrypt() call for same tenant reuses cached DEK (no KMS call)")
        void secondEncryptUsesCachedDek() {
            byte[] plaintext = "payload".getBytes();
            stubGenerateDataKey();

            // First call — generates DEK
            var r1 = adapter.encrypt(plaintext, DataClassification.INTERNAL, "tenant-cache");
            // Second call — should hit cache
            var r2 = adapter.encrypt(plaintext, DataClassification.INTERNAL, "tenant-cache");

            // GenerateDataKey called only once
            verify(kmsClient, times(1)).generateDataKey(any(GenerateDataKeyRequest.class));
            assertThat(r1.keyVersion()).isEqualTo(r2.keyVersion());
        }

        @Test
        @DisplayName("evictAll() forces a fresh GenerateDataKey on next encrypt()")
        void evictAllForcesNewDek() {
            byte[] plaintext = "payload".getBytes();
            stubGenerateDataKey();

            adapter.encrypt(plaintext, DataClassification.CONFIDENTIAL, "tenant-evict");
            dataKeyCache.evictAll();

            // Need another stub for the second call
            stubGenerateDataKey();
            adapter.encrypt(plaintext, DataClassification.CONFIDENTIAL, "tenant-evict");

            verify(kmsClient, times(2)).generateDataKey(any(GenerateDataKeyRequest.class));
        }
    }

    // ── Rotation ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Key rotation")
    class RotationTests {

        @Test
        @DisplayName("triggerRotation() enables KMS rotation and returns result")
        void triggerRotationEnablesKmsRotation() {
            when(kmsClient.describeKey(any(DescribeKeyRequest.class)))
                    .thenReturn(DescribeKeyResponse.builder()
                            .keyMetadata(m -> m.keyId(CMK_ID))
                            .build());
            when(kmsClient.enableKeyRotation(any(EnableKeyRotationRequest.class)))
                    .thenReturn(EnableKeyRotationResponse.builder().build());

            RotatableKeyAdapter.RotationResult result =
                    adapter.triggerRotation(DataClassification.RESTRICTED);

            assertThat(result).isNotNull();
            assertThat(result.cloudProvider()).isEqualTo("AWS");
            assertThat(result.previousVersion()).isEqualTo(CMK_ID);
            assertThat(result.rotatedAt()).isNotNull();
            verify(kmsClient).enableKeyRotation(any(EnableKeyRotationRequest.class));
        }

        @Test
        @DisplayName("triggerRotation() evicts the DEK cache")
        void triggerRotationEvictsDekCache() {
            stubGenerateDataKey();
            adapter.encrypt("data".getBytes(), DataClassification.CONFIDENTIAL, "tenant-rot");
            assertThat(dataKeyCache.size()).isGreaterThan(0);

            when(kmsClient.describeKey(any(DescribeKeyRequest.class)))
                    .thenReturn(DescribeKeyResponse.builder()
                            .keyMetadata(m -> m.keyId(CMK_ID)).build());
            when(kmsClient.enableKeyRotation(any(EnableKeyRotationRequest.class)))
                    .thenReturn(EnableKeyRotationResponse.builder().build());

            adapter.triggerRotation(DataClassification.CONFIDENTIAL);

            assertThat(dataKeyCache.size()).isZero();
        }

        @Test
        @DisplayName("isRotationEnabled() returns KMS rotation status")
        void isRotationEnabledReadsKms() {
            when(kmsClient.getKeyRotationStatus(any(GetKeyRotationStatusRequest.class)))
                    .thenReturn(GetKeyRotationStatusResponse.builder()
                            .keyRotationEnabled(true).build());

            assertThat(adapter.isRotationEnabled(DataClassification.CONFIDENTIAL)).isTrue();
        }
    }

    // ── currentKeyVersion ────────────────────────────────────────────────────

    @Test
    @DisplayName("currentKeyVersion() returns CMK key ID from DescribeKey")
    void currentKeyVersionDelegatesToKms() {
        when(kmsClient.describeKey(any(DescribeKeyRequest.class)))
                .thenReturn(DescribeKeyResponse.builder()
                        .keyMetadata(m -> m.keyId(CMK_ID)).build());

        assertThat(adapter.currentKeyVersion(DataClassification.PUBLIC)).isEqualTo(CMK_ID);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubGenerateDataKey() {
        when(kmsClient.generateDataKey(any(GenerateDataKeyRequest.class)))
                .thenReturn(GenerateDataKeyResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(PLAINTEXT_DEK))
                        .ciphertextBlob(SdkBytes.fromByteArray(ENCRYPTED_DEK))
                        .keyId(CMK_ID)
                        .build());
    }

    private void stubDecrypt() {
        when(kmsClient.decrypt(any(DecryptRequest.class)))
                .thenReturn(DecryptResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(PLAINTEXT_DEK))
                        .keyId(CMK_ID)
                        .build());
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
