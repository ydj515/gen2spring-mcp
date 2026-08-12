package io.gen2spring.mcp.domain.platform.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class NetworkAddressPolicyTest {
    @Test
    void acceptsCanonicalHttpAndHttpsTargets() {
        ImportTarget https = ImportTarget.parse("https://api.example.com/openapi.json?version=1");
        ImportTarget http = ImportTarget.parse("http://api.example.com:80/openapi.yaml");

        assertEquals(URI.create("https://api.example.com/openapi.json?version=1"), https.uri());
        assertEquals("api.example.com", https.host());
        assertEquals(443, https.port());
        assertEquals(80, http.port());
    }

    @ParameterizedTest
    @MethodSource("invalidTargets")
    void rejectsUnsupportedOrAmbiguousTargetsWithoutEchoingThem(String value) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ImportTarget.parse(value));
        assertEquals("Import target is invalid", failure.getMessage());
    }

    @ParameterizedTest
    @MethodSource("addressCases")
    void classifiesFiniteIpv4AndIpv6Ranges(String address, boolean allowed) throws Exception {
        List<InetAddress> resolved = List.of(InetAddress.getByName(address));
        if (allowed) {
            NetworkAddressPolicy.requirePublicDestination(resolved);
        } else {
            assertRejected(resolved);
        }
    }

    @Test
    void rejectsIpv4MappedIpv6AndMixedPublicPrivateAnswers() throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        mapped[12] = 10;
        mapped[15] = 1;

        assertRejected(List.of(InetAddress.getByAddress(mapped)));
        assertRejected(List.of(
                InetAddress.getByName("93.184.216.34"),
                InetAddress.getByName("127.0.0.1")));
        assertRejected(List.of());
    }

    private static Stream<String> invalidTargets() {
        return Stream.of(
                "ftp://api.example.com/openapi.json",
                "http://api.example.com:443/openapi.json",
                "https://api.example.com:80/openapi.json",
                "https://user:password@api.example.com/openapi.json",
                "https://api.example.com/openapi.json#fragment",
                "https:///openapi.json",
                "https://metadata.google.internal/openapi.json",
                "https://metadata.azure.internal/openapi.json",
                "https://instance-data/openapi.json",
                "https://api_example.com/openapi.json",
                "private marker");
    }

    private static Stream<Arguments> addressCases() {
        return Stream.of(
                Arguments.of("0.0.0.0", false),
                Arguments.of("10.0.0.1", false),
                Arguments.of("100.64.0.1", false),
                Arguments.of("127.0.0.1", false),
                Arguments.of("169.254.169.254", false),
                Arguments.of("172.16.0.1", false),
                Arguments.of("192.0.2.1", false),
                Arguments.of("192.168.0.1", false),
                Arguments.of("198.18.0.1", false),
                Arguments.of("198.51.100.1", false),
                Arguments.of("203.0.113.1", false),
                Arguments.of("224.0.0.1", false),
                Arguments.of("240.0.0.1", false),
                Arguments.of("93.184.216.34", true),
                Arguments.of("::", false),
                Arguments.of("::1", false),
                Arguments.of("64:ff9b::1", false),
                Arguments.of("100::1", false),
                Arguments.of("2001:2::1", false),
                Arguments.of("2001:db8::1", false),
                Arguments.of("2002::1", false),
                Arguments.of("fc00::1", false),
                Arguments.of("fe80::1", false),
                Arguments.of("ff02::1", false),
                Arguments.of("2001:4860:4860::8888", true));
    }

    private void assertRejected(List<InetAddress> addresses) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> NetworkAddressPolicy.requirePublicDestination(addresses));
        assertEquals("Import destination is not public", failure.getMessage());
    }
}
