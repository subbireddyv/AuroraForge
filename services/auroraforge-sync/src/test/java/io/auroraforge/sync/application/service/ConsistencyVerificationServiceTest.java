package io.auroraforge.sync.application.service;

import io.auroraforge.sync.config.DisasterRecoveryProperties;
import io.auroraforge.sync.domain.model.ConsistencyReport;
import io.auroraforge.sync.domain.model.SyncRecord;
import io.auroraforge.sync.domain.model.SyncStatus;
import io.auroraforge.sync.domain.model.VectorClock;
import io.auroraforge.sync.infrastructure.aws.AwsDataSyncAdapter;
import io.auroraforge.sync.infrastructure.azure.AzureDataSyncAdapter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ConsistencyVerificationService}.
 * Verifies that payload comparison, gap detection, and sampling logic are correct.
 */
@DisplayName("ConsistencyVerificationService")
class ConsistencyVerificationServiceTest {

    private AwsDataSyncAdapter        awsAdapter;
    private AzureDataSyncAdapter      azureAdapter;
    private DisasterRecoveryProperties drProps;
    private ConsistencyVerificationService service;

    @BeforeEach
    void setUp() {
        awsAdapter   = mock(AwsDataSyncAdapter.class);
        azureAdapter = mock(AzureDataSyncAdapter.class);
        drProps      = mock(DisasterRecoveryProperties.class);

        when(drProps.consistencyCheckSampleSize()).thenReturn(100);

        service = new ConsistencyVerificationService(
                awsAdapter, azureAdapter, drProps, new SimpleMeterRegistry());
    }

    private SyncRecord recordWithPayload(byte[] payload) {
        return SyncRecord.builder()
                .tenantId("t1")
                .aggregateId("agg-1")
                .payload(payload)
                .vectorClock(VectorClock.empty())
                .syncStatus(SyncStatus.SYNCED)
                .wallClockTs(Instant.now().toEpochMilli())
                .schemaVersion(1)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    @DisplayName("fully consistent when both clouds return identical payloads")
    void fullyConsistentWhenPayloadsMatch() {
        byte[] payload = new byte[]{1, 2, 3, 4};
        when(awsAdapter.findExisting("t1", "agg-1"))
                .thenReturn(Optional.of(recordWithPayload(payload)));
        when(azureAdapter.findExisting("t1", "agg-1"))
                .thenReturn(Optional.of(recordWithPayload(payload)));

        ConsistencyReport report = service.verify("t1", List.of("agg-1"));

        assertThat(report.isFullyConsistent()).isTrue();
        assertThat(report.consistencyPercent()).isEqualTo(100.0);
        assertThat(report.divergedAggregateIds()).isEmpty();
    }

    @Test
    @DisplayName("reports divergence when payloads differ between clouds")
    void reportsDivergenceOnPayloadMismatch() {
        when(awsAdapter.findExisting("t1", "agg-1"))
                .thenReturn(Optional.of(recordWithPayload(new byte[]{1, 2})));
        when(azureAdapter.findExisting("t1", "agg-1"))
                .thenReturn(Optional.of(recordWithPayload(new byte[]{3, 4})));

        ConsistencyReport report = service.verify("t1", List.of("agg-1"));

        assertThat(report.isFullyConsistent()).isFalse();
        assertThat(report.divergedRecords()).isEqualTo(1);
        assertThat(report.divergedAggregateIds()).containsExactly("agg-1");
    }

    @Test
    @DisplayName("reports divergence when record is present in only one cloud")
    void reportsDivergenceWhenRecordMissingFromOneCloud() {
        when(awsAdapter.findExisting("t1", "agg-1"))
                .thenReturn(Optional.of(recordWithPayload(new byte[]{1})));
        when(azureAdapter.findExisting("t1", "agg-1"))
                .thenReturn(Optional.empty());

        ConsistencyReport report = service.verify("t1", List.of("agg-1"));

        assertThat(report.isFullyConsistent()).isFalse();
        assertThat(report.divergedAggregateIds()).contains("agg-1");
    }

    @Test
    @DisplayName("absent record in both clouds is treated as consistent")
    void absentInBothCloudsIsConsistent() {
        when(awsAdapter.findExisting("t1", "agg-1")).thenReturn(Optional.empty());
        when(azureAdapter.findExisting("t1", "agg-1")).thenReturn(Optional.empty());

        ConsistencyReport report = service.verify("t1", List.of("agg-1"));

        assertThat(report.isFullyConsistent()).isTrue();
        assertThat(report.sampledRecords()).isEqualTo(1);
    }

    @Test
    @DisplayName("samples only up to consistencyCheckSampleSize")
    void samplesAtMostSampleSize() {
        when(drProps.consistencyCheckSampleSize()).thenReturn(2);
        service = new ConsistencyVerificationService(
                awsAdapter, azureAdapter, drProps, new SimpleMeterRegistry());

        when(awsAdapter.findExisting(any(), any())).thenReturn(Optional.empty());
        when(azureAdapter.findExisting(any(), any())).thenReturn(Optional.empty());

        // Provide 5 IDs; only 2 should be sampled
        ConsistencyReport report = service.verify("t1",
                List.of("a1", "a2", "a3", "a4", "a5"));

        assertThat(report.sampledRecords()).isEqualTo(2);
        verify(awsAdapter, times(2)).findExisting(any(), any());
    }

    @Test
    @DisplayName("returns perfect report when aggregate ID list is empty")
    void emptyListReturnsPerfectReport() {
        ConsistencyReport report = service.verify("t1", List.of());

        assertThat(report.sampledRecords()).isEqualTo(0);
        assertThat(report.consistencyPercent()).isEqualTo(100.0);
        assertThat(report.isFullyConsistent()).isTrue();
        verifyNoInteractions(awsAdapter, azureAdapter);
    }

    @Test
    @DisplayName("probe exception is treated as divergence, not thrown")
    void probeExceptionCountsAsDivergence() {
        when(awsAdapter.findExisting("t1", "agg-err"))
                .thenThrow(new RuntimeException("S3 timeout"));
        when(azureAdapter.findExisting("t1", "agg-err"))
                .thenReturn(Optional.empty());

        ConsistencyReport report = service.verify("t1", List.of("agg-err"));

        assertThat(report.divergedRecords()).isEqualTo(1);
    }
}
