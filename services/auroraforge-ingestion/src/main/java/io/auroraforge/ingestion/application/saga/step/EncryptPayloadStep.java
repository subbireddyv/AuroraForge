package io.auroraforge.ingestion.application.saga.step;

import io.auroraforge.core.application.port.out.KeyManagementPort;
import io.auroraforge.ingestion.application.saga.IngestionSagaContext;
import io.auroraforge.ingestion.application.saga.SagaStep;
import io.auroraforge.ingestion.application.saga.SagaStepException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Step 1 — Encrypt the raw event payload using the tenant's current DEK.
 *
 * Compensation: key-based encryption is content-addressed — there is nothing
 * to undo; we simply discard the encrypted bytes when compensating (they are
 * never persisted at this point). A log entry is emitted for audit purposes.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EncryptPayloadStep implements SagaStep {

    private final KeyManagementPort keyManagementPort;

    @Override
    public String name() { return "ENCRYPT_PAYLOAD"; }

    @Override
    public IngestionSagaContext execute(IngestionSagaContext ctx) throws SagaStepException {
        log.debug("saga={} step={} tenant={} classification={}",
                ctx.getSagaId(), name(), ctx.getTenantId(), ctx.getClassification());
        try {
            var encCtx = io.auroraforge.core.domain.model.EncryptionContext.of(
                    ctx.getTenantId(), ctx.getClassification());
            var result = keyManagementPort.encrypt(ctx.getRawPayload(), encCtx);
            log.info("saga={} step={} status=ok keyId={}", ctx.getSagaId(), name(), result.keyId());
            return ctx.withEncryption(result.ciphertext(), result.keyId());
        } catch (Exception e) {
            throw new SagaStepException(name(), "Encryption failed: " + e.getMessage(), e, isRetryable(e));
        }
    }

    @Override
    public void compensate(IngestionSagaContext ctx) {
        // No remote state to undo — encryption is purely local at this point.
        log.info("saga={} step={} compensation=noop (in-memory only)", ctx.getSagaId(), name());
    }

    private boolean isRetryable(Exception e) {
        String msg = e.getClass().getName();
        return msg.contains("SdkClientException") || msg.contains("AzureException")
                || e instanceof java.net.ConnectException;
    }
}
