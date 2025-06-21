package com.wandersnail.bledemo;

import android.content.Context;
import android.text.method.ScrollingMovementMethod;
import android.util.AttributeSet;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatTextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class LogTextView extends AppCompatTextView {
    private static final int MAX_LINES = 1000; // 最大行数
    private static final int MAX_LENGTH = 10000; // 最大字符数
    private static final float TEXT_SIZE_SP = 10.0f; // 设置字体大小为10sp
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

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
        // 设置字体大小
        setTextSize(TEXT_SIZE_SP);
    }

    /**
     * 添加日志（带时间戳，最新显示在最上面）
     * @param log 日志内容
     */
    public void appendLog(String log) {
        // 生成带时间戳的日志
        String timestamp = timeFormat.format(new Date());
        String logWithTimestamp = "[" + timestamp + "] " + log;
        
        // 获取当前文本
        String currentText = getText().toString();
        
        // 检查是否需要清理旧日志
        if (getLineCount() > MAX_LINES) {
            String[] lines = currentText.split("\n");
            int linesToRemove = lines.length - MAX_LINES / 2;
            StringBuilder newText = new StringBuilder();
            for (int i = linesToRemove; i < lines.length; i++) {
                if (i > linesToRemove) {
                    newText.append("\n");
                }
                newText.append(lines[i]);
            }
            currentText = newText.toString();
        }

        // 检查总长度是否需要清理
        if (currentText.length() > MAX_LENGTH) {
            int cutIndex = currentText.length() - MAX_LENGTH / 2;
            int newlineIndex = currentText.indexOf('\n', cutIndex);
            if (newlineIndex > 0) {
                currentText = currentText.substring(newlineIndex + 1);
            } else {
                currentText = currentText.substring(cutIndex);
            }
        }

        // 将新日志添加到最前面
        String newText;
        if (currentText.isEmpty()) {
            newText = logWithTimestamp;
        } else {
            newText = logWithTimestamp + "\n" + currentText;
        }
        
        setText(newText);

        // 滚动到顶部显示最新日志
        post(() -> {
            scrollTo(0, 0);
        });
    }

    /**
     * 清空日志
     */
    public void clearLog() {
        setText("");
    }
} 