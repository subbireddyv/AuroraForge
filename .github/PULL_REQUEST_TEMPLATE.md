## Summary

<!-- 1–3 bullet points describing what this PR does and why -->

-
-

## Type of change

- [ ] Bug fix (non-breaking)
- [ ] New feature (non-breaking)
- [ ] Breaking change (requires migration or config update)
- [ ] Refactor / tech debt
- [ ] Infrastructure / IaC
- [ ] Documentation

## Test plan

- [ ] `mvn -f services/pom.xml test` passes
- [ ] `mvn -f services/pom.xml verify -Pfailsafe` passes (requires `make dev-up`)
- [ ] `mvn -f services/pom.xml test -Dtest="*ArchTest"` passes
- [ ] New endpoints added to the authorization matrix in `AuroraForgeSecurityConfig`
- [ ] Sensitive fields absent from logs (password / token / secret)
- [ ] New external calls decorated with `@CircuitBreaker`, `@Bulkhead`, `@Retry`
- [ ] `@AuditLog` annotation added for any new auditable actions

## Security checklist

- [ ] No secrets committed (checked with `git diff --name-only | xargs grep -l "password\|token\|key"`)
- [ ] Input validated at the HTTP boundary (Jakarta Bean Validation)
- [ ] No new SQL constructed from user input (parameterised queries only)
- [ ] RBAC roles reviewed for new endpoints

## Kafka / Avro checklist (if applicable)

- [ ] Schema change is BACKWARD_TRANSITIVE compatible
- [ ] New topic added to `scripts/init-kafka.sh` and `KafkaTopicProperties`
- [ ] Consumer group ID is unique and documented

## Infrastructure checklist (if applicable)

- [ ] `terraform validate` passes for affected modules
- [ ] `kubectl apply --dry-run=client -k k8s/base` passes
- [ ] Secrets injected via ExternalSecret, not hardcoded ConfigMap

## Related issues

Closes #
