package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliShareUtil;
import com.im.oldchat.models.Message;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;
import com.im.oldchat.util.MessagePayload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

abstract class MessageAdapterSupport2 extends android.support.v7.widget.RecyclerView.Adapter<android.support.v7.widget.RecyclerView.ViewHolder> {
    protected static final int DERIVED_INFO_CACHE_LIMIT = 200;
    protected static final int TIME_LABEL_CACHE_LIMIT = 400;
    protected List<Message> messages;
    protected String myUID;
    protected Context context;
    protected MessageVoicePlayer voicePlayer;
    protected float fontScale = 1.0f;
    protected final android.support.v4.util.LruCache<String, CachedResourceInfo> resourceInfoCache =
            new android.support.v4.util.LruCache<String, CachedResourceInfo>(DERIVED_INFO_CACHE_LIMIT);
    protected final android.support.v4.util.LruCache<String, CachedMusicShareInfo> musicShareInfoCache =
            new android.support.v4.util.LruCache<String, CachedMusicShareInfo>(DERIVED_INFO_CACHE_LIMIT);
    protected final android.support.v4.util.LruCache<String, String> timeLabelCache =
            new android.support.v4.util.LruCache<String, String>(TIME_LABEL_CACHE_LIMIT);
    protected final Map<String, Boolean> recalledMessageStateCache = new HashMap<String, Boolean>();
    protected int recalledMessageCacheSize = -1;

    @Override
    public long getItemId(int position) {
        if (position < 0 || position >= getItemCount()) {
            return android.support.v7.widget.RecyclerView.NO_ID;
        }
        if (position >= messages.size()) {
            return Long.MIN_VALUE + 1L + position;
        }
        Message msg = messages.get(position);
        return buildStableItemId(msg, position);
    }

    protected long buildStableItemId(Message msg, int position) {
        String key = messageIdentityKey(msg, position);
        if (key.length() == 0) {
            return position;
        }
        long hash = 1125899906842597L;
        for (int i = 0; i < key.length(); i++) {
            hash = 31L * hash + key.charAt(i);
        }
        return hash;
    }

