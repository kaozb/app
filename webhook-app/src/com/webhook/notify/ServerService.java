package com.webhook.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

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
    static final String ACTION_ACK = "com.webhook.notify.action.ACK";
    private static final String[] MSG_KEYS = {"text", "message", "content", "body", "msg"};

    /** 持续响铃：单次最长 60 秒，结束后 10 秒冷却 */
    private static final long RING_DURATION_MS = 60000L;
    private static final long RING_COOLDOWN_MS = 10000L;
    private static final int RING_NOTIFY_ID = 100;

    static volatile boolean running = false;
    static volatile int listenPort = -1;
    static volatile String lastError = null;

    private static final String CH_SERVICE = "service";
    private static final int FGS_ID = 1;

    private static volatile ServerService instance;

    private ServerThread thread;
    private int nextNotifyId = 2;
    private FloatingManager floating;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Ringtone lastRing;
    private String curMsgChannel;

    /** 锁屏保活：息屏后 CPU 休眠、WiFi 进省电模式，入站推送会被系统吞掉 */
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    /** 强力保活：0 音量静音音频，让系统把本进程当作正在播放音乐的应用 */
    private SilentAudio silent;

    /** 持续响铃状态 */
    private final Object ringLock = new Object();
    private MediaPlayer ringPlayer;
    private Vibrator ringVib;
    private volatile boolean ringing = false;
    private long ringStartAt = 0;
    private long lastRingEndAt = 0;
    private final Runnable ringTimeout = new Runnable() {
        @Override
        public void run() {
            stopRingContinuous();
        }
    };
    /** 亮屏/解锁时校准响铃时长；息屏时重申前台状态，尽量维持保活等级 */
    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                // 部分 ROM 息屏后会把前台服务降级为普通服务，随后清理进程；
                // 这里在息屏瞬间重申一次前台状态，尽量保住进程优先级
                reassertForeground();
                return;
            }
            syncRingClock();
            // 亮屏/解锁后网络恢复，补发息屏期间 Doze 下没发出去的短信
            SmsOutbox.flushAsync(context);
            reassertForeground();
            // 醒来后按当前开关重新对齐保活，避免休眠期间闹钟被整体顺延
            KeepAlive.sync(context);
        }
    };

    /** 重新声明前台服务（息屏/亮屏时调用），保持进程优先级 */
    private void reassertForeground() {
        if (!Prefs.get(this, "fgsNotify", true)) return;
        try {
            startForeground(FGS_ID, buildServiceNotification(listenPort > 0 ? listenPort : Prefs.getPort(this)));
        } catch (Exception ignored) {
        }
    }

    /** 2 秒内的消息合并成一次提醒，避免高频推送反复响铃 / 刷通知 */
    private final List<String> batch = new ArrayList<String>();
    private long lastFlushAt = 0;
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
        // 用 LOW 而非 MIN：部分 ROM（尤其华为/鸿蒙）会隐藏 MIN 级通道，
        // 前台服务通知一旦不可见，进程会被判定为后台而清理
        nm.createNotificationChannel(new NotificationChannel(CH_SERVICE, "音乐播放", NotificationManager.IMPORTANCE_LOW));
        ensureMsgChannel();
        floating = new FloatingManager(this);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenOn, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 通知栏「已确认」：停止持续响铃（服务此时仍在运行）
        if (intent != null && ACTION_ACK.equals(intent.getAction())) {
            acknowledgeRing();
            return START_STICKY;
        }
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
        // 持有 CPU/WiFi 锁，保证锁屏状态下推送能立刻到达
        acquireKeepAwake();
        // 统一门控：只有短信转发或启动服务开启时才保活；强力保活时由 sync 启动静音音频
        KeepAlive.sync(this);
        return START_STICKY;
    }

    /** 用户从最近任务划掉时，延时尝试重新拉起 */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (KeepAlive.needed(this)) BootReceiver.scheduleRestart(this, 2000);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (thread != null) thread.shutdown();
        thread = null;
        running = false;
        listenPort = -1;
        stopSilent();
        releaseKeepAwake();
        if (floating != null) floating.shutdown();
        stopRing();
        stopRingContinuous();
        try {
            unregisterReceiver(screenOn);
        } catch (Exception ignored) {
        }
        ui.removeCallbacks(flushBatch);
        instance = null;
        // 不在这里清 running 标记：被系统回收时保留，看门狗才能自动拉起
        // 但要让保活组件随服务状态重新对齐（短信转发开启时仍保留静音保活）
        KeepAlive.sync(this);
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

    /**
     * 服务运行期间持有 CPU 唤醒锁与 WiFi 锁：锁屏后系统会让 CPU 休眠、WiFi 进省电模式，
     * 入站连接会被吞掉或延迟到亮屏，这是「推送到端口没反应」的直接原因。
     * 只有短信转发或启动服务开启时服务才会运行，因此不会在不需要时耗电。
     */
    private void acquireKeepAwake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "music:playback");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wifiLock == null) {
                int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                // 高性能模式且系统支持（Android 10+）时用低延迟档，锁屏推送更快到达
                if (Build.VERSION.SDK_INT >= 29 && !Prefs.get(this, "power", true)) {
                    mode = WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
                }
                wifiLock = wm.createWifiLock(mode, "music:playback");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseKeepAwake() {
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }
        if (wifiLock != null) {
            try {
                wifiLock.release();
            } catch (Exception ignored) {
            }
            wifiLock = null;
        }
    }

    /** 切换「省电模式」时立即生效：按新模式重建 WiFi 锁档位 */
    static void applyPowerSetting(Context c) {
        ServerService s = instance;
        if (s == null) return;
        try {
            s.releaseKeepAwake();
            s.acquireKeepAwake();
        } catch (Exception ignored) {
        }
    }

    /** 「强力保活」开关变化时立即生效：开关与门控都满足才播放静音音频 */
    static void applyStrongSetting(Context c) {
        ServerService s = instance;
        if (s == null) return;
        if (KeepAlive.strongEnabled(c) && KeepAlive.needed(c)) s.startSilent();
        else s.stopSilent();
    }

    private void startSilent() {
        if (silent == null) silent = new SilentAudio();
        silent.start();
    }

    private void stopSilent() {
        if (silent != null) {
            silent.stop();
            silent = null;
        }
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

    /** 「持续响铃」开启时的提醒入口：响铃中或冷却期内的消息只出通知，不重新响铃 */
    private void alertForNewMessage() {
        if (!Prefs.get(this, "ringLoop", false)) {
            playAlert();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (ringLock) {
            // 响铃期间收到的多条消息不叠加、不延长
            if (ringing) {
                // 锁屏休眠会让计时晚触发，这里按真实时长先收尾
                if (now - ringStartAt >= RING_DURATION_MS) stopRingContinuous();
                else return;
            }
            if (lastRingEndAt > 0 && now - lastRingEndAt < RING_COOLDOWN_MS) return;
            startRingContinuous();
        }
    }

    private void startRingContinuous() {
        ringing = true;
        ringStartAt = SystemClock.elapsedRealtime();
        boolean sound = Prefs.get(this, "sound", true);
        boolean vib = Prefs.get(this, "vibrate", true);
        if (sound) {
            MediaPlayer mp = null;
            try {
                String custom = Prefs.ringtone(this);
                Uri uri = custom != null ? Uri.parse(custom)
                        : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                mp.setDataSource(getApplicationContext(), uri);
                mp.setLooping(true);
                mp.prepare();
                mp.start();
                ringPlayer = mp;
            } catch (Exception e) {
                if (mp != null) {
                    try {
                        mp.release();
                    } catch (Exception ignored) {
                    }
                }
                ringPlayer = null;
            }
        }
        if (vib) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    // 长震动节奏，循环到停止
                    v.vibrate(VibrationEffect.createWaveform(new long[]{0, 240, 900}, 0));
                    ringVib = v;
                }
            } catch (Exception ignored) {
            }
        }
        ui.removeCallbacks(ringTimeout);
        ui.postDelayed(ringTimeout, RING_DURATION_MS);
        showRingNotification();
    }

    /** 停止持续响铃并进入 10 秒冷却（自动超时、手动确认、关闭开关都会走到这里） */
    private void stopRingContinuous() {
        synchronized (ringLock) {
            boolean was = ringing;
            ringing = false;
            ui.removeCallbacks(ringTimeout);
            if (ringPlayer != null) {
                try {
                    if (ringPlayer.isPlaying()) ringPlayer.stop();
                } catch (Exception ignored) {
                }
                try {
                    ringPlayer.release();
                } catch (Exception ignored) {
                }
                ringPlayer = null;
            }
            if (ringVib != null) {
                try {
                    ringVib.cancel();
                } catch (Exception ignored) {
                }
                ringVib = null;
            }
            if (was) {
                // 提前确认按当前时间；超时/休眠后补停则按原本应结束的时间，冷却不重复延长
                long now = SystemClock.elapsedRealtime();
                lastRingEndAt = Math.min(now, ringStartAt + RING_DURATION_MS);
            }
        }
        getSystemService(NotificationManager.class).cancel(RING_NOTIFY_ID);
    }

    /** 亮屏/解锁时按真实时间校准：锁屏期间已超过 60 秒则立即停响，不再继续 */
    private void syncRingClock() {
        synchronized (ringLock) {
            if (!ringing) return;
            long elapsed = SystemClock.elapsedRealtime() - ringStartAt;
            if (elapsed >= RING_DURATION_MS) {
                stopRingContinuous();
                return;
            }
            // 休眠会让原定时器偏晚，重新按剩余时间计时
            ui.removeCallbacks(ringTimeout);
            ui.postDelayed(ringTimeout, RING_DURATION_MS - elapsed);
        }
    }

    /** 手动确认：立即停响，并收起悬浮条 */
    void acknowledgeRing() {
        stopRingContinuous();
        stopRing();
        if (floating != null) floating.dismiss();
    }

    /** 供界面关闭「持续响铃」开关时立即生效 */
    static void stopRingNow() {
        ServerService s = instance;
        if (s != null) s.acknowledgeRing();
    }

    /** 响铃期间提供「已确认」入口，避免用户只能干等 60 秒 */
    private void showRingNotification() {
        Intent ack = new Intent(this, ServerService.class).setAction(ACTION_ACK);
        PendingIntent pi = PendingIntent.getService(this, 1, ack,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, curMsgChannel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Webhook · 持续响铃")
                .setContentText("点击「已确认」停止响铃（最长 60 秒）")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notification), "已确认", pi).build())
                .build();
        getSystemService(NotificationManager.class).notify(RING_NOTIFY_ID, n);
    }

    private void startServer(int port) {
        lastError = null;
        thread = new ServerThread(port);
        thread.start();
    }

    private Notification buildServiceNotification(int port) {
        return new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("正在播放")
                .setContentText("本地音乐 · 后台播放中")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build();
    }

    /** 消息通知渠道：声音与震动由本应用自行播放，渠道固定静音，避免被系统缓存影响 */
    private void ensureMsgChannel() {
        if (curMsgChannel != null) return;
        String id = "msg_alert";
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(id, "消息提醒", NotificationManager.IMPORTANCE_DEFAULT);
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
                        BootReceiver.cancelWatchdog(ServerService.this);
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

    /** 收到一条消息：先落盘；空闲期单条立刻提醒，只有连续消息才进入 2 秒合并窗口 */
    private void onMessage(String msg) {
        String text = msg == null || msg.trim().length() == 0 ? "(空消息)" : msg;
        String time = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date());
        HistoryStore.append(this, "Webhook", text, time);

        boolean immediate = false;
        boolean startTimer = false;
        synchronized (batch) {
            if (batch.isEmpty()) {
                // 上一条提醒已过去 3 秒以上：这是独立消息，立即送达（0 延迟）
                immediate = SystemClock.elapsedRealtime() - lastFlushAt >= 3000;
                startTimer = !immediate;
            }
            if (batch.size() < 100) batch.add(text);
        }
        if (immediate) flush();
        else if (startTimer) ui.postDelayed(flushBatch, 2000);
    }

    /** 合并窗口结束：单条照旧，多条合并成一条提醒 */
    private void flush() {
        List<String> items;
        synchronized (batch) {
            items = new ArrayList<String>(batch);
            batch.clear();
        }
        if (items.isEmpty()) return;
        lastFlushAt = SystemClock.elapsedRealtime();

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
        alertForNewMessage();
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
            // 响铃期间悬浮条保留到响铃结束，方便点「已确认」
            floating.showMessage(title, f, ringing ? RING_DURATION_MS : 4000);
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
