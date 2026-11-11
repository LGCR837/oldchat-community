package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.service.FileDownloadService;
import com.im.oldchat.service.MusicPlaybackService;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公开法庭富媒体智能解析与渲染助手。
 * 自动识别举报指控与陈述中的涉案图片、音乐歌曲（支持一键试听）、资源文件（支持一键下载）、结构化引用消息及群聊上下文。
 */
public class PublicCourtMediaHelper {

    private static final Pattern RE_IMAGE_URL = Pattern.compile("(?:https?://[^/\\s]+)?/(?:v1/)?uploads/([a-zA-Z0-9_\\-\\./]+\\.(?:jpg|jpeg|png|gif|webp))", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HTTP_IMAGE = Pattern.compile("(https?://[^\\s]+\\.(?:jpg|jpeg|png|gif|webp))", Pattern.CASE_INSENSITIVE);

    private static final Pattern RE_SONG_NAME = Pattern.compile("(?:歌名|歌曲名|name|song)[:：=]\\s*([^\\n,，]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_SONG_ARTIST = Pattern.compile("(?:歌手|artist|上传者|owner)[:：=]\\s*([^\\n,，]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_SONG_ID = Pattern.compile("(?:歌曲ID|item_id|song_id|id)[:：=]\\s*([a-zA-Z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_AUDIO_URL = Pattern.compile("((?:https?://[^/\\s]+)?/(?:v1/)?uploads/(?:music/|media/)?[a-zA-Z0-9_\\-\\./]+\\.(?:mp3|wav|ogg|m4a|aac))", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_HTTP_AUDIO = Pattern.compile("(https?://[^\\s]+\\.(?:mp3|wav|ogg|m4a|aac))", Pattern.CASE_INSENSITIVE);

    private static final Pattern RE_FILE_NAME = Pattern.compile("(?:文件名|资源名|file_name|res_name)[:：=]\\s*([^\\n,，]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_FILE_ID = Pattern.compile("(?:资源ID|res_id|file_id)[:：=]\\s*([a-zA-Z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RE_FILE_URL = Pattern.compile("((?:https?://[^/\\s]+)?/(?:files/|resources/|v1/uploads/)[a-zA-Z0-9_\\-\\./]+\\.(?:zip|apk|7z|rar|pdf|doc|docx|txt|bin|tar|gz))", Pattern.CASE_INSENSITIVE);

    public interface OnChatContextClickListener {
        void onOpenChatContext(String groupId, String msgId);
    }

    public static class MediaContent {
        public String mainReason;
        public String groupId;
        public String msgId;
        public String msgType;
        public String bodyText;
        public String quoteUser;
        public String quoteText;
        public String quoteThumbUrl;

        public final List<String> imageUrls = new ArrayList<String>();
        public boolean hasMusic;
        public String songId;
        public String songName;
        public String songArtist;
        public String audioUrl;
        public String coverUrl;

        public boolean hasFile;
        public String fileId;
        public String fileName;
        public String fileUrl;

        public boolean hasChatContext() {
            return !TextUtils.isEmpty(groupId);
        }

        public boolean isEmpty() {
            return imageUrls.isEmpty() && !hasMusic && !hasFile && TextUtils.isEmpty(quoteText) && !hasChatContext() && TextUtils.isEmpty(bodyText);
        }
    }

    public static MediaContent parse(String rawText) {
        MediaContent media = new MediaContent();
        if (TextUtils.isEmpty(rawText)) {
            return media;
        }

        String text = rawText.trim();
        String[] lines = text.split("\n");
        List<String> nonMetaLines = new ArrayList<String>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (TextUtils.isEmpty(line)) {
                continue;
            }

            if (line.contains("[音乐举报]") || line.contains("[音乐广场举报]")) {
                media.hasMusic = true;
            }

            int colonIdx = line.indexOf(':');
            if (colonIdx == -1) {
                colonIdx = line.indexOf('：');
            }

            if (colonIdx != -1) {
                String key = line.substring(0, colonIdx).trim().toLowerCase();
                String val = line.substring(colonIdx + 1).trim();

                if ("群id".equals(key) || "group_id".equals(key) || "groupid".equals(key)) {
                    media.groupId = val;
                    continue;
                } else if ("消息类型".equals(key) || "msg_type".equals(key) || "type".equals(key)) {
                    media.msgType = val;
                    continue;
                } else if ("消息id".equals(key) || "msg_id".equals(key) || "message_id".equals(key)) {
                    media.msgId = val;
                    continue;
                } else if ("媒体".equals(key) || "media".equals(key) || "media_url".equals(key) || "url".equals(key) || "图片".equals(key) || "音频".equals(key)) {
                    if (val.matches("(?i).*\\.(mp3|wav|ogg|m4a|aac).*")) {
                        media.audioUrl = val;
                        media.hasMusic = true;
                    } else if (val.matches("(?i).*\\.(jpg|jpeg|png|gif|webp).*")) {
                        if (!media.imageUrls.contains(val)) {
                            media.imageUrls.add(val);
                        }
                    }
                    continue;
                } else if ("封面".equals(key) || "cover".equals(key) || "cover_url".equals(key)) {
                    media.coverUrl = val;
                    continue;
                } else if ("歌名".equals(key) || "song_name".equals(key) || "name".equals(key)) {
                    media.songName = val;
                    media.hasMusic = true;
                    continue;
                } else if ("歌手".equals(key) || "artist".equals(key) || "上传者".equals(key)) {
                    media.songArtist = val;
                    media.hasMusic = true;
                    continue;
                } else if ("歌曲id".equals(key) || "song_id".equals(key)) {
                    media.songId = val;
                    media.hasMusic = true;
                    continue;
                } else if ("内容".equals(key) || "content".equals(key) || "body".equals(key)) {
                    parseContentField(media, val);
                    continue;
                } else if ("理由".equals(key) || "reason".equals(key) || "诉求".equals(key)) {
                    media.mainReason = val;
                    continue;
                }
            }

            nonMetaLines.add(line);
        }

        if (TextUtils.isEmpty(media.mainReason) && !nonMetaLines.isEmpty()) {
            String firstLine = nonMetaLines.get(0).trim();
            if (!firstLine.startsWith("{") && !firstLine.startsWith("[")) {
                media.mainReason = firstLine;
            }
        }

        // 尝试从整段文字中探测 JSON
        int jsonStart = text.indexOf('{');
        int jsonEnd = text.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            String jsonPart = text.substring(jsonStart, jsonEnd + 1);
            parseContentField(media, jsonPart);
        }

        // 1. 解析图片
        Matcher mImg1 = RE_IMAGE_URL.matcher(text);
        while (mImg1.find()) {
            String path = mImg1.group(0);
            if (!media.imageUrls.contains(path)) {
                media.imageUrls.add(path);
            }
        }
        Matcher mImg2 = RE_HTTP_IMAGE.matcher(text);
        while (mImg2.find()) {
            String path = mImg2.group(1);
            if (!media.imageUrls.contains(path)) {
                media.imageUrls.add(path);
            }
        }

        // 2. 解析音乐
        Matcher mAudio1 = RE_AUDIO_URL.matcher(text);
        if (mAudio1.find() && TextUtils.isEmpty(media.audioUrl)) {
            media.audioUrl = mAudio1.group(1);
            media.hasMusic = true;
        } else {
            Matcher mAudio2 = RE_HTTP_AUDIO.matcher(text);
            if (mAudio2.find() && TextUtils.isEmpty(media.audioUrl)) {
                media.audioUrl = mAudio2.group(1);
                media.hasMusic = true;
            }
        }

        Matcher mSongName = RE_SONG_NAME.matcher(text);
        if (mSongName.find() && TextUtils.isEmpty(media.songName)) {
            media.songName = mSongName.group(1).trim();
            media.hasMusic = true;
        }
        Matcher mSongArtist = RE_SONG_ARTIST.matcher(text);
        if (mSongArtist.find() && TextUtils.isEmpty(media.songArtist)) {
            media.songArtist = mSongArtist.group(1).trim();
            media.hasMusic = true;
        }
        Matcher mSongId = RE_SONG_ID.matcher(text);
        if (mSongId.find() && TextUtils.isEmpty(media.songId)) {
            media.songId = mSongId.group(1).trim();
        }

        // 3. 解析资源文件
        Matcher mFileUrl = RE_FILE_URL.matcher(text);
        if (mFileUrl.find()) {
            media.fileUrl = mFileUrl.group(1);
            media.hasFile = true;
        }
        Matcher mFileName = RE_FILE_NAME.matcher(text);
        if (mFileName.find() && TextUtils.isEmpty(media.fileName)) {
            media.fileName = mFileName.group(1).trim();
            media.hasFile = true;
        }
        Matcher mFileId = RE_FILE_ID.matcher(text);
        if (mFileId.find() && TextUtils.isEmpty(media.fileId)) {
            media.fileId = mFileId.group(1).trim();
        }

        return media;
    }

    private static void parseContentField(MediaContent media, String content) {
        if (TextUtils.isEmpty(content)) {
            return;
        }
        content = content.trim();
        if (content.startsWith("{") && content.endsWith("}")) {
            try {
                JSONObject obj = new JSONObject(content);
                if (obj.has("text")) {
                    media.bodyText = obj.optString("text", "");
                } else if (obj.has("body")) {
                    media.bodyText = obj.optString("body", "");
                }
                if (obj.has("media_url")) {
                    String mUrl = obj.optString("media_url", "");
                    if (!TextUtils.isEmpty(mUrl) && !media.imageUrls.contains(mUrl)) {
                        media.imageUrls.add(mUrl);
                    }
                }
                if (obj.has("image_url")) {
                    String iUrl = obj.optString("image_url", "");
                    if (!TextUtils.isEmpty(iUrl) && !media.imageUrls.contains(iUrl)) {
                        media.imageUrls.add(iUrl);
                    }
                }
                if (obj.has("audio_url")) {
                    media.audioUrl = obj.optString("audio_url", "");
                    media.hasMusic = true;
                }
                if (obj.has("cover_url")) {
                    media.coverUrl = obj.optString("cover_url", "");
                }
                if (obj.has("title") && TextUtils.isEmpty(media.songName)) {
                    media.songName = obj.optString("title", "");
                    media.hasMusic = true;
                }
                if (obj.has("artist") && TextUtils.isEmpty(media.songArtist)) {
                    media.songArtist = obj.optString("artist", "");
                    media.hasMusic = true;
                }
                if (obj.has("quote")) {
                    JSONObject q = obj.optJSONObject("quote");
                    if (q != null) {
                        String from = q.optString("from_name", "");
                        if (TextUtils.isEmpty(from)) {
                            from = q.optString("from_uid", "");
                        }
                        if (TextUtils.isEmpty(from)) {
                            from = q.optString("from_ncuid", "");
                        }
                        media.quoteUser = from;
                        String qText = q.optString("text", "");
                        if (TextUtils.isEmpty(qText)) {
                            qText = q.optString("body", "");
                        }
                        if (TextUtils.isEmpty(qText) && q.has("type")) {
                            qText = "[" + q.optString("type") + "]";
                        }
                        media.quoteText = qText;
                        media.quoteThumbUrl = q.optString("thumb_url", "");
                    }
                }
            } catch (Exception e) {
                // ignore parse failure
            }
        } else if (TextUtils.isEmpty(media.bodyText)) {
            media.bodyText = content;
        }
    }

    public static void bindMediaViews(Context context, LinearLayout container, String rawText) {
        bindMediaViews(context, container, rawText, null);
    }

    public static void bindMediaViews(final Context context, LinearLayout container, String rawText, final OnChatContextClickListener contextListener) {
        if (container == null) {
            return;
        }
        container.removeAllViews();
        if (context == null || TextUtils.isEmpty(rawText)) {
            container.setVisibility(View.GONE);
            return;
        }

        final MediaContent media = parse(rawText);
        if (media.isEmpty()) {
            container.setVisibility(View.GONE);
            return;
        }

        container.setVisibility(View.VISIBLE);

        // 1. 渲染引用消息框 (Quote)
        if (!TextUtils.isEmpty(media.quoteText)) {
            View quoteView = buildQuoteBubble(context, media);
            if (quoteView != null) {
                container.addView(quoteView);
            }
        }

        // 2. 渲染被举报正文内容 (Body Text)
        if (!TextUtils.isEmpty(media.bodyText) && !media.bodyText.startsWith("{")) {
            View bodyView = buildBodyBubble(context, media.bodyText);
            if (bodyView != null) {
                container.addView(bodyView);
            }
        }

        // 3. 渲染涉案群聊上下文查看按钮 (Chat Context Button)
        if (media.hasChatContext() && contextListener != null) {
            View btnContext = buildChatContextButton(context, media, contextListener);
            if (btnContext != null) {
                container.addView(btnContext);
            }
        }

        // 4. 渲染涉案音乐卡片
        if (media.hasMusic) {
            View musicCard = buildMusicCard(context, media);
            if (musicCard != null) {
                container.addView(musicCard);
            }
        }

        // 5. 渲染涉案文件卡片
        if (media.hasFile) {
            View fileCard = buildFileCard(context, media);
            if (fileCard != null) {
                container.addView(fileCard);
            }
        }

        // 6. 渲染涉案图片证据列表
        if (!media.imageUrls.isEmpty()) {
            View imagesView = buildImagesGrid(context, media.imageUrls);
            if (imagesView != null) {
                container.addView(imagesView);
            }
        }
    }

    private static View buildQuoteBubble(Context context, MediaContent media) {
        LinearLayout quoteBox = new LinearLayout(context);
        quoteBox.setOrientation(LinearLayout.VERTICAL);
        quoteBox.setBackgroundResource(R.drawable.bg_profile_item);
        int pad = dp(context, 8);
        quoteBox.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 4);
        lp.bottomMargin = dp(context, 4);
        quoteBox.setLayoutParams(lp);

        TextView tvQuoteUser = new TextView(context);
        String user = TextUtils.isEmpty(media.quoteUser) ? "被引用者" : media.quoteUser;
        tvQuoteUser.setText("[引用 @" + user + "]：");
        tvQuoteUser.setTextColor(context.getResources().getColor(R.color.color_text_secondary));
        tvQuoteUser.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvQuoteUser.setTypeface(Typeface.DEFAULT_BOLD);
        quoteBox.addView(tvQuoteUser);

        TextView tvQuoteText = new TextView(context);
        tvQuoteText.setText(media.quoteText);
        tvQuoteText.setTextColor(context.getResources().getColor(R.color.color_text_primary));
        tvQuoteText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvQuoteText.setPadding(0, dp(context, 2), 0, 0);
        quoteBox.addView(tvQuoteText);

        if (!TextUtils.isEmpty(media.quoteThumbUrl)) {
            ImageView ivThumb = new ImageView(context);
            int thumbSize = dp(context, 60);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(thumbSize, thumbSize);
            ivLp.topMargin = dp(context, 4);
            ivThumb.setLayoutParams(ivLp);
            ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            ImageLoader.load(ivThumb, media.quoteThumbUrl);
            quoteBox.addView(ivThumb);
        }

        return quoteBox;
    }

    private static View buildBodyBubble(Context context, String bodyText) {
        LinearLayout bodyBox = new LinearLayout(context);
        bodyBox.setOrientation(LinearLayout.VERTICAL);
        bodyBox.setBackgroundResource(R.drawable.bg_profile_item);
        int pad = dp(context, 10);
        bodyBox.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 4);
        lp.bottomMargin = dp(context, 4);
        bodyBox.setLayoutParams(lp);

        TextView tvLabel = new TextView(context);
        tvLabel.setText("[正文] 涉案内容：");
        tvLabel.setTextColor(context.getResources().getColor(R.color.color_text_secondary));
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvLabel.setTypeface(Typeface.DEFAULT_BOLD);
        bodyBox.addView(tvLabel);

        TextView tvBody = new TextView(context);
        tvBody.setText(bodyText);
        tvBody.setTextColor(context.getResources().getColor(R.color.color_text_primary));
        tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvBody.setTypeface(Typeface.DEFAULT_BOLD);
        tvBody.setPadding(0, dp(context, 4), 0, 0);
        bodyBox.addView(tvBody);

        return bodyBox;
    }

    private static View buildChatContextButton(Context context, final MediaContent media, final OnChatContextClickListener listener) {
        LinearLayout btn = new LinearLayout(context);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER_VERTICAL);
        btn.setBackgroundResource(R.drawable.flat_button_bg);
        int padH = dp(context, 12);
        int padV = dp(context, 8);
        btn.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 6);
        lp.bottomMargin = dp(context, 4);
        btn.setLayoutParams(lp);

        TextView tvText = new TextView(context);
        tvText.setText("[查看上下文] 查看该群前后各 10 条聊天记录");
        tvText.setTextColor(context.getResources().getColor(R.color.color_accent));
        tvText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvText.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvText.setLayoutParams(tvLp);
        btn.addView(tvText);

        TextView tvArrow = new TextView(context);
        tvArrow.setText("展开 →");
        tvArrow.setTextColor(context.getResources().getColor(R.color.color_text_secondary));
        tvArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        btn.addView(tvArrow);

        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onOpenChatContext(media.groupId, media.msgId);
                }
            }
        });

