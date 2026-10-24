package com.webhook.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ServerService extends Service {

    static final String EXTRA_PORT = "port";
    private static final String[] TITLE_KEYS = {"title", "subject"};
    private static final String[] MSG_KEYS = {"text", "message", "content", "body", "msg"};

    static volatile boolean running = false;
    static volatile int listenPort = -1;
    static volatile String lastError = null;

    /** 最近消息，元素为 {title, text, time}，供悬浮球面板展示 */
    static final List<String[]> history = new ArrayList<String[]>();

    private static final String CH_SERVICE = "service";
    private static final int FGS_ID = 1;

    private static volatile ServerService instance;

    private ServerThread thread;
    private int nextNotifyId = 2;
    private FloatingManager floating;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private String curMsgChannel;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_SERVICE, "服务状态", NotificationManager.IMPORTANCE_MIN));
        ensureMsgChannel();
        floating = new FloatingManager(this);
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    try {
                        int r = tts.setLanguage(Locale.CHINA);
                        if (r < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US);
                        ttsReady = true;
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int port = intent != null ? intent.getIntExtra(EXTRA_PORT, Prefs.getPort(this)) : Prefs.getPort(this);
        ensureMsgChannel();
        startForeground(FGS_ID, buildServiceNotification(port));
        synchronized (this) {
            if (thread == null) {
                startServer(port);
            } else if (port != listenPort) {
                thread.shutdown();
                startServer(port);
            }
        }
        if (floating != null) floating.setBallEnabled(Prefs.get(this, "floatBall", false));
        Prefs.setRunning(this, true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (thread != null) thread.shutdown();
        thread = null;
        running = false;
        listenPort = -1;
        if (floating != null) floating.shutdown();
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
            ttsReady = false;
        }
        instance = null;
        Prefs.setRunning(this, false);
        super.onDestroy();
    }

    /** MainActivity 切换悬浮球开关时同步到正在运行的服务 */
    static void applyBallSetting(Context c) {
        ServerService s = instance;
        if (s != null && s.floating != null) s.floating.setBallEnabled(Prefs.get(c, "floatBall", false));
    }

    private void startServer(int port) {
        lastError = null;
        thread = new ServerThread(port);
        thread.start();
    }

    private Notification buildServiceNotification(int port) {
        return new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Webhook 服务运行中")
                .setContentText("监听 0.0.0.0:" + port)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build();
    }

    /** 消息通知渠道：ID 随「声音/铃声/震动」设置变化，避免渠道设置被系统缓存 */
    private String msgChannelId() {
        boolean sound = Prefs.get(this, "sound", true);
        boolean vib = Prefs.get(this, "vibrate", true);
        String r = sound ? Prefs.ringtone(this) : null;
        return "msg_" + (sound ? (r == null ? "def" : Integer.toHexString(r.hashCode())) : "silent") + (vib ? "v" : "");
    }

    private void ensureMsgChannel() {
        String id = msgChannelId();
        if (id.equals(curMsgChannel)) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        for (NotificationChannel c : nm.getNotificationChannels()) {
            if (c.getId().startsWith("msg_") && !c.getId().equals(id)) nm.deleteNotificationChannel(c.getId());
        }
        NotificationChannel ch = new NotificationChannel(id, "Webhook 消息", NotificationManager.IMPORTANCE_DEFAULT);
        ch.enableVibration(Prefs.get(this, "vibrate", true));
        String r = Prefs.get(this, "sound", true) ? Prefs.ringtone(this) : null;
        if (r != null) {
            ch.setSound(Uri.parse(r), new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        } else {
            ch.setSound(null, null);
        }
        nm.createNotificationChannel(ch);
        curMsgChannel = id;
    }

    private class ServerThread extends Thread {
        private final int port;
        private volatile boolean stoppedByRequest = false;
        private volatile ServerSocket ss;

        ServerThread(int port) {
            this.port = port;
        }

        @Override
        public void run() {
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
                listenPort = port;
                running = true;
                while (!ss.isClosed()) {
                    Socket s = ss.accept();
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handle(s);
                        }
                    }).start();
                }
            } catch (Exception e) {
                if (!stoppedByRequest) {
                    lastError = "启动失败: " + e.getMessage() + "（端口 " + port + " 可能被占用）";
                    if (thread == this) {
                        running = false;
                        listenPort = -1;
                        stopSelf();
                    }
                }
            } finally {
                try {
                    if (ss != null) ss.close();
                } catch (Exception ignored) {
                }
            }
        }

        void shutdown() {
            stoppedByRequest = true;
            try {
                if (ss != null) ss.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(8000);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();

            String req = readLine(in);
            if (req == null || req.length() == 0) return;
            int sp = req.indexOf(' ');
            String method = sp > 0 ? req.substring(0, sp) : "GET";
            int end = req.lastIndexOf(' ');
            String target = end > sp ? req.substring(sp + 1, end) : req.substring(sp + 1);
            String query = "";
            int qm = target.indexOf('?');
            if (qm >= 0) query = target.substring(qm + 1);

            int len = 0;
            boolean expect = false;
            String h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                String hl = h.toLowerCase();
                if (hl.startsWith("content-length:")) {
                    try {
                        len = Integer.parseInt(hl.substring(15).trim());
                    } catch (Exception ignored) {
                    }
                } else if (hl.startsWith("expect:")) {
                    expect = hl.contains("100-continue");
                }
            }
            if (len > 1048576) len = 1048576;
            if (len < 0) len = 0;

            OutputStream out = s.getOutputStream();
            if (expect) out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.UTF_8));

            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(body, off, len - off);
                if (n < 0) break;
                off += n;
            }
            String text = off > 0 ? new String(body, 0, off, StandardCharsets.UTF_8) : "";

            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: 2\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            if (!"HEAD".equals(method)) out.write("ok".getBytes(StandardCharsets.UTF_8));
            out.flush();

            String content = text.length() > 0 ? text : query;
            if (content.length() > 0 || !("GET".equals(method) || "HEAD".equals(method))) {
                notifyMessage(content);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(80);
        int c = -1;
        while ((c = in.read()) != -1 && c != '\n') buf.write(c);
        if (c == -1 && buf.size() == 0) return null;
        String line = new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        return line;
    }

    private synchronized void notifyMessage(String raw) {
        String title = "Webhook";
        String msg = raw == null ? "" : raw.trim();
        if (msg.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(msg);
                title = firstOf(o, TITLE_KEYS, title);
                msg = firstOf(o, MSG_KEYS, msg);
            } catch (Exception ignored) {
            }
        } else if (msg.indexOf('=') >= 0) {
            String t = pickParam(msg, TITLE_KEYS);
            String m = pickParam(msg, MSG_KEYS);
            if (t != null) title = t;
            if (m != null) {
                msg = m;
            } else if (t == null) {
                try {
                    msg = URLDecoder.decode(msg, "UTF-8");
                } catch (Exception ignored) {
                }
            }
        } else if (msg.indexOf('%') >= 0) {
            try {
                msg = URLDecoder.decode(msg, "UTF-8");
            } catch (Exception ignored) {
            }
        }
        if (msg.length() == 0) msg = "(空消息)";

        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        synchronized (history) {
            history.add(new String[]{title, msg, time});
            while (history.size() > 20) history.remove(0);
        }

        ensureMsgChannel();
        if (Prefs.get(this, "notify", true)) {
            Notification n = new Notification.Builder(this, curMsgChannel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(msg)
                    .setStyle(new Notification.BigTextStyle().bigText(msg))
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(this, 0,
                            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                    .build();
            getSystemService(NotificationManager.class).notify(nextNotifyId++, n);
        }

        if (floating != null) floating.showMessage(title, msg);

        if (ttsReady && tts != null && Prefs.get(this, "tts", false)) {
            try {
                tts.speak(title + "。" + msg, TextToSpeech.QUEUE_ADD, null,
                        "webhook_" + System.currentTimeMillis());
            } catch (Exception ignored) {
            }
        }
    }

    private static String firstOf(JSONObject o, String[] keys, String def) {
        for (String k : keys) if (o.has(k)) return o.optString(k);
        return def;
    }

    private static String pickParam(String s, String[] keys) {
        for (String pair : s.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                for (String key : keys) {
                    if (key.equals(k)) return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
