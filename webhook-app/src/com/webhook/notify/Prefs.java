package com.webhook.notify;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static int getPort(Context c) {
        return p(c).getInt("port", 8080);
    }

    static void setPort(Context c, int v) {
        p(c).edit().putInt("port", v).apply();
    }

    static boolean get(Context c, String k, boolean def) {
        return p(c).getBoolean(k, def);
    }

    static void set(Context c, String k, boolean v) {
        p(c).edit().putBoolean(k, v).apply();
    }

    static String ringtone(Context c) {
        return p(c).getString("ringtone", null);
    }

    static void ringtone(Context c, String v) {
        p(c).edit().putString("ringtone", v).apply();
    }

    /** 保留消息条数，默认 50 */
    static int keep(Context c) {
        return p(c).getInt("keep", 50);
    }

    static void keep(Context c, int v) {
        p(c).edit().putInt("keep", v).apply();
    }

    /** 服务是否处于运行状态（用于开机自启时恢复上次状态） */
    static void setRunning(Context c, boolean v) {
        set(c, "running", v);
    }
}
