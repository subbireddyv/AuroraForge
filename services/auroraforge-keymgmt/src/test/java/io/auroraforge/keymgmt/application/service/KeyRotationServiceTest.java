package io.auroraforge.keymgmt.application.service;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.keymgmt.application.port.RotatableKeyAdapter;
import io.auroraforge.keymgmt.infrastructure.cache.DataKeyCache;
import io.auroraforge.keymgmt.infrastructure.config.KeyRotationProperties;
import io.auroraforge.keymgmt.infrastructure.persistence.KeyVersionEntity;
import io.auroraforge.keymgmt.infrastructure.persistence.KeyVersionRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KeyRotationService}.
 *
 * Verifies that the service orchestrates rotation, cache eviction, audit persistence,
 * and error handling correctly — without a real DB or cloud provider.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeyRotationService")
class KeyRotationServiceTest {

    @Mock private KeyVersionRepository      keyVersionRepository;
    @Mock private KeyManagementAuditService auditService;
    @Mock private RotatableKeyAdapter       rotatableKeyAdapter;

    private DataKeyCache      dataKeyCache;
    private KeyRotationService service;

    @BeforeEach
    void setUp() {
        KeyRotationProperties props = new KeyRotationProperties(
                null, null, null, null,
                300, 300, 60, 60,
                90);
        dataKeyCache = new DataKeyCache(props);

        service = new KeyRotationService(keyVersionRepository, auditService, dataKeyCache);
        // Inject the optional RotatableKeyAdapter (simulates AWS/Azure adapter being present)
        ReflectionTestUtils.setField(service, "rotatableKeyAdapter", rotatableKeyAdapter);
    }

    // ── Single-tenant rotation ────────────────────────────────────────────────

    @Nested
    @DisplayName("rotateTenantKey()")
    class SingleTenantRotationTests {

        @Test
        @DisplayName("returns RotationResult and delegates to RotatableKeyAdapter")
        void rotateTenantKeyDelegatesToAdapter() {
            RotatableKeyAdapter.RotationResult expected = new RotatableKeyAdapter.RotationResult(
                    "v1", "v2", Instant.now(), "AWS");
            when(rotatableKeyAdapter.triggerRotation(DataClassification.RESTRICTED))
                    .thenReturn(expected);

            KeyVersionEntity savedEntity = KeyVersionEntity.builder()
                    .tenantId("tenant-A")
                    .classification(DataClassification.RESTRICTED)
                    .keyVersion("v2")
                    .cloudProvider("AWS")
                    .active(true)
                    .build();
            when(auditService.recordNewKeyVersion(anyString(), any(), anyString(), anyString()))
                    .thenReturn(savedEntity);

            RotatableKeyAdapter.RotationResult result =
                    service.rotateTenantKey("tenant-A", DataClassification.RESTRICTED);

            assertThat(result).isNotNull();
            assertThat(result.newVersion()).isEqualTo("v2");
            assertThat(result.previousVersion()).isEqualTo("v1");

            verify(rotatableKeyAdapter).triggerRotation(DataClassification.RESTRICTED);
            verify(auditService).recordNewKeyVersion("tenant-A", DataClassification.RESTRICTED, "v2", "AWS");
            verify(auditService).auditRotationComplete(eq("tenant-A"), eq(DataClassification.RESTRICTED),
                    eq("v1"), eq("v2"), eq("AWS"));
        }

        @Test
        @DisplayName("evicts tenant DEK cache entry after successful rotation")
        void evictsDekCacheOnRotation() {
            // Pre-populate the DEK cache for this tenant
            dataKeyCache.put("tenant-B", DataClassification.CONFIDENTIAL,
                    new byte[32], new byte[185], "v1");
            assertThat(dataKeyCache.size()).isEqualTo(1);

            RotatableKeyAdapter.RotationResult result = new RotatableKeyAdapter.RotationResult(
                    "v1", "v2", Instant.now(), "AZURE");
            when(rotatableKeyAdapter.triggerRotation(DataClassification.CONFIDENTIAL))
                    .thenReturn(result);
            when(auditService.recordNewKeyVersion(anyString(), any(), anyString(), anyString()))
                    .thenReturn(mock(KeyVersionEntity.class));

            service.rotateTenantKey("tenant-B", DataClassification.CONFIDENTIAL);

            assertThat(dataKeyCache.size()).isZero();
        }

        @Test
        @DisplayName("returns null without rotating when no RotatableKeyAdapter (LOCAL mode)")
        void returnsNullInLocalMode() {
            // Simulate LOCAL mode: null adapter
            ReflectionTestUtils.setField(service, "rotatableKeyAdapter", null);

            RotatableKeyAdapter.RotationResult result =
                    service.rotateTenantKey("tenant-X", DataClassification.PUBLIC);

            assertThat(result).isNull();
            verifyNoInteractions(rotatableKeyAdapter, auditService);
        }

