package com.im.oldchat.ui;

import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.models.ResourceItem;
import com.im.oldchat.util.MediaUrlResolver;

import java.util.ArrayList;
import java.util.List;

abstract class ResourceSectionActivitySupport4 extends BaseActivity implements ResourceItemAdapter.ActionListener {
    protected static final String AUTH_PREFS = "auth";
    protected static final int PAGE_SIZE = 20;
    protected static final int REQ_PICK_RESOURCE = 5201;
    protected static final int REQ_STORAGE = 5202;
    protected static final long MAX_RESOURCE_BYTES = 500L * 1024 * 1024;
    protected static final long DEFAULT_QUOTA_BYTES = 10L * 1024 * 1024 * 1024;

    protected ListView lvResources;
    protected ProgressBar pbLoading;
    protected ResourceItemAdapter adapter;
    protected final List<ResourceItem> items = new ArrayList<ResourceItem>();
    protected String token;
    protected String myUid;
    protected String sectionId;
    protected String sectionName;
    protected String sectionOwnerUid;
    protected View btnLoadMore;
    protected View loadMoreFooter;
    protected View uploadProgressLayout;
    protected ProgressBar pbUpload;
    protected TextView tvUploadStatus;
    protected TextView tvQuota;
    protected EditText etSearch;
    protected View btnSearch;
    protected int currentOffset = 0;
    protected boolean hasMore = true;
    protected boolean isLoadingMore = false;
    protected boolean uploading = false;
    protected String currentQuery = "";

    protected void showLoading(boolean loading) {
        if (pbLoading != null) {
            pbLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    protected boolean canDeleteItem(ResourceItem item) {
        if (item == null || myUid == null || myUid.isEmpty()) {
            return false;
        }
        if (myUid.equals(item.uploader_uid)) {
            return true;
        }
        return sectionOwnerUid != null && myUid.equals(sectionOwnerUid);
    }

    protected static String formatBytesQuota(long bytes) {
        if (bytes <= 0) {
            return "0B";
        }
        double b = (double) bytes;
        double kb = b / 1024.0;
        double mb = kb / 1024.0;
        double gb = mb / 1024.0;
        if (gb >= 1.0) {
            return String.format(java.util.Locale.US, "%.2fGB", gb);
        }
        if (mb >= 1.0) {
            return String.format(java.util.Locale.US, "%.2fMB", mb);
        }
        if (kb >= 1.0) {
            return String.format(java.util.Locale.US, "%.2fKB", kb);
        }
        return String.format(java.util.Locale.US, "%dB", bytes);
    }

    protected String formatSize(long written, long total) {
        if (total <= 0) {
            return formatBytes(written);
        }
        return formatBytes(written) + "/" + formatBytes(total);
    }

    protected String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        long kb = bytes / 1024;
        if (kb < 1024) {
            return kb + "KB";
        }
        long mb = kb / 1024;
        long mbRemain = kb % 1024;
        long mbTenth = (mbRemain * 10) / 1024;
        return mb + "." + mbTenth + "MB";
    }

    protected String formatSpeed(long bps) {
        if (bps <= 0) {
            return "";
        }
        long kbps = bps / 1024;
        if (kbps < 1024) {
            return kbps + "KB/s";
        }
        long mbps = kbps / 1024;
        long mbRemain = kbps % 1024;
        long mbTenth = (mbRemain * 10) / 1024;
        return mbps + "." + mbTenth + "MB/s";
    }

    protected String resolveUrl(String url) {
        return MediaUrlResolver.resolve(url);
    }
}
