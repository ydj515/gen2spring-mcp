package io.gen2spring.mcp.app.cli.application.port.out;

import io.gen2spring.mcp.application.analysis.SpecificationAnalysisView;
import java.nio.file.Path;

public interface CliFilePort {
    SpecificationCopy specificationCopy(Path requested);

    AnalysisOutput newAnalysisOutput(Path requested);

    ProjectOutput newProjectOutput(Path requested);

    interface AnalysisOutput {
        Path path();

        void publish(SpecificationAnalysisView analysis);
    }

    interface ProjectOutput {
        Path path();

        void verifyAvailable();
    }

    interface SpecificationCopy extends AutoCloseable {
        Path path();

        @Override
        void close();
    }
}
