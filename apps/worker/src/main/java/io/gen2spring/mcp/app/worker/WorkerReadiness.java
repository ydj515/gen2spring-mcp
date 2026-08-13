package io.gen2spring.mcp.app.worker;

import java.util.List;
import java.util.Objects;

final class WorkerReadiness {
    private final List<Probe> probes;

    WorkerReadiness(List<Probe> probes) {
        this.probes = List.copyOf(Objects.requireNonNull(probes, "probes"));
        if (this.probes.isEmpty() || this.probes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Hosted worker configuration is invalid");
        }
    }

    void verify() {
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
    interface Probe {
        void verify() throws Exception;
    }
}
