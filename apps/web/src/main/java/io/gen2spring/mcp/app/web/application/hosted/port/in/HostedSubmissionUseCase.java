package io.gen2spring.mcp.app.web.application.hosted.port.in;

import io.gen2spring.mcp.application.analysis.SpecificationAnalysisView;
import io.gen2spring.mcp.application.hosted.job.CreateJobResult;
import io.gen2spring.mcp.application.usecase.GenerationPreview;
import io.gen2spring.mcp.domain.platform.identity.AccountId;
import io.gen2spring.mcp.domain.platform.specification.SpecificationId;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/** Hosted submission operations shared by HTTP delivery and the storage adapter. */
public interface HostedSubmissionUseCase {
    HostedSpecificationAnalysis upload(AccountId owner, InputStream input, String mediaType, String specificationName);

    HostedSpecificationAnalysis analysis(AccountId owner, SpecificationId specificationId);

    GenerationPreview preview(AccountId owner, SpecificationId specificationId, byte[] configurationBytes);

    CreateJobResult importUrl(AccountId owner, String idempotencyKey, String url);

    CreateJobResult generate(AccountId owner, SpecificationId specificationId,
            Optional<UUID> predecessorCatalogId, String idempotencyKey, byte[] configurationBytes);

    CreateJobResult generate(AccountId owner, SpecificationId specificationId,
            String idempotencyKey, byte[] configurationBytes);

    final class HostedSubmissionFailure extends RuntimeException {
        public HostedSubmissionFailure() {
            super("Hosted submission failed", null, false, false);
        }

        public HostedSubmissionFailure(Throwable cause) {
            super("Hosted submission failed", cause, false, false);
        }
    }

    final class HostedSpecificationNotFound extends RuntimeException {
        public HostedSpecificationNotFound() {
            super("Hosted specification was not found", null, false, false);
        }
    }

    record HostedSpecificationAnalysis(
            SpecificationId id,
            String displayLabel,
            long byteSize,
            SpecificationAnalysisView analysis) {
        public HostedSpecificationAnalysis {
            if (id == null || displayLabel == null || displayLabel.isBlank()
                    || byteSize < 1 || analysis == null) {
                throw new IllegalArgumentException("Hosted specification analysis is invalid");
            }
        }
    }
}
