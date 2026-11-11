package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliShareUtil;
import com.im.oldchat.models.GroupMessage;
import com.im.oldchat.models.GroupRoleCache;
import com.im.oldchat.models.UserNameCache;
import com.im.oldchat.models.UserTitleCache;
import com.im.oldchat.util.GroupAvatarCache;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;
import com.im.oldchat.util.MessagePayload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

abstract class GroupMessageAdapterSupport2 extends GroupMessageAdapterSupport3 {
    protected static final int DERIVED_INFO_CACHE_LIMIT = 200;
    protected static final int TIME_LABEL_CACHE_LIMIT = 400;
    protected final android.support.v4.util.LruCache<String, CachedResourceInfo> resourceInfoCache =
            new android.support.v4.util.LruCache<String, CachedResourceInfo>(DERIVED_INFO_CACHE_LIMIT);
    protected final android.support.v4.util.LruCache<String, CachedMusicShareInfo> musicShareInfoCache =
            new android.support.v4.util.LruCache<String, CachedMusicShareInfo>(DERIVED_INFO_CACHE_LIMIT);
    protected final android.support.v4.util.LruCache<String, String> timeLabelCache =
            new android.support.v4.util.LruCache<String, String>(TIME_LABEL_CACHE_LIMIT);
    protected final Map<String, Boolean> recalledMessageStateCache = new HashMap<String, Boolean>();
    protected final Map<String, ResolvedMemberMeta> memberMetaCache = new HashMap<String, ResolvedMemberMeta>();
    protected final Map<String, String> memberAvatarCache = new HashMap<String, String>();
    protected int recalledMessageCacheSize = -1;
    protected int memberMetaVersion = 1;

    // 缓存未命中时触发一次群成员重载（回调由 Activity 设置）
    private static final long AVATAR_RELOAD_COOLDOWN_MS = 30000L;
    private Runnable onAvatarCacheMiss;
    private boolean avatarReloadPending = false;
    private long lastAvatarReloadAtMs = 0L;
    private final android.os.Handler avatarReloadHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable avatarReloadAction = new Runnable() {
        @Override
        public void run() {
            avatarReloadPending = false;
            lastAvatarReloadAtMs = android.os.SystemClock.uptimeMillis();
            if (onAvatarCacheMiss != null) {
                onAvatarCacheMiss.run();
            }
        }
    };

    public void setOnAvatarCacheMiss(Runnable callback) {
        this.onAvatarCacheMiss = callback;
    }

    GroupMessageAdapterSupport2(android.content.Context context, List<GroupMessage> messages, String myUID) {
        super(context, messages, myUID);
    }

    public void invalidateQuoteTargetCache() {
        recalledMessageStateCache.clear();
        recalledMessageCacheSize = -1;
    }

    protected void clearResolvedMemberMetaCache() {
        memberMetaCache.clear();
        memberAvatarCache.clear();
        memberMetaVersion++;
        if (memberMetaVersion == Integer.MAX_VALUE) {
            memberMetaVersion = 1;
        }
    }

    protected ResolvedMemberMeta resolveMemberMetaForMessage(GroupMessage message, String legacyUid) {
        // 群聊资料关联只认不可变 NCUID；legacyUid 仅用于尚未补齐 NCUID 的本地 pending 消息。
        String identity = message != null && message.sender_ncuid != null
                && message.sender_ncuid.length() > 0 ? message.sender_ncuid : legacyUid;
        if (message != null && message.cachedMemberMetaVersion == memberMetaVersion
                && sameText(message.cachedMemberMetaSourceUid, identity)
                && message.cachedResolvedMemberMeta instanceof ResolvedMemberMeta) {
            return (ResolvedMemberMeta) message.cachedResolvedMemberMeta;
        }
        String groupId = message == null ? null : message.group_id;
        ResolvedMemberMeta meta = resolveMemberMeta(identity, groupId);
        if (message != null) {
            message.cachedMemberMetaVersion = memberMetaVersion;
            message.cachedMemberMetaSourceUid = identity;
            message.cachedCanonicalUid = meta.canonicalUid;
            message.cachedSenderName = meta.name;
            message.cachedSenderTitle = meta.title;
            message.cachedSenderRole = meta.role;
            message.cachedResolvedMemberMeta = meta;
        }
        return meta;
    }