    protected String messageIdentityKey(Message msg, int position) {
        if (msg == null) {
            return "row:" + position;
        }
        if (msg.id != null && msg.id.length() > 0) {
            return "id:" + msg.id;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ts:").append(msg.created_at);
        sb.append("|from:").append(msg.from_uid == null ? "" : msg.from_uid);
        sb.append("|type:").append(msg.msg_type == null ? "" : msg.msg_type);
        return sb.toString();
    }

    protected void invalidateQuoteTargetCache() {
        recalledMessageStateCache.clear();
        recalledMessageCacheSize = -1;
    }

    protected void ensureQuoteTargetCache() {
        if (messages == null) {
            invalidateQuoteTargetCache();
            return;
        }
        if (recalledMessageCacheSize == messages.size()) {
            return;
        }
        recalledMessageStateCache.clear();
        for (int i = 0; i < messages.size(); i++) {
            Message item = messages.get(i);
            if (item == null || item.id == null || item.id.length() == 0) {
                continue;
            }
            String type = item.msg_type == null ? "" : item.msg_type.trim().toLowerCase();
            recalledMessageStateCache.put(item.id, Boolean.valueOf("recall".equals(type)));
        }
        recalledMessageCacheSize = messages.size();
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
            if (title.length() == 0 && (line.startsWith("资源:") || line.startsWith("资源：") || line.startsWith("文件:")
                    || line.startsWith("文件：") || line.startsWith("视频:") || line.startsWith("视频：")
                    || line.startsWith("标题:") || line.startsWith("标题："))) {
                int idx = line.indexOf(':');
                if (idx < 0) {
                    idx = line.indexOf('：');
                }
                if (idx >= 0 && idx + 1 < line.length()) {
                    title = line.substring(idx + 1).trim();
                }
                continue;
            }
            if (size.length() == 0 && (line.startsWith("大小:") || line.startsWith("大小：")
                    || line.startsWith("时长:") || line.startsWith("时长："))) {
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

    private boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    protected void bindResourcePreview(MessageAdapterSupport1.ViewHolder holder, ResourceInfo info) {
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


    protected void bindMusicPreview(MessageAdapterSupport1.ViewHolder holder, String coverUrl) {
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

    protected void openVideo(Message message) {
        if (message == null || message.media_url == null || message.media_url.isEmpty()) {
            Toast.makeText(context, "无法获取播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] candidates = MediaUrlResolver.resolveCandidates(message.media_url);
        if (candidates == null || candidates.length == 0) {
            Toast.makeText(context, "无法获取播放地址", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(context, OldViewVideoFullActivity.class);
            // 传递原始地址，由播放器按 files → 60 → oc 顺序处理错误回退。
            intent.putExtra(OldViewVideoFullActivity.EXTRA_URL, message.media_url);
            intent.putExtra(OldViewVideoFullActivity.EXTRA_TITLE, "视频");
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

    protected void bindQuote(MessageAdapterSupport1.ViewHolder holder, MessagePayload.Quote quote, boolean isMine) {
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
            sender = "对方";
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
        boolean isSelf = false;
        try {
            isSelf = com.im.oldchat.util.NcuidIdentity.isMine(context, quoteUid, quoteUid, myUID);
        } catch (Throwable ignored) {}
        String canonicalQuoteUid = quoteUid;
        if (isSelf) {
            String selfUid = com.im.oldchat.util.MyUidStore.getCurrentUid(context);
            if (selfUid != null && selfUid.length() > 0) canonicalQuoteUid = selfUid;
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

    protected boolean canRecallMessage(Message msg) {
        if (msg.created_at <= 0) {
            return false;
        }
        long currentTime = System.currentTimeMillis();
        long messageTime = ChatMessageUtil.normalizeTimestamp(msg.created_at);
        return (currentTime - messageTime) < 60 * 1000;
    }

    protected int dpToPx(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    protected android.view.animation.Animation buildVoiceAnimation() {
        android.view.animation.AlphaAnimation anim = new android.view.animation.AlphaAnimation(0.4f, 1.0f);
        anim.setDuration(400);
        anim.setRepeatMode(android.view.animation.Animation.REVERSE);
        anim.setRepeatCount(android.view.animation.Animation.INFINITE);
        return anim;
    }

    protected void bindTimeLabel(MessageAdapterSupport1.ViewHolder holder, Message msg, int position) {
        if (holder.messageTime == null) {
            return;
        }
        boolean shouldShow = shouldShowTime(msg, position);
        if (shouldShow) {
            String label = getCachedTimeLabel(msg.created_at);
            if (label.isEmpty()) {
                holder.messageTime.setVisibility(android.view.View.GONE);
            } else {
                holder.messageTime.setText(label);
                holder.messageTime.setVisibility(android.view.View.VISIBLE);
            }
        } else {
            holder.messageTime.setVisibility(android.view.View.GONE);
        }
    }

    protected boolean shouldShowTime(Message msg, int position) {
        if (position == 0) {
            return true;
        }
        Message prev = messages.get(position - 1);
        return ChatTimeFormatter.shouldShowTime(msg.created_at, prev.created_at);
    }

    protected void bindMessageFooterTime(MessageAdapterSupport1.ViewHolder holder, Message msg,
                                         boolean isMine) {
        if (holder == null || holder.messageFooterTime == null) {
            return;
        }
        String label = ChatTimeFormatter.formatMessageFooterTime(msg == null ? 0L : msg.created_at);
        if (label == null || label.length() == 0) {
            holder.messageFooterTime.setText("");
            holder.messageFooterTime.setVisibility(android.view.View.GONE);
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
        holder.messageFooterTime.setVisibility(android.view.View.VISIBLE);
    }

    protected MessageAdapter.QuoteClickListener quoteClickListener;
}
