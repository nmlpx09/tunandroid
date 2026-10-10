// SPDX-License-Identifier: GPL-2.0
package org.tnet.android;

public final class TunnelConfig {
    public final String server, address, dns, key;
    public final int port, mtu;
    public TunnelConfig(String server, String port, String address, String dns, String mtu, String key) {
        this.server = ipv4(server, "Server");
        this.address = ipv4(address, "Tunnel address");
        this.dns = ipv4(dns, "DNS");
        this.port = number(port, 1, 65535, "Port");
        this.mtu = number(mtu, 576, 1472, "MTU");
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("Key must not be empty");
        this.key = key;
        if (this.address.equals(this.server)) throw new IllegalArgumentException("Tunnel address must differ from server");
    }
    private static int number(String text, int min, int max, String name) {
        try {
            int n = Integer.parseInt(text.trim());
            if (n >= min && n <= max) return n;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException(name + " must be " + min + "–" + max);
    }
    private static String ipv4(String text, String name) {
        String ip = text.trim();
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) throw new IllegalArgumentException(name + " must be a numeric IPv4 address");
        for (String part : parts) {
            if (!part.matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException(name + " must be a numeric IPv4 address");
            number(part, 0, 255, name + " octet");
        }
        int first = Integer.parseInt(parts[0]);
        if (first == 0 || first == 127 || first >= 224 || ip.equals("255.255.255.255"))
            throw new IllegalArgumentException(name + " must be a unicast IPv4 address");
        return ip;
    }
}
