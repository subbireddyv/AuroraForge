package io.auroraforge.ingestion.application.saga.step;

import io.auroraforge.core.application.port.out.CloudObjectStoragePort;
import io.auroraforge.ingestion.application.saga.IngestionSagaContext;
import io.auroraforge.ingestion.application.saga.SagaStep;
import io.auroraforge.ingestion.application.saga.SagaStepException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Step 2 — Upload the encrypted payload to cloud object storage (S3 / Azure Blob).
 *
 * Object key format: {tenantId}/raw/{aggregateId}/{sagaId}
 *
 * Compensation: delete the uploaded object (idempotent — SDK returns success
 * on delete-missing-object for both S3 and Azure Blob).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreRawObjectStep implements SagaStep {

    private final CloudObjectStoragePort objectStoragePort;

    @Override
    public String name() { return "STORE_RAW_OBJECT"; }

    @Override
    public IngestionSagaContext execute(IngestionSagaContext ctx) throws SagaStepException {
        String key = buildObjectKey(ctx);
        log.debug("saga={} step={} key={}", ctx.getSagaId(), name(), key);
        try {
            objectStoragePort.upload(
                    ctx.getTenantId(),
                    key,
                    ctx.getEncryptedPayload(),
                    Map.of(
                            "x-saga-id",          ctx.getSagaId(),
                            "x-request-id",       ctx.getRequestId(),
                            "x-event-type",       ctx.getEventType(),
                            "x-encryption-key-id", ctx.getEncryptionKeyId(),
                            "x-classification",   ctx.getClassification().name()
                    )
            );
            log.info("saga={} step={} status=ok key={}", ctx.getSagaId(), name(), key);
            return ctx.withStorageObjectKey(key);
        } catch (Exception e) {
            throw new SagaStepException(name(), "Object upload failed for key=" + key, e, true);
        }
    }

    @Override
    public void compensate(IngestionSagaContext ctx) {
        if (ctx.getStorageObjectKey() == null) return;
        try {
            objectStoragePort.delete(ctx.getTenantId(), ctx.getStorageObjectKey());
            log.info("saga={} step={} compensation=ok key={}",
                    ctx.getSagaId(), name(), ctx.getStorageObjectKey());
        } catch (Exception e) {
            // Log and continue — orphaned objects are cleaned by the lifecycle policy.
            log.warn("saga={} step={} compensation=failed key={} error={}",
                    ctx.getSagaId(), name(), ctx.getStorageObjectKey(), e.getMessage());
        }
    }

    private String buildObjectKey(IngestionSagaContext ctx) {
        return ctx.getTenantId().value() + "/raw/" + ctx.getAggregateId() + "/" + ctx.getSagaId();
    }
}
