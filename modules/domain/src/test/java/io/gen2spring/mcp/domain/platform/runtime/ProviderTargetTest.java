package io.gen2spring.mcp.domain.platform.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProviderTargetTest {
    @Test
    void canonicalizesSupportedPublicHttpTargetsWithoutResolvingDns() {
        ProviderTarget https = ProviderTarget.parse("HTTPS://API.Example.COM/v1/../v2");
        ProviderTarget http = ProviderTarget.parse("http://api.example.com:80/base");

        assertEquals("https://api.example.com/v2", https.uri().toASCIIString());
        assertEquals("http://api.example.com/base", http.uri().toASCIIString());
        assertEquals("api.example.com", https.host());
        assertEquals(443, https.port());
        assertEquals(80, http.port());
        assertEquals("ProviderTarget[redacted]", https.toString());
    }

    @Test
    void rejectsUnsupportedOrAuthorityBearingTargetsWithOneSafeMessage() {
        String privateMarker = "sensitive-provider";
        String[] invalid = {
            null,
            "",
            "/relative",
            "ftp://api.example.com/resource",
            "https://user:password@api.example.com",
            "https://api.example.com?token=secret",
            "https://api.example.com/#fragment",
            "https://api.example.com:8443",
            "http://api.example.com:443",
            "https://" + privateMarker + ".example.com/\nheader"
        };

        for (String value : invalid) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> ProviderTarget.parse(value));
            assertEquals("Provider target is invalid", failure.getMessage());
            assertFalse(failure.getMessage().contains(privateMarker));
        }
    }

    @Test
    void acceptsIpLiteralsForConnectTimeAddressPolicyToClassify() {
        assertEquals("https://127.0.0.1/", ProviderTarget.parse("https://127.0.0.1").uri().toString());
        assertEquals("https://[2001:db8::1]/", ProviderTarget.parse("https://[2001:db8::1]").uri().toString());
    }
}
