// SPDX-License-Identifier: GPL-2.0
import org.tnet.android.PacketCodec;
import org.tnet.android.TunnelConfig;
import java.util.Arrays;
import java.util.Random;

public final class ProtocolTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void invalid(String host, String port, String address, String dns, String mtu, String key) {
        try { new TunnelConfig(host, port, address, dns, mtu, key); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid config was accepted");
    }
    public static void main(String[] args) throws Exception {
        PacketCodec codec = new PacketCodec("secret", 1472);
        byte[] zeros = new byte[16];
        byte[] encrypted = zeros.clone();
        encrypted = Arrays.copyOf(encrypted, encrypted.length + 4);
        int encryptedLength = codec.encode(encrypted, zeros.length, 0);
        check(encryptedLength == 20, "Encryption header length");
        check(Arrays.equals(Arrays.copyOf(encrypted, 4), new byte[4]), "Big-endian seed header");
        byte[] expected = {(byte) 0xed, 0x36, (byte) 0xd2, 0x1c, (byte) 0x82, (byte) 0xa4, (byte) 0xd6, (byte) 0xa6,
                0x32, (byte) 0xcb, (byte) 0xbb, (byte) 0xdc, (byte) 0xf3, (byte) 0xe6, (byte) 0xf5, 0x37};
        check(Arrays.equals(Arrays.copyOfRange(encrypted, 4, encrypted.length), expected), "RC4 keystream must match tundriver for key secret");
        check(codec.decode(encrypted, encryptedLength) == zeros.length, "Decryption length");
        check(Arrays.equals(Arrays.copyOf(encrypted, zeros.length), zeros), "RC4 round trip");
        Random random = new Random(7);
        for (int length : new int[]{20, 28, 576, 1400, 1472}) {
            codec = new PacketCodec("secret", 1472);
            byte[] ip = new byte[length + 8];
            random.nextBytes(ip);
            ip[0] = 0x45;
            ip[2] = (byte) (length >> 8);
            ip[3] = (byte) length;
            ip[16] = 10; ip[17] = 0; ip[18] = 3; ip[19] = 2;
            byte[] original = ip.clone();
            check(PacketCodec.isIpv4(ip, length), "Valid IPv4");
            check(PacketCodec.addressedTo(ip, "10.0.3.2"), "Correct destination");
            check(!PacketCodec.addressedTo(ip, "10.0.3.3"), "Wrong destination");
            int encodedLength = codec.encode(ip, length, 0x76543210);
            check(encodedLength == length + 4, "Encrypted packet includes seed");
            check(codec.decode(ip, encodedLength) == length, "Decoded packet length");
            check(Arrays.equals(Arrays.copyOf(ip, length), Arrays.copyOf(original, length)), "Packet round trip");
            check(!PacketCodec.isIpv4(ip, length - 1), "Reject truncation");
            ip[0] = 0x65;
            check(!PacketCodec.isIpv4(ip, length), "Reject IPv6");
            ip[0] = 0x44;
            check(!PacketCodec.isIpv4(ip, length), "Reject short IPv4 header");
        }
        check(!PacketCodec.isIpv4(new byte[19], 19), "Reject short packet");
        boolean missingHeaderRejected = false;
        try { codec.decode(new byte[3], 3); }
        catch (IllegalArgumentException ignored) { missingHeaderRejected = true; }
        check(missingHeaderRejected, "Reject missing encryption header");
        new TunnelConfig("203.0.113.10", "69", "10.0.3.2", "1.1.1.1", "1472", "secret");
        invalid("server.example", "69", "10.0.3.2", "1.1.1.1", "1472", "secret");
        invalid("203.0.113.256", "69", "10.0.3.2", "1.1.1.1", "1472", "secret");
        invalid("203.0.113.10", "0", "10.0.3.2", "1.1.1.1", "1472", "secret");
        invalid("203.0.113.10", "65536", "10.0.3.2", "1.1.1.1", "1472", "secret");
        invalid("203.0.113.10", "69", "10.0.3.2", "1.1.1.1", "1500", "secret");
        invalid("203.0.113.10", "69", "10.0.3.2", "::1", "1472", "secret");
        invalid("203.0.113.10", "69", "224.0.0.1", "1.1.1.1", "1472", "secret");
        invalid("203.0.113.10", "69", "10.0.3.2", "1.1.1.1", "1472", "");
        System.out.println("RC4 protocol, IPv4 filtering and configuration tests passed.");
    }
}
