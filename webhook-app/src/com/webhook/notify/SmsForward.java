package com.webhook.notify;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 短信转发：把短信内容套进用户自定义的 JSON 模板，POST 到用户自定义的地址。
 * 模板变量：{{内容}}/{{text}}、{{发送者}}/{{from}}、{{时间}}/{{time}}
 */
final class SmsForward {

    static final String DEFAULT_JSON =
            "{\"text\":\"{{内容}}\",\"from\":\"{{发送者}}\",\"time\":\"{{时间}}\"}";

    private SmsForward() {
    }

    /** 变量替换。变量值按 JSON 字符串规则转义，避免短信里的引号/换行把 JSON 弄坏 */
    static String buildJson(String tpl, String body, String sender, String time) {
        String t = (tpl == null || tpl.trim().isEmpty()) ? DEFAULT_JSON : tpl;
        String b = esc(body);
        String f = esc(sender);
        String tm = esc(time);
        return t.replace("{{内容}}", b).replace("{{text}}", b)
                .replace("{{发送者}}", f).replace("{{from}}", f)
                .replace("{{时间}}", tm).replace("{{time}}", tm);
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 同步 POST JSON，返回是否 2xx */
    static boolean post(String url, String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("User-Agent", "Webhook-SMS/1.0");
            byte[] data = json.getBytes(Charset.forName("UTF-8"));
            c.setFixedLengthStreamingMode(data.length);
            java.io.OutputStream os = c.getOutputStream();
            try {
                os.write(data);
            } finally {
                os.close();
            }
            int code = c.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
