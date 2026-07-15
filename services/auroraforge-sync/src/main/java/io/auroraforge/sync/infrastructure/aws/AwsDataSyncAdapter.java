package io.auroraforge.sync.infrastructure.aws;

import io.auroraforge.sync.domain.model.SyncRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

/**
 * Infrastructure adapter: syncs data to AWS S3 (binary payloads)
 * and optionally to RDS (structured metadata).
 *
 * S3 key structure:
 *   {tenant-id}/{aggregate-type}/{aggregate-id}/{schema-version}/{wall-clock-ts}.bin
 *
 * This structure supports:
 *  - Listing all versions of an aggregate (S3 prefix query)
 *  - Efficient lifecycle transitions (prefix-based rules)
 *  - Athena queries over S3 for analytics (schema-version partitioning)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AwsDataSyncAdapter {

    private final S3Client s3Client;

    @org.springframework.beans.factory.annotation.Value("${auroraforge.aws.s3.processed-bucket}")
    private String processedBucket;

    public void upsert(SyncRecord record) {
        String key = buildS3Key(record);

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(processedBucket)
                .key(key)
                .contentType("application/octet-stream")
                .metadata(java.util.Map.of(
                        "tenantId",        record.getTenantId(),
                        "aggregateType",   String.valueOf(record.getAggregateType()),
                        "schemaVersion",   String.valueOf(record.getSchemaVersion()),
                        "vectorClock",     String.valueOf(record.getVectorClock()),
                        "syncStatus",      record.getSyncStatus().name(),
                        "sourceCloud",     String.valueOf(record.getSourceCloud()),
                        "wallClockTs",     String.valueOf(record.getWallClockTs())
                ))
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(
                record.getPayload() != null ? record.getPayload() : new byte[0]));

        log.debug("Synced to S3: bucket={} key={}", processedBucket, key);
    }

    public Optional<SyncRecord> findExisting(String tenantId, String aggregateId) {
        if (aggregateId == null) return Optional.empty();

        String key = "%s/aggregates/%s/latest.bin".formatted(tenantId, aggregateId);
        try {
            var response = s3Client.getObject(
                    GetObjectRequest.builder()
                            .bucket(processedBucket)
                            .key(key)
                            .build());

            var metadata = response.response().metadata();
            return Optional.of(SyncRecord.builder()
                    .tenantId(tenantId)
                    .aggregateId(aggregateId)
                    .sourceCloud(metadata.getOrDefault("sourceCloud", "aws"))
                    .wallClockTs(Long.parseLong(metadata.getOrDefault("wallClockTs", "0")))
                    .schemaVersion(Integer.parseInt(metadata.getOrDefault("schemaVersion", "1")))
                    .vectorClock(io.auroraforge.sync.domain.model.VectorClock.empty())
                    .syncStatus(io.auroraforge.sync.domain.model.SyncStatus.SYNCED)
                    .payload(response.readAllBytes())
                    .build());

        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to read existing record from S3: key={}", key, e);
            return Optional.empty();
        }
    }

    private String buildS3Key(SyncRecord record) {
        String tenantId       = record.getTenantId();
        String aggregateType  = record.getAggregateType() != null ? record.getAggregateType() : "events";
        String aggregateId    = record.getAggregateId()   != null ? record.getAggregateId()   : record.getId();
        return "%s/%s/%s/%d/%d.bin".formatted(
                tenantId, aggregateType, aggregateId,
                record.getSchemaVersion(), record.getWallClockTs());
    }
}
