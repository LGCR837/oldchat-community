package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.im.oldchat.bili.BiliModels;

final class OldViewUpProfileVideoNavigator {
    private OldViewUpProfileVideoNavigator() {
    }

    static void openVideoDetail(Context context, BiliModels.RecommendItem item) {
        if (context == null) {
            return;
        }
        String bvid = extractBvid(item);
        long aid = extractAid(item);
        if ((bvid == null || bvid.length() == 0) && aid <= 0) {
            Toast.makeText(context, "视频信息不完整", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(context, OldViewVideoDetailActivity.class);
        if (bvid != null && bvid.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_BVID, bvid);
        }
        if (aid > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_AID, aid);
        }
        if (item != null && item.cover != null && item.cover.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_COVER, item.cover);
        }
        if (item != null && item.title != null) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_TITLE, item.title);
        }
        context.startActivity(intent);
    }

    private static String extractBvid(BiliModels.RecommendItem item) {
        if (item == null || item.param == null) {
            return "";
        }
        String value = item.param.trim();
        return value.startsWith("BV") || value.startsWith("bv") ? value : "";
    }

    private static long extractAid(BiliModels.RecommendItem item) {
        if (item == null) {
            return 0L;
        }
        if (item.args != null && item.args.aid > 0) {
            return item.args.aid;
        }
        if (item.param != null && item.param.length() > 0) {
            String value = item.param.trim();
            if (value.startsWith("av") || value.startsWith("AV")) {
                value = value.substring(2);
            }
            try {
                return Long.parseLong(value);
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }
}
