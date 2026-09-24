package io.gen2spring.mcp.application.hosted.specification.port.out;

import io.gen2spring.mcp.application.hosted.storage.ObjectKey;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

@FunctionalInterface
public interface SpecificationCatalog {
    boolean belongsTo(AccountId owner, SpecificationId specificationId);

    default RegistrationResult register(Registration registration) {
        throw new UnsupportedOperationException("Specification registration is unavailable");
    }

    enum RegistrationResult {
        CREATED,
        REPLAYED
    }

    record Registration(
            SpecificationId id,
            AccountId owner,
            ObjectKey objectKey,
            String sha256,
            long byteSize,
            String sourceType,
            String displayLabel,
            String parseState,
            Instant observedAt) {
        private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");

        public Registration {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(objectKey, "objectKey");
            Objects.requireNonNull(observedAt, "observedAt");
            if (sha256 == null
                    || !SHA256.matcher(sha256).matches()
                    || byteSize < 1
                    || byteSize > 10 * 1024 * 1024
                    || !("URL".equals(sourceType) || "UPLOAD".equals(sourceType))
                    || displayLabel == null
                    || displayLabel.isBlank()
                    || displayLabel.length() > 160
                    || !displayLabel.equals(displayLabel.strip())
                    || displayLabel.equals(".")
                    || displayLabel.equals("..")
                    || displayLabel.indexOf('/') >= 0
                    || displayLabel.indexOf('\\') >= 0
                    || displayLabel.chars().anyMatch(Character::isISOControl)
                    || !"READY".equals(parseState)) {
                throw new IllegalArgumentException("Specification registration is invalid");
            }
        }
    }
}
