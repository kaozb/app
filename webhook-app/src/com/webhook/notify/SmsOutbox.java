package com.webhook.notify;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 短信转发发件箱：息屏时系统可能进入 Doze 限制后台网络，或冻结后台应用导致
 * 广播延迟派发，一次性发送很容易失败。这里先把短信落盘（进程被杀也不丢），
 * 失败的消息留在队列里反复重试，直到送达。
 * 补发时机：收到短信时、亮屏/解锁时、打开应用时、退避闹钟到期时。
 */
final class SmsOutbox {

    private static final String FILE = "sms_outbox.jsonl";
    /** 第 n 次发送失败后的等待时间（逐步退避，15 分钟封顶） */
    private static final long[] BACKOFF = {15000L, 60000L, 300000L, 900000L};
    /** 超过 24 小时仍发不出去的消息放弃，避免队列无限堆积 */
    private static final long EXPIRE_MS = 24 * 60 * 60 * 1000L;
    private static final int RETRY_REQ = 2001;

    /** 单飞锁：多处同时触发补发时只跑一轮，期间新触发的在本轮结束后合并再跑 */
    private static final Object lock = new Object();
    private static boolean flushing = false;
    private static boolean pendingCall = false;

    private SmsOutbox() {
    }

    /** 收到短信立即同步落盘（毫秒级），随后才尝试发送 */
    static void enqueue(Context c, String url, String json) {
        try {
            JSONObject o = new JSONObject();
            o.put("url", url);
            o.put("json", json);
            o.put("at", System.currentTimeMillis());
            o.put("n", 0);
            FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE), true);
            try {
                out.write((o.toString() + "\n").getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** 在调用方线程里补发；息屏时唤醒 CPU，防止发送中途休眠被掐断 */
    static void flush(Context c) {
        final Context app = c.getApplicationContext();
        synchronized (lock) {
            if (flushing) {
                pendingCall = true;
                return;
            }
            flushing = true;
        }
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webhook:smsOutbox");
                wl.acquire(30000L);
            }
        } catch (Exception ignored) {
        }
        try {
            boolean again;
            do {
                again = false;
                sendOnce(app);
                synchronized (lock) {
                    // 发送期间有新消息进来（比如连续收到多条短信），再跑一轮保证及时发出
                    if (pendingCall) {
                        pendingCall = false;
                        again = true;
                    }
                }
            } while (again);
        } finally {
            if (wl != null) {
                try {
                    wl.release();
                } catch (Exception ignored) {
                }
            }
            synchronized (lock) {
                flushing = false;
            }
        }
    }

    /** 任意线程触发的补发（界面/广播里调用，内部开线程不阻塞调用方） */
    static void flushAsync(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                flush(app);
            }
        }).start();
    }

    /** 补发一轮：按落盘顺序逐条发送，成功删除，失败保留并安排退避闹钟 */
    private static void sendOnce(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        List<String> lines = readLines(f);
        if (lines.isEmpty()) {
            cancelRetry(c);
            return;
        }
        List<String> keep = new ArrayList<String>();
        long now = System.currentTimeMillis();
        long nextDelay = -1;
        for (String line : lines) {
            JSONObject o;
            try {
                o = new JSONObject(line);
            } catch (Exception e) {
                continue; // 行损坏无法恢复，丢弃避免堵住后面的消息
            }
            if (now - o.optLong("at") > EXPIRE_MS) continue; // 超过 24 小时，放弃
            boolean ok;
            try {
                ok = SmsForward.post(o.optString("url"), o.optString("json"));
            } catch (Exception e) {
                ok = false;
            }
            if (ok) continue; // 送达：从队列移除
            try {
                o.put("n", o.optInt("n") + 1);
            } catch (Exception ignored) {
            }
            keep.add(o.toString());
            long d = backoff(o.optInt("n"));
            if (nextDelay < 0 || d < nextDelay) nextDelay = d;
        }
        writeLines(f, keep);
        if (keep.isEmpty()) cancelRetry(c);
        else scheduleRetry(c, nextDelay < 0 ? BACKOFF[0] : nextDelay);
    }

    /** 第 n 次失败后的等待时间 */
    private static long backoff(int n) {
        int i = n - 1;
        if (i < 0) i = 0;
        return i < BACKOFF.length ? BACKOFF[i] : BACKOFF[BACKOFF.length - 1];
    }

    /**
     * 用可唤醒闹钟安排重试：Doze 下也会触发（系统会合并到最小间隔），
     * 短退避被顺延也不要紧，亮屏/解锁与打开应用时另有补发兜底。
     */
    private static void scheduleRetry(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, retryIntent(c));
        } catch (Exception ignored) {
        }
    }

    private static void cancelRetry(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(retryIntent(c));
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent retryIntent(Context c) {
        Intent i = new Intent(c, SmsOutboxReceiver.class);
        return PendingIntent.getBroadcast(c, RETRY_REQ, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static List<String> readLines(File f) {
        List<String> lines = new ArrayList<String>();
        if (!f.exists()) return lines;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().length() > 0) lines.add(line);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
        }
        return lines;
    }

    private static void writeLines(File f, List<String> lines) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }
}
