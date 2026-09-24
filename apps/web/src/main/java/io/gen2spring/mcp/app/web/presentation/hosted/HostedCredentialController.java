package io.gen2spring.mcp.app.web.presentation.hosted;

import io.gen2spring.mcp.application.managed.credential.CredentialSecret;
import io.gen2spring.mcp.application.managed.credential.ManagedCredentialService;
import io.gen2spring.mcp.app.web.presentation.security.HostedAccountResolver;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredential;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialId;
import io.gen2spring.mcp.domain.platform.credential.ManagedCredentialKind;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "gen2spring.mode", havingValue = "hosted")
final class HostedCredentialController {
    private final HostedAccountResolver accounts;
    private final ManagedCredentialService credentials;

    HostedCredentialController(HostedAccountResolver accounts, ManagedCredentialService credentials) {
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @PostMapping("/api/credentials")
    ResponseEntity<CredentialResponse> create(
            Authentication authentication,
            @RequestBody CredentialRequest request) {
        ManagedCredential created = credentials.create(
                accounts.resolve(authentication).accountId(), require(request).label(), secret(request));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(response(created));
    }

    @GetMapping("/api/credentials")
    ResponseEntity<List<CredentialResponse>> list(Authentication authentication) {
        List<CredentialResponse> result = credentials.list(accounts.resolve(authentication).accountId()).stream()
                .map(HostedCredentialController::response).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/api/credentials/{credentialId}")
    ResponseEntity<CredentialResponse> get(Authentication authentication, @PathVariable String credentialId) {
        CredentialResponse result = response(credentials.require(
                accounts.resolve(authentication).accountId(), credentialId(credentialId)));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @PostMapping("/api/credentials/{credentialId}/rotation")
    ResponseEntity<CredentialResponse> rotate(
            Authentication authentication,
            @PathVariable String credentialId,
            @RequestBody CredentialRequest request) {
        var owner = accounts.resolve(authentication).accountId();
        ManagedCredential current = credentials.require(owner, credentialId(credentialId));
        CredentialRequest checked = require(request);
        if (checked.kind() != null && kind(checked.kind()) != current.kind()) throw invalid();
        ManagedCredential rotated = credentials.rotate(owner, current.id(), secret(checked, current.kind()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response(rotated));
    }

    @PostMapping("/api/credentials/{credentialId}/revocation")
    ResponseEntity<Void> revoke(Authentication authentication, @PathVariable String credentialId) {
        credentials.revoke(accounts.resolve(authentication).accountId(), credentialId(credentialId));
        return ResponseEntity.noContent().build();
    }

    private CredentialSecret secret(CredentialRequest request) {
        CredentialRequest checked = require(request);
        return secret(checked, kind(checked.kind()));
    }

    private CredentialSecret secret(CredentialRequest request, ManagedCredentialKind kind) {
        try {
            return switch (kind) {
                case OPAQUE -> {
                    if (request.username() != null || request.password() != null) throw invalid();
                    yield CredentialSecret.opaque(request.value());
                }
                case BEARER -> {
                    if (request.username() != null || request.password() != null) throw invalid();
                    yield CredentialSecret.bearer(request.value());
                }
                case BASIC -> {
                    if (request.value() != null) throw invalid();
                    yield CredentialSecret.basic(request.username(), request.password());
                }
            };
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private ManagedCredentialKind kind(String value) {
        try {
            return ManagedCredentialKind.valueOf(value == null ? "" : value);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private CredentialRequest require(CredentialRequest request) {
        if (request == null) throw invalid();
        return request;
    }

    private ManagedCredentialId credentialId(String value) {
        try {
            return ManagedCredentialId.parse(value);
        } catch (RuntimeException failure) {
            throw invalid();
        }
    }

    private static CredentialResponse response(ManagedCredential credential) {
        return new CredentialResponse(
                credential.id().value().toString(), credential.label(), credential.kind().name(),
                credential.version(), credential.state().name(), credential.createdAt().toString(),
                credential.rotatedAt().toString(), credential.revokedAt().map(Instant::toString).orElse(null));
    }

    private static ManagedCredentialService.ManagedCredentialRequestInvalid invalid() {
        return new ManagedCredentialService.ManagedCredentialRequestInvalid();
    }

    record CredentialRequest(
            String label,
            String kind,
            String value,
            String username,
            String password) {
        @Override
        public String toString() {
            return "CredentialRequest[label=redacted, kind=" + kind + ", value=redacted]";
        }
    }

    record CredentialResponse(
            String credentialId,
            String label,
            String kind,
            long version,
            String state,
            String createdAt,
            String rotatedAt,
            String revokedAt) {}
}
