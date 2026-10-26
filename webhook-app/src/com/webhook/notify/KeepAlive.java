package com.webhook.notify;

import android.content.Context;

/**
 * 保活总调度。
 *
 * 只有「短信转发」或「启动服务」任意一个开启时才需要保活；两者都关闭时
 * 静音音频、看门狗、唤醒锁全部停掉，不在后台空转。
 *
 * - 启动服务：由 ServerService 持有 CPU/WiFi 锁并播放静音音频；
 * - 仅短信转发：由 KeepAliveService 播放静音音频，保持进程不被 ROM 冻结。
 */
final class KeepAlive {

    private KeepAlive() {
    }

    /** 是否需要保活：短信转发或 Webhook 服务任意一个处于开启状态 */
    static boolean needed(Context c) {
        return Prefs.get(c, "smsForward", false) || Prefs.get(c, "running", false);
    }

    static boolean watchdogEnabled(Context c) {
        return Prefs.get(c, "keepAlive", true);
    }

    static boolean strongEnabled(Context c) {
        return Prefs.get(c, "strongKeep", true);
    }

    /** 开关变化后调用：让静音音频与看门狗和当前状态对齐 */
    static void sync(Context c) {
        Context app = c.getApplicationContext();
        if (!needed(app)) {
            KeepAliveService.stop(app);
            ServerService.applyStrongSetting(app);
            BootReceiver.cancelWatchdog(app);
            return;
        }

        boolean server = Prefs.get(app, "running", false) || ServerService.running;
        boolean strong = strongEnabled(app);

        // 服务运行时静音音频由 ServerService 负责，避免出现两条重复的播放通知
        if (strong && !server) KeepAliveService.start(app);
        else KeepAliveService.stop(app);
        ServerService.applyStrongSetting(app);

        // 看门狗只在有可恢复目标时运行：服务应在运行，或需要维持短信转发的静音保活
        if (watchdogEnabled(app) && (server || strong)) BootReceiver.scheduleWatchdog(app);
        else BootReceiver.cancelWatchdog(app);
    }
}
