package com.webhook.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

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
import java.util.concurrent.atomic.AtomicInteger;

public class ServerService extends Service {

    static final String EXTRA_PORT = "port";
    private static final String[] MSG_KEYS = {"text", "message", "content", "body", "msg"};

    static volatile boolean running = false;
    static volatile int listenPort = -1;
    static volatile String lastError = null;

    private static final String CH_SERVICE = "service";
    private static final int FGS_ID = 1;

    private static volatile ServerService instance;

    private ServerThread thread;
    private int nextNotifyId = 2;
    private FloatingManager floating;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private String pendingSpeak;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Ringtone lastRing;
    private String curMsgChannel;

    /** 省电模式：朗读闲置 60 秒后释放引擎，下次用到再初始化 */
    private final Runnable ttsRelease = new Runnable() {
        @Override
        public void run() {
            shutdownTts();
        }
    };

    /** 2 秒内的消息合并成一次提醒，避免高频推送反复响铃 / 刷通知 */
    private final List<String> batch = new ArrayList<String>();
    private final Runnable flushBatch = new Runnable() {
        @Override
        public void run() {
            flush();
        }
    };

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
        initTts();
    }

    /** 懒初始化 TTS；省电模式下会被释放，下次朗读时再创建 */
    private void initTts() {
        if (tts != null) return;
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    try {
                        int r = tts.setLanguage(Locale.CHINA);
                        if (r < TextToSpeech.LANG_AVAILABLE) tts.setLanguage(Locale.US);
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build());
                        ttsReady = true;
                    } catch (Exception ignored) {
                    }
                    if (pendingSpeak != null) {
                        String s = pendingSpeak;
                        pendingSpeak = null;
                        speakNow(s);
                    }
                } else {
                    ttsReady = false;
                    if (tts != null) {
                        try {
                            tts.shutdown();
                        } catch (Exception ignored) {
                        }
                        tts = null;
                    }
                    pendingSpeak = null;
                    // 仅在用户主动使用语音朗读时提示，避免服务启动时打扰
                    if (Prefs.get(ServerService.this, "tts", false)) {
                        Toast.makeText(ServerService.this,
                                "本机未找到语音引擎（TTS）：请在系统设置中安装或启用「文字转语音」",
                                Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
    }

    private void speakNow(String text) {
        if (!ttsReady || tts == null) {
            pendingSpeak = text;
            return;
        }
        try {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null,
                    "webhook_" + System.currentTimeMillis());
        } catch (Exception ignored) {
        }
    }

    /** 朗读一段文本；省电模式会在闲置 60 秒后释放引擎 */
    private void speak(String text) {
        pendingSpeak = text;
        if (tts == null) initTts();
        if (ttsReady) {
            pendingSpeak = null;
            speakNow(text);
        }
        if (Prefs.get(this, "power", true)) {
            ui.removeCallbacks(ttsRelease);
            ui.postDelayed(ttsRelease, 60000);
        } else {
            ui.removeCallbacks(ttsRelease);
        }
    }

    private void shutdownTts() {
        if (tts == null) return;
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception ignored) {
        }
        tts = null;
        ttsReady = false;
        pendingSpeak = null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int port = intent != null ? intent.getIntExtra(EXTRA_PORT, Prefs.getPort(this)) : Prefs.getPort(this);
        ensureMsgChannel();
        startForeground(FGS_ID, buildServiceNotification(port));
        if (!Prefs.get(this, "fgsNotify", true)) {
            // 用户关闭「常驻通知」：先满足前台服务要求，再移除通知
            stopForeground(STOP_FOREGROUND_REMOVE);
        }
        synchronized (this) {
            if (thread == null) {
                startServer(port);
            } else if (port != listenPort) {
                thread.shutdown();
                startServer(port);
            }
        }
        Prefs.setRunning(this, true);
        if (Prefs.get(this, "keepAlive", true)) BootReceiver.scheduleWatchdog(this);
        return START_STICKY;
    }

    /** 用户从最近任务划掉时，延时尝试重新拉起 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (Prefs.get(this, "keepAlive", true)) BootReceiver.scheduleRestart(this, 2000);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (thread != null) thread.shutdown();
        thread = null;
        running = false;
        listenPort = -1;
        if (floating != null) floating.shutdown();
        stopRing();
        ui.removeCallbacks(ttsRelease);
        ui.removeCallbacks(flushBatch);
        shutdownTts();
        instance = null;
        // 不在这里清 running 标记：被系统回收时保留，看门狗才能自动拉起
        super.onDestroy();
    }

    /** 切换「常驻通知」开关时立即生效 */
    static void applyFgsSetting(Context c) {
        ServerService s = instance;
        if (s == null) return;
        try {
            if (Prefs.get(c, "fgsNotify", true)) {
                int port = s.listenPort > 0 ? s.listenPort : Prefs.getPort(c);
                s.startForeground(FGS_ID, s.buildServiceNotification(port));
            } else {
                s.stopForeground(STOP_FOREGROUND_REMOVE);
            }
        } catch (Exception ignored) {
        }
    }

    /** 打开语音朗读开关时立即试读一句，便于确认 TTS 可用 */
    static void testTts(Context c) {
        ServerService s = instance;
        if (s == null) {
            Toast.makeText(c, "请先开启 Webhook 服务，再试语音朗读", Toast.LENGTH_SHORT).show();
            return;
        }
        // 引擎缺失的提示在初始化回调里处理，这里直接尝试朗读
        s.speak("语音朗读已开启");
    }

    /** 自检：发一条测试消息，走完整提醒链路但不写入历史 */
    static void testMessage(Context c) {
        ServerService s = instance;
        if (s == null) {
            Toast.makeText(c, "请先开启 Webhook 服务", Toast.LENGTH_SHORT).show();
            return;
        }
        s.deliver("Webhook 测试", "这是一条测试消息", "这是一条测试消息");
        Toast.makeText(c, "已触发测试提醒（不写入历史）", Toast.LENGTH_SHORT).show();
    }

    /** 自主播放提示铃声：不依赖通知渠道（渠道的声音设置一旦创建即被系统锁定） */
    private void playAlert() {
        boolean sound = Prefs.get(this, "sound", true);
        boolean vib = Prefs.get(this, "vibrate", true);
        if (sound) {
            try {
                String custom = Prefs.ringtone(this);
                Uri uri = custom != null ? Uri.parse(custom)
                        : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                Ringtone r = RingtoneManager.getRingtone(this.getApplicationContext(), uri);
                if (r != null) {
                    r.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                    stopRing();
                    lastRing = r;
                    r.play();
                }
            } catch (Exception ignored) {
            }
        }
        if (vib) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    v.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void stopRing() {
        if (lastRing != null) {
            try {
                if (lastRing.isPlaying()) lastRing.stop();
            } catch (Exception ignored) {
            }
            lastRing = null;
        }
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

    /** 消息通知渠道：声音与震动由本应用自行播放，渠道固定静音，避免被系统缓存影响 */
    private void ensureMsgChannel() {
        if (curMsgChannel != null) return;
        String id = "msg_alert";
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(id, "Webhook 消息", NotificationManager.IMPORTANCE_DEFAULT);
        ch.enableVibration(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
        curMsgChannel = id;
    }

    private class ServerThread extends Thread {
        /** 并发连接上限：防止端口被扫描或异常流量打出一堆线程 */
        private static final int MAX_CONN = 8;
        private final int port;
        private final AtomicInteger active = new AtomicInteger();
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
                    final Socket s = ss.accept();
                    if (active.incrementAndGet() > MAX_CONN) {
                        active.decrementAndGet();
                        reject(s);
                        continue;
                    }
                    try {
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    handle(s);
                                } finally {
                                    active.decrementAndGet();
                                }
                            }
                        }).start();
                    } catch (Exception e) {
                        active.decrementAndGet();
                        try {
                            s.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception e) {
                if (!stoppedByRequest) {
                    lastError = "启动失败: " + e.getMessage() + "（端口 " + port + " 可能被占用）";
                    if (thread == this) {
                        running = false;
                        listenPort = -1;
                        // 端口被占用属于无法自愈的错误，停止看门狗重试
                        Prefs.setRunning(ServerService.this, false);
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

        /** 并发已满：直接返回 503，避免积压线程 */
        private void reject(Socket s) {
            try {
                respond(s.getOutputStream(), 503, "服务繁忙，请稍后重试", false);
            } catch (Exception ignored) {
            } finally {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(5000);
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
            String raw = off > 0 ? new String(body, 0, off, StandardCharsets.UTF_8) : "";

            // 任意路径、任意方法都接受；有 body 用 body，否则用查询参数
            String msg = extractText(raw.length() > 0 ? raw : query);
            if (msg == null) {
                respond(out, 400, "缺少 text 字段，请发送 {\"text\":\"内容\"}", "HEAD".equals(method));
            } else {
                onMessage(msg);
                respond(out, 200, "success", "HEAD".equals(method));
            }
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** 统一响应格式：{"text":"..."} */
    private static void respond(OutputStream out, int code, String text, boolean headOnly) throws Exception {
        byte[] b = ("{\"text\":" + JSONObject.quote(text) + "}").getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? " OK" : (code == 503 ? " Service Unavailable" : " Bad Request");
        String head = "HTTP/1.1 " + code + reason
                + "\r\nContent-Type: application/json; charset=utf-8"
                + "\r\nContent-Length: " + (headOnly ? 0 : b.length)
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        if (!headOnly) out.write(b);
        out.flush();
    }

    /** 从 JSON / 表单 / 纯文本中取出 text，取不到返回 null */
    private static String extractText(String content) {
        String s = content == null ? "" : content.trim();
        if (s.length() == 0) return null;
        if (s.startsWith("{")) {
            try {
                JSONObject o = new JSONObject(s);
                for (String k : MSG_KEYS) {
                    String v = o.optString(k, "");
                    if (v.length() > 0) return v;
                }
            } catch (Exception ignored) {
            }
            return null;
        }
        if (s.indexOf('=') >= 0) {
            String v = pickParam(s, MSG_KEYS);
            return v != null && v.length() > 0 ? v : null;
        }
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
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

    /** 收到一条消息：先落盘，再进入 2 秒合并窗口 */
    private void onMessage(String msg) {
        String text = msg == null || msg.trim().length() == 0 ? "(空消息)" : msg;
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        HistoryStore.append(this, "Webhook", text, time);

        boolean startTimer;
        synchronized (batch) {
            startTimer = batch.isEmpty();
            if (batch.size() < 100) batch.add(text);
        }
        if (startTimer) ui.postDelayed(flushBatch, 2000);
    }

    /** 合并窗口结束：单条照旧，多条合并成一条提醒 */
    private void flush() {
        List<String> items;
        synchronized (batch) {
            items = new ArrayList<String>(batch);
            batch.clear();
        }
        if (items.isEmpty()) return;

        if (items.size() == 1) {
            deliver("Webhook", items.get(0), items.get(0));
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("• ").append(s);
        }
        deliver("Webhook · " + items.size() + " 条消息",
                items.get(0) + " …等 " + items.size() + " 条", sb.toString());
    }

    private void deliver(String title, String body, String big) {
        ensureMsgChannel();
        playAlert();
        if (Prefs.get(this, "notify", true)) {
            Notification n = new Notification.Builder(this, curMsgChannel)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new Notification.BigTextStyle().bigText(big))
                    .setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(this, 0,
                            new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                    .build();
            getSystemService(NotificationManager.class).notify(nextNotifyId++, n);
        }

        if (floating != null) {
            String f = big.length() > 160 ? big.substring(0, 160) + "…" : big;
            floating.showMessage(title, f);
        }

        if (Prefs.get(this, "tts", false)) {
            String t = body.length() > 120 ? body.substring(0, 120) : body;
            speak(title + "。" + t);
        }
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
