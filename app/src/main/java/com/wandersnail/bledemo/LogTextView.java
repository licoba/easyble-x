package com.wandersnail.bledemo;

import android.content.Context;
import android.text.method.ScrollingMovementMethod;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatTextView;

public class LogTextView extends AppCompatTextView {
    private static final int MAX_LINES = 1000; // 最大行数
    private static final int MAX_LENGTH = 10000; // 最大字符数

    public LogTextView(Context context) {
        super(context);
        init();
    }

    public LogTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LogTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // 启用滚动
        setMovementMethod(ScrollingMovementMethod.getInstance());
        // 设置最大行数
        setMaxLines(MAX_LINES);
        // 设置单行
        setSingleLine(false);
        // 设置文本可编辑
        setTextIsSelectable(true);
    }

    /**
     * 添加日志
     * @param log 日志内容
     */
    public void appendLog(String log) {
        // 检查是否需要清理旧日志
        if (getLineCount() > MAX_LINES) {
            String text = getText().toString();
            int startIndex = text.indexOf('\n', text.length() / 2);
            if (startIndex > 0) {
                setText(text.substring(startIndex + 1));
            }
        }

        // 检查总长度是否需要清理
        if (getText().length() > MAX_LENGTH) {
            String text = getText().toString();
            setText(text.substring(text.length() - MAX_LENGTH / 2));
        }

        // 添加新日志
        append(log + "\n");

        // 滚动到底部
        post(() -> {
            int scrollAmount = getLayout().getLineTop(getLineCount()) - getHeight();
            if (scrollAmount > 0) {
                scrollTo(0, scrollAmount);
            }
        });
    }

    /**
     * 清空日志
     */
    public void clearLog() {
        setText("");
    }
} 