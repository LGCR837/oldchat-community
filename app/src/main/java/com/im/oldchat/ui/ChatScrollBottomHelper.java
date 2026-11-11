package com.im.oldchat.ui;

import android.support.v4.view.ViewCompat;
import android.view.View;

final class ChatScrollBottomHelper {
    private ChatScrollBottomHelper() {
    }

    static boolean isAtBottom(View view) {
        if (view == null) {
            return true;
        }
        if (view instanceof android.view.ViewGroup
                && ((android.view.ViewGroup) view).getChildCount() == 0) {
            // 尚未布局/数据刚替换但未布局时，不能判定为“不在底部”。
            // 否则 onResume 的 keepPosition 会误判为 true，触发“拉取很多消息却停在中间”。
            return true;
        }
        return !ViewCompat.canScrollVertically(view, 1);
    }
}