        @Test
        @DisplayName("propagates exception from adapter and logs audit failure")
        void propagatesAdapterException() {
            when(rotatableKeyAdapter.triggerRotation(DataClassification.RESTRICTED))
                    .thenThrow(new RuntimeException("KMS throttled"));

            assertThatThrownBy(() ->
                    service.rotateTenantKey("tenant-C", DataClassification.RESTRICTED))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("KMS throttled");

            verify(auditService).auditRotationFailure(
                    eq("tenant-C"), eq(DataClassification.RESTRICTED), contains("KMS throttled"));
        }
    }

    // ── Bulk rotation ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("rotateAllTenantsForClassification()")
    class BulkRotationTests {

        @Test
        @DisplayName("rotates each tracked tenant independently")
        void rotatesEachTenant() {
            List<KeyVersionEntity> activeVersions = List.of(
                    buildEntity("tenant-1", DataClassification.CONFIDENTIAL),
                    buildEntity("tenant-2", DataClassification.CONFIDENTIAL));
            when(keyVersionRepository.findByClassificationAndActiveTrueOrderByCreatedAtAsc(
                    DataClassification.CONFIDENTIAL)).thenReturn(activeVersions);

            RotatableKeyAdapter.RotationResult fakeResult = new RotatableKeyAdapter.RotationResult(
                    "old", "new", Instant.now(), "AWS");
            when(rotatableKeyAdapter.triggerRotation(DataClassification.CONFIDENTIAL))
                    .thenReturn(fakeResult);
            when(auditService.recordNewKeyVersion(anyString(), any(), anyString(), anyString()))
                    .thenReturn(mock(KeyVersionEntity.class));

            service.rotateAllTenantsForClassification(DataClassification.CONFIDENTIAL);

            verify(rotatableKeyAdapter, times(2)).triggerRotation(DataClassification.CONFIDENTIAL);
        }

        @Test
        @DisplayName("continues rotating other tenants when one fails")
        void continuesOnPartialFailure() {
            List<KeyVersionEntity> activeVersions = List.of(
                    buildEntity("tenant-ok",   DataClassification.RESTRICTED),
                    buildEntity("tenant-fail", DataClassification.RESTRICTED));
            when(keyVersionRepository.findByClassificationAndActiveTrueOrderByCreatedAtAsc(
                    DataClassification.RESTRICTED)).thenReturn(activeVersions);

            RotatableKeyAdapter.RotationResult okResult = new RotatableKeyAdapter.RotationResult(
                    "v1", "v2", Instant.now(), "AWS");

            when(rotatableKeyAdapter.triggerRotation(DataClassification.RESTRICTED))
                    .thenReturn(okResult)               // tenant-ok succeeds
                    .thenThrow(new RuntimeException()); // tenant-fail throws

            when(auditService.recordNewKeyVersion(anyString(), any(), anyString(), anyString()))
                    .thenReturn(mock(KeyVersionEntity.class));

            // Should NOT throw — partial failure is swallowed
            assertThatCode(() ->
                    service.rotateAllTenantsForClassification(DataClassification.RESTRICTED))
                    .doesNotThrowAnyException();

            verify(rotatableKeyAdapter, times(2)).triggerRotation(DataClassification.RESTRICTED);
        }

        @Test
        @DisplayName("triggers CMK-level rotation when no tracked tenants exist")
        void triggersCmkLevelRotationWhenNoTenantsTracked() {
            when(keyVersionRepository.findByClassificationAndActiveTrueOrderByCreatedAtAsc(
                    DataClassification.PUBLIC)).thenReturn(List.of());
            RotatableKeyAdapter.RotationResult fakeResult = new RotatableKeyAdapter.RotationResult(
                    "v1", "v1", Instant.now(), "AWS");
            when(rotatableKeyAdapter.triggerRotation(DataClassification.PUBLIC))
                    .thenReturn(fakeResult);

            service.rotateAllTenantsForClassification(DataClassification.PUBLIC);

            verify(rotatableKeyAdapter, times(1)).triggerRotation(DataClassification.PUBLIC);
        }

        @Test
        @DisplayName("no-op when no RotatableKeyAdapter present (LOCAL mode)")
        void noOpInLocalMode() {
            ReflectionTestUtils.setField(service, "rotatableKeyAdapter", null);

            service.rotateAllTenantsForClassification(DataClassification.CONFIDENTIAL);

            verifyNoInteractions(keyVersionRepository);
        }
    }

    // ── Status queries ────────────────────────────────────────────────────────

    @Test
    @DisplayName("hasRotatableAdapter() returns true when adapter is present")
    void hasRotatableAdapterTrue() {
        assertThat(service.hasRotatableAdapter()).isTrue();
    }

    @Test
    @DisplayName("hasRotatableAdapter() returns false in LOCAL mode")
    void hasRotatableAdapterFalse() {
        ReflectionTestUtils.setField(service, "rotatableKeyAdapter", null);
        assertThat(service.hasRotatableAdapter()).isFalse();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private KeyVersionEntity buildEntity(String tenantId, DataClassification classification) {
        return KeyVersionEntity.builder()
                .tenantId(tenantId)
                .classification(classification)
                .keyVersion("v1")
                .cloudProvider("AWS")
                .active(true)
                .build();
    }
}
