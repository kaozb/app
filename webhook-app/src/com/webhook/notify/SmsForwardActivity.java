package com.webhook.notify;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 短信转发配置：接口地址 + JSON 模板 */
public class SmsForwardActivity extends Activity {

    private EditText urlEdit;
    private EditText jsonEdit;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sms);
        urlEdit = findViewById(R.id.sms_url);
        jsonEdit = findViewById(R.id.sms_json);

        String url = Prefs.smsUrl(this);
        if (url != null && !url.isEmpty()) urlEdit.setText(url);
        String json = Prefs.smsJson(this);
        jsonEdit.setText(json == null || json.trim().isEmpty() ? SmsForward.DEFAULT_JSON : json);

        findViewById(R.id.sms_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        findViewById(R.id.sms_test).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendTest();
            }
        });
    }

    private String url() {
        return urlEdit.getText().toString().trim();
    }

    private boolean validUrl(String u) {
        if (u.startsWith("http://") || u.startsWith("https://")) return true;
        Toast.makeText(this, "接口地址需以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void save() {
        String u = url();
        if (!validUrl(u)) return;
        if (jsonEdit.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "JSON 模板不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        Prefs.smsUrl(this, u);
        Prefs.smsJson(this, jsonEdit.getText().toString());
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    /** 用示例内容跑一次，方便验证接口地址和 JSON 模板是否可用 */
    private void sendTest() {
        String u = url();
        if (!validUrl(u)) return;
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
        final String json = SmsForward.buildJson(
                jsonEdit.getText().toString(), "这是一条测试短信", "10086", time);
        final String target = u;
        Toast.makeText(this, "正在发送…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = SmsForward.post(target, json);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(SmsForwardActivity.this,
                                ok ? "发送成功" : "发送失败，请检查地址或网络",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }
}
