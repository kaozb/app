package com.webhook.notify;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 悬浮窗消息弹条：收到消息时从顶部滑出，4 秒后自动消失，点击可关闭。
 */
final class FloatingManager {

    private final ServerService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private View msgView;

    private final Runnable hideMsg = new Runnable() {
        @Override
        public void run() {
            hideMsgNow();
        }
    };

    FloatingManager(ServerService s) {
        svc = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        density = s.getResources().getDisplayMetrics().density;
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    void showMessage(final String title, final String text, final long hideAfterMs) {
        if (!Prefs.get(svc, "floatMsg", false) || !Settings.canDrawOverlays(svc)) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (msgView == null) {
                        msgView = LayoutInflater.from(svc).inflate(R.layout.floating_msg, null);
                        msgView.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                hideMsgNow();
                            }
                        });
                        msgView.findViewById(R.id.f_ack).setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                svc.acknowledgeRing();
                            }
                        });
                        int w = Resources.getSystem().getDisplayMetrics().widthPixels - dp(32);
                        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w,
                                WindowManager.LayoutParams.WRAP_CONTENT,
                                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                                PixelFormat.TRANSLUCENT);
                        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                        lp.y = dp(52);
                        wm.addView(msgView, lp);
                        msgView.setAlpha(0f);
                        msgView.animate().alpha(1f).setDuration(180).start();
                    }
                    ((TextView) msgView.findViewById(R.id.f_title)).setText(title);
                    ((TextView) msgView.findViewById(R.id.f_text)).setText(text);
                    ((TextView) msgView.findViewById(R.id.f_time)).setText("刚刚 · Webhook");
                    main.removeCallbacks(hideMsg);
                    main.postDelayed(hideMsg, hideAfterMs);
                } catch (Exception ignored) {
                }
            }
        });
    }

    /** 手动确认后立即收起悬浮条 */
    void dismiss() {
        main.post(new Runnable() {
            @Override
            public void run() {
                hideMsgNow();
            }
        });
    }

    private void hideMsgNow() {
        final View v = msgView;
        if (v == null) return;
        msgView = null;
        main.removeCallbacks(hideMsg);
        try {
            v.animate().alpha(0f).setDuration(150).start();
        } catch (Exception ignored) {
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                safeRemove(v);
            }
        }, 160);
    }

    private void safeRemove(View v) {
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    void shutdown() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (msgView != null) {
                    safeRemove(msgView);
                    msgView = null;
                }
            }
        });
    }
}
