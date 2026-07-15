package io.auroraforge.sync.infrastructure.resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.auroraforge.sync.config.DisasterRecoveryProperties;
import io.auroraforge.sync.domain.model.ConflictStrategy;
import io.auroraforge.sync.domain.model.SyncRecord;
import io.auroraforge.sync.domain.model.SyncStatus;
import io.auroraforge.sync.domain.model.VectorClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MultiStrategyConflictResolver}.
 * Uses a real {@link SimpleMeterRegistry} and mocks {@link DisasterRecoveryProperties}.
 */
@DisplayName("MultiStrategyConflictResolver")
class MultiStrategyConflictResolverTest {

    private DisasterRecoveryProperties drProps;
    private MultiStrategyConflictResolver resolver;

    @BeforeEach
    void setUp() {
        drProps  = mock(DisasterRecoveryProperties.class);
        when(drProps.primaryCloud()).thenReturn("aws");
        resolver = new MultiStrategyConflictResolver(drProps, new ObjectMapper(), new SimpleMeterRegistry());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private SyncRecord record(String cloud, long wallClock, VectorClock vc, byte[] payload) {
        return SyncRecord.builder()
                .tenantId("tenant-1")
                .aggregateType("ORDER")
                .aggregateId("agg-1")
                .sourceCloud(cloud)
                .vectorClock(vc)
                .wallClockTs(wallClock)
                .syncStatus(SyncStatus.SYNCED)
                .payload(payload)
                .createdAt(Instant.now())
                .schemaVersion(1)
                .build();
    }

    private SyncRecord localAt(long ts)  { return record("aws",   ts, VectorClock.empty(), new byte[]{1}); }
    private SyncRecord remoteAt(long ts) { return record("azure", ts, VectorClock.empty(), new byte[]{2}); }

    // ── LAST_WRITE_WINS ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("LAST_WRITE_WINS strategy")
    class LwwTests {

        @BeforeEach
        void useStrategy() {
            when(drProps.strategyFor(any())).thenReturn(ConflictStrategy.LAST_WRITE_WINS);
        }

        @Test
        @DisplayName("local wins when its wall-clock is higher")
        void localWinsOnHigherTs() {
            SyncRecord result = resolver.resolve(localAt(200L), remoteAt(100L));
            assertThat(result.getSourceCloud()).isEqualTo("aws");
            assertThat(result.getSyncStatus()).isEqualTo(SyncStatus.CONFLICT_RESOLVED);
        }

        @Test
        @DisplayName("remote wins when its wall-clock is higher")
        void remoteWinsOnHigherTs() {
            SyncRecord result = resolver.resolve(localAt(100L), remoteAt(200L));
            assertThat(result.getSourceCloud()).isEqualTo("azure");
            assertThat(result.getSyncStatus()).isEqualTo(SyncStatus.CONFLICT_RESOLVED);
        }

        @Test
        @DisplayName("cloud-ID tiebreaker used when wall-clocks are equal")
        void cloudIdTiebreakerWhenEqual() {
            // "aws" > "azure" lexicographically → aws wins
            SyncRecord result = resolver.resolve(localAt(100L), remoteAt(100L));
            assertThat(result.getSourceCloud()).isEqualTo("aws");
            assertThat(result.getSyncStatus()).isEqualTo(SyncStatus.CONFLICT_RESOLVED);
        }

        @Test
        @DisplayName("merged vector clock is propagated to the winner")
        void mergesVectorClock() {
            SyncRecord local  = record("aws",   200L, VectorClock.empty().increment("aws"),   new byte[]{1});
            SyncRecord remote = record("azure", 100L, VectorClock.empty().increment("azure"), new byte[]{2});
            SyncRecord result = resolver.resolve(local, remote);
            assertThat(result.getVectorClock().clocks()).containsKeys("aws", "azure");
        }
    }

    // ── HIGHEST_VECTOR_CLOCK ──────────────────────────────────────────────────

    @Nested
    @DisplayName("HIGHEST_VECTOR_CLOCK strategy")
    class VectorClockTests {

        @BeforeEach
        void useStrategy() {
            when(drProps.strategyFor(any())).thenReturn(ConflictStrategy.HIGHEST_VECTOR_CLOCK);
        }

        @Test
        @DisplayName("remote wins when it causally supersedes local")
        void remoteWinsWhenCausallyNewer() {
            VectorClock base    = VectorClock.empty().increment("node-a");
            VectorClock evolved = base.increment("node-a");  // remote is a step ahead
            SyncRecord local  = record("aws",   100L, base,    new byte[]{1});
            SyncRecord remote = record("azure", 200L, evolved, new byte[]{2});
            SyncRecord result = resolver.resolve(local, remote);
            assertThat(result.getSourceCloud()).isEqualTo("azure");
        }

        @Test
        @DisplayName("local wins when it causally supersedes remote")
        void localWinsWhenCausallyNewer() {
            VectorClock base    = VectorClock.empty().increment("node-a");
            VectorClock evolved = base.increment("node-a");
            SyncRecord local  = record("aws",   200L, evolved, new byte[]{1});
            SyncRecord remote = record("azure", 100L, base,    new byte[]{2});
            SyncRecord result = resolver.resolve(local, remote);
            assertThat(result.getSourceCloud()).isEqualTo("aws");
        }

        @Test
        @DisplayName("falls back to LWW for concurrent (incomparable) clocks")
        void fallsBackToLwwForConcurrentClocks() {
            VectorClock vc1 = VectorClock.empty().increment("aws");
            VectorClock vc2 = VectorClock.empty().increment("azure");
            SyncRecord local  = record("aws",   200L, vc1, new byte[]{1});
            SyncRecord remote = record("azure", 100L, vc2, new byte[]{2});
            // With concurrent clocks, LWW applies → aws wins (higher wall-clock)
            SyncRecord result = resolver.resolve(local, remote);
            assertThat(result.getSourceCloud()).isEqualTo("aws");
            assertThat(result.getSyncStatus()).isEqualTo(SyncStatus.CONFLICT_RESOLVED);
        }
    }

    // ── CLOUD_PRIORITY ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CLOUD_PRIORITY strategy")
    class CloudPriorityTests {

        @BeforeEach
        void useStrategy() {
            when(drProps.strategyFor(any())).thenReturn(ConflictStrategy.CLOUD_PRIORITY);
        }

        @Test
        @DisplayName("primary cloud wins regardless of wall-clock")
        void primaryCloudWins() {
            // drProps.primaryCloud() returns "aws"; remote (azure) has a higher wall-clock
            SyncRecord result = resolver.resolve(localAt(100L), remoteAt(999L));
            assertThat(result.getSourceCloud()).isEqualTo("aws");
            assertThat(result.getSyncStatus()).isEqualTo(SyncStatus.CONFLICT_RESOLVED);
        }
    }

    // ── FIELD_MERGE ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("FIELD_MERGE strategy")
    class FieldMergeTests {

        @BeforeEach
        void useStrategy() {
            when(drProps.strategyFor(any())).thenReturn(ConflictStrategy.FIELD_MERGE);
        }

        @Test
        @DisplayName("merges non-conflicting fields from both records")
        void mergesNonConflictingFields() throws Exception {
            ObjectMapper om  = new ObjectMapper();
            byte[] localPayload  = om.writeValueAsBytes(Map.of("a", 1, "b", 2));
            byte[] remotePayload = om.writeValueAsBytes(Map.of("c", 3));

            SyncRecord local  = record("aws",   200L, VectorClock.empty(), localPayload);
            SyncRecord remote = record("azure", 100L, VectorClock.empty(), remotePayload);

            SyncRecord result = resolver.resolve(local, remote);

            @SuppressWarnings("unchecked")
            Map<String, Object> merged = om.readValue(result.getPayload(), Map.class);
            assertThat(merged).containsKeys("a", "b", "c");
            assertThat(result.getSyncStatus()).isEqualTo(SyncStatus.CONFLICT_RESOLVED);
        }

        @Test
        @DisplayName("falls back to LWW when payload is not a JSON object")
        void fallsBackToLwwForNonJsonPayload() {
            SyncRecord local  = record("aws",   200L, VectorClock.empty(), new byte[]{0x01, 0x02});
            SyncRecord remote = record("azure", 100L, VectorClock.empty(), new byte[]{0x03, 0x04});
            SyncRecord result = resolver.resolve(local, remote);
            // LWW: local (aws, ts=200) wins
            assertThat(result.getSourceCloud()).isEqualTo("aws");
            assertThat(result.getSyncStatus()).isEqualTo(SyncStatus.CONFLICT_RESOLVED);
        }
    }

    // ── MANUAL_REVIEW ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MANUAL_REVIEW strategy")
    class ManualReviewTests {

        @BeforeEach
        void useStrategy() {
            when(drProps.strategyFor(any())).thenReturn(ConflictStrategy.MANUAL_REVIEW);
        }

        @Test
        @DisplayName("flags local record as CONFLICT_DETECTED without choosing a winner")
        void flagsForManualReview() {
            SyncRecord result = resolver.resolve(localAt(100L), remoteAt(200L));
            // Local record is returned (unchanged except status) — no automatic winner
            assertThat(result.getSyncStatus()).isEqualTo(SyncStatus.CONFLICT_DETECTED);
            assertThat(result.getSourceCloud()).isEqualTo("aws");
        }
    }
}
