package com.webhook.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!Prefs.get(context, "boot", false)) return;
        if (!Prefs.get(context, "running", false)) return;
        Intent i = new Intent(context, ServerService.class);
        i.putExtra(ServerService.EXTRA_PORT, Prefs.getPort(context));
        try {
            context.startForegroundService(i);
        } catch (Exception ignored) {
        }
    }
}
