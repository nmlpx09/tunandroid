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
import android.text.method.PasswordTransformationMethod;
import android.util.Base64;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PASSWORD_SALT = "appPasswordSalt";
    private static final String PASSWORD_HASH = "appPasswordHash";
    private static volatile boolean unlocked;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText server, port, address, dns, mtu, key;
    private TextView status, traffic;
    private Button toggle;
    private CheckBox showKey;
    private SharedPreferences prefs;
    private TunnelConfig pending;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            status.setText(TunnelService.status);
            traffic.setText(String.format(Locale.US, "Sent %.1f KB    •    Received %.1f KB", TunnelService.sent / 1024.0, TunnelService.received / 1024.0));
            toggle.setText(TunnelService.active ? R.string.disconnect : R.string.connect);
            toggle.setEnabled(!TunnelService.stopping);
            for (EditText field : new EditText[]{server, port, address, dns, mtu, key}) field.setEnabled(!TunnelService.active);
            showKey.setEnabled(!TunnelService.active);
            handler.postDelayed(this, 750);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("profile", MODE_PRIVATE);
        if (unlocked) showMain(saved);
        else if (prefs.contains(PASSWORD_HASH)) showUnlock();
        else showPasswordSetup();
    }
    private void showMain(Bundle saved) {
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
        mtu = field(layout, "MTU", "mtu", "1468", true);
        key = field(layout, "Key", "key", "", false);
        key.setTransformationMethod(PasswordTransformationMethod.getInstance());
        showKey = new CheckBox(this);
        showKey.setText("Show key");
        showKey.setTextColor(getColor(R.color.text_secondary));
        showKey.setOnCheckedChangeListener((button, checked) -> {
            key.setTransformationMethod(checked ? null : PasswordTransformationMethod.getInstance());
            key.setSelection(key.length());
        });
        layout.addView(showKey);
        if (saved != null) {
            EditText[] fields = {server, port, address, dns, mtu, key};
            for (int i = 0; i < fields.length; i++) fields[i].setText(saved.getString("field" + i, value(fields[i])));
            showKey.setChecked(saved.getBoolean("showKey", false));
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
                pending = new TunnelConfig(value(server), value(port), value(address), value(dns), value(mtu), value(key));
            } catch (IllegalArgumentException e) {
                new AlertDialog.Builder(this).setTitle("Check settings").setMessage(e.getMessage()).setPositiveButton("OK", null).show();
                return;
            }
            if (!prefs.getBoolean("obfuscationAccepted", false)) {
                new AlertDialog.Builder(this).setTitle("About this tunnel")
                        .setMessage("Tundriver RC4 reuses the same keystream for every packet. It does not authenticate your tunnel traffic securely. Use HTTPS for sensitive data.")
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
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }
    private void showPasswordSetup() {
        ScrollView scroll = authScroll();
        LinearLayout layout = (LinearLayout) scroll.getChildAt(0);
        label(layout, "Set app password", 18, getColor(R.color.accent));
        EditText password = passwordField(layout, "Password");
        EditText repeat = passwordField(layout, "Repeat password");
        Button save = new Button(this);
        save.setText("Save");
        layout.addView(save);
        save.setOnClickListener(v -> {
            String first = value(password);
            if (first.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("Check password").setMessage("Password must not be empty").setPositiveButton("OK", null).show();
                return;
            }
            if (!first.equals(value(repeat))) {
                new AlertDialog.Builder(this).setTitle("Check password").setMessage("Passwords do not match").setPositiveButton("OK", null).show();
                return;
            }
            String salt = randomSalt();
            prefs.edit().putString(PASSWORD_SALT, salt).putString(PASSWORD_HASH, passwordHash(salt, first)).apply();
            unlocked = true;
            showMain(null);
        });
        setContentView(scroll);
    }
    private void showUnlock() {
        ScrollView scroll = authScroll();
        LinearLayout layout = (LinearLayout) scroll.getChildAt(0);
        label(layout, "Enter password", 18, getColor(R.color.accent));
        EditText password = passwordField(layout, "Password");
        Button unlock = new Button(this);
        unlock.setText("Unlock");
        layout.addView(unlock);
        unlock.setOnClickListener(v -> {
            String salt = prefs.getString(PASSWORD_SALT, "");
            String expected = prefs.getString(PASSWORD_HASH, "");
            if (expected.equals(passwordHash(salt, value(password)))) {
                unlocked = true;
                showMain(null);
            } else {
                new AlertDialog.Builder(this).setTitle("Check password").setMessage("Wrong password").setPositiveButton("OK", null).show();
            }
        });
        setContentView(scroll);
    }
    private ScrollView authScroll() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(30), dp(24), dp(30));
        scroll.addView(layout);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        return scroll;
    }
    private EditText passwordField(LinearLayout layout, String title) {
        label(layout, title, 14, getColor(R.color.text_secondary));
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        view.setTransformationMethod(PasswordTransformationMethod.getInstance());
        layout.addView(view, new LinearLayout.LayoutParams(-1, dp(52)));
        return view;
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
                pending = new TunnelConfig(value(server), value(port), value(address), value(dns), value(mtu), value(key));
                startTunnel();
            } catch (IllegalArgumentException e) { status.setText(e.getMessage()); }
        }
    }
    private void startTunnel() {
        if (pending == null || TunnelService.active) return;
        TunnelConfig config = pending;
        prefs.edit().putString("server", config.server).putString("port", "" + config.port)
                .putString("address", config.address).putString("dns", config.dns)
                .putString("mtu", "" + config.mtu).putString("key", config.key).apply();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        startForegroundService(new Intent(this, TunnelService.class).setAction(TunnelService.CONNECT)
                .putExtra("server", config.server).putExtra("port", "" + config.port)
                .putExtra("address", config.address).putExtra("dns", config.dns)
                .putExtra("mtu", "" + config.mtu).putExtra("key", config.key));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (server == null) return;
        EditText[] fields = {server, port, address, dns, mtu, key};
        for (int i = 0; i < fields.length; i++) state.putString("field" + i, value(fields[i]));
        state.putBoolean("showKey", showKey.isChecked());
    }
    @Override protected void onResume() {
        super.onResume();
        if (toggle != null) {
            handler.removeCallbacks(refresh);
            handler.post(refresh);
        }
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private String randomSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.encodeToString(salt, Base64.NO_WRAP);
    }
    private String passwordHash(String salt, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return Base64.encodeToString(digest.digest(password.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
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
