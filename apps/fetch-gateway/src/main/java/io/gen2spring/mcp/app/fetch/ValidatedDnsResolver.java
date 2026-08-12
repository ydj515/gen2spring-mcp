package io.gen2spring.mcp.app.fetch;

import io.gen2spring.mcp.domain.platform.imports.NetworkAddressPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
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
        try {
            InetAddress[] resolved = lookup.resolve(host);
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
        return new UnknownHostException("Import destination is not public");
    }

    private static final class ListSupport {
        private ListSupport() {}

        private static java.util.List<InetAddress> copy(InetAddress[] addresses) {
            return java.util.List.copyOf(Arrays.asList(addresses));
        }
    }
}
