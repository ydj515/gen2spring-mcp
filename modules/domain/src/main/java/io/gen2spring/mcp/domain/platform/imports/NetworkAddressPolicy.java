package io.gen2spring.mcp.domain.platform.imports;

import java.net.InetAddress;
import java.util.List;

public final class NetworkAddressPolicy {
    private static final String REJECTED = "Import destination is not public";

    private NetworkAddressPolicy() {}

    public static void requirePublicDestination(List<InetAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            throw rejected();
        }
        for (InetAddress address : List.copyOf(addresses)) {
            if (address == null || !isPublic(address)) {
                throw rejected();
            }
        }
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return publicIpv4(bytes);
        }
        if (bytes.length == 16) {
            return publicIpv6(bytes);
        }
        return false;
    }

    private static boolean publicIpv4(byte[] address) {
        int first = unsigned(address[0]);
        int second = unsigned(address[1]);
        int third = unsigned(address[2]);
        if (first == 0
                || first == 10
                || first == 127
                || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))) {
            return false;
        }
        if (first == 192 && second == 0 && (third == 0 || third == 2 || third == 99)) {
            return false;
        }
        if (first == 198 && second == 51 && third == 100) {
            return false;
        }
        return !(first == 203 && second == 0 && third == 113);
    }

    private static boolean publicIpv6(byte[] address) {
        if (ipv4Mapped(address)) {
            byte[] embedded = {address[12], address[13], address[14], address[15]};
            return publicIpv4(embedded);
        }
        if ((unsigned(address[0]) & 0xe0) != 0x20) {
            return false;
        }
        if (prefix(address, new int[] {0x20, 0x01, 0x00, 0x00}, 32)
                || prefix(address, new int[] {0x20, 0x01, 0x00, 0x02, 0x00, 0x00}, 48)
                || prefix(address, new int[] {0x20, 0x01, 0x0d, 0xb8}, 32)
                || prefix(address, new int[] {0x20, 0x02}, 16)) {
            return false;
        }
        int first28 = (unsigned(address[0]) << 20)
                | (unsigned(address[1]) << 12)
                | (unsigned(address[2]) << 4)
                | (unsigned(address[3]) >>> 4);
        return first28 != 0x2001001 && first28 != 0x2001002;
    }

    private static boolean ipv4Mapped(byte[] address) {
        for (int index = 0; index < 10; index++) {
            if (address[index] != 0) {
                return false;
            }
        }
        return unsigned(address[10]) == 0xff && unsigned(address[11]) == 0xff;
    }

    private static boolean prefix(byte[] address, int[] expected, int bits) {
        int fullBytes = bits / 8;
        int remainingBits = bits % 8;
        for (int index = 0; index < fullBytes; index++) {
            if (unsigned(address[index]) != expected[index]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits) & 0xff;
        return (unsigned(address[fullBytes]) & mask) == (expected[fullBytes] & mask);
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private static IllegalArgumentException rejected() {
        return new IllegalArgumentException(REJECTED);
    }
}
