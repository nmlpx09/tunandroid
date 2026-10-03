// SPDX-License-Identifier: GPL-2.0
import org.tnet.android.PacketCodec;
import org.tnet.android.TunnelConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProtocolTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void invalid(String host, String port, String address, String dns, String mtu) {
        try { new TunnelConfig(host, port, address, dns, mtu); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid config was accepted");
    }
    public static void main(String[] args) throws Exception {
        String source = Files.readString(Path.of(args[0]));
        String[] names = {"ENCRYPT_TABLE", "DECRYPT_TABLE"};
        byte[][] tables = new byte[2][256];
        for (int t = 0; t < 2; t++) {
            String table = source.split(names[t] + "\\[\\] = \\{")[1].split("};")[0];
            Matcher m = Pattern.compile("0x([0-9A-Fa-f]{2})").matcher(table);
            int count = 0;
            while (m.find()) tables[t][count++] = (byte) Integer.parseInt(m.group(1), 16);
            check(count == 256, "Server table length");
        }
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i;
        byte[] encoded = all.clone();
        PacketCodec.encode(encoded, 256);
        check(Arrays.equals(encoded, tables[0]), "Encoding must match kernel table for all bytes");
        byte[] decoded = all.clone();
        PacketCodec.decode(decoded, 256);
        check(Arrays.equals(decoded, tables[1]), "Decoding must match kernel table for all bytes");
        Random random = new Random(7);
        for (int length : new int[]{20, 28, 576, 1400, 1472}) {
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
            PacketCodec.encode(ip, length);
            check(Arrays.equals(Arrays.copyOfRange(ip, length, ip.length), Arrays.copyOfRange(original, length, original.length)), "Tail remains untouched");
            PacketCodec.decode(ip, length);
            check(Arrays.equals(ip, original), "Packet round trip");
            check(!PacketCodec.isIpv4(ip, length - 1), "Reject truncation");
            ip[0] = 0x65;
            check(!PacketCodec.isIpv4(ip, length), "Reject IPv6");
            ip[0] = 0x44;
            check(!PacketCodec.isIpv4(ip, length), "Reject short IPv4 header");
        }
        check(!PacketCodec.isIpv4(new byte[19], 19), "Reject short packet");
        new TunnelConfig("203.0.113.10", "69", "10.0.3.2", "1.1.1.1", "1472");
        invalid("server.example", "69", "10.0.3.2", "1.1.1.1", "1472");
        invalid("203.0.113.256", "69", "10.0.3.2", "1.1.1.1", "1472");
        invalid("203.0.113.10", "0", "10.0.3.2", "1.1.1.1", "1472");
        invalid("203.0.113.10", "65536", "10.0.3.2", "1.1.1.1", "1472");
        invalid("203.0.113.10", "69", "10.0.3.2", "1.1.1.1", "1500");
        invalid("203.0.113.10", "69", "10.0.3.2", "::1", "1472");
        invalid("203.0.113.10", "69", "224.0.0.1", "1.1.1.1", "1472");
        System.out.println("Protocol compatibility, IPv4 filtering and configuration tests passed.");
    }
}
