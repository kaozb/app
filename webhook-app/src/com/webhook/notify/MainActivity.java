package com.webhook.notify;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
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
    private static final int REQ_RING = 2;

    private EditText portEdit;
    private Switch toggle;
    private TextView status, urls, ringVal, battery, svcLabel;
    private View led;
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

        led = findViewById(R.id.led);
        status = findViewById(R.id.status);
        urls = findViewById(R.id.urls);
        portEdit = findViewById(R.id.port);
        toggle = findViewById(R.id.toggle);
        ringVal = findViewById(R.id.ring_val);
        battery = findViewById(R.id.battery);
        svcLabel = findViewById(R.id.svc_label);

        portEdit.setText(String.valueOf(Prefs.getPort(this)));
        ringVal.setText(ringTitle());

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
                    Prefs.setPort(MainActivity.this, port);
                    Intent i = new Intent(MainActivity.this, ServerService.class);
                    i.putExtra(ServerService.EXTRA_PORT, port);
                    startForegroundService(i);
                    maybeAskBattery();
                } else {
                    Prefs.setRunning(MainActivity.this, false);
                    stopService(new Intent(MainActivity.this, ServerService.class));
                    syncUi();
                }
            }
        });

        bindBool(R.id.sw_notify, "notify", true);
        bindBool(R.id.sw_sound, "sound", true);
        bindBool(R.id.sw_vibrate, "vibrate", true);
        bindBool(R.id.sw_boot, "boot", false);

        Switch swFgs = (Switch) findViewById(R.id.sw_fgs);
        swFgs.setChecked(Prefs.get(this, "fgsNotify", true));
        swFgs.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.set(MainActivity.this, "fgsNotify", on);
                ServerService.applyFgsSetting(MainActivity.this);
                if (!on) {
                    Toast.makeText(MainActivity.this,
                            "关闭常驻通知会显著降低后台存活率，建议保持开启",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        findViewById(R.id.clip_btn).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ClipboardActivity.class));
            }
        });

        Switch swTts = (Switch) findViewById(R.id.sw_tts);
        swTts.setChecked(Prefs.get(this, "tts", false));
        swTts.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.set(MainActivity.this, "tts", on);
                if (on) ServerService.testTts(MainActivity.this);
            }
        });

        bindFloat(R.id.sw_float_msg, "floatMsg");

        findViewById(R.id.ring_row).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
                    i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
                    i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
                    i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
                    String cur = Prefs.ringtone(MainActivity.this);
                    if (cur != null) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur));
                    startActivityForResult(i, REQ_RING);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "无法打开铃声选择器", Toast.LENGTH_SHORT).show();
                }
            }
        });

        findViewById(R.id.reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("重置服务")
                        .setMessage("将停止服务并清除全部设置与消息记录，且不可恢复。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                stopService(new Intent(MainActivity.this, ServerService.class));
                                Prefs.clear(MainActivity.this);
                                HistoryStore.clear(MainActivity.this);
                                getSystemService(NotificationManager.class).cancelAll();
                                ServerService.running = false;
                                ServerService.listenPort = -1;
                                ServerService.lastError = null;
                                recreate();
                            }
                        })
                        .show();
            }
        });

        battery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Toast.makeText(MainActivity.this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show();
                    return;
                }
                openBatterySettings();
            }
        });
    }

    /** 首次启动服务且未加入白名单时，引导一次 */
    private void maybeAskBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        if (Prefs.get(this, "batAsked", false)) return;
        Prefs.set(this, "batAsked", true);
        new AlertDialog.Builder(this)
                .setTitle("保持后台接收")
                .setMessage("系统可能在后台清理本应用，导致漏收消息。建议把 Webhook 加入电池优化白名单；"
                        + "部分手机还需在系统设置中允许「自启动」与「后台运行」。")
                .setNegativeButton("暂不", null)
                .setPositiveButton("去设置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        openBatterySettings();
                    }
                })
                .show();
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void bindBool(int id, final String key, boolean def) {
        Switch s = (Switch) findViewById(id);
        s.setChecked(Prefs.get(this, key, def));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                Prefs.set(MainActivity.this, key, on);
            }
        });
    }

    /** 悬浮类开关：开启前需先取得「显示在其他应用上层」权限 */
    private void bindFloat(int id, final String key) {
        final Switch s = (Switch) findViewById(id);
        s.setChecked(Prefs.get(this, key, false));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (on && !Settings.canDrawOverlays(MainActivity.this)) {
                    s.setChecked(false);
                    Toast.makeText(MainActivity.this, "请先允许「显示在其他应用上层」", Toast.LENGTH_LONG).show();
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {
                    }
                    return;
                }
                Prefs.set(MainActivity.this, key, on);
            }
        });
    }

    private String ringTitle() {
        String u = Prefs.ringtone(this);
        if (u == null) return "系统默认";
        try {
            Ringtone r = RingtoneManager.getRingtone(this, Uri.parse(u));
            if (r != null) return r.getTitle(this);
        } catch (Exception ignored) {
        }
        return "自定义";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_RING && data != null) {
            Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            Prefs.ringtone(this, uri != null ? uri.toString() : null);
            ringVal.setText(ringTitle());
            if (uri != null) {
                try {
                    Ringtone r = RingtoneManager.getRingtone(this, uri);
                    if (r != null) r.play();
                } catch (Exception ignored) {
                }
            }
        }
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
        boolean run = ServerService.running;
        if (toggle.isChecked() != run) toggle.setChecked(run);
        portEdit.setEnabled(!run);
        led.setBackgroundResource(run ? R.drawable.led : R.drawable.led_off);
        svcLabel.setText(run ? "停止服务" : "启动服务");
        if (run) {
            status.setText("服务运行中");
            status.setTextColor(getColor(R.color.accent_deep));
            urls.setText(localUrls(ServerService.listenPort));
        } else if (ServerService.lastError != null) {
            status.setText("启动失败");
            status.setTextColor(getColor(R.color.muted));
            urls.setText(ServerService.lastError);
        } else {
            status.setText("已停止");
            status.setTextColor(getColor(R.color.muted));
            urls.setText("");
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        battery.setText(pm.isIgnoringBatteryOptimizations(getPackageName()) ? "已忽略" : "去设置");
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
