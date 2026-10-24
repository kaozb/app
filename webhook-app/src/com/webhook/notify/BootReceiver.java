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
 */
public class BootReceiver extends BroadcastReceiver {

    static final String ACTION_WATCHDOG = "com.webhook.notify.WATCHDOG";
    private static final long CHECK_INTERVAL = 15 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action);

        if (boot) {
            if (Prefs.get(context, "keepAlive", true)) scheduleWatchdog(context);
            if (!Prefs.get(context, "boot", false)) return;
        } else if (!ACTION_WATCHDOG.equals(action)) {
            return;
        }

        // 后台保活关闭后不再自动拉起
        if (!Prefs.get(context, "keepAlive", true)) return;

        // 只要上次是用户主动运行的，就恢复服务
        if (!Prefs.get(context, "running", false)) return;
        Intent i = new Intent(context, ServerService.class);
        i.putExtra(ServerService.EXTRA_PORT, Prefs.getPort(context));
        try {
            context.startForegroundService(i);
        } catch (Exception ignored) {
        }
    }

    /** 每 15 分钟检查一次，进程被清理后重新拉起 */
    static void scheduleWatchdog(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + CHECK_INTERVAL,
                    CHECK_INTERVAL, watchdogIntent(c));
        } catch (Exception ignored) {
        }
    }

    /** 任务被划掉等场景下延时几秒尝试拉起 */
    static void scheduleRestart(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMs, watchdogIntent(c));
        } catch (Exception ignored) {
        }
    }

    /** 关闭「后台保活」时取消定时器 */
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
