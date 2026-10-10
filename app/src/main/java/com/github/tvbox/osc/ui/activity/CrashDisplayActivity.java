package com.github.tvbox.osc.ui.activity;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 崩溃堆栈显示页。BootGuard 拦截到崩溃时启动它,把完整堆栈打在屏幕上,
 * 供用户在无电脑环境下直接截图取证(复制到剪贴板再发给开发者)。
 */
public class CrashDisplayActivity extends Activity {
    public static final String EXTRA_STACK = "extra_stack";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        String stack = getIntent() != null ? getIntent().getStringExtra(EXTRA_STACK) : null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(30, 30, 34));
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("检测到崩溃，请截图发回 (含下方完整堆栈)");
        title.setTextColor(Color.YELLOW);
        title.setTextSize(16);
        title.setPadding(0, 0, 0, 16);
        root.addView(title);

        final TextView tv = new TextView(this);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11);
        tv.setTextIsSelectable(true);
        tv.setText(TextUtils.isEmpty(stack) ? "(堆栈为空)" : stack);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(sv);

        Button copyBtn = new Button(this);
        copyBtn.setText("复制全部堆栈");
        copyBtn.setTextColor(Color.WHITE);
        copyBtn.setBackgroundColor(Color.rgb(80, 120, 200));
        copyBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", tv.getText()));
                    Toast.makeText(CrashDisplayActivity.this, "已复制，去粘贴发给开发者", Toast.LENGTH_LONG).show();
                }
            }
        });
        Button exitBtn = new Button(this);
        exitBtn.setText("退出");
        exitBtn.setTextColor(Color.WHITE);
        exitBtn.setBackgroundColor(Color.rgb(180, 70, 70));
        exitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishAffinity();
                System.exit(0);
            }
        });

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        btns.setPadding(0, 16, 0, 0);
        btns.addView(copyBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(40, 1);
        btns.addView(new View(this), sp);
        btns.addView(exitBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(btns);

        setContentView(root);
    }
}