package com.webhook.notify;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 1;

    private EditText portEdit;
    private Switch toggle;
    private TextView status;
    private final Handler handler = new Handler();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            syncUi();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        portEdit = findViewById(R.id.port);
        toggle = findViewById(R.id.toggle);
        status = findViewById(R.id.status);
        portEdit.setText(String.valueOf(ServerService.getPort(this)));

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (on) {
                    int port = parsePort();
                    if (port <= 0) {
                        Toast.makeText(MainActivity.this, "端口无效（1-65535）", Toast.LENGTH_SHORT).show();
                        toggle.setChecked(false);
                        return;
                    }
                    ServerService.savePort(MainActivity.this, port);
                    portEdit.setEnabled(false);
                    Intent i = new Intent(MainActivity.this, ServerService.class);
                    i.putExtra(ServerService.EXTRA_PORT, port);
                    startForegroundService(i);
                } else {
                    stopService(new Intent(MainActivity.this, ServerService.class));
                    syncUi();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncUi();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(poll);
    }

    private int parsePort() {
        try {
            int p = Integer.parseInt(portEdit.getText().toString().trim());
            return (p >= 1 && p <= 65535) ? p : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void syncUi() {
        boolean running = ServerService.running;
        if (toggle.isChecked() != running) toggle.setChecked(running);
        portEdit.setEnabled(!running);
        if (running) {
            status.setText("服务运行中 · 监听 0.0.0.0:" + ServerService.listenPort
                    + "\n" + localUrls(ServerService.listenPort));
        } else if (ServerService.lastError != null) {
            status.setText(ServerService.lastError);
        } else {
            status.setText(R.string.status_stopped);
        }
    }

    static String localUrls(int port) {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append("http://").append(a.getHostAddress()).append(':').append(port);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() > 0 ? sb.toString() : "http://<手机IP>:" + port;
    }
}
