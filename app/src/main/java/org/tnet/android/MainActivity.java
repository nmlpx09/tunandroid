// SPDX-License-Identifier: GPL-2.0
package org.tnet.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText server, port, address, dns, mtu;
    private TextView status, traffic;
    private Button toggle;
    private SharedPreferences prefs;
    private TunnelConfig pending;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(TunnelService.status);
            traffic.setText(String.format(Locale.US, "Sent %.1f KB    •    Received %.1f KB", TunnelService.sent / 1024.0, TunnelService.received / 1024.0));
            toggle.setText(TunnelService.active ? R.string.disconnect : R.string.connect);
            toggle.setEnabled(!TunnelService.stopping);
            for (EditText field : new EditText[]{server, port, address, dns, mtu}) field.setEnabled(!TunnelService.active);
            handler.postDelayed(this, 750);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("profile", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(30), dp(24), dp(30));
        scroll.addView(layout);
        status = label(layout, TunnelService.status, 18, getColor(R.color.accent));
        traffic = label(layout, "", 13, getColor(R.color.text_secondary));
        server = field(layout, "Server IPv4 address", "server", "", false);
        server.setHint("e.g. 203.0.113.10");
        port = field(layout, "UDP port", "port", "69", true);
        address = field(layout, "Phone tunnel IPv4 address", "address", "10.0.3.2", false);
        dns = field(layout, "DNS IPv4 address", "dns", "1.1.1.1", false);
        mtu = field(layout, "MTU", "mtu", "1472", true);
        if (saved != null) {
            EditText[] fields = {server, port, address, dns, mtu};
            for (int i = 0; i < fields.length; i++) fields[i].setText(saved.getString("field" + i, value(fields[i])));
        }
        toggle = new Button(this);
        toggle.setText(R.string.connect);
        layout.addView(toggle);
        toggle.setOnClickListener(v -> {
            if (TunnelService.active) {
                startService(new Intent(this, TunnelService.class).setAction(TunnelService.DISCONNECT));
                return;
            }
            try {
                pending = new TunnelConfig(value(server), value(port), value(address), value(dns), value(mtu));
            } catch (IllegalArgumentException e) {
                new AlertDialog.Builder(this).setTitle("Check settings").setMessage(e.getMessage()).setPositiveButton("OK", null).show();
                return;
            }
            if (!prefs.getBoolean("obfuscationAccepted", false)) {
                new AlertDialog.Builder(this).setTitle("About this tunnel")
                        .setMessage("Tundriver uses fixed byte substitution. It does not encrypt or authenticate your tunnel traffic securely. Use HTTPS for sensitive data.")
                        .setPositiveButton("Continue", (dialog, which) -> {
                            prefs.edit().putBoolean("obfuscationAccepted", true).apply();
                            prepareVpn();
                        }).setNegativeButton("Cancel", null).show();
            } else prepareVpn();
        });
        setContentView(scroll);
        // Keep content clear of Android 15 system bars when targeting API 35.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
    }
    private void prepareVpn() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) startActivityForResult(consent, 1);
        else startTunnel();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK) {
            try {
                pending = new TunnelConfig(value(server), value(port), value(address), value(dns), value(mtu));
                startTunnel();
            } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
        }
    }
    private void startTunnel() {
        if (pending == null || TunnelService.active) return;
        TunnelConfig config = pending;
        prefs.edit().putString("server", config.server).putString("port", "" + config.port)
                .putString("address", config.address).putString("dns", config.dns).putString("mtu", "" + config.mtu).apply();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        startForegroundService(new Intent(this, TunnelService.class).setAction(TunnelService.CONNECT)
                .putExtra("server", config.server).putExtra("port", "" + config.port)
                .putExtra("address", config.address).putExtra("dns", config.dns).putExtra("mtu", "" + config.mtu));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        EditText[] fields = {server, port, address, dns, mtu};
        for (int i = 0; i < fields.length; i++) state.putString("field" + i, value(fields[i]));
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private String value(EditText field) { return field.getText().toString(); }
    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout layout, String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setPadding(0, dp(8), 0, dp(8));
        layout.addView(view);
        return view;
    }
    private EditText field(LinearLayout layout, String title, String key, String fallback, boolean numeric) {
        label(layout, title, 14, getColor(R.color.text_secondary));
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        view.setText(prefs.getString(key, fallback));
        layout.addView(view, new LinearLayout.LayoutParams(-1, dp(52)));
        return view;
    }
}
