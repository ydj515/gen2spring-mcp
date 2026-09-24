package io.gen2spring.mcp.app.fetch.infrastructure.client.fetch;

import io.gen2spring.mcp.domain.platform.imports.NetworkAddressPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.hc.client5.http.DnsResolver;

public final class ValidatedDnsResolver implements DnsResolver {
    private final HostLookup lookup;

    public ValidatedDnsResolver() {
        this(InetAddress::getAllByName);
    }

    ValidatedDnsResolver(HostLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] resolved;
        try {
            resolved = lookup.resolve(host);
        } catch (UnknownHostException failure) {
            throw new ResolutionUnavailable();
        } catch (Exception failure) {
            throw rejected();
        }
        try {
            InetAddress[] snapshot = Arrays.copyOf(resolved, resolved.length);
            NetworkAddressPolicy.requirePublicDestination(ListSupport.copy(snapshot));
            return snapshot;
        } catch (Exception failure) {
            throw rejected();
        }
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        resolve(host);
        if (host == null || host.isBlank()) {
            throw rejected();
        }
        return host.toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    interface HostLookup {
        InetAddress[] resolve(String host) throws Exception;
    }

    private UnknownHostException rejected() {
        return new DestinationRejected();
    }

    static final class DestinationRejected extends UnknownHostException {
        private DestinationRejected() {
            super("Import destination is not public");
        }
    }

    static final class ResolutionUnavailable extends UnknownHostException {
        private ResolutionUnavailable() {
            super("Import destination resolution failed");
        }
    }

    private static final class ListSupport {
        private ListSupport() {}

        private static List<InetAddress> copy(InetAddress[] addresses) {
            return List.copyOf(Arrays.asList(addresses));
        }
    }
}
