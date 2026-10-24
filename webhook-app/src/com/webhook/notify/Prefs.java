package com.webhook.notify;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    /** 首次启动随机分配一个 10000 以上的端口，之后沿用已保存的值 */
    static int getPort(Context c) {
        int p = p(c).getInt("port", 0);
        if (p < 1 || p > 65535) {
            p = 10000 + new java.util.Random().nextInt(55536);
            setPort(c, p);
        }
        return p;
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

    /** 重置：清空全部设置 */
    static void clear(Context c) {
        p(c).edit().clear().apply();
    }
}
