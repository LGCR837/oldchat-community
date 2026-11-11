package com.im.oldchat.bili;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public final class BiliModels {
    private BiliModels() {
    }

    public static class BiliResponse<T> {
        public int code;
        public String message;
        public int ttl;
        public T data;
    }

    public static class QRAuthCodeResult {
        public int code;
        public String message;
        public int ttl;
        public QRAuthCodeResponse data;
    }

    public static class QRPollResult {
        public int code;
        public String message;
        public int ttl;
        public QRPollResponse data;
    }

    public static class RecommendResult {
        public int code;
        public String message;
        public int ttl;
        public RecommendData data;
    }

    public static class VideoDetailResult {
        public int code;
        public String message;
        public int ttl;
        public VideoDetailData data;
    }

    public static class CommentResult {
        public int code;
        public String message;
        public int ttl;
        public CommentData data;
    }

    public static class QRAuthCodeResponse {
        public String url;
        @SerializedName("auth_code")
        public String authCode;
    }

    public static class QRPollResponse {
        public long mid;
        @SerializedName("access_token")
        public String accessToken;
        @SerializedName("refresh_token")
        public String refreshToken;
        @SerializedName("expires_in")
        public long expiresIn;
        @SerializedName("token_info")
        public TokenInfo tokenInfo;
        @SerializedName("cookie_info")
        public CookieInfo cookieInfo;
    }

    public static class TokenInfo {
        public long mid;
        @SerializedName("access_token")
        public String accessToken;
        @SerializedName("refresh_token")
        public String refreshToken;
        @SerializedName("expires_in")
        public long expiresIn;
    }

    public static class CookieInfo {
        public List<BiliCookie> cookies;
        public List<String> domains;
    }

    public static class BiliCookie {
        public String name;
        public String value;
        @SerializedName("http_only")
        public int httpOnly;
        public long expires;
        public int secure;
    }

    public static class RecommendData {
        public List<RecommendItem> items;
    }

    public static class RecommendItem {
        @SerializedName("card_type")
        public String cardType;
        @SerializedName("card_goto")
        public String cardGoto;
        @SerializedName("goto")
        public String gotoType;
        public String param;
        public String cover;
        public String title;
        public String uri;
        public RecommendArgs args;
        @SerializedName("cover_left_text_1")
        public String playCount;
        @SerializedName("cover_left_text_2")
        public String danmakuCount;
        @SerializedName("cover_right_text")
        public String duration;
        public String face;
        @SerializedName("desc_button")
        public DescButton descButton;
    }

    public static class RecommendArgs {
        @SerializedName("up_id")
        public long upId;
        @SerializedName("up_name")
        public String upName;
        public long aid;
        public long mid;
    }

    public static class DescButton {
        public String text;
    }

    public static class SearchResult {
        public int code;
        public String message;
        public SearchData data;
    }

    public static class SearchData {
        @SerializedName("result")
        public List<SearchItem> result;
        public int page;
        @SerializedName(value = "pagesize", alternate = {"page_size"})
        public int pageSize;
        @SerializedName(value = "numPages", alternate = {"num_pages"})
        public int numPages;
        @SerializedName(value = "numResults", alternate = {"num_results"})
        public int numResults;
    }

    public static class SearchItem {
        public String title;
        public String pic;
        public String author;
        public String play;
        @SerializedName("video_review")
        public int danmaku;
        public String duration;
        public long aid;
        public String bvid;
    }

    public static class SimpleResult {
        public int code;
        public String message;
    }

    public static class VideoDetailData {
        public long aid;
        public String bvid;
        public long cid;
        public String title;
        public String desc;
        public String pic;
        public long duration;
        public VideoOwner owner;
        public VideoStat stat;
        public List<VideoPage> pages;
    }

    public static class VideoPage {
        public long cid;
        public String part;
    }

    public static class VideoOwner {
        public long mid;
        public String name;
        public String face;
    }

    public static class VideoStat {
        public int view;
        public int like;
        public int danmaku;
        public int reply;
    }

    public static class CommentData {
        public List<CommentReply> replies;
        public CommentPage page;
        public List<CommentReply> hots;
        public CommentTop top;
        public CommentUpper upper;
    }

    public static class CommentReply {
        public long rpid;
        public long ctime;
        public int like;
        public int rcount;
        public long root;
        public long parent;
        public CommentContent content;
        public CommentMember member;
        public List<CommentReply> replies;
        public transient boolean hotComment;
        public transient boolean topComment;
        public transient boolean likedByMe;
        public transient boolean showAllReplies;
    }

    public static class CommentContent {
        public String message;
    }

    public static class CommentMember {
        public String uname;
        public String avatar;
    }

    public static class CommentTop {
        public CommentReply reply;
        public CommentReply upper;
    }

    public static class CommentUpper {
        public CommentReply top;
    }

    public static class CommentPage {
        public int num;
        public int size;
        public int count;
    }

    public static class PlayUrlResult {
        public int code;
        public String message;
        public int ttl;
        public PlayUrlData data;
    }

    public static class PlayUrlData {
        public int quality;
        public List<PlayUrlDurl> durl;
    }

    public static class PlayUrlDurl {
        public String url;
        public long length;
        public long size;
    }

    public static class NavResult {
        public int code;
        public String message;
        public int ttl;
        public NavData data;
    }

    public static class NavData {
        public long mid;
        @SerializedName("wbi_img")
        public WbiImg wbiImg;
    }

    public static class WbiImg {
        @SerializedName("img_url")
        public String imgUrl;
        @SerializedName("sub_url")
        public String subUrl;
    }

    public static class HistoryResult {
        public int code;
        public String message;
        public int ttl;
        public HistoryData data;
    }

    public static class HistoryData {
        public HistoryCursor cursor;
        public List<HistoryItem> list;
    }

    public static class HistoryCursor {
        public long max;
        @SerializedName("view_at")
        public long viewAt;
        public String business;
        public int ps;
    }

    public static class HistoryItem {
        public String title;
        public String cover;
        @SerializedName("author_name")
        public String authorName;
        public HistoryDetail history;
        @SerializedName("view_at")
        public long viewAt;
        public int progress;
        public int duration;
    }

    public static class HistoryDetail {
        public long oid;
        public String bvid;
        public String business;
    }

    public static class FavFolderResult {
        public int code;
        public String message;
        public int ttl;
        public FavFolderData data;
    }

    public static class FavFolderData {
        public int count;
        public List<FavFolder> list;
    }

    public static class FavFolder {
        public long id;
        public long fid;
        public long mid;
        public String title;
        @SerializedName("fav_state")
        public int favState;
        @SerializedName("media_count")
        public int mediaCount;
    }

    public static class FavResourceResult {
        public int code;
        public String message;
        public int ttl;
        public FavResourceData data;
    }

    public static class FavResourceData {
        public List<FavMedia> medias;
        @SerializedName("has_more")
        public boolean hasMore;
    }

    public static class FavMedia {
        public long id;
        public int type;
        public String title;
        public String cover;
        public FavUpper upper;
        public String bvid;
        @SerializedName("bv_id")
        public String bvId;
        public String intro;
        public int duration;
    }

    public static class FavUpper {
        public long mid;
        public String name;
        public String face;
    }
}
