package io.auroraforge.auth.infrastructure.jwt;

import io.auroraforge.core.domain.model.DataClassification;
import io.auroraforge.core.domain.security.AuroraForgeRole;
import io.auroraforge.core.domain.security.TenantPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Converts a validated {@link Jwt} (already signature-checked by Spring's resource server filter)
 * into a fully-populated {@link TenantPrincipal}.
 *
 * Claim mapping (matches what {@link JwtTokenProvider} writes):
 * <ul>
 *   <li>{@code sub}   → principal name (username / service account)</li>
 *   <li>{@code tid}   → tenantId</li>
 *   <li>{@code roles} → role enum names → GrantedAuthority("ROLE_DATA_INGEST"), etc.</li>
 *   <li>{@code cls}   → DataClassification enum names → {@code allowedClassifications} set</li>
 * </ul>
 *
 * Registered as a bean and wired into {@link io.auroraforge.auth.infrastructure.security.AuroraForgeSecurityConfig}
 * via {@code .oauth2ResourceServer(rs -> rs.jwt(j -> j.jwtAuthenticationConverter(converter)))}.
 */
@Slf4j
@Component
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String tenantId = jwt.getClaimAsString(JwtTokenProvider.CLAIM_TENANT_ID);
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("JWT missing 'tid' claim — sub={}", jwt.getSubject());
            tenantId = "unknown";
        }

        Set<AuroraForgeRole> roles = extractRoles(jwt);
        Set<DataClassification> classifications = extractClassifications(jwt);
        Collection<GrantedAuthority> authorities = toAuthorities(roles);

        return new TenantPrincipal(jwt, authorities, tenantId, roles, classifications);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private Set<AuroraForgeRole> extractRoles(Jwt jwt) {
        List<String> roleNames = jwt.getClaimAsStringList(JwtTokenProvider.CLAIM_ROLES);
        if (roleNames == null) return Set.of();

        Set<AuroraForgeRole> result = new LinkedHashSet<>();
        for (String name : roleNames) {
            try {
                result.add(AuroraForgeRole.valueOf(name));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown role in JWT: '{}' — skipping", name);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private Set<DataClassification> extractClassifications(Jwt jwt) {
        List<String> clsNames = jwt.getClaimAsStringList(JwtTokenProvider.CLAIM_CLASSIFICATIONS);
        if (clsNames == null) return Set.of();

        Set<DataClassification> result = new LinkedHashSet<>();
        for (String name : clsNames) {
            try {
                result.add(DataClassification.valueOf(name));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown DataClassification in JWT: '{}' — skipping", name);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private Collection<GrantedAuthority> toAuthorities(Set<AuroraForgeRole> roles) {
        return roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority(r.authority()))
                .collect(Collectors.toUnmodifiableList());
    }
}
