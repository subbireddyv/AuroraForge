package io.auroraforge.auth.application.service;

import io.auroraforge.auth.infrastructure.persistence.UserEntity;
import io.auroraforge.auth.infrastructure.persistence.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Spring Security {@link UserDetailsService} that loads users from PostgreSQL.
 *
 * Used only during token issuance (POST /auth/token) to authenticate the username/password pair.
 * Subsequent API requests are authenticated via JWT only — no DB lookup on each request.
 *
 * Account lock-out: after {@code MAX_FAILED_ATTEMPTS} consecutive failures, the account is
 * locked for {@code LOCK_DURATION_MINUTES} minutes.  The lock is stored in {@code UserEntity}
 * rather than in-memory so it survives service restarts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuroraForgeUserDetailsService implements UserDetailsService {

    static final int  MAX_FAILED_ATTEMPTS   = 5;
    static final long LOCK_DURATION_MINUTES = 15L;

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(
                    "User not found: " + username));

        return buildDetails(user);
    }

    // ── Failed-login tracking ─────────────────────────────────────────────────

    @Transactional
    public void recordFailedLogin(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            Instant lockUntil = user.getFailedLoginCount() + 1 >= MAX_FAILED_ATTEMPTS
                    ? Instant.now().plusSeconds(LOCK_DURATION_MINUTES * 60)
                    : user.getLockedUntil();
            user.recordFailedLogin(lockUntil);
            userRepository.save(user);

            if (user.getFailedLoginCount() >= MAX_FAILED_ATTEMPTS) {
                log.warn("Account locked after {} failed attempts: username={} lockedUntil={}",
                         MAX_FAILED_ATTEMPTS, username, lockUntil);
            }
        });
    }

    @Transactional
    public void recordSuccessfulLogin(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            if (user.getFailedLoginCount() > 0) {
                user.resetFailedLogin();
                userRepository.save(user);
            }
        });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UserDetails buildDetails(UserEntity user) {
        Collection<GrantedAuthority> authorities = user.getRoles().stream()
                .map(r -> new SimpleGrantedAuthority(r.authority()))
                .collect(Collectors.toUnmodifiableList());

        return User.builder()
                .username(user.getUsername())
                .password(user.getPasswordHash())
                .authorities(authorities)
                .accountExpired(false)
                .accountLocked(user.isLocked())
                .credentialsExpired(false)
                .disabled(!user.isEnabled())
                .build();
    }
}
