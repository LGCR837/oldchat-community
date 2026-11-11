package com.im.oldchat.ui.fragments;
import android.graphics.Typeface;
import android.support.v4.view.ViewCompat;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Interpolator;
import android.view.animation.ScaleAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.im.oldchat.R;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.ThemeColorUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
class PastelHomeUiController {
    interface Callbacks {
        void onRecentClick(RecentItem item, View avatarView);
        void onRecentClick(RecentItem item);
        void onRecentLongClick(View anchor, RecentItem item);
        void onCustomGroupLongClick(View anchor, RecentCustomGroupStore.Group group);
    }
    private static final int SEC_NEWS = 0;
    private static final int SEC_CHATS = 1;
    private static final int SEC_FOLDED = 2;
    private static final long ANIM_MS = 260L;
    private final View root;
    private final Typeface roundedBold;
    private final Callbacks callbacks;
    private final List<Integer> openQueue = new ArrayList<Integer>();
    private final SectionUi[] sections = new SectionUi[3];
    private final ProgressBar pbHomeLoading;
    private final TextView tvHomeUnreadCount;
    private final View sectionNewsRoot;
    private final View sectionChatsRoot;
    private final View sectionFoldedRoot;
    private final LinearLayout mainContainer;
    private final LinearLayout customSectionsContainer;
    private final PastelCustomSectionRenderer customSectionRenderer = new PastelCustomSectionRenderer();
    private final View homeHeader;
    private boolean styleInitialized;
    private boolean classicStyle;
    PastelHomeUiController(View root, Typeface roundedBold, Callbacks callbacks) {
        this.root = root;
        this.roundedBold = roundedBold;
        this.callbacks = callbacks;
        pbHomeLoading = (ProgressBar) root.findViewById(R.id.pbHomeLoading);
        tvHomeUnreadCount = (TextView) root.findViewById(R.id.tvHomeUnreadCount);
        sectionNewsRoot = root.findViewById(R.id.sectionNewsRoot);
        sectionChatsRoot = root.findViewById(R.id.sectionChatsRoot);
        sectionFoldedRoot = root.findViewById(R.id.sectionFoldedRoot);
        mainContainer = (LinearLayout) root.findViewById(R.id.pastelMainContainer);
        customSectionsContainer = (LinearLayout) root.findViewById(R.id.customChatSectionsContainer);
        homeHeader = root.findViewById(R.id.homeHeaderBar);
        if (ThemeColorUtil.isMinimalDark(root.getContext()) && homeHeader != null
                && homeHeader.getParent() instanceof View) {
            ((View) homeHeader.getParent()).setBackgroundColor(0xFF08090A);
        }
        if (sectionNewsRoot != null && !SettingsPrefs.isHomeNewsEnabled(root.getContext())) sectionNewsRoot.setVisibility(View.GONE);
        sections[SEC_NEWS] = bindSection(root, R.id.headerNews, R.id.dotNews, R.id.tvNewsCount, R.id.panelNews, R.id.contentNews, true);
        sections[SEC_CHATS] = bindSection(root, R.id.headerChats, R.id.dotChats, R.id.tvChatsCount, R.id.panelChats, R.id.contentChats, true);
        sections[SEC_FOLDED] = bindSection(root, R.id.headerFolded, R.id.dotFolded, R.id.tvFoldedCount, R.id.panelFolded, R.id.contentFolded, false);
        openQueue.add(SEC_NEWS); openQueue.add(SEC_CHATS);
        for (int i = 0; i < sections.length; i++) {
            if (ThemeColorUtil.isMinimalDark(root.getContext()) && sections[i].header != null) {
                sections[i].header.setBackgroundResource(R.drawable.bg_minimal_dark_row);
            }
            applySectionStateImmediate(sections[i]);
            final int idx = i;
            View.OnClickListener listener = new View.OnClickListener() { @Override public void onClick(View v) { toggleSection(idx); } };
            sections[i].header.setOnClickListener(listener);
            View parent = (View) sections[i].header.getParent(); if (parent != null) parent.setOnClickListener(listener);
        }
    }
    void setWsConnected(boolean connected) { if (pbHomeLoading != null) pbHomeLoading.setVisibility(connected ? View.GONE : View.VISIBLE); }
    void renderSections(List<RecentItem> news, List<RecentItem> chats, List<RecentItem> folded,
                        List<RecentCustomGroupStore.Group> customGroups,
                        Map<String, List<RecentItem>> customItems) {
        updateHeaderUnreadCount(news, chats, folded, customItems);
        boolean useClassic = SettingsPrefs.isClassicHomeStyle(root.getContext());
        applyHomeStyle(useClassic);
        if (useClassic) {
            List<RecentItem> all = new ArrayList<RecentItem>();
            if (news != null) all.addAll(news);
            if (chats != null) all.addAll(chats);
            if (folded != null) all.addAll(folded);
            if (customGroups != null && customItems != null) {
                for (int i = 0; i < customGroups.size(); i++) {
                    List<RecentItem> groupItems = customItems.get(customGroups.get(i).id);
                    if (groupItems != null) all.addAll(groupItems);
                }
            }
            PastelChatRowRenderer.render(root, sections[SEC_CHATS].content, all,
                    R.layout.item_home_classic_row, null, false, callbacks);
            syncOpenPanelHeights();
            return;
        }
        boolean newsEnabled = SettingsPrefs.isHomeNewsEnabled(root.getContext());
        if (!newsEnabled && news != null && !news.isEmpty()) {
            chats.addAll(0, news);
            news.clear();
        }
        if (sectionNewsRoot != null) {
            sectionNewsRoot.setVisibility(newsEnabled ? View.VISIBLE : View.GONE);
        }
        int newsCount = news == null ? 0 : news.size();
        sections[SEC_NEWS].count.setText(String.valueOf(newsCount));
        sections[SEC_NEWS].count.setBackgroundResource(newsCount > 0 ? R.drawable.bg_pastel_pill_red : R.drawable.bg_pastel_pill_yellow);
        sections[SEC_NEWS].count.setTextColor(newsCount > 0 ? 0xFFFFFFFF : color(R.color.home_count_text_muted));
        sections[SEC_CHATS].count.setText(String.valueOf(chats.size()));
        sections[SEC_FOLDED].count.setText(String.valueOf(folded.size()));
        PastelChatRowRenderer.render(root, sections[SEC_NEWS].content, news, R.layout.item_pastel_news_row, roundedBold, false, callbacks);
        PastelChatRowRenderer.render(root, sections[SEC_CHATS].content, chats, R.layout.item_pastel_news_row, roundedBold, false, callbacks);
        customSectionRenderer.render(root, customSectionsContainer, customGroups, customItems, roundedBold, callbacks);
        renderFoldedGrid(sections[SEC_FOLDED].content, folded);
        syncOpenPanelHeights();
    }
    private void applyHomeStyle(boolean classic) {
        if (!styleInitialized || classicStyle != classic) {
            for (int i = 0; i < sections.length; i++) {
                if (sections[i] != null && sections[i].content != null) {
                    sections[i].content.removeAllViews();
                }
            }
            styleInitialized = true;
            classicStyle = classic;
        }
        int side = classic ? 0 : dp(14);
        int top = classic ? dp(8) : dp(12);
        if (mainContainer != null) {
            mainContainer.setPadding(side, top, side, dp(20));
        }
        if (homeHeader != null) {
            int headerSide = classic ? dp(14) : 0;
            homeHeader.setPadding(headerSide, 0, headerSide, 0);
        }
        View chatsHeaderContainer = sections[SEC_CHATS].header == null
                ? null : (View) sections[SEC_CHATS].header.getParent();
        if (chatsHeaderContainer != null) {
            chatsHeaderContainer.setVisibility(classic ? View.GONE : View.VISIBLE);
        }
        if (sectionChatsRoot != null) {
            sectionChatsRoot.setVisibility(View.VISIBLE);
            ViewGroup.LayoutParams params = sectionChatsRoot.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) params;
                lp.topMargin = classic ? 0 : dp(10);
                sectionChatsRoot.setLayoutParams(lp);
            }
        }
        if (sectionNewsRoot != null) {
            sectionNewsRoot.setVisibility(classic ? View.GONE : View.VISIBLE);
        }
        if (sectionFoldedRoot != null) {
            sectionFoldedRoot.setVisibility(classic ? View.GONE : View.VISIBLE);
        }
        if (customSectionsContainer != null) {
            customSectionsContainer.setVisibility(classic ? View.GONE : View.VISIBLE);
        }
        SectionUi chatsSection = sections[SEC_CHATS];
        chatsSection.open = true;
        chatsSection.panel.setVisibility(View.VISIBLE);
        ViewGroup.LayoutParams panelParams = chatsSection.panel.getLayoutParams();
        if (panelParams != null) {
            panelParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (panelParams instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) panelParams).topMargin = classic ? 0 : dp(6);
            }
            chatsSection.panel.setLayoutParams(panelParams);
        }
    }

    private void renderFoldedGrid(LinearLayout parent, List<RecentItem> items) {
        PastelFoldedTileRenderer.render(root, parent, items, roundedBold, callbacks);
    }

    private void updateHeaderUnreadCount(List<RecentItem> news, List<RecentItem> chats,
                                         List<RecentItem> folded,
                                         Map<String, List<RecentItem>> customItems) {
        if (tvHomeUnreadCount == null) {
            return;
        }
        int total = sumUnread(news) + sumUnread(chats) + sumUnread(folded);
        if (customItems != null) {
            for (List<RecentItem> items : customItems.values()) total += sumUnread(items);
        }
        if (total <= 0) {
            tvHomeUnreadCount.setText("");
            tvHomeUnreadCount.setVisibility(View.GONE);
            return;
        }
        tvHomeUnreadCount.setText(total > 99 ? "99+" : String.valueOf(total));
        tvHomeUnreadCount.setVisibility(View.VISIBLE);
    }

    private int sumUnread(List<RecentItem> items) {
        if (items == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < items.size(); i++) {
            RecentItem item = items.get(i);
            if (item != null && !item.isFoldedFolder && item.unreadCount > 0) {
                total += item.unreadCount;
                if (total > 9999) {
                    return 9999;
                }
            }
        }
        return total;
    }
    private void toggleSection(int idx) {
        if (sections[idx].open) {
            setSectionOpen(idx, false, true);
            return;
        }
        if (openQueue.size() >= 2) {
            setSectionOpen(openQueue.get(0), false, true);
        }
        setSectionOpen(idx, true, true);
    }
    private void setSectionOpen(int idx, boolean open, boolean animate) {
        SectionUi s = sections[idx];
        s.open = open;
        openQueue.remove((Integer) idx);
        if (open) openQueue.add(idx);
        updateDotState(s.dot, open);
        animatePanel(s.panel, open, animate);
    }
    private void animatePanel(final FrameLayout panel, final boolean open, boolean animate) {
        panel.clearAnimation(); panel.setTag(open ? Boolean.TRUE : Boolean.FALSE);
        if (!animate) {
            panel.setVisibility(open ? View.VISIBLE : View.GONE);
            panel.getLayoutParams().height = open ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
            ViewCompat.setAlpha(panel, open ? 1f : 0f);
            return;
        }
        int start = panel.getHeight();
        if (open) {
            panel.setVisibility(View.VISIBLE);
            int target = measureWrapHeight(panel);
            panel.startAnimation(new HeightAlphaAnimation(panel, Math.max(0, start), target, 0f, 1f));
            return;
        }
        int from = start > 0 ? start : measureWrapHeight(panel);
        HeightAlphaAnimation a = new HeightAlphaAnimation(panel, from, 0, 1f, 0f);
        a.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation animation) { }
            @Override public void onAnimationRepeat(Animation animation) { }
            @Override public void onAnimationEnd(Animation animation) { Object tag = panel.getTag(); if (!(tag instanceof Boolean) || !((Boolean) tag).booleanValue()) panel.setVisibility(View.GONE); }
        });
        panel.startAnimation(a);
    }
    private int measureWrapHeight(View view) {
        int width = ((View) view.getParent()).getWidth();
        if (width <= 0) {
            width = root.getResources().getDisplayMetrics().widthPixels - dp(48);
        }
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return Math.max(1, view.getMeasuredHeight());
    }
    private void applySectionStateImmediate(SectionUi s) {
        updateDotState(s.dot, s.open);
        animatePanel(s.panel, s.open, false);
    }
    private void updateDotState(View dot, boolean open) {
        dot.setBackgroundResource(open ? R.drawable.bg_pastel_dot_open : R.drawable.bg_pastel_dot_closed);
        float from = open ? 0.72f : 1f;
        float to = open ? 1f : 0.72f;
        AnimationSet set = new AnimationSet(true);
        set.setInterpolator(new AccelerateDecelerateInterpolator());
        set.setDuration(ANIM_MS - 20);
        set.addAnimation(new ScaleAnimation(from, to, from, to, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f));
        set.addAnimation(new AlphaAnimation(open ? 0.7f : 1f, open ? 1f : 0.7f));
        dot.startAnimation(set);
    }
    private SectionUi bindSection(View rootView, int headerId, int dotId, int countId, int panelId, int contentId, boolean open) {
        SectionUi s = new SectionUi();
        s.header = rootView.findViewById(headerId);
        s.dot = rootView.findViewById(dotId);
        s.count = (TextView) rootView.findViewById(countId);
        s.panel = (FrameLayout) rootView.findViewById(panelId);
        s.content = (LinearLayout) rootView.findViewById(contentId);
        s.open = open;
        if (roundedBold != null) {
            TextView title = (TextView) rootView.findViewById(resolveTitleId(headerId));
            if (title != null) title.setTypeface(roundedBold);
        }
        return s;
    }
    private int resolveTitleId(int headerId) {
        if (headerId == R.id.headerNews) {
            return R.id.tvNewsTitle;
        }
        if (headerId == R.id.headerChats) {
            return R.id.tvChatsTitle;
        }
        return R.id.tvFoldedTitle;
    }
    private int color(int resId) {
        return root.getResources().getColor(resId);
    }
    private void syncOpenPanelHeights() {
        for (int i = 0; i < sections.length; i++) {
            SectionUi s = sections[i];
            if (s == null || s.panel == null) continue;
            if (!s.open) continue;
            ViewGroup.LayoutParams lp = s.panel.getLayoutParams();
            s.panel.setVisibility(View.VISIBLE);
            if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) { lp.height = ViewGroup.LayoutParams.WRAP_CONTENT; s.panel.setLayoutParams(lp); }
            ViewCompat.setAlpha(s.panel, 1f);
            s.panel.requestLayout();
        }
    }
    private int dp(int value) {
        return (int) (root.getResources().getDisplayMetrics().density * value + 0.5f);
    }
    private static class SectionUi {
        View header;
        View dot;
        TextView count;
        FrameLayout panel;
        LinearLayout content;
        boolean open;
    }
    private static class HeightAlphaAnimation extends Animation {
        private final View view;
        private final int fromH;
        private final int toH;
        private final float fromA;
        private final float toA;
        HeightAlphaAnimation(View view, int fromH, int toH, float fromA, float toA) {
            this.view = view;
            this.fromH = fromH;
            this.toH = toH;
            this.fromA = fromA;
            this.toA = toA;
            setDuration(ANIM_MS);
            setInterpolator(new Interpolator() {
                @Override
                public float getInterpolation(float t) {
                    float x = t - 1f;
                    return 1f + x * x * x;
                }
            });
        }
        @Override
        protected void applyTransformation(float t, android.view.animation.Transformation tr) {
            int h = fromH + (int) ((toH - fromH) * t);
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            lp.height = h <= 0 ? 0 : h;
            view.setLayoutParams(lp);
            ViewCompat.setAlpha(view, fromA + (toA - fromA) * t);
        }
        @Override
        public boolean willChangeBounds() {
            return true;
        }
    }
}
