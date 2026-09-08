package com.im.oldchat.ui.fragments;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.im.oldchat.R;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 渲染用户创建的主页会话分组。 */
final class PastelCustomSectionRenderer {
    private final Map<String, Boolean> openStates = new HashMap<String, Boolean>();

    void render(final View root, LinearLayout container,
                List<RecentCustomGroupStore.Group> groups,
                Map<String, List<RecentItem>> itemsByGroup,
                Typeface roundedBold, final PastelHomeUiController.Callbacks callbacks) {
        container.removeAllViews();
        if (groups == null || groups.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        LayoutInflater inflater = LayoutInflater.from(root.getContext());
        for (int i = 0; i < groups.size(); i++) {
            final RecentCustomGroupStore.Group group = groups.get(i);
            List<RecentItem> items = itemsByGroup.get(group.id);
            View section = inflater.inflate(R.layout.view_pastel_section_chats, container, false);
            final View header = section.findViewById(R.id.headerChats);
            final View dot = section.findViewById(R.id.dotChats);
            TextView title = (TextView) section.findViewById(R.id.tvChatsTitle);
            TextView count = (TextView) section.findViewById(R.id.tvChatsCount);
            final FrameLayout panel = (FrameLayout) section.findViewById(R.id.panelChats);
            LinearLayout content = (LinearLayout) section.findViewById(R.id.contentChats);
            title.setText(group.name);
            if (roundedBold != null) title.setTypeface(roundedBold);
            count.setText(String.valueOf(items == null ? 0 : items.size()));
            if (ThemeColorUtil.isMinimalDark(root.getContext())) {
                header.setBackgroundResource(R.drawable.bg_minimal_dark_row);
            }
            Boolean saved = openStates.get(group.id);
            final boolean initiallyOpen = saved == null || saved.booleanValue();
            applyOpen(dot, panel, initiallyOpen);
            View.OnClickListener clickListener = new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean open = panel.getVisibility() != View.VISIBLE;
                    openStates.put(group.id, Boolean.valueOf(open));
                    applyOpen(dot, panel, open);
                }
            };
            View.OnLongClickListener longClickListener = new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (callbacks != null) callbacks.onCustomGroupLongClick(v, group);
                    return true;
                }
            };
            header.setOnClickListener(clickListener);
            header.setOnLongClickListener(longClickListener);
            View headerParent = header.getParent() instanceof View ? (View) header.getParent() : null;
            if (headerParent != null) {
                headerParent.setOnClickListener(clickListener);
                headerParent.setOnLongClickListener(longClickListener);
            }
            PastelChatRowRenderer.render(root, content, items, R.layout.item_pastel_news_row,
                    roundedBold, false, callbacks);
            container.addView(section);
        }
    }

    private static void applyOpen(View dot, FrameLayout panel, boolean open) {
        dot.setBackgroundResource(open ? R.drawable.bg_pastel_dot_open : R.drawable.bg_pastel_dot_closed);
        panel.setVisibility(open ? View.VISIBLE : View.GONE);
    }
}
