package com.webhook.notify;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 收到短信后，按用户配置的接口地址与 JSON 模板转发出去 */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) return;
        if (!Prefs.get(context, "smsForward", false)) return;
        String url = Prefs.smsUrl(context);
        if (url == null || url.trim().isEmpty()) return;

        String body = readBody(intent);
        if (body == null || body.length() == 0) return;
        String sender = readSender(intent);
        long when = readTime(intent);

        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(when));
        final String json = SmsForward.buildJson(Prefs.smsJson(context), body, sender, time);
        final String target = url.trim();

        // 息屏时系统可能限制后台网络（Doze）或延迟派发广播，一次性发送很容易失败：
        // 先同步落盘，再尝试发送；失败的消息留在发件箱，等亮屏/解锁/退避闹钟补发
        SmsOutbox.enqueue(context.getApplicationContext(), target, json);
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

    private static Object[] pdus(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) return null;
        Object[] p = (Object[]) extras.get("pdus");
        return p;
    }

    private static SmsMessage message(Intent intent, Object pdu) {
        String format = intent.getStringExtra("format");
        try {
            return format != null
                    ? SmsMessage.createFromPdu((byte[]) pdu, format)
                    : SmsMessage.createFromPdu((byte[]) pdu);
        } catch (Exception e) {
            return null;
        }
    }

    /** 长短信会拆成多个 PDU，这里拼回完整正文 */
    private static String readBody(Intent intent) {
        Object[] list = pdus(intent);
        if (list == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Object pdu : list) {
            SmsMessage m = message(intent, pdu);
            if (m == null) continue;
            String t = m.getMessageBody();
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }

    private static String readSender(Intent intent) {
        Object[] list = pdus(intent);
        if (list == null) return "";
        for (Object pdu : list) {
            SmsMessage m = message(intent, pdu);
            if (m != null && m.getOriginatingAddress() != null) {
                return m.getOriginatingAddress();
            }
        }
        return "";
    }

    private static long readTime(Intent intent) {
        Object[] list = pdus(intent);
        if (list == null) return System.currentTimeMillis();
        for (Object pdu : list) {
            SmsMessage m = message(intent, pdu);
            if (m != null && m.getTimestampMillis() > 0) return m.getTimestampMillis();
        }
        return System.currentTimeMillis();
    }
}
