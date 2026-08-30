package io.gen2spring.mcp.app.provideregress.egress;

import io.gen2spring.mcp.domain.platform.imports.NetworkAddressPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.apache.hc.client5.http.DnsResolver;

final class ValidatedProviderResolver implements DnsResolver {
    private final HostLookup lookup;

    ValidatedProviderResolver() {
        this(InetAddress::getAllByName);
    }

    ValidatedProviderResolver(HostLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        try {
            InetAddress[] resolved = lookup.resolve(host);
            InetAddress[] snapshot = Arrays.copyOf(resolved, resolved.length);
            NetworkAddressPolicy.requirePublicDestination(List.copyOf(Arrays.asList(snapshot)));
            return snapshot;
        } catch (DestinationRejected failure) {
            throw failure;
        } catch (Exception failure) {
            throw new DestinationRejected();
        }
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        resolve(host);
        if (host == null || host.isBlank()) {
            throw new DestinationRejected();
        }
        return host.toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    interface HostLookup {
        InetAddress[] resolve(String host) throws Exception;
    }

    static final class DestinationRejected extends UnknownHostException {
        private DestinationRejected() {
            super("Provider destination is not public");
        }
    }
}
