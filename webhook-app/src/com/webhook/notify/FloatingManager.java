package com.webhook.notify;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮窗消息弹条 + 悬浮球（可拖动、点开展开最近消息）
 */
final class FloatingManager {

    private final ServerService svc;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private View msgView;
    private View ballView;
    private LinearLayout panelView;
    private WindowManager.LayoutParams ballParams;

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

    void showMessage(final String title, final String text) {
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
                    main.postDelayed(hideMsg, 4000);
                } catch (Exception ignored) {
                }
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

    void setBallEnabled(final boolean on) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (on) addBall();
                else removeBallAndPanel();
            }
        });
    }

    private void addBall() {
        if (ballView != null || !Settings.canDrawOverlays(svc)) return;
        ImageView iv = new ImageView(svc);
        iv.setImageResource(R.drawable.ic_notification);
        iv.setBackgroundResource(R.drawable.bg_pill);
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        iv.setColorFilter(0xFF66855A);
        ballParams = new WindowManager.LayoutParams(dp(48), dp(48),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;
        ballParams.x = dp(14);
        ballParams.y = dp(220);
        iv.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int startX, startY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int w = Resources.getSystem().getDisplayMetrics().widthPixels;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = ballParams.x;
                        startY = ballParams.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float mx = e.getRawX() - downRawX;
                        float my = e.getRawY() - downRawY;
                        if (Math.abs(mx) > 10 || Math.abs(my) > 10) moved = true;
                        if (moved) {
                            ballParams.x = startX + (int) mx;
                            ballParams.y = startY + (int) my;
                            try {
                                wm.updateViewLayout(ballView, ballParams);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            togglePanel();
                        } else {
                            int nx = startX + (int) (e.getRawX() - downRawX);
                            ballParams.x = nx + dp(24) < w / 2 ? 0 : w - dp(48);
                            try {
                                wm.updateViewLayout(ballView, ballParams);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                }
                return false;
            }
        });
        try {
            wm.addView(iv, ballParams);
            ballView = iv;
        } catch (Exception ignored) {
        }
    }

    private void removeBallAndPanel() {
        if (panelView != null) {
            safeRemove(panelView);
            panelView = null;
        }
        if (ballView != null) {
            safeRemove(ballView);
            ballView = null;
        }
    }

    private void togglePanel() {
        if (panelView != null) {
            safeRemove(panelView);
            panelView = null;
            return;
        }
        if (ballView == null) return;
        panelView = new LinearLayout(svc);
        panelView.setOrientation(LinearLayout.VERTICAL);
        panelView.setBackgroundResource(R.drawable.bg_card);
        int p = dp(14);
        panelView.setPadding(p, p, p, p);
        TextView head = new TextView(svc);
        head.setText("最近消息");
        head.setTextColor(0xFF9A8F7B);
        head.setTextSize(12);
        head.setPadding(0, 0, 0, dp(4));
        panelView.addView(head);
        int count = 0;
        java.util.List<String[]> recent = HistoryStore.load(svc, 5);
        for (String[] m : recent) {
            TextView row = new TextView(svc);
            row.setText("· " + m[2] + "  " + m[1]);
            row.setTextColor(0xFF3E3930);
            row.setTextSize(13);
            row.setMaxLines(2);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(0, dp(3), 0, dp(3));
            panelView.addView(row);
            count++;
        }
        if (count == 0) {
            TextView empty = new TextView(svc);
            empty.setText("暂无消息");
            empty.setTextColor(0xFF9A8F7B);
            empty.setTextSize(13);
            panelView.addView(empty);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(dp(250),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        panelView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    if (panelView != null) {
                        safeRemove(panelView);
                        panelView = null;
                    }
                }
                return false;
            }
        });
        try {
            wm.addView(panelView, lp);
        } catch (Exception ignored) {
            panelView = null;
        }
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
                removeBallAndPanel();
            }
        });
    }
}
