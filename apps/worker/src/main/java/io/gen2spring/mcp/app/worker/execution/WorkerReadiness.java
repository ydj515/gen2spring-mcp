package io.gen2spring.mcp.app.worker.execution;

import java.util.List;
import java.util.Objects;

public final class WorkerReadiness {
    private final List<Probe> probes;

    public WorkerReadiness(List<Probe> probes) {
        this.probes = List.copyOf(Objects.requireNonNull(probes, "probes"));
        if (this.probes.isEmpty() || this.probes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Hosted worker configuration is invalid");
        }
    }

    public void verify() {
        try {
            for (Probe probe : probes) {
                probe.verify();
            }
        } catch (Error fatal) {
            throw fatal;
        } catch (Exception failure) {
            throw new WorkerStartupFailure();
        }
    }

    @FunctionalInterface
    public interface Probe {
        void verify() throws Exception;
    }
}
