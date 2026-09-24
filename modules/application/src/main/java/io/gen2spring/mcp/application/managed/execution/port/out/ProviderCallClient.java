package io.gen2spring.mcp.application.managed.execution.port.out;

import io.gen2spring.mcp.application.managed.execution.ProviderCallRequest;
import io.gen2spring.mcp.application.managed.execution.ProviderCallResponse;
import java.time.Duration;

public interface ProviderCallClient {
    ProviderCallResponse execute(ProviderCallRequest request, Duration timeout);

    final class ProviderCallFailure extends RuntimeException {
        private final Kind kind;

        private ProviderCallFailure(Kind kind) {
            super("Provider call failed", null, false, false);
            this.kind = kind;
        }

        public static ProviderCallFailure timeout() {
            return new ProviderCallFailure(Kind.TIMEOUT);
        }

        public static ProviderCallFailure unavailable() {
            return new ProviderCallFailure(Kind.UNAVAILABLE);
        }

        public static ProviderCallFailure protocol() {
            return new ProviderCallFailure(Kind.PROTOCOL);
        }

        public Kind kind() {
            return kind;
        }

        public enum Kind {
            TIMEOUT,
            UNAVAILABLE,
            PROTOCOL
        }
    }
}