    protected String resolveAvatarUrl(String canonicalUid) {
        String key = canonicalUid == null ? "" : canonicalUid;
        String cached = memberAvatarCache.get(key);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        String avatarUrl = avatarMap.get(key);
        if (avatarUrl == null || avatarUrl.isEmpty()) {
            avatarUrl = GroupAvatarCache.getCachedAvatar(context, key);
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                avatarMap.put(key, avatarUrl);
            }
        }
        // 所有缓存都未命中时，延迟触发一次群成员重载（仅一次，防抖）
        long now = android.os.SystemClock.uptimeMillis();
        if ((avatarUrl == null || avatarUrl.isEmpty()) && key.length() > 0
                && onAvatarCacheMiss != null && !avatarReloadPending
                && (lastAvatarReloadAtMs == 0L
                || now - lastAvatarReloadAtMs >= AVATAR_RELOAD_COOLDOWN_MS)) {
            avatarReloadPending = true;
            avatarReloadHandler.postDelayed(avatarReloadAction, 500L);
        }
        String normalized = avatarUrl == null ? "" : avatarUrl;
        if (!normalized.isEmpty()) {
            memberAvatarCache.put(key, normalized);
        }
        return normalized;
    }

    protected String resolveAvatarUrlForMessage(GroupMessage message, String canonicalUid) {
        if (message != null && message.cachedMemberMetaVersion == memberMetaVersion
                && sameText(message.cachedAvatarSourceUid, canonicalUid)) {
            if (message.cachedAvatarUrl != null && message.cachedAvatarUrl.length() > 0) {
                return message.cachedAvatarUrl;
            }
        }
        String avatarUrl = resolveAvatarUrl(canonicalUid);
        if (message != null) {
            message.cachedMemberMetaVersion = memberMetaVersion;
            message.cachedAvatarSourceUid = canonicalUid;
            message.cachedAvatarUrl = avatarUrl;
        }
        return avatarUrl;
    }

    private static boolean sameText(String first, String second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        return first.equals(second);
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    protected void ensureQuoteTargetCache() {
        if (messages == null) {
            invalidateQuoteTargetCache();
            return;
        }
        int currentSize = messages.size();
        if (recalledMessageCacheSize == currentSize) {
            return;
        }
        // Incremental update: only process new messages instead of clearing all
        if (recalledMessageCacheSize < currentSize && recalledMessageCacheSize >= 0) {
            // Messages were added, only process the new ones
            for (int i = recalledMessageCacheSize; i < currentSize; i++) {
                GroupMessage item = messages.get(i);
                if (item == null || item.id == null || item.id.length() == 0) {
                    continue;
                }
                String type = item.msg_type == null ? "" : item.msg_type.trim().toLowerCase();
                recalledMessageStateCache.put(item.id, Boolean.valueOf("recall".equals(type)));
            }
        } else {
            // Cache invalid or messages removed, rebuild from scratch but limit iteration
            recalledMessageStateCache.clear();
            // Only cache last 500 messages to avoid memory and performance issues
            int startIdx = Math.max(0, currentSize - 500);
            for (int i = startIdx; i < currentSize; i++) {
                GroupMessage item = messages.get(i);
                if (item == null || item.id == null || item.id.length() == 0) {
                    continue;
                }
                String type = item.msg_type == null ? "" : item.msg_type.trim().toLowerCase();
                recalledMessageStateCache.put(item.id, Boolean.valueOf("recall".equals(type)));
            }
        }
        recalledMessageCacheSize = currentSize;
    }

    protected static class ResourceInfo {
        String title;
        String sub;
        boolean biliShare;
        String coverUrl;
        String coverFallbackUrl;
    }
    protected static class MusicShareInfo {
        String title;
        String artist;
        String sub;
        String coverUrl;
        String lyricsUrl;
        String itemId;
        String ownerUid;
    }

    protected static class CachedResourceInfo {
        String rawText;
        String url;
        ResourceInfo info;
    }

    protected static class CachedMusicShareInfo {
        String rawText;
        String songUrl;
        String thumbUrl;
        MusicShareInfo info;
    }

    protected static class ResolvedMemberMeta {
        String canonicalUid;
        String name;
        String title;
        int role;
    }

    protected ResourceInfo getResourceInfo(String rawText, String url) {
        String key = buildDerivedCacheKey("res", rawText, url, null);
        CachedResourceInfo cached = resourceInfoCache.get(key);
        if (cached != null && safeEquals(cached.rawText, rawText) && safeEquals(cached.url, url) && cached.info != null) {
            return cached.info;
        }
        ResourceInfo info = parseResourceInfo(rawText, url);
        CachedResourceInfo entry = new CachedResourceInfo();
        entry.rawText = rawText;
        entry.url = url;
        entry.info = info;
        resourceInfoCache.put(key, entry);
        return info;
    }

    protected MusicShareInfo getMusicShareInfo(String rawText, String songUrl, String thumbUrl) {
        String key = buildDerivedCacheKey("music", rawText, songUrl, thumbUrl);
        CachedMusicShareInfo cached = musicShareInfoCache.get(key);
        if (cached != null
                && safeEquals(cached.rawText, rawText)
                && safeEquals(cached.songUrl, songUrl)
                && safeEquals(cached.thumbUrl, thumbUrl)
                && cached.info != null) {
            return cached.info;
        }
        MusicShareInfo info = parseMusicShareInfo(rawText, songUrl, thumbUrl);
        CachedMusicShareInfo entry = new CachedMusicShareInfo();
        entry.rawText = rawText;
        entry.songUrl = songUrl;
        entry.thumbUrl = thumbUrl;
        entry.info = info;
        musicShareInfoCache.put(key, entry);
        return info;
    }
    protected ResourceInfo parseResourceInfo(String rawText, String url) {
        ResourceInfo info = new ResourceInfo();
        String text = rawText == null ? "" : rawText;
        String title = "";
        String size = "";
        String hint = "";
        boolean isBiliShare = BiliShareUtil.isShareUrl(url);
        BiliShareUtil.ShareInfo shareInfo = isBiliShare ? BiliShareUtil.parseShareUrl(url) : null;

        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.length() == 0) {
                continue;
            }

            if (title.length() == 0 && (line.startsWith("资源:") || line.startsWith("资源：") || line.startsWith("文件:") || line.startsWith("文件：")
                    || line.startsWith("视频:") || line.startsWith("视频：") || line.startsWith("标题:") || line.startsWith("标题："))) {
                int idx = line.indexOf(':');
                if (idx < 0) {
                    idx = line.indexOf('：');
                }
                if (idx >= 0 && idx + 1 < line.length()) {
                    title = line.substring(idx + 1).trim();
                }
                continue;
            }
            if (size.length() == 0 && (line.startsWith("大小:") || line.startsWith("大小：") || line.startsWith("时长:") || line.startsWith("时长："))) {
                int idx = line.indexOf(':');
                if (idx < 0) {
                    idx = line.indexOf('：');
                }
                if (idx >= 0 && idx + 1 < line.length()) {
                    size = line.substring(idx + 1).trim();
                }
                continue;
            }
            if (hint.length() == 0 && line.contains("点击")) {
                if (line.contains("观看")) {
                    hint = "点击观看";
                } else if (line.contains("下载")) {
                    hint = "点击下载";
                }
            }
        }

        if (title.length() == 0 && shareInfo != null && shareInfo.title != null && shareInfo.title.length() > 0) {
            title = shareInfo.title.trim();
        }
        if (title.length() == 0) {
            title = guessNameFromUrl(url);
        }
        if (title.length() == 0) {
            title = isBiliShare ? "B站视频" : "资源分享";
        }

        if (size.length() == 0 && shareInfo != null && shareInfo.duration > 0) {
            size = "时长 " + formatShareDuration(shareInfo.duration);
        }

        if (size.length() > 0) {
            info.sub = size;
        } else if (hint.length() > 0) {
            info.sub = hint;
        } else {
            info.sub = isBiliShare ? "点击观看" : "点击下载";
        }
        info.title = title;
        info.biliShare = isBiliShare;
        String shareCover = shareInfo == null ? null : shareInfo.cover;
        info.coverUrl = normalizeShareCover(shareCover, true);
        info.coverFallbackUrl = normalizeShareCover(shareCover, false);
        return info;
    }

    protected MusicShareInfo parseMusicShareInfo(String rawText, String songUrl, String thumbUrl) {
        MusicShareInfo info = new MusicShareInfo();
        String text = rawText == null ? "" : rawText.trim();
        String title = "";
        String artist = "";
        String duration = "";
        String cover = normalizeMusicCoverUrl(thumbUrl);
        String lyrics = "";
        String itemId = "";
        String ownerUid = "";

        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                JSONObject obj = new JSONObject(text);
                title = obj.optString("title", "");
                if (title.length() == 0) {
                    title = obj.optString("song_name", "");
                }
                if (title.length() == 0) {
                    title = obj.optString("name", "");
                }
                artist = obj.optString("artist", "");
                if (artist.length() == 0) {
                    artist = obj.optString("owner_name", "");
                }
                ownerUid = obj.optString("owner_uid", obj.optString("ownerUid", ""));
                duration = obj.optString("duration", "");
                lyrics = obj.optString("lyrics_url", "");
                itemId = obj.optString("item_id", obj.optString("id", ""));
                if (cover.length() == 0) {
                    cover = obj.optString("cover_url", "");
                }
                if (cover.length() == 0) {
                    cover = obj.optString("cover", "");
                }
                if (cover.length() == 0) {
                    cover = obj.optString("thumb_url", "");
                }
                if (cover.length() == 0) {
                    cover = obj.optString("thumb", "");
                }
            } catch (Exception e) {
            }
        }

        String firstLine = "";
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i] == null ? "" : lines[i].trim();
            if (line.length() == 0) {
                continue;
            }
            if (firstLine.length() == 0) {
                firstLine = line;
            }
            if (title.length() == 0) {
                String parsedTitle = parseLabelValue(line, new String[]{"歌曲:", "歌曲：", "歌名:", "歌名：", "标题:", "标题："});
                if (parsedTitle.length() > 0) {
                    title = parsedTitle;
                    continue;
                }
            }
            if (artist.length() == 0) {
                String parsedArtist = parseLabelValue(line, new String[]{"歌手:", "歌手：", "作者:", "作者：", "演唱:", "演唱：", "上传者:", "上传者："});
                if (parsedArtist.length() > 0) {
                    artist = parsedArtist;
                    continue;
                }
            }
            if (ownerUid.length() == 0) {
                String parsedUid = parseLabelValue(line, new String[]{"发布者UID:", "发布者UID：", "上传者UID:", "上传者UID：", "owner_uid:", "owner_uid："});
                if (parsedUid.length() > 0) {
                    ownerUid = parsedUid;
                    continue;
                }
            }
            if (duration.length() == 0) {
                String parsedDuration = parseLabelValue(line, new String[]{"时长:", "时长："});
                if (parsedDuration.length() > 0) {
                    duration = parsedDuration;
                    continue;
                }
            }
            if (cover.length() == 0) {
                String parsedCover = parseLabelValue(line, new String[]{"封面:", "封面：", "封面链接:", "封面链接：", "cover:", "cover：", "cover_url:", "cover_url：", "thumb_url:", "thumb_url："});
                if (parsedCover.length() > 0) {
                    cover = parsedCover;
                }
            }
            if (lyrics.length() == 0) {
                lyrics = parseLabelValue(line, new String[]{"歌词:", "歌词：", "歌词链接:", "歌词链接：", "lyrics_url:", "lyrics_url："});
            }
            if (itemId.length() == 0) {
                itemId = parseLabelValue(line, new String[]{"歌曲ID:", "歌曲ID：", "item_id:", "item_id："});
            }
        }

        if (title.length() == 0) {
            title = firstLine;
        }
        if (title.length() == 0) {
            title = guessNameFromUrl(songUrl);
        }
        if (title.length() == 0) {
            title = "音乐分享";
        }

        String sub;
        if (artist.length() > 0 && duration.length() > 0) {
            sub = artist + " · " + duration;
        } else if (artist.length() > 0) {
            sub = artist;
        } else if (duration.length() > 0) {
            sub = duration;
        } else {
            sub = "点击播放";
        }

        info.title = title;
        info.artist = artist;
        info.sub = sub;
        info.coverUrl = normalizeMusicCoverUrl(cover);
        info.lyricsUrl = lyrics == null ? "" : lyrics.trim();
        info.itemId = itemId == null ? "" : itemId.trim();
        info.ownerUid = ownerUid == null ? "" : ownerUid.trim();
        return info;
    }

    private String normalizeMusicCoverUrl(String raw) {
        if (raw == null) {
            return "";
        }
        String out = raw.trim();
        if (out.length() == 0) {
            return "";
        }
        if (out.length() > 1) {
            if ((out.startsWith("\"") && out.endsWith("\"")) || (out.startsWith("'") && out.endsWith("'"))) {
                out = out.substring(1, out.length() - 1).trim();
            }
        }
        out = out.replace("\\u0026", "&");
        out = out.replace("\\/", "/");
        if (out.startsWith("cover_url=") || out.startsWith("thumb_url=")) {
            int idx = out.indexOf('=');
            if (idx >= 0 && idx + 1 < out.length()) {
                out = out.substring(idx + 1).trim();
            }
        }
        if (out.startsWith("v1/") || out.startsWith("music/") || out.startsWith("uploads/")) {
            out = "/" + out;
        }
        if (out.startsWith("/v1/uploads/media/")) {
            String name = out.substring("/v1/uploads/media/".length()).trim();
            if (name.length() > 0 && name.indexOf('/') < 0 && name.indexOf('\\') < 0) {
                out = "/v1/music/cover/" + name;
            }
        } else if (out.startsWith("/uploads/media/")) {
            String name = out.substring("/uploads/media/".length()).trim();
            if (name.length() > 0 && name.indexOf('/') < 0 && name.indexOf('\\') < 0) {
                out = "/v1/music/cover/" + name;
            }
        } else if (out.startsWith("/music/cover/")) {
            out = "/v1" + out;
        }
        return out;
    }

    private String parseLabelValue(String line, String[] labels) {
        if (line == null || labels == null) {
            return "";
        }
        String value = line.trim();
        if (value.length() == 0) {
            return "";
        }
        for (int i = 0; i < labels.length; i++) {
            String label = labels[i];
            if (label != null && value.startsWith(label)) {
                String parsed = value.substring(label.length()).trim();
                return parsed == null ? "" : parsed;
            }
        }
        return "";
    }

    private String getCachedTimeLabel(long timestamp) {
        String key = String.valueOf(timestamp);
        String cached = timeLabelCache.get(key);
        if (cached != null) {
            return cached;
        }
        String label = ChatTimeFormatter.formatTime(timestamp);
        if (label == null) {
            label = "";
        }
        timeLabelCache.put(key, label);
        return label;
    }

    private String buildDerivedCacheKey(String prefix, String first, String second, String third) {
        StringBuilder builder = new StringBuilder(prefix);
        builder.append('#').append(safeHash(first));
        builder.append(':').append(safeHash(second));
        builder.append(':').append(safeHash(third));
        return builder.toString();
    }

    private int safeHash(String value) {
        return value == null ? 0 : value.hashCode();
    }

    protected void bindResourcePreview(ViewHolder holder, ResourceInfo info) {
        if (holder == null || holder.resourceCoverContainer == null) {
            return;
        }
        holder.resourceCoverContainer.setVisibility(View.GONE);
        if (holder.resourceCover != null) {
            holder.resourceCover.setTag(null);
            holder.resourceCover.setImageDrawable(null);
        }
        if (holder.resourcePlay != null) {
            holder.resourcePlay.setVisibility(View.VISIBLE);
            holder.resourcePlay.setImageResource(R.drawable.ic_action_video);
        }
        if (info == null || !info.biliShare) {
            return;
        }
        String cover = info.coverUrl == null ? "" : info.coverUrl.trim();
        String fallback = info.coverFallbackUrl == null ? "" : info.coverFallbackUrl.trim();
        if (cover.length() == 0) {
            cover = fallback;
            fallback = "";
        }
        if (cover.length() == 0 || holder.resourceCover == null) {
            return;
        }
        holder.resourceCoverContainer.setVisibility(View.VISIBLE);
        loadShareCover(holder.resourceCover, cover, fallback);
    }


    protected void bindMusicPreview(ViewHolder holder, String coverUrl) {
        if (holder == null || holder.resourceCoverContainer == null) {
            return;
        }
        holder.resourceCoverContainer.setVisibility(View.GONE);
        if (holder.resourceCover != null) {
            holder.resourceCover.setTag(null);
            holder.resourceCover.setImageDrawable(null);
        }
        if (holder.resourcePlay != null) {
            holder.resourcePlay.setVisibility(View.VISIBLE);
            holder.resourcePlay.setImageResource(R.drawable.ic_voice);
        }
    }

    private void loadShareCover(final ImageView view, final String cover, final String fallbackCover) {
        if (view == null || cover == null || cover.length() == 0) {
            return;
        }
        Object tag = view.getTag();
        if (tag != null && cover.equals(tag)) {
            return;
        }
        view.setTag(cover);
        view.setImageResource(R.drawable.bg_image_placeholder);
        ImageLoader.load(view, cover, new ImageLoader.ImageLoadListener() {
            @Override
            public void onComplete(String url) {
                Object currentTag = view.getTag();
                if (currentTag == null || !cover.equals(currentTag)) {
                    return;
                }
                // Avoid overriding an already loaded cover with fallback placeholder.
                if (view.getDrawable() instanceof BitmapDrawable) {
                    return;
                }
                if (fallbackCover == null || fallbackCover.length() == 0 || cover.equals(fallbackCover)) {
                    return;
                }
                android.content.Context ctx = view.getContext();
                if (ctx != null && ImageLoader.isCached(ctx, cover)) {
                    return;
                }
                view.setTag(fallbackCover);
                view.setImageResource(R.drawable.bg_image_placeholder);
                ImageLoader.load(view, fallbackCover);
            }
        });
    }

    private String normalizeShareCover(String cover, boolean stripProcess) {
        if (cover == null) {
            return "";
        }
        String out = cover.trim();
        if (out.length() == 0) {
            return "";
        }
        out = out.replace("\\u0026", "&");
        out = out.replace("\\/", "/");
        out = BiliApi.normalizeUrl(out);
        if (out.startsWith("//")) {
            out = "https:" + out;
        }
        int hash = out.indexOf('#');
        if (hash >= 0) {
            out = out.substring(0, hash);
        }
        int q = out.indexOf('?');
        if (q >= 0) {
            out = out.substring(0, q);
        }
        if (stripProcess) {
            int slash = out.lastIndexOf('/');
            int at = out.indexOf('@', slash >= 0 ? slash : 0);
            if (at > 0) {
                out = out.substring(0, at);
            }
            out = stripAfterImageExt(out);
        }
        return out;
    }

    private String stripAfterImageExt(String value) {
        if (value == null || value.length() == 0) {
            return "";
        }
        String lower = value.toLowerCase(java.util.Locale.US);
        String[] exts = new String[]{".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"};
        int cut = -1;
        for (int i = 0; i < exts.length; i++) {
            int idx = lower.indexOf(exts[i]);
            if (idx < 0) {
                continue;
            }
            int end = idx + exts[i].length();
            if (cut < 0 || end < cut) {
                cut = end;
            }
        }
        if (cut > 0 && cut < value.length()) {
            return value.substring(0, cut);
        }
        return value;
    }

    private String formatShareDuration(long durationSec) {
        if (durationSec <= 0) {
            return "";
        }
        long hours = durationSec / 3600;
        long minutes = (durationSec % 3600) / 60;
        long seconds = durationSec % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    protected void openMusicShare(String title, String artist, String songUrl, String coverUrl,
                                  String lyricsUrl, String itemId) {
        openMusicShare(title, artist, songUrl, coverUrl, lyricsUrl, itemId, "");
    }

    protected void openMusicShare(String title, String artist, String songUrl, String coverUrl,
                                  String lyricsUrl, String itemId, String ownerUid) {
        String url = MediaUrlResolver.resolve(songUrl);
        if (url == null || url.length() == 0) {
            Toast.makeText(context, "无法获取播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(context, MusicPlayerActivity.class);
        intent.putExtra("song_name", title == null ? "" : title);
        intent.putExtra("song_url", url);
        intent.putExtra("cover_url", coverUrl == null ? "" : coverUrl);
        intent.putExtra("lyrics_url", lyricsUrl == null ? "" : lyricsUrl);
        intent.putExtra("item_id", itemId == null ? "" : itemId);
        intent.putExtra("owner_name", artist == null ? "" : artist);
        intent.putExtra("owner_uid", ownerUid == null ? "" : ownerUid);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    protected void openVideo(GroupMessage message) {
        if (message == null || message.media_url == null || message.media_url.isEmpty()) {
            Toast.makeText(context, "无法获取播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = MediaUrlResolver.resolve(message.media_url);
        if (url == null || url.length() == 0) {
            Toast.makeText(context, "无法获取播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), "video/*");
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(context, "无法播放视频", Toast.LENGTH_SHORT).show();
        }
    }
    protected void openBiliShare(String url) {
        BiliShareUtil.ShareInfo info = BiliShareUtil.parseShareUrl(url);
        Intent intent = new Intent(context, OldViewVideoDetailActivity.class);
        if (info.bvid != null && info.bvid.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_BVID, info.bvid);
        }
        if (info.aid > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_AID, info.aid);
        }
        if (info.cid > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_CID, info.cid);
        }
        String normalizedCover = normalizeShareCover(info.cover, false);
        if (normalizedCover.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_COVER, normalizedCover);
        }
        if (info.title != null && info.title.length() > 0) {
            intent.putExtra(OldViewVideoDetailActivity.EXTRA_TITLE, info.title);
        }
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }
    protected void openRedPacket(String packetId) {
        if (packetId == null || packetId.isEmpty()) {
            Toast.makeText(context, R.string.red_packet_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(context, RedPacketOpenActivity.class);
        intent.putExtra(RedPacketOpenActivity.EXTRA_PACKET_ID, packetId);
        if (!(context instanceof android.app.Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }
    protected String guessNameFromUrl(String url) {
        if (url == null) {
            return "";
        }
        String s = url;
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int h = s.indexOf('#');
        if (h >= 0) {
            s = s.substring(0, h);
        }
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < s.length()) {
            return s.substring(slash + 1);
        }
        return "";
    }
    protected android.view.animation.Animation buildVoiceAnimation() {
        android.view.animation.AlphaAnimation anim = new android.view.animation.AlphaAnimation(0.4f, 1.0f);
        anim.setDuration(400);
        anim.setRepeatMode(android.view.animation.Animation.REVERSE);
        anim.setRepeatCount(android.view.animation.Animation.INFINITE);
        return anim;
    }
    protected void bindQuote(ViewHolder holder, MessagePayload.Quote quote, boolean isMine) {
        if (holder.quoteContainer == null) {
            return;
        }
        if (quote == null) {
            holder.quoteContainer.setVisibility(View.GONE);
            holder.quoteContainer.setOnClickListener(null);
            if (holder.quoteImage != null) {
                holder.quoteImage.setVisibility(View.GONE);
                holder.quoteImage.setTag(null);
            }
            return;
        }
        String sender = quote.fromName != null && !quote.fromName.isEmpty() ? quote.fromName : quote.fromUid;
        if (sender == null || sender.isEmpty()) {
            sender = "成员";
        }
        boolean targetRecalled = isQuoteTargetRecalled(quote);
        String quoteType = quote.type;
        String quoteMediaKind = quote.mediaKind;
        String quoteText = quote.text;
        String quoteThumbUrl = quote.thumbUrl;
        if (targetRecalled || "recall".equalsIgnoreCase(quoteType)) {
            quoteType = "recall";
            quoteMediaKind = "";
            quoteText = context.getString(R.string.message_recalled);
            quoteThumbUrl = "";
        }
        String quoteUid = quote.fromNcuid != null && !quote.fromNcuid.isEmpty() ? quote.fromNcuid : quote.fromUid;
        if (quoteUid == null) quoteUid = "";
        ResolvedMemberMeta quoteMeta = resolveMemberMeta(quoteUid);
        String canonicalQuoteUid = quoteMeta.canonicalUid != null && !quoteMeta.canonicalUid.isEmpty()
                ? quoteMeta.canonicalUid : quoteUid;
        if (quoteMeta.name != null && !quoteMeta.name.isEmpty()) {
            sender = quoteMeta.name;
        }
        int quoteColor = com.im.oldchat.util.UserNameColorUtil.getUserNameColor(context, canonicalQuoteUid);
        if (isMine) {
            holder.quoteSender.setText(sender);
            holder.quoteSender.setTextColor(0xFFFFFFFF);
            if (holder.quoteLine != null) {
                holder.quoteLine.setBackgroundColor(0xB3FFFFFF);
            }
            holder.quoteContent.setTextColor(0xD9FFFFFF);
        } else {
            boolean isDark = com.im.oldchat.util.ThemeColorUtil.isDarkAppearance(context);
            holder.quoteSender.setText(sender);
            holder.quoteSender.setTextColor(quoteColor);
            if (holder.quoteLine != null) {
                holder.quoteLine.setBackgroundColor(quoteColor);
            }
            holder.quoteContent.setTextColor(isDark ? 0xCCFFFFFF : ContextCompat.getColor(context, R.color.color_text_secondary));
        }
        holder.quoteContent.setText(ChatMessageUtil.quotePreview(quoteType, quoteMediaKind, quoteText));
        holder.quoteContainer.setVisibility(View.VISIBLE);
        bindQuoteThumb(holder.quoteImage, quoteThumbUrl);
        final MessagePayload.Quote target = quote;
        holder.quoteContainer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (quoteClickListener != null && target.id != null && !target.id.isEmpty()) {
                    quoteClickListener.onQuoteClick(target);
                }
            }
        });
    }

    private boolean isQuoteTargetRecalled(MessagePayload.Quote quote) {
        if (quote == null || quote.id == null || quote.id.isEmpty() || messages == null) {
            return false;
        }
        ensureQuoteTargetCache();
        Boolean recalled = recalledMessageStateCache.get(quote.id);
        return recalled != null && recalled.booleanValue();
    }
    protected void bindQuoteThumb(ImageView view, String url) {
        if (view == null) {
            return;
        }
        if (url == null || url.isEmpty()) {
            view.setVisibility(View.GONE);
            view.setTag(null);
            view.setImageDrawable(null);
            return;
        }
        view.setVisibility(View.VISIBLE);
        view.setImageResource(R.drawable.bg_image_placeholder);
        ImageLoader.load(view, url);
    }
    protected MessagePayload getPayload(GroupMessage msg) {
        if (msg == null) {
            return new MessagePayload();
        }
        if (msg.cachedPayload != null) {
            return msg.cachedPayload;
        }
        String key = msg.id != null && msg.id.length() > 0 ? msg.id : msg.body;
        if (key != null) {
            CachedPayload cached = payloadCache.get(key);
            if (cached != null && safeEquals(cached.body, msg.body) && cached.payload != null) {
                msg.cachedPayload = cached.payload;
                return cached.payload;
            }
        }
        MessagePayload payload = MessagePayload.fromBody(msg.body);
        msg.cachedPayload = payload;
        cachePayload(key, msg.body, payload);
        return payload;
    }
    protected void cachePayload(String key, String body, MessagePayload payload) {
        if (key == null) {
            return;
        }
        if (payloadCache.size() >= PAYLOAD_CACHE_LIMIT) {
            payloadCache.clear();
        }
        CachedPayload entry = new CachedPayload();
        entry.body = body;
        entry.payload = payload;
        payloadCache.put(key, entry);
    }
    protected boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }
    protected void bindReadStatus(ViewHolder holder, int readCount, boolean useMedia, boolean isImage) {
        if (holder == null) {
            return;
        }
        boolean showOnImage = useMedia && isImage && holder.imageStatus != null;
        if (showOnImage) {
            // 图片/视频消息：勾叠加在图片右下角
            if (holder.statusRowMedia != null) {
                holder.statusRowMedia.setVisibility(View.GONE);
            }
            if (holder.statusRow != null) {
                holder.statusRow.setVisibility(View.GONE);
            }
            holder.imageStatus.setVisibility(View.VISIBLE);
            holder.imageStatus.setImageResource(readCount > 0 ? R.drawable.read : R.drawable.sent);
            return;
        }
        if (holder.imageStatus != null) {
            holder.imageStatus.setVisibility(View.GONE);
        }
        LinearLayout row = useMedia ? holder.statusRowMedia : holder.statusRow;
        ImageView icon = useMedia ? holder.statusIconMedia : holder.statusIcon;
        TextView count = useMedia ? holder.readCountMedia : holder.readCount;
        if (holder.statusRowMedia != null && !useMedia) {
            holder.statusRowMedia.setVisibility(View.GONE);
        }
        if (holder.statusRow != null && useMedia) {
            holder.statusRow.setVisibility(View.GONE);
        }
        if (row == null || icon == null || count == null) {
            return;
        }
        row.setVisibility(View.VISIBLE);
        if (readCount > 0) {
            icon.setImageResource(R.drawable.read);
            count.setText(String.valueOf(readCount));
        } else {
            icon.setImageResource(R.drawable.sent);
            count.setText("");
        }
    }
    protected boolean canRecallMessage(GroupMessage msg) {
        if (msg == null || msg.from_uid == null || msg.from_uid.isEmpty()) {
            return false;
        }
        boolean isMine = com.im.oldchat.util.NcuidIdentity.isMine(
                context, msg.sender_ncuid, msg.from_uid, myUID);
        if (isMine) {
            if (msg.created_at <= 0) {
                return false;
            }
            long currentTime = System.currentTimeMillis();
            long messageTime = ChatMessageUtil.normalizeTimestamp(msg.created_at);
            return (currentTime - messageTime) < 60 * 1000;
        }
        if (myRole >= 2) {
            return true;
        }
        if (myRole >= 1) {
            int senderRole = resolveMemberMetaForMessage(msg, msg.from_uid).role;
            return senderRole < 2;
        }
        return false;
    }
    protected String resolveName(String uid) {
        return resolveMemberMeta(uid, null).name;
    }

    /** 读取本地存储的"我的显示名"（登录响应写入 auth prefs）。 */
    private String readMyDisplayName() {
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("auth", android.content.Context.MODE_PRIVATE);
            String display = prefs.getString("display_name", "");
            if (display != null && !display.isEmpty()) {
                return display;
            }
            // 回退 username（登录响应里的 username 字段，不是登录输入框的值）
            String username = prefs.getString("my_username", "");
            if (username != null && !username.isEmpty()) {
                return username;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }
    protected String resolveTitle(String uid) {
        return resolveMemberMeta(uid, null).title;
    }
    protected int resolveRole(String uid) {
        return resolveMemberMeta(uid, null).role;
    }

    protected String resolveCanonicalUid(String uid) {
        return resolveMemberMeta(uid, null).canonicalUid;
    }

    protected ResolvedMemberMeta resolveMemberMeta(String rawUid) {
        return resolveMemberMeta(rawUid, null);
    }

    protected ResolvedMemberMeta resolveMemberMeta(String rawUid, String groupId) {
        String cacheKey = rawUid == null ? "" : rawUid;
        ResolvedMemberMeta cached = memberMetaCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        ResolvedMemberMeta meta = new ResolvedMemberMeta();
        if (cacheKey.length() == 0) {
            meta.canonicalUid = "";
            meta.name = "";
            meta.title = "";
            meta.role = 0;
            memberMetaCache.put(cacheKey, meta);
            return meta;
        }
        String resolvedUid = cacheKey;
        // 自己（兼容 from_uid 和 sender_ncuid 两种 key）：直接用本地用户名，
        // 避免 pending 消息显示 UID/NCUID（发送前名字不对）。
        boolean isSelf = false;
        try {
            isSelf = com.im.oldchat.util.NcuidIdentity.isMine(context, cacheKey, cacheKey, myUID);
        } catch (Throwable ignored) {
        }
        if (myUID != null && (myUID.equals(resolvedUid) || isSelf)) {
            String localName = readMyDisplayName();
            if (localName == null || localName.isEmpty()) {
                localName = com.im.oldchat.models.UserNameCache.getName(context, resolvedUid);
            }
            if (localName == null || localName.isEmpty()) {
                localName = resolvedUid;
            }
            // 自己的称号/角色也要查（不能清空——否则自己看不到自己的 Rank/管理员标识）
            String selfTitle = titleMap.get(resolvedUid);
            if (selfTitle == null || selfTitle.isEmpty()) {
                selfTitle = titleMap.get(cacheKey);
            }
            if (selfTitle == null || selfTitle.isEmpty()) {
                selfTitle = com.im.oldchat.models.UserTitleCache.getTitle(context, resolvedUid);
            }
            if (selfTitle == null || selfTitle.isEmpty()) {
                selfTitle = com.im.oldchat.models.UserTitleCache.getTitle(context, cacheKey);
            }
            Integer selfRole = roleMap.get(resolvedUid);
            if (selfRole == null) {
                selfRole = roleMap.get(cacheKey);
            }
            if (selfRole == null) {
                int cachedRole = GroupRoleCache.getRole(context, groupId, resolvedUid);
                if (cachedRole > 0) {
                    selfRole = Integer.valueOf(cachedRole);
                }
            }
            meta.canonicalUid = resolvedUid;
            meta.name = localName;
            meta.title = selfTitle == null ? "" : selfTitle;
            meta.role = selfRole == null ? 0 : selfRole.intValue();
            memberMetaCache.put(cacheKey, meta);
            return meta;
        }
        String name = nameMap.get(resolvedUid);
        if (name == null || name.isEmpty()) {
            name = nameMap.get(cacheKey);
        }
        if (name == null || name.isEmpty()) {
            name = UserNameCache.getName(context, resolvedUid);
        }
        if (name == null || name.isEmpty()) {
            name = UserNameCache.getName(context, cacheKey);
        }
        if (name == null || name.isEmpty() || name.equals(resolvedUid) || name.equals(cacheKey)) {
            com.im.oldchat.models.GroupMember m = com.im.oldchat.models.GroupMemberMetaCache.getMemberByIdentity(context, groupId, resolvedUid);
            if (m == null && !resolvedUid.equals(cacheKey)) {
                m = com.im.oldchat.models.GroupMemberMetaCache.getMemberByIdentity(context, groupId, cacheKey);
            }
            if (m != null) {
                String resolvedName = com.im.oldchat.models.FriendNameResolver.resolve("", m.display_name, m.username, m.uid);
                if (resolvedName != null && !resolvedName.isEmpty()) {
                    name = resolvedName;
                    nameMap.put(resolvedUid, name);
                    nameMap.put(cacheKey, name);
                }
                if (m.user_title != null && !m.user_title.isEmpty()) {
                    titleMap.put(resolvedUid, m.user_title);
                    titleMap.put(cacheKey, m.user_title);
                }
                if (m.role != 0) {
                    roleMap.put(resolvedUid, Integer.valueOf(m.role));
                    roleMap.put(cacheKey, Integer.valueOf(m.role));
                }
            }
        }
        if (name == null || name.isEmpty()) {
            name = resolvedUid;
        }
        String title = titleMap.get(resolvedUid);
        if (title == null || title.isEmpty()) {
            title = titleMap.get(cacheKey);
        }
        if (title == null || title.isEmpty()) {
            title = UserTitleCache.getTitle(context, resolvedUid);
        }
        if (title == null || title.isEmpty()) {
            title = UserTitleCache.getTitle(context, cacheKey);
        }
        Integer role = roleMap.get(resolvedUid);
        if (role == null) {
            role = roleMap.get(cacheKey);
        }
        if (role == null) {
            int cachedRole = GroupRoleCache.getRole(context, groupId, resolvedUid);
            if (cachedRole > 0) {
                role = Integer.valueOf(cachedRole);
                roleMap.put(resolvedUid, role);
            }
        }
        if (role == null) {
            int cachedRole = GroupRoleCache.getRole(context, groupId, cacheKey);
            if (cachedRole > 0) {
                role = Integer.valueOf(cachedRole);
                roleMap.put(cacheKey, role);
            }
        }
        meta.canonicalUid = resolvedUid;
        meta.name = name == null ? "" : name;
        meta.title = title == null ? "" : title;
        meta.role = role == null ? 0 : role.intValue();
        memberMetaCache.put(cacheKey, meta);
        return meta;
    }
    protected void bindRoleBadge(TextView badge, int role) {
        if (badge == null) {
            return;
        }
        badge.setBackgroundDrawable(null);
        badge.setPadding(0, 0, 0, 0);
        if (role == 2) {
            badge.setText("群主");
            badge.setTextColor(0xFFF59E0B);
            badge.setVisibility(View.VISIBLE);
        } else if (role == 1) {
            badge.setText("管理员");
            badge.setTextColor(0xFF3B82F6);
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }
    protected void bindTimeLabel(ViewHolder holder, GroupMessage msg, int position) {
        if (holder.messageTime == null) {
            return;
        }
        boolean shouldShow = shouldShowTime(msg, position);
        if (shouldShow) {
            String label = getCachedTimeLabel(msg.created_at);
            if (label.isEmpty()) {
                holder.messageTime.setVisibility(View.GONE);
            } else {
                holder.messageTime.setText(label);
                holder.messageTime.setVisibility(View.VISIBLE);
            }
        } else {
            holder.messageTime.setVisibility(View.GONE);
        }
    }
    protected boolean shouldShowTime(GroupMessage msg, int position) {
        if (position == 0) {
            return true;
        }
        GroupMessage prev = messages.get(position - 1);
        return ChatTimeFormatter.shouldShowTime(msg.created_at, prev.created_at);
    }

    protected void bindMessageFooterTime(ViewHolder holder, GroupMessage msg, boolean isMine) {
        if (holder == null || holder.messageFooterTime == null) {
            return;
        }
        String label = ChatTimeFormatter.formatMessageFooterTime(msg == null ? 0L : msg.created_at);
        if (label == null || label.length() == 0) {
            holder.messageFooterTime.setText("");
            holder.messageFooterTime.setVisibility(View.GONE);
            return;
        }
        holder.messageFooterTime.setText(label);
        android.view.ViewGroup.LayoutParams rawParams = holder.messageFooterTime.getLayoutParams();
        if (rawParams instanceof android.widget.LinearLayout.LayoutParams) {
            ((android.widget.LinearLayout.LayoutParams) rawParams).gravity =
                    isMine ? android.view.Gravity.RIGHT : android.view.Gravity.LEFT;
            holder.messageFooterTime.setLayoutParams(rawParams);
        }
        holder.messageFooterTime.setGravity(isMine ? android.view.Gravity.RIGHT : android.view.Gravity.LEFT);
        holder.messageFooterTime.setVisibility(View.VISIBLE);
    }

    protected void resetMessageHolderForBind(ViewHolder holder) {
        holder.text.setVisibility(View.GONE);
        if (holder.imageContainer != null) {
            holder.imageContainer.setVisibility(View.GONE);
        }
        if (holder.videoPlay != null) {
            holder.videoPlay.setVisibility(View.GONE);
        }
        holder.image.setVisibility(View.GONE);
        holder.image.clearAnimation();
        holder.image.setTag(null);
        holder.image.setImageDrawable(null);
        holder.voiceRow.setVisibility(View.GONE);
        holder.voiceRow.setOnClickListener(null);
        if (holder.voiceTranscript != null) {
            holder.voiceTranscript.setVisibility(View.GONE);
            holder.voiceTranscript.setText("");
        }
        if (holder.voiceLoading != null) {
            holder.voiceLoading.setVisibility(View.GONE);
        }
        if (holder.messageFooterTime != null) {
            holder.messageFooterTime.setVisibility(View.GONE);
        }
        if (holder.imageTimeBadge != null) {
            holder.imageTimeBadge.setVisibility(View.GONE);
        }
        if (holder.resourceFooter != null) {
            holder.resourceFooter.setVisibility(View.GONE);
        }
        if (holder.voiceTime != null) {
            holder.voiceTime.setVisibility(View.GONE);
        }
        if (holder.imageLoading != null) {
            holder.imageLoading.setVisibility(View.GONE);
        }
        holder.image.setOnClickListener(null);
        holder.bubble.setOnClickListener(null);
        holder.text.setOnClickListener(null);
        holder.messageContainer.setOnClickListener(null);
        holder.messageContainer.setOnLongClickListener(null);
        holder.bubble.setOnLongClickListener(null);
        holder.text.setOnLongClickListener(null);
        holder.image.setOnLongClickListener(null);
        holder.voiceRow.setOnLongClickListener(null);
        if (holder.voiceTranscript != null) {
            holder.voiceTranscript.setOnLongClickListener(null);
        }
        holder.quoteContainer.setVisibility(View.GONE);
        holder.quoteContainer.setOnLongClickListener(null);
        if (holder.quoteImage != null) {
            holder.quoteImage.setVisibility(View.GONE);
            holder.quoteImage.setTag(null);
            holder.quoteImage.setImageDrawable(null);
        }
        holder.voiceIcon.clearAnimation();
        holder.avatar.setImageDrawable(null);
        holder.avatar.setTag(null);
        holder.avatar.setVisibility(View.GONE);
        holder.avatar.setOnClickListener(null);
        holder.avatar.setOnLongClickListener(null);
        if (holder.avatarRight != null) {
            holder.avatarRight.setImageDrawable(null);
            holder.avatarRight.setTag(null);
            holder.avatarRight.setVisibility(View.GONE);
            holder.avatarRight.setOnClickListener(null);
            holder.avatarRight.setOnLongClickListener(null);
        }
        holder.resourceCard.setVisibility(View.GONE);
        if (holder.fileUploadProgress != null) {
            holder.fileUploadProgress.setVisibility(View.GONE);
            holder.fileUploadProgress.setIndeterminate(false);
            holder.fileUploadProgress.setProgress(0);
        }
        holder.resourceCard.setOnClickListener(null);
        holder.resourceAction.setOnClickListener(null);
        holder.resourceCard.setOnLongClickListener(null);
        holder.resourceAction.setOnLongClickListener(null);
        if (holder.resourceCoverContainer != null) {
            holder.resourceCoverContainer.setVisibility(View.GONE);
        }
        if (holder.resourceCover != null) {
            holder.resourceCover.setTag(null);
            holder.resourceCover.setImageDrawable(null);
        }
        holder.redPacketContainer.setVisibility(View.GONE);
        holder.redPacketContainer.setOnClickListener(null);
        holder.redPacketContainer.setOnLongClickListener(null);
        if (holder.senderRow != null) {
            holder.senderRow.setVisibility(View.GONE);
        }
        if (holder.senderBadge != null) {
            holder.senderBadge.setVisibility(View.GONE);
        }
        if (holder.senderTitle != null) {
            holder.senderTitle.setVisibility(View.GONE);
        }
    }
}