        return btn;
    }

    private static View buildMusicCard(final Context context, final MediaContent media) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.flat_button_bg);
        int pad = dp(context, 8);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 4);
        lp.bottomMargin = dp(context, 4);
        card.setLayoutParams(lp);

        // 封面或图标
        ImageView ivIcon = new ImageView(context);
        int iconSize = dp(context, 38);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.rightMargin = dp(context, 8);
        ivIcon.setLayoutParams(iconLp);
        ivIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivIcon.setBackgroundColor(0x22000000);
        if (!TextUtils.isEmpty(media.coverUrl)) {
            ImageLoader.load(ivIcon, media.coverUrl);
        } else {
            ivIcon.setImageResource(android.R.drawable.ic_media_play);
        }
        card.addView(ivIcon);

        // 歌曲信息
        LinearLayout infoLayout = new LinearLayout(context);
        infoLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLayout.setLayoutParams(infoLp);

        TextView tvTitle = new TextView(context);
        String title = TextUtils.isEmpty(media.songName) ? "涉案音乐歌曲" : ("[音乐] " + media.songName);
        tvTitle.setText(title);
        tvTitle.setTextColor(context.getResources().getColor(R.color.color_text_primary));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        infoLayout.addView(tvTitle);

        TextView tvSub = new TextView(context);
        String sub = "";
        if (!TextUtils.isEmpty(media.songArtist)) {
            sub = "歌手/上传者：" + media.songArtist;
        } else if (!TextUtils.isEmpty(media.songId)) {
            sub = "歌曲ID：" + media.songId;
        } else {
            sub = "点击进入播放器试听涉案音频";
        }
        tvSub.setText(sub);
        tvSub.setTextColor(context.getResources().getColor(R.color.color_text_secondary));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvSub.setSingleLine(true);
        tvSub.setEllipsize(TextUtils.TruncateAt.END);
        infoLayout.addView(tvSub);

        card.addView(infoLayout);

        // 播放试听按钮
        TextView btnPlay = new TextView(context);
        btnPlay.setText("试听");
        btnPlay.setTextColor(Color.WHITE);
        btnPlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btnPlay.setTypeface(Typeface.DEFAULT_BOLD);
        btnPlay.setBackgroundResource(R.drawable.flat_button_accent_bg);
        btnPlay.setGravity(Gravity.CENTER);
        int btnPadH = dp(context, 12);
        int btnPadV = dp(context, 6);
        btnPlay.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = dp(context, 8);
        btnPlay.setLayoutParams(btnLp);
        card.addView(btnPlay);

        View.OnClickListener playListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String playUrl = media.audioUrl;
                if (!TextUtils.isEmpty(playUrl)) {
                    playUrl = MediaUrlResolver.resolve(playUrl);
                }
                Intent intent = new Intent(context, MusicPlayerActivity.class);
                intent.putExtra(MusicPlaybackService.EXTRA_SONG_NAME, TextUtils.isEmpty(media.songName) ? "涉案音乐" : media.songName);
                if (!TextUtils.isEmpty(playUrl)) {
                    intent.putExtra(MusicPlaybackService.EXTRA_SONG_URL, playUrl);
                }
                if (!TextUtils.isEmpty(media.coverUrl)) {
                    intent.putExtra(MusicPlaybackService.EXTRA_COVER_URL, MediaUrlResolver.resolve(media.coverUrl));
                }
                if (!TextUtils.isEmpty(media.songArtist)) {
                    intent.putExtra(MusicPlaybackService.EXTRA_OWNER_NAME, media.songArtist);
                }
                if (!TextUtils.isEmpty(media.songId)) {
                    intent.putExtra(MusicPlaybackService.EXTRA_ITEM_ID, media.songId);
                }
                context.startActivity(intent);
            }
        };

        card.setOnClickListener(playListener);
        btnPlay.setOnClickListener(playListener);

        return card;
    }

    private static View buildFileCard(final Context context, final MediaContent media) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.flat_button_bg);
        int pad = dp(context, 8);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(context, 4);
        lp.bottomMargin = dp(context, 4);
        card.setLayoutParams(lp);

        // 文件图标
        ImageView ivIcon = new ImageView(context);
        int iconSize = dp(context, 32);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.rightMargin = dp(context, 8);
        ivIcon.setLayoutParams(iconLp);
        ivIcon.setImageResource(android.R.drawable.ic_menu_save);
        card.addView(ivIcon);

        // 文件名信息
        LinearLayout infoLayout = new LinearLayout(context);
        infoLayout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLayout.setLayoutParams(infoLp);

        TextView tvTitle = new TextView(context);
        String name = TextUtils.isEmpty(media.fileName) ? "涉案资源文件" : ("[文件] " + media.fileName);
        tvTitle.setText(name);
        tvTitle.setTextColor(context.getResources().getColor(R.color.color_text_primary));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        infoLayout.addView(tvTitle);

        TextView tvSub = new TextView(context);
        String sub = !TextUtils.isEmpty(media.fileId) ? ("资源ID: " + media.fileId) : "点击下载涉案文件";
        tvSub.setText(sub);
        tvSub.setTextColor(context.getResources().getColor(R.color.color_text_secondary));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvSub.setSingleLine(true);
        tvSub.setEllipsize(TextUtils.TruncateAt.END);
        infoLayout.addView(tvSub);

        card.addView(infoLayout);

        // 下载按钮
        TextView btnDownload = new TextView(context);
        btnDownload.setText("下载");
        btnDownload.setTextColor(Color.WHITE);
        btnDownload.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        btnDownload.setTypeface(Typeface.DEFAULT_BOLD);
        btnDownload.setBackgroundResource(R.drawable.flat_button_accent_bg);
        btnDownload.setGravity(Gravity.CENTER);
        int btnPadH = dp(context, 12);
        int btnPadV = dp(context, 6);
        btnDownload.setPadding(btnPadH, btnPadV, btnPadH, btnPadV);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = dp(context, 8);
        btnDownload.setLayoutParams(btnLp);
        card.addView(btnDownload);

        View.OnClickListener downloadListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TextUtils.isEmpty(media.fileUrl)) {
                    Toast.makeText(context, "未找到文件下载地址", Toast.LENGTH_SHORT).show();
                    return;
                }
                String resolved = MediaUrlResolver.resolve(media.fileUrl);
                String dlName = TextUtils.isEmpty(media.fileName) ? "case_resource_file" : media.fileName;
                FileDownloadService.enqueue(context, resolved, dlName, "application/octet-stream");
                Toast.makeText(context, "已加入后台下载队列：" + dlName, Toast.LENGTH_SHORT).show();
            }
        };

        card.setOnClickListener(downloadListener);
        btnDownload.setOnClickListener(downloadListener);

        return card;
    }

    private static View buildImagesGrid(final Context context, final List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return null;
        }

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rootLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rootLp.topMargin = dp(context, 4);
        rootLp.bottomMargin = dp(context, 4);
        root.setLayoutParams(rootLp);

        TextView tvHint = new TextView(context);
        tvHint.setText("[图片证据] 涉案截图（点击查看大图）：");
        tvHint.setTextColor(context.getResources().getColor(R.color.color_text_secondary));
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvHint.setPadding(0, 0, 0, dp(context, 4));
        root.addView(tvHint);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rowLp);

        int maxShow = Math.min(3, imageUrls.size());
        for (int i = 0; i < maxShow; i++) {
            final String url = imageUrls.get(i);
            ImageView iv = new ImageView(context);
            int h = dp(context, 90);
            LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(0, h, 1f);
            if (i > 0) {
                ivLp.leftMargin = dp(context, 6);
            }
            iv.setLayoutParams(ivLp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0x15000000);
            ImageLoader.load(iv, url);
            iv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ImagePreviewActivity.start(context, url);
                }
            });
            row.addView(iv);
        }

        root.addView(row);
        return root;
    }

    private static int dp(Context context, int dpVal) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dpVal * density + 0.5f);
    }
}
