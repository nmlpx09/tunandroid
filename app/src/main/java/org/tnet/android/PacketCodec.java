// SPDX-License-Identifier: GPL-2.0
package org.tnet.android;

import java.nio.charset.StandardCharsets;

/** Exact tnet wire format: RC4-XORed IPv4 bytes, one packet per UDP datagram. */
public final class PacketCodec {
    private final byte[] prga;

    public PacketCodec(String key, int mtu) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length == 0) throw new IllegalArgumentException("Key must not be empty");
        if (mtu <= 0) throw new IllegalArgumentException("MTU must be positive");
        prga = new byte[mtu];
        byte[] s = new byte[256];
        for (int i = 0; i < 256; i++) s[i] = (byte) i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j + (s[i] & 255) + (keyBytes[i % keyBytes.length] & 255)) & 255;
            swap(s, i, j);
        }
        int i = 0;
        j = 0;
        for (int k = 0; k < prga.length; k++) {
            i = (i + 1) & 255;
            j = (j + (s[i] & 255)) & 255;
            swap(s, i, j);
            prga[k] = s[((s[i] & 255) + (s[j] & 255)) & 255];
        }
    }

    public void encode(byte[] bytes, int length) { crypt(bytes, length); }
    public void decode(byte[] bytes, int length) { crypt(bytes, length); }

    private void crypt(byte[] bytes, int length) {
        for (int i = 0; i < length; i++) bytes[i] ^= prga[i % prga.length];
    }

    private static void swap(byte[] bytes, int a, int b) {
        byte tmp = bytes[a];
        bytes[a] = bytes[b];
        bytes[b] = tmp;
    }

    public static boolean isIpv4(byte[] bytes, int length) {
        if (length < 20 || length > bytes.length || (bytes[0] & 0xf0) != 0x40) return false;
        int header = (bytes[0] & 15) * 4;
        int total = ((bytes[2] & 255) << 8) | (bytes[3] & 255);
        return header >= 20 && header <= length && total == length;
    }
    public static boolean addressedTo(byte[] packet, String ip) {
        String[] parts = ip.split("\\.");
        for (int i = 0; i < 4; i++) if ((packet[16 + i] & 255) != Integer.parseInt(parts[i])) return false;
        return true;
    }
}
