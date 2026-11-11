package com.im.oldchat.ui.fragments;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RecentAvatarTracker implements CombinedChatAdapter.AvatarTracker {
    private final Set<String> pendingAvatarKeys = new HashSet<>();
    private final Set<String> loadedAvatarKeys = new HashSet<>();
    private boolean hasAvatarUrls = false;
    private boolean avatarLoadStarted = false;

    public void reset(List<RecentItem> items) {
        pendingAvatarKeys.clear();
        avatarLoadStarted = false;
        hasAvatarUrls = false;
        if (items != null) {
            for (RecentItem item : items) {
                if (item == null) {
                    continue;
                }
                if (item.avatarUrl != null && !item.avatarUrl.isEmpty()) {
                    String key = buildKey(item);
                    if (!loadedAvatarKeys.contains(key)) {
                        hasAvatarUrls = true;
                    }
                }
            }
        }
    }

    @Override
    public String buildKey(RecentItem item) {
        String id = item.id == null ? "" : item.id;
        String url = item.avatarUrl == null ? "" : item.avatarUrl;
        return (item.isGroup ? "g:" : "u:") + id + "|" + url;
    }

    @Override
    public boolean isLoaded(String key) {
        return loadedAvatarKeys.contains(key);
    }

    @Override
    public void markLoading(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if (loadedAvatarKeys.contains(key)) {
            return;
        }
        avatarLoadStarted = true;
        pendingAvatarKeys.add(key);
    }

    @Override
    public void markLoaded(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if (pendingAvatarKeys.remove(key)) {
            loadedAvatarKeys.add(key);
        }
    }

    public boolean hasAvatarUrls() {
        return hasAvatarUrls;
    }

    public boolean isAvatarLoadStarted() {
        return avatarLoadStarted;
    }
}
