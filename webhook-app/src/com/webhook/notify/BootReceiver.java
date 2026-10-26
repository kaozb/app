package com.webhook.notify;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 负责两件事：
 * 1. 开机后恢复上次运行状态；
 * 2. 看门狗定时器，服务被系统清理后自动拉起来。
 * 保活只在「短信转发」或「启动服务」开启时运行，其余情况一律取消。
 */
public class BootReceiver extends BroadcastReceiver {

    static final String ACTION_WATCHDOG = "com.webhook.notify.WATCHDOG";
    /** 高性能模式：15 分钟检查一次，唤醒 CPU 保证尽快自愈 */
    private static final long CHECK_FAST = 15 * 60 * 1000L;
    /** 省电模式：30 分钟检查一次，且不主动唤醒设备 */
    private static final long CHECK_POWER = 30 * 60 * 1000L;

    private static long interval(Context c) {
        return Prefs.get(c, "power", true) ? CHECK_POWER : CHECK_FAST;
    }

    private static int alarmType(Context c) {
        // 省电模式用非唤醒闹钟：设备睡着时不唤醒 CPU，等它自然醒来（或收到网络事件）后再检查。
        // 服务活着时本来就不需要检查，服务死了手机睡着也收不到消息，因此不牺牲送达时效。
        return Prefs.get(c, "power", true)
                ? AlarmManager.ELAPSED_REALTIME
                : AlarmManager.ELAPSED_REALTIME_WAKEUP;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);

        if (boot) {
            if (!KeepAlive.needed(context)) {
                KeepAliveService.stop(context);
                cancelWatchdog(context);
                return;
            }
            // 仅当「开机自启」与上次运行态都满足时才恢复 Webhook 服务
            if (Prefs.get(context, "boot", false) && Prefs.get(context, "running", false)) {
                startServer(context);
            } else if (Prefs.get(context, "running", false)) {
                // 关闭了开机自启：清掉上次运行态，避免看门狗在后台又把服务拉起来
                Prefs.setRunning(context, false);
            }
            // 短信转发开启时也需要静音保活；具体由门控统一决定
            KeepAlive.sync(context);
            return;
        }

        if (!ACTION_WATCHDOG.equals(action)) return;

        if (!KeepAlive.needed(context)) {
            KeepAliveService.stop(context);
            cancelWatchdog(context);
            return;
        }

        // 服务应当在运行但进程已不在：尝试恢复；短信转发场景由 sync 恢复静音音频
        if (Prefs.get(context, "running", false) && !ServerService.running) {
            startServer(context);
        }
        KeepAlive.sync(context);
    }

    private static void startServer(Context c) {
        Intent i = new Intent(c, ServerService.class);
        i.putExtra(ServerService.EXTRA_PORT, Prefs.getPort(c));
        try {
            c.startForegroundService(i);
        } catch (Exception ignored) {
        }
    }

    /** 定时检查进程是否还在，被清理后重新拉起（间隔随「省电模式」变化） */
    static void scheduleWatchdog(Context c) {
        // 门控：短信转发与启动服务都没开时不允许存在看门狗
        if (!KeepAlive.needed(c) || !Prefs.get(c, "keepAlive", true)) {
            cancelWatchdog(c);
            return;
        }
        try {
            long iv = interval(c);
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.setInexactRepeating(alarmType(c),
                    SystemClock.elapsedRealtime() + iv,
                    iv, watchdogIntent(c));
        } catch (Exception ignored) {
        }
    }

    /** 任务被划掉等场景下延时几秒尝试拉起 */
    static void scheduleRestart(Context c, long delayMs) {
        if (!KeepAlive.needed(c) || !Prefs.get(c, "keepAlive", true)) {
            cancelWatchdog(c);
            return;
        }
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, watchdogIntent(c));
        } catch (Exception ignored) {
        }
    }

    /** 关闭「后台保活」或服务未运行时取消定时器 */
    static void cancelWatchdog(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(watchdogIntent(c));
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent watchdogIntent(Context c) {
        Intent i = new Intent(c, BootReceiver.class);
        i.setAction(ACTION_WATCHDOG);
        return PendingIntent.getBroadcast(c, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
