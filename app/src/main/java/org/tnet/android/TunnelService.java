// SPDX-License-Identifier: GPL-2.0
package org.tnet.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class TunnelService extends VpnService {
    public static final String CONNECT = "org.tnet.android.CONNECT";
    public static final String DISCONNECT = "org.tnet.android.DISCONNECT";
    public static volatile boolean active, stopping;
    public static volatile String status = "Disconnected";
    public static volatile long sent, received, lastReceived;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private boolean destroyed;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && DISCONNECT.equals(intent.getAction())) {
            disconnect();
            return START_NOT_STICKY;
        }
        if (intent == null || !CONNECT.equals(intent.getAction())) {
            if (session == null) stopSelf();
            return START_NOT_STICKY;
        }
        if (session != null) return START_NOT_STICKY;
        if (active) { stopSelf(); return START_NOT_STICKY; }
        TunnelConfig config;
        try {
            config = new TunnelConfig(intent.getStringExtra("server"), intent.getStringExtra("port"),
                    intent.getStringExtra("address"), intent.getStringExtra("dns"), intent.getStringExtra("mtu"),
                    intent.getStringExtra("key"));
        } catch (RuntimeException e) {
            status = "Invalid settings: " + e.getMessage();
            stopSelf();
            return START_NOT_STICKY;
        }
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("tunnel", "VPN connection", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "tunnel")
                .setSmallIcon(org.tnet.android.R.drawable.ic_tunnel)
                .setContentTitle("Tnet VPN")
                .setContentText("IPv4 tunnel to " + config.server)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Disconnect",
                        PendingIntent.getService(this, 1, new Intent(this, TunnelService.class).setAction(DISCONNECT), PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, notification);
        active = true;
        stopping = false;
        sent = received = lastReceived = 0;
        status = "Starting tunnel…";
        session = new Session(config);
        new Thread(session::run, "tnet-session").start();
        return START_NOT_STICKY;
    }

    private void disconnect() {
        if (session != null) {
            stopping = true;
            status = "Stopping tunnel…";
            // Android keeps an established VPN service bound. Cancel the workers
            // so they close the TUN descriptor before we stop the service.
            session.cancel();
        } else {
            active = stopping = false;
            status = "Disconnected";
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Override public void onRevoke() {
        main.post(this::disconnect);
    }
    @Override public void onDestroy() {
        destroyed = true;
        if (session != null) {
            session.cancel();
            status = "Stopping tunnel…";
        }
        super.onDestroy();
    }

    private final class Session {
        final TunnelConfig config;
        final AtomicReference<Exception> failure = new AtomicReference<>();
        volatile boolean running = true;
        DatagramSocket socket;
        ParcelFileDescriptor tun;
        Session(TunnelConfig config) { this.config = config; }
        synchronized void cancel() {
            running = false;
            if (socket != null) socket.close();
        }
        void fail(Exception e) {
            if (running) failure.compareAndSet(null, e);
            cancel();
        }
        void run() {
            Thread tx = null, rx = null;
            try {
                synchronized (this) {
                    if (!running) return;
                    socket = new DatagramSocket(null);
                    if (!protect(socket)) throw new IOException("Cannot protect tunnel socket");
                    socket.bind(new InetSocketAddress(0));
                    socket.connect(InetAddress.getByName(config.server), config.port);
                    socket.setSoTimeout(500);
                }
                synchronized (this) {
                    if (!running) return;
                    tun = new Builder().setSession("Tnet · " + config.server)
                            .setMtu(config.mtu).addAddress(config.address, 32)
                            .addRoute("0.0.0.0", 0).addDnsServer(config.dns)
                            // IPv6 is blocked by VpnService when no IPv6 family is configured.
                            .setBlocking(false)
                            .setConfigureIntent(PendingIntent.getActivity(TunnelService.this, 0,
                                    new Intent(TunnelService.this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                            .establish();
                    if (tun == null) throw new IOException("VPN permission was revoked");
                }
                status = "Tunnel active · waiting for server traffic";
                tx = new Thread(this::sendPackets, "tnet-send");
                rx = new Thread(this::receivePackets, "tnet-receive");
                tx.start();
                rx.start();
                tx.join();
                rx.join();
            } catch (Exception e) {
                fail(e);
            } finally {
                cancel();
                // Poll/receive timeouts let both workers exit before the descriptor is closed.
                join(tx);
                join(rx);
                if (tun != null) try { tun.close(); } catch (IOException ignored) {}
                main.post(() -> {
                    active = stopping = false;
                    if (destroyed) { status = "Disconnected"; return; }
                    Exception error = failure.get();
                    status = error == null ? "Disconnected" : "Tunnel failed: " + error.getMessage();
                    session = null;
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                });
            }
        }
        void join(Thread thread) {
            if (thread == null) return;
            boolean interrupted = false;
            while (thread.isAlive()) {
                try { thread.join(); } catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        void sendPackets() {
            byte[] data = new byte[65535];
            PacketCodec codec = new PacketCodec(config.key, config.mtu);
            StructPollfd poll = new StructPollfd();
            poll.fd = tun.getFileDescriptor();
            poll.events = (short) OsConstants.POLLIN;
            try {
                while (running) {
                    if (Os.poll(new StructPollfd[]{poll}, 250) == 0) continue;
                    if (!running) break;
                    if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0)
                        throw new IOException("VPN interface closed");
                    int length;
                    try { length = Os.read(poll.fd, data, 0, data.length); }
                    catch (ErrnoException e) { if (e.errno == OsConstants.EAGAIN) continue; throw e; }
                    if (length <= 0) throw new IOException("VPN interface ended");
                    if (!PacketCodec.isIpv4(data, length) || length > config.mtu) continue;
                    codec.encode(data, length);
                    socket.send(new DatagramPacket(data, length));
                    sent += length;
                }
            } catch (Exception e) { fail(e); }
        }
        void receivePackets() {
            byte[] data = new byte[65535];
            PacketCodec codec = new PacketCodec(config.key, config.mtu);
            DatagramPacket packet = new DatagramPacket(data, data.length);
            try {
                while (running) {
                    packet.setLength(data.length);
                    try { socket.receive(packet); } catch (SocketTimeoutException ignored) { continue; }
                    int length = packet.getLength();
                    codec.decode(data, length);
                    if (length > config.mtu || !PacketCodec.isIpv4(data, length) || !PacketCodec.addressedTo(data, config.address)) continue;
                    try {
                        int written = Os.write(tun.getFileDescriptor(), data, 0, length);
                        if (written != length) throw new IOException("Incomplete TUN packet write");
                    } catch (ErrnoException e) {
                        if (e.errno == OsConstants.EAGAIN) continue;
                        throw e;
                    }
                    received += length;
                    lastReceived = android.os.SystemClock.elapsedRealtime();
                    status = "Tunnel active · receiving server traffic";
                }
            } catch (Exception e) { fail(e); }
        }
    }
}
