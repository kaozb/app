package com.webhook.notify;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 消息历史持久化（JSON 行文件），供剪切板页与悬浮球面板读取。
 * 每条只保留前 2000 字，避免文件过大。
 */
final class HistoryStore {

    private static final String FILE = "history.jsonl";
    private static final int MAX_TEXT = 2000;

    private HistoryStore() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    static synchronized void append(Context c, String title, String text, String time) {
        try {
            String t = text == null ? "" : text;
            if (t.length() > MAX_TEXT) t = t.substring(0, MAX_TEXT);
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("text", t);
            o.put("time", time);
            List<String> lines = readLines(c);
            lines.add(o.toString());
            while (lines.size() > limit(c)) lines.remove(0);
            writeLines(c, lines);
        } catch (Exception ignored) {
        }
    }

    /** 按时间倒序返回，元素为 {title, text, time} */
    static synchronized List<String[]> load(Context c, int max) {
        List<String[]> out = new ArrayList<String[]>();
        List<String> lines = readLines(c);
        for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
            try {
                JSONObject o = new JSONObject(lines.get(i));
                out.add(new String[]{o.optString("title"), o.optString("text"), o.optString("time")});
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 用户调小保留条数后裁剪文件 */
    static synchronized void trim(Context c) {
        try {
            List<String> lines = readLines(c);
            int limit = limit(c);
            if (lines.size() <= limit) return;
            while (lines.size() > limit) lines.remove(0);
            writeLines(c, lines);
        } catch (Exception ignored) {
        }
    }

    private static int limit(Context c) {
        int v = Prefs.keep(c);
        return v < 1 ? 1 : v;
    }

    private static List<String> readLines(Context c) {
        List<String> lines = new ArrayList<String>();
        File f = file(c);
        if (!f.exists()) return lines;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().length() > 0) lines.add(line);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                if (r != null) r.close();
            } catch (Exception ignored) {
            }
        }
        return lines;
    }

    private static void writeLines(Context c, List<String> lines) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(c));
            StringBuilder sb = new StringBuilder();
            for (String l : lines) sb.append(l).append('\n');
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                if (out != null) out.close();
            } catch (Exception ignored) {
            }
        }
    }
}
