package com.webhook.notify;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 剪切板：按时间倒序展示历史消息，一行一条，点击复制全文 */
public class ClipboardActivity extends Activity {

    private ListView list;
    private TextView empty;
    private TextView keep;
    private final List<String[]> data = new ArrayList<String[]>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_clipboard);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        keep = findViewById(R.id.keep);

        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                copy(data.get(position)[1]);
            }
        });
        keep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                askKeep();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        keep.setText("保留 " + Prefs.keep(this) + " 条");
        refresh();
    }

    private void refresh() {
        data.clear();
        data.addAll(HistoryStore.load(this, Integer.MAX_VALUE));
        adapter.notifyDataSetChanged();
        boolean none = data.isEmpty();
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Webhook", text));
        Toast.makeText(this, "已复制到剪切板", Toast.LENGTH_SHORT).show();
    }

    private void askKeep() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(Prefs.keep(this)));
        input.setSelection(input.getText().length());
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("保留消息条数（1-500）")
                .setView(box)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int v;
                        try {
                            v = Integer.parseInt(input.getText().toString().trim());
                        } catch (Exception e) {
                            v = 50;
                        }
                        if (v < 1) v = 1;
                        if (v > 500) v = 500;
                        Prefs.keep(ClipboardActivity.this, v);
                        HistoryStore.trim(ClipboardActivity.this);
                        keep.setText("保留 " + v + " 条");
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class Adapter extends BaseAdapter {

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_message, parent, false);
            }
            String[] m = data.get(position);
            ((TextView) v.findViewById(R.id.m_time)).setText(m[2]);
            ((TextView) v.findViewById(R.id.m_text)).setText(m[1]);
            return v;
        }
    }
}
