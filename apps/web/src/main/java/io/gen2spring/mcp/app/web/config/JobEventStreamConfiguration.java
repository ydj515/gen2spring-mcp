package io.gen2spring.mcp.app.web.config;

import io.gen2spring.mcp.app.web.job.JobEventStream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mode-neutral on purpose. {@code WebRuntimeConfiguration} is local-only and
 * {@code HostedWebConfiguration} is hosted-only, but both modes serve the job
 * event stream, so the shared pool cannot live in either.
 */
@Configuration(proxyBeanMethods = false)
class JobEventStreamConfiguration {
    private static final int MAXIMUM_CONCURRENT_STREAMS = 8;

    @Bean(destroyMethod = "close")
    JobEventStream jobEventStream() {
        return new JobEventStream(MAXIMUM_CONCURRENT_STREAMS);
    }
}
