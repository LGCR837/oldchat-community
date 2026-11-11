package com.im.oldchat.ui;

import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.im.oldchat.R;

abstract class OldViewVideoDetailSupport5 extends OldViewVideoDetailSupport4 {
    protected TextView tabRelated;
    protected TextView tabComments;
    protected boolean commentTabSelected = false;
    protected boolean descExpanded = false;

    protected void bindBottomTabs(View rootView) {
        if (rootView == null) {
            return;
        }
        tabRelated = (TextView) rootView.findViewById(R.id.tabOldViewRelated);
        tabComments = (TextView) rootView.findViewById(R.id.tabOldViewComments);

        if (tabRelated != null) {
            tabRelated.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectBottomTab(false);
                }
            });
        }
        if (tabComments != null) {
            tabComments.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectBottomTab(true);
                }
            });
        }
        selectBottomTab(false);
    }

    protected void bindDescriptionToggle() {
        if (tvDesc == null) {
            return;
        }
        tvDesc.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                descExpanded = !descExpanded;
                applyDescriptionState();
            }
        });
        applyDescriptionState();
    }

    protected void resetDescriptionState() {
        descExpanded = false;
        applyDescriptionState();
    }

    @Override
    protected void updateEmpty(boolean show, String text) {
        super.updateEmpty(commentTabSelected && show, text);
    }

    private void selectBottomTab(boolean showComments) {
        if (commentTabSelected == showComments && tabRelated != null && tabComments != null) {
            renderTabStyle();
            return;
        }
        commentTabSelected = showComments;
        renderTabStyle();
        onBottomTabChanged(showComments);
    }

    private void renderTabStyle() {
        if (tabRelated != null) {
            tabRelated.setTextColor(getResources().getColor(commentTabSelected ? R.color.color_text_secondary : R.color.color_text_primary));
        }
        if (tabComments != null) {
            tabComments.setTextColor(getResources().getColor(commentTabSelected ? R.color.color_text_primary : R.color.color_text_secondary));
        }
        View indRelated = findTabIndicator(R.id.viewOldViewTabIndicatorRelated);
        View indComments = findTabIndicator(R.id.viewOldViewTabIndicatorComments);
        if (indRelated != null) {
            indRelated.setBackgroundColor(commentTabSelected ? 0x00000000 : 0xFFE8400F);
        }
        if (indComments != null) {
            indComments.setBackgroundColor(commentTabSelected ? 0xFFE8400F : 0x00000000);
        }
    }

    private View findTabIndicator(int id) {
        try {
            if (tvDesc == null) {
                return null;
            }
            View root = tvDesc.getRootView();
            if (root == null) {
                return null;
            }
            return root.findViewById(id);
        } catch (Exception e) {
            return null;
        }
    }

    private void applyDescriptionState() {
        if (tvDesc == null) {
            return;
        }
        if (descExpanded) {
            tvDesc.setMaxLines(12);
            tvDesc.setEllipsize(null);
        } else {
            tvDesc.setMaxLines(2);
            tvDesc.setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    protected abstract void onBottomTabChanged(boolean showComments);
}
