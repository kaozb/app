package com.webhook.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 音乐播放器外衣的保活服务：静音播放音频 + mediaPlayback 前台服务。
 *
 * 只在「短信转发已开启、Webhook 服务未运行」时使用；服务运行时静音音频由
 * ServerService 自己播放，避免出现两条重复的播放通知。
 */
public class KeepAliveService extends Service {

    private static final String CH = "music_playback";
    private static final int NOTIFY_ID = 900;

    private static volatile boolean isRunning = false;
    private SilentAudio audio;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "音乐播放", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 不满足门控（短信转发/启动服务都没开）时立刻退出，避免后台空转
        if (!KeepAlive.needed(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("正在播放")
                .setContentText("本地音乐 · 无损音质")
                .setOngoing(true)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFY_ID, n);
        }
        isRunning = true;
        if (audio == null) audio = new SilentAudio();
        audio.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (audio != null) {
            audio.stop();
            audio = null;
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    static void start(Context c) {
        if (isRunning) return;
        try {
            c.startForegroundService(new Intent(c, KeepAliveService.class));
        } catch (Exception ignored) {
            // Android 12+ 后台启动前台服务受限时可能抛异常，短信广播/打开应用时会再自愈
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, KeepAliveService.class));
        } catch (Exception ignored) {
        }
    }
}
