// SPDX-License-Identifier: GPL-2.0
package org.tnet.android;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Exact tnet wire format: a big-endian RC4 offset followed by RC4-XORed IPv4 bytes. */
public final class PacketCodec {
    private static final int SEED_LENGTH = 4;
    private final byte[] prga;
    private final SecureRandom random = new SecureRandom();

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

    public int encode(byte[] bytes, int length) {
        return encode(bytes, length, random.nextInt());
    }

    public int encode(byte[] bytes, int length, int seed) {
        if (length < 0 || length + SEED_LENGTH > bytes.length) {
            throw new IllegalArgumentException("Packet does not fit encryption header");
        }
        System.arraycopy(bytes, 0, bytes, SEED_LENGTH, length);
        bytes[0] = (byte) (seed >>> 24);
        bytes[1] = (byte) (seed >>> 16);
        bytes[2] = (byte) (seed >>> 8);
        bytes[3] = (byte) seed;
        crypt(bytes, SEED_LENGTH, length, seed);
        return length + SEED_LENGTH;
    }

    public int decode(byte[] bytes, int length) {
        if (length < SEED_LENGTH || length > bytes.length) {
            throw new IllegalArgumentException("Packet is missing encryption header");
        }
        int seed = ((bytes[0] & 255) << 24) | ((bytes[1] & 255) << 16)
                | ((bytes[2] & 255) << 8) | (bytes[3] & 255);
        int payloadLength = length - SEED_LENGTH;
        crypt(bytes, SEED_LENGTH, payloadLength, seed);
        System.arraycopy(bytes, SEED_LENGTH, bytes, 0, payloadLength);
        return payloadLength;
    }

    private void crypt(byte[] bytes, int offset, int length, int seed) {
        for (int i = 0; i < length; i++) {
            bytes[offset + i] ^= prga[(int) ((Integer.toUnsignedLong(seed) + i) % prga.length)];
        }
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
