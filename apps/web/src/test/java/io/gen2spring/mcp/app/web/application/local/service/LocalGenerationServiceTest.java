package io.gen2spring.mcp.app.web.application.local.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.gen2spring.mcp.app.web.application.local.exception.LocalJobFailure;
import io.gen2spring.mcp.app.web.application.local.port.out.GenerationConfigurationDecoder;
import io.gen2spring.mcp.app.web.application.local.port.out.GenerationJobs;
import io.gen2spring.mcp.app.web.application.local.port.out.SpecificationStorage;
import io.gen2spring.mcp.app.web.application.local.result.StoredSpecification;
import io.gen2spring.mcp.application.generation.command.GenerationCommand;
import io.gen2spring.mcp.application.generation.usecase.GenerationPipeline;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class LocalGenerationServiceTest {
    @Test
    void releasesPinnedSpecificationWhenConfigurationIsInvalid() {
        SpecificationStorage specifications = mock(SpecificationStorage.class);
        GenerationConfigurationDecoder decoder = mock(GenerationConfigurationDecoder.class);
        byte[] request = new byte[] {1};
        when(specifications.retain("id")).thenReturn(stored());
        when(decoder.decode(request)).thenThrow(new IllegalArgumentException("invalid"));
        LocalGenerationService service = new LocalGenerationService(
                specifications, mock(GenerationJobs.class), mock(GenerationPipeline.class), decoder);

        assertThrows(IllegalArgumentException.class, () -> service.preview("id", request));
        verify(specifications).release("id");
    }

    @Test
    void releasesPinnedSpecificationWhenJobSubmissionIsRejected() {
        SpecificationStorage specifications = mock(SpecificationStorage.class);
        GenerationJobs jobs = mock(GenerationJobs.class);
        GenerationConfigurationDecoder decoder = mock(GenerationConfigurationDecoder.class);
        GenerationCommand command = mock(GenerationCommand.class);
        byte[] request = new byte[] {1};
        when(specifications.retain("id")).thenReturn(stored());
        when(decoder.decode(request)).thenReturn(command);
        when(jobs.submit(eq(Path.of("/tmp/spec.yaml")), eq(command), any(Runnable.class)))
                .thenThrow(new LocalJobFailure(LocalJobFailure.Kind.CAPACITY));
        LocalGenerationService service = new LocalGenerationService(
                specifications, jobs, mock(GenerationPipeline.class), decoder);

        assertThrows(LocalJobFailure.class, () -> service.submit("id", request));
        verify(specifications).release("id");
    }

    private StoredSpecification stored() {
        return new StoredSpecification("id", "spec.yaml", Path.of("/tmp/spec.yaml"), 1, null);
    }
}
