package com.webhook.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 发件箱重试闹钟到期：补发息屏期间没发出去的短信 */
public class SmsOutboxReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SmsOutbox.flush(app);
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }
}
