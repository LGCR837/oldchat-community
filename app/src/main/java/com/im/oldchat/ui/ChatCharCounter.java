package com.im.oldchat.ui;

import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.im.oldchat.R;

/**
 * 聊天输入框剩余字符计数：
 *  - 最大 2000 字符（超限自动拦截，服务端 /v2 接口已支持 2000）
 *  - 输入框右下角显示剩余可用字符
 *  - 剩余 ≤20 变红色提示
 */
public final class ChatCharCounter {
    public static final int MAX_CHARS = 2000;

    private ChatCharCounter() {
    }

    /** 绑定输入框与计数 TextView。 */
    public static void bind(final EditText editText, final TextView counterView) {
        if (editText == null) {
            return;
        }
        // 硬限制最大长度
        InputFilter[] filters = editText.getFilters();
        InputFilter[] newFilters = new InputFilter[(filters == null ? 0 : filters.length) + 1];
        if (filters != null) {
            System.arraycopy(filters, 0, newFilters, 0, filters.length);
        }
        newFilters[newFilters.length - 1] = new InputFilter.LengthFilter(MAX_CHARS);
        editText.setFilters(newFilters);

        if (counterView == null) {
            return;
        }
        counterView.setVisibility(View.VISIBLE);
        update(editText, counterView);
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                update(editText, counterView);
            }
        });
    }

    private static void update(EditText editText, TextView counterView) {
        int length = editText == null || editText.getText() == null
                ? 0 : editText.getText().length();
        int remaining = MAX_CHARS - length;
        if (remaining < 0) {
            remaining = 0;
        }
        counterView.setText(String.valueOf(remaining));
        try {
            if (remaining <= 20) {
                counterView.setTextColor(0xFFEF4444); // 红色警示
            } else {
                counterView.setTextColor(0xFF9CA3AF); // 灰色
            }
        } catch (Throwable ignored) {
        }
    }
}
