package com.im.oldchat.ui;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.AlertDialog;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ImageLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MusicManageActivity extends BaseActivity {
    private static final int PAGE_SIZE = 50;
    private static final int REQ_PICK_EDIT_COVER = 5401;
    private static final int REQ_PICK_MEDIA_PERMISSION = 5402;
    private static final int REQ_PICK_LYRICS = 5403;
    private static final int PICK_KIND_NONE = 0;
    private static final int PICK_KIND_COVER = 2;
    private static final long MAX_COVER_BYTES = 1L * 1024L * 1024L;

    private EditText etKeyword;
    private TextView btnSearch;
    private TextView btnBatchDelete;
    private TextView tvSummary;
    private TextView tvPageInfo;
    private TextView btnPrev;
    private TextView btnNext;
    private TextView tvEmpty;
    private ListView lv;

    private String token;
    private boolean allListMode = false;
    private final List<MusicItem> items = new ArrayList<MusicItem>();
    private MusicAdapter adapter;
    private int currentOffset = 0;
    private int totalCount = 0;
    private boolean hasMore = false;
    private int pendingPickKind = PICK_KIND_NONE;
    private Uri pendingEditCoverUri;
    private MusicItem pendingLyricsItem;
    private AlertDialog editDialog;
    private TextView editCoverTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_manage);

        allListMode = getIntent() != null && getIntent().getBooleanExtra("all_list_mode", false);

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        View back = findViewByIdCompat(R.id.btnMusicManageBack);
        TextView tvTitle = findViewByIdCompat(R.id.tvMusicManageTitle);
        etKeyword = findViewByIdCompat(R.id.etMusicManageKeyword);
        com.im.oldchat.util.AutofillCompatApi26.disableAutofill(etKeyword);
        btnSearch = findViewByIdCompat(R.id.btnMusicManageSearch);
        btnBatchDelete = findViewByIdCompat(R.id.btnMusicManageBatchDelete);
        tvSummary = findViewByIdCompat(R.id.tvMusicManageSummary);
        tvPageInfo = findViewByIdCompat(R.id.tvMusicManagePageInfo);
        btnPrev = findViewByIdCompat(R.id.btnMusicManagePrev);
        btnNext = findViewByIdCompat(R.id.btnMusicManageNext);
        tvEmpty = findViewByIdCompat(R.id.tvMusicManageEmpty);
        lv = findViewByIdCompat(R.id.lvMusicManage);

        if (allListMode) {
            if (tvTitle != null) {
                tvTitle.setText("全部歌曲");
            }
            if (btnBatchDelete != null) {
                btnBatchDelete.setVisibility(View.GONE);
            }
            if (tvEmpty != null) {
                tvEmpty.setText("暂无歌曲");
            }
        }

        if (back != null) {
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        adapter = new MusicAdapter();
        lv.setAdapter(adapter);
        lv.setEmptyView(tvEmpty);
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= items.size()) {
                    return;
                }
                playItem(items.get(position));
            }
        });
        lv.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= items.size()) {
                    return false;
                }
                showItemActions(items.get(position));
                return true;
            }
        });

        if (btnSearch != null) {
            btnSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    currentOffset = 0;
                    loadItems();
                }
            });
        }
        if (btnBatchDelete != null) {
            btnBatchDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (allListMode) {
                        return;
                    }
                    deleteCurrentPage();
                }
            });
        }
        if (etKeyword != null) {
            etKeyword.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                        currentOffset = 0;
                        loadItems();
                        return true;
                    }
                    if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                        currentOffset = 0;
                        loadItems();
                        return true;
                    }
                    return false;
                }
            });
        }

        if (btnPrev != null) {
            btnPrev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (currentOffset <= 0) {
                        return;
                    }
                    currentOffset = Math.max(0, currentOffset - PAGE_SIZE);
                    loadItems();
                }
            });
        }
        if (btnNext != null) {
            btnNext.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!hasMore) {
                        return;
                    }
                    currentOffset += PAGE_SIZE;
                    loadItems();
                }
            });
        }

        loadItems();
    }

    private void loadItems() {
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        final String q = etKeyword == null || etKeyword.getText() == null
                ? "" : etKeyword.getText().toString().trim();
        StringBuilder path;
        if (allListMode) {
            path = new StringBuilder("/music/plaza?limit=").append(PAGE_SIZE)
                    .append("&offset=").append(currentOffset)
                    .append("&sort=latest");
        } else {
            path = new StringBuilder("/music/plaza/mine?limit=").append(PAGE_SIZE)
                    .append("&offset=").append(currentOffset);
        }
        if (q.length() > 0) {
            path.append("&q=").append(urlEncode(q));
        }
        HttpUtil.get(path.toString(), token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.optJSONArray("items");
                    totalCount = obj.optInt("total", 0);
                    hasMore = obj.optBoolean("has_more", false);
                    items.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject one = arr.optJSONObject(i);
                            if (one == null) {
                                continue;
                            }
                            MusicItem item = new MusicItem();
                            item.id = one.optString("id", "");
                            item.name = one.optString("name", "");
                            item.songUrl = one.optString("song_url", "");
                            item.coverUrl = one.optString("cover_url", "");
                            item.lyricsUrl = one.optString("lyrics_url", "");
                            item.ownerUid = one.optString("owner_uid", "");
                            item.ownerName = one.optString("owner_name", "");
                            item.ownerTitle = one.optString("owner_title", "");
                            item.ownerAvatar = one.optString("owner_avatar", "");
                            item.sizeBytes = one.optLong("size_bytes", 0);
                            item.durationMs = parseDurationMs(one);
                            item.likes = one.optInt("likes", 0);
                            item.comments = one.optInt("comments", 0);
                            item.playCount = one.optInt("play_count", 0);
                            item.liked = one.optBoolean("liked", false);
                            item.canDelete = one.optBoolean("can_delete", false);
                            if (item.id.length() == 0 || item.songUrl.length() == 0) {
                                continue;
                            }
                            items.add(item);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    updatePagerUi();
                } catch (Exception e) {
                    Toast.makeText(MusicManageActivity.this, "加载失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(MusicManageActivity.this, "加载失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updatePagerUi() {
        if (tvSummary != null) {
            int likedCount = 0;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) != null && items.get(i).liked) {
                    likedCount++;
                }
            }
            if (allListMode) {
                tvSummary.setText("全部歌曲 " + totalCount + " 首 · 当前页 " + items.size() + " 首 · 已点赞 " + likedCount + " 首");
            } else {
                tvSummary.setText("我的上传 " + totalCount + " 首 · 当前页 " + items.size() + " 首 · 已点赞 " + likedCount + " 首");
            }
        }
        if (tvPageInfo != null) {
            int from = totalCount <= 0 ? 0 : (currentOffset + 1);
            int to = Math.min(totalCount, currentOffset + items.size());
            tvPageInfo.setText("第 " + from + " - " + to + " 条，共 " + totalCount + " 条");
        }
        if (btnPrev != null) {
            btnPrev.setEnabled(currentOffset > 0);
            ViewCompat.setAlpha(btnPrev, currentOffset > 0 ? 1f : 0.55f);
        }
        if (btnNext != null) {
            btnNext.setEnabled(hasMore);
            ViewCompat.setAlpha(btnNext, hasMore ? 1f : 0.55f);
        }
        if (btnBatchDelete != null && !allListMode) {
            boolean canDelete = !items.isEmpty();
            btnBatchDelete.setEnabled(canDelete);
            ViewCompat.setAlpha(btnBatchDelete, canDelete ? 1f : 0.55f);
        }
    }

    private void showItemActions(final MusicItem item) {
        if (item == null) {
            return;
        }
        final ArrayList<String> actions = new ArrayList<String>();
        actions.add("播放");
        actions.add("评论");
        actions.add("分享到聊天");
        if (allListMode) {
            actions.add(item.liked ? "取消点赞" : "点赞");
            actions.add("收藏");
            if (item.canDelete) {
                actions.add("编辑信息");
                actions.add("上传歌词");
                actions.add("删除");
            }
        } else {
            if (item.canDelete) {
                actions.add("编辑信息");
                actions.add("上传歌词");
                actions.add("删除");
            }
        }
        final CharSequence[] array = actions.toArray(new CharSequence[actions.size()]);
        new AlertDialog.Builder(this)
                .setTitle(item.name == null || item.name.length() == 0 ? "歌曲管理" : item.name)
                .setItems(array, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which < 0 || which >= actions.size()) {
                            return;
                        }
                        String action = actions.get(which);
                        if ("播放".equals(action)) {
                            playItem(item);
                            return;
                        }
                        if ("评论".equals(action)) {
                            openComments(item);
                            return;
                        }
                        if ("分享到聊天".equals(action)) {
                            shareMusicItem(item);
                            return;
                        }
                        if ("点赞".equals(action) || "取消点赞".equals(action)) {
                            toggleLike(item);
                            return;
                        }
                        if ("收藏".equals(action)) {
                            String owner = item.ownerName;
                            if (owner == null || owner.length() == 0) {
                                owner = item.ownerUid;
                            }
                            FavoriteHelper.addMusicFavorite(MusicManageActivity.this,
                                    item.id,
                                    item.name,
                                    owner,
                                    item.songUrl,
                                    item.coverUrl,
                                    item.lyricsUrl);
                            return;
                        }
                        if ("编辑信息".equals(action)) {
                            showEditDialog(item);
                            return;
                        }
                        if ("上传歌词".equals(action)) {
                            pickLyricsFile(item);
                            return;
                        }
                        if ("删除".equals(action)) {
                            deleteMusicItem(item);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteCurrentPage() {
        if (items.isEmpty()) {
            Toast.makeText(this, "当前页没有可删除歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("批量删除")
                .setMessage("确定删除当前页全部 " + items.size() + " 首歌曲吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            JSONArray itemIDs = new JSONArray();
                            for (int i = 0; i < items.size(); i++) {
                                MusicItem item = items.get(i);
                                if (item != null && item.id != null && item.id.length() > 0) {
                                    itemIDs.put(item.id);
                                }
                            }
                            if (itemIDs.length() == 0) {
                                Toast.makeText(MusicManageActivity.this, "没有可删除项", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            JSONObject json = new JSONObject();
                            json.put("item_ids", itemIDs);
                            HttpUtil.post("/music/plaza/mine/delete-batch", json, token, new HttpUtil.Callback() {
                                @Override
                                public void onSuccess(String response) {
                                    int deleted = 0;
                                    try {
                                        JSONObject obj = new JSONObject(response);
                                        deleted = obj.optInt("deleted", 0);
                                    } catch (Exception e) {
                                    }
                                    Toast.makeText(MusicManageActivity.this,
                                            "已删除 " + deleted + " 首",
                                            Toast.LENGTH_SHORT).show();
                                    loadItems();
                                }

                                @Override
                                public void onError(int code, String error) {
                                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                                        return;
                                    }
                                    Toast.makeText(MusicManageActivity.this, "批量删除失败", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (Exception e) {
                            Toast.makeText(MusicManageActivity.this, "批量删除失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteMusicItem(final MusicItem item) {
        if (item == null || !item.canDelete || TextUtils.isEmpty(item.id)) {
            if (allListMode) {
                Toast.makeText(this, "只能删除自己发布的歌曲", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除歌曲")
                .setMessage("确定删除这首歌曲吗？")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            JSONObject json = new JSONObject();
                            json.put("item_id", item.id);
                            HttpUtil.post("/music/plaza/delete", json, token, new HttpUtil.Callback() {
                                @Override
                                public void onSuccess(String response) {
                                    Toast.makeText(MusicManageActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                                    loadItems();
                                }

                                @Override
                                public void onError(int code, String error) {
                                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                                        return;
                                    }
                                    Toast.makeText(MusicManageActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                                }
                            });
                        } catch (Exception e) {
                            Toast.makeText(MusicManageActivity.this, "删除失败", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEditDialog(final MusicItem item) {
        if (item == null || !item.canDelete || TextUtils.isEmpty(item.id)) {
            Toast.makeText(this, "只能编辑自己发布的歌曲", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingEditCoverUri = null;
        final EditText nameInput = new EditText(this);
        nameInput.setHint("请输入歌曲名称");
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        if (!TextUtils.isEmpty(item.name)) {
            nameInput.setText(item.name);
            nameInput.setSelection(item.name.length());
        }

        final TextView chooseCover = buildFlatTextButton("选择新封面");
        final TextView coverTip = new TextView(this);
        editCoverTextView = coverTip;
        coverTip.setPadding(0, dpToPx(6), 0, dpToPx(4));
        refreshEditSelectionLabels();

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), 0);
        root.addView(nameInput);
        root.addView(coverTip);
        root.addView(chooseCover);

        chooseCover.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tryPickEditCoverFile();
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("编辑歌曲信息")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        editDialog = dialog;
        dialog.show();
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface dialogInterface) {
                if (editDialog == dialog) {
                    editDialog = null;
                    editCoverTextView = null;
                    pendingEditCoverUri = null;
                }
            }
        });
        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                    if (name.length() == 0) {
                        Toast.makeText(MusicManageActivity.this, "请填写歌曲名称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    submitMusicItemEdit(item, name, pendingEditCoverUri);
                }
            });
        }
    }

    private void submitMusicItemEdit(final MusicItem item, final String newName, final Uri newCoverUri) {
        if (item == null || TextUtils.isEmpty(item.id)) {
            return;
        }
        if (TextUtils.isEmpty(token)) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        byte[] coverBytes = null;
        String coverName = "cover.jpg";
        String coverType = "image/jpeg";
        if (newCoverUri != null) {
            if (!hasPickPermission(PICK_KIND_COVER)) {
                Toast.makeText(this, "未授予媒体读取权限，请重新选择", Toast.LENGTH_SHORT).show();
                return;
            }
            long coverSize = resolveContentLength(newCoverUri);
            if (coverSize > MAX_COVER_BYTES) {
                Toast.makeText(this, "封面不能超过1MB", Toast.LENGTH_SHORT).show();
                return;
            }
            coverName = queryDisplayName(newCoverUri);
            if (TextUtils.isEmpty(coverName)) {
                coverName = "cover.jpg";
            }
            coverType = resolveImageMime(newCoverUri, coverName);
            coverBytes = readUriBytesSafe(newCoverUri, MAX_COVER_BYTES);
            if (coverBytes == null || coverBytes.length == 0) {
                Toast.makeText(this, "封面读取失败或超过1MB", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        final byte[] finalCoverBytes = coverBytes;
        final String finalCoverName = coverName;
        final String finalCoverType = coverType;
        Toast.makeText(this, "正在保存修改...", Toast.LENGTH_SHORT).show();
        HttpUtil.postMultipartWithThumb(
                "/music/plaza/update?item_id=" + urlEncode(item.id) + "&name=" + urlEncode(newName),
                new byte[0],
                "meta.txt",
                "text/plain",
                finalCoverBytes,
                finalCoverName,
                finalCoverType,
                token,
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        String resolvedName = newName;
                        String resolvedCover = item.coverUrl;
                        try {
                            JSONObject obj = new JSONObject(response);
                            String serverName = obj.optString("name", "");
                            if (!TextUtils.isEmpty(serverName)) {
                                resolvedName = serverName.trim();
                            }
                            String serverCover = obj.optString("cover_url", "");
                            if (!TextUtils.isEmpty(serverCover)) {
                                resolvedCover = serverCover.trim();
                            }
                        } catch (Exception e) {
                        }
                        item.name = resolvedName;
                        if (!TextUtils.isEmpty(resolvedCover)) {
                            item.coverUrl = resolvedCover;
                        }
                        adapter.notifyDataSetChanged();
                        Toast.makeText(MusicManageActivity.this, "修改成功", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(int code, String error) {
                        if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                            return;
                        }
                        if (code == 400 && error != null && error.contains("invalid_name")) {
                            Toast.makeText(MusicManageActivity.this, "歌曲名称不合法", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 400 && error != null && error.contains("invalid_cover")) {
                            Toast.makeText(MusicManageActivity.this, "封面格式不支持", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 413 && error != null && error.contains("cover_too_large")) {
                            Toast.makeText(MusicManageActivity.this, "封面不能超过1MB", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 404) {
                            Toast.makeText(MusicManageActivity.this, "歌曲不存在，列表将刷新", Toast.LENGTH_SHORT).show();
                            loadItems();
                            return;
                        }
                        if (code == 403) {
                            Toast.makeText(MusicManageActivity.this, "只能编辑自己发布的歌曲", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(MusicManageActivity.this, "修改失败: " + code, Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void refreshEditSelectionLabels() {
        if (editCoverTextView == null) {
            return;
        }
        if (pendingEditCoverUri == null) {
            editCoverTextView.setText("新封面: 不修改");
        } else {
            editCoverTextView.setText("新封面: " + queryDisplayName(pendingEditCoverUri));
        }
    }

    private TextView buildFlatTextButton(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getResources().getColor(R.color.color_text_primary));
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8));
        tv.setBackgroundResource(R.drawable.flat_button_bg);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dpToPx(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void pickEditCoverFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "选择新的封面图片"), REQ_PICK_EDIT_COVER);
    }

    private void tryPickEditCoverFile() {
        if (ensurePickPermission(PICK_KIND_COVER)) {
            pickEditCoverFile();
        }
    }

    private boolean ensurePickPermission(int pickKind) {
        if (hasPickPermission(pickKind)) {
            return true;
        }
        String permission = resolveReadPermissionForPick(pickKind);
        if (TextUtils.isEmpty(permission)) {
            return true;
        }
        pendingPickKind = pickKind;
        ActivityCompat.requestPermissions(this, new String[]{permission}, REQ_PICK_MEDIA_PERMISSION);
        return false;
    }

    private boolean hasPickPermission(int pickKind) {
        String permission = resolveReadPermissionForPick(pickKind);
        if (TextUtils.isEmpty(permission)) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private String resolveReadPermissionForPick(int pickKind) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "";
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (pickKind == PICK_KIND_COVER) {
                return Manifest.permission.READ_MEDIA_IMAGES;
            }
            return "";
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PICK_MEDIA_PERMISSION) {
            return;
        }
        int kind = pendingPickKind;
        pendingPickKind = PICK_KIND_NONE;
        boolean granted = grantResults != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, "未授予媒体读取权限，无法选择封面", Toast.LENGTH_SHORT).show();
            return;
        }
        if (kind == PICK_KIND_COVER) {
            pickEditCoverFile();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_LYRICS) {
            if (resultCode == RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    uploadLyrics(uri);
                }
            }
            return;
        }
        if (requestCode != REQ_PICK_EDIT_COVER || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        pendingEditCoverUri = uri;
        refreshEditSelectionLabels();
        Toast.makeText(this, "已选择新封面: " + queryDisplayName(uri), Toast.LENGTH_SHORT).show();
    }

    private void pickLyricsFile(final MusicItem item) {
        if (item == null || item.id == null || item.id.length() == 0) {
            Toast.makeText(this, "歌曲信息不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingLyricsItem = item;
        String action = android.os.Build.VERSION.SDK_INT >= 19
                ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT;
        Intent intent = new Intent(action);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // 不设置 EXTRA_MIME_TYPES：.lrc 在很多文件管理器中被识别为
        // application/octet-stream 或未知类型，MIME 过滤会把 .lrc 置灰无法选中。
        // 放宽为所有文件可选，提交时按扩展名 (.lrc/.ttml/.xml) 校验。
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(intent, "选择歌词文件(.lrc/.ttml)"), REQ_PICK_LYRICS);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void uploadLyrics(Uri uri) {
        final MusicItem item = pendingLyricsItem;
        if (item == null || item.id == null || item.id.length() == 0) {
            Toast.makeText(this, "歌曲信息不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        String displayName = queryDisplayName(uri);
        String lower = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".lrc") && !lower.endsWith(".ttml") && !lower.endsWith(".xml")) {
            Toast.makeText(this, "请选择 .lrc / .ttml / .xml 歌词文件", Toast.LENGTH_SHORT).show();
            return;
        }
        final byte[] data = readUriBytesSafe(uri, 1 << 20);
        if (data == null || data.length == 0) {
            Toast.makeText(this, "歌词读取失败或为空", Toast.LENGTH_SHORT).show();
            return;
        }
        final String finalName = displayName == null ? "lyrics.lrc" : displayName;
        Toast.makeText(this, "正在上传歌词...", Toast.LENGTH_SHORT).show();
        HttpUtil.postMultipartNamed(
                "/music/plaza/lyrics?item_id=" + urlEncode(item.id),
                "lyrics",
                data,
                finalName,
                "text/plain",
                token,
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        Toast.makeText(MusicManageActivity.this, "歌词上传成功", Toast.LENGTH_SHORT).show();
                        loadItems();
                    }

                    @Override
                    public void onError(int code, String error) {
                        String msg = "歌词上传失败";
                        if (code == 413) {
                            msg = "歌词文件过大(超过1MB)";
                        } else if (code == 403) {
                            msg = "只能给本人发布的歌曲上传歌词";
                        } else if (code == 400) {
                            msg = "歌词格式不支持";
                        }
                        Toast.makeText(MusicManageActivity.this, msg, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    private String queryDisplayName(Uri uri) {
        if (uri == null) {
            return "";
        }
        String name = null;
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Exception e) {
            name = null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (TextUtils.isEmpty(name)) {
            String path = uri.getLastPathSegment();
            if (!TextUtils.isEmpty(path)) {
                name = path;
            }
        }
        return name == null ? "" : name;
    }

    private String resolveImageMime(Uri uri, String fileName) {
        String type = null;
        try {
            type = getContentResolver().getType(uri);
        } catch (Exception e) {
            type = null;
        }
        if (!TextUtils.isEmpty(type)) {
            return type;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        return "image/jpeg";
    }

    private InputStream openInputStreamCompat(Uri uri) throws IOException {
        if (uri == null) {
            throw new IOException("uri_empty");
        }
        InputStream input = null;
        try {
            input = getContentResolver().openInputStream(uri);
        } catch (Exception e) {
            input = null;
        }
        if (input != null) {
            return input;
        }
        try {
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd != null) {
                return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            }
        } catch (Exception e) {
        }
        throw new IOException("cannot_open_stream");
    }

    private byte[] readUriBytesSafe(Uri uri, long maxBytes) {
        if (uri == null) {
            return null;
        }
        InputStream is = null;
        java.io.ByteArrayOutputStream bos = null;
        try {
            is = openInputStreamCompat(uri);
            if (is == null) {
                return null;
            }
            bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            long total = 0;
            while ((len = is.read(buf)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    return null;
                }
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                }
            }
            if (bos != null) {
                try {
                    bos.close();
                } catch (Exception e) {
                }
            }
        }
    }

    private long resolveContentLength(Uri uri) {
        if (uri == null) {
            return -1;
        }
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    long size = cursor.getLong(idx);
                    if (size > 0) {
                        return size;
                    }
                }
            }
        } catch (Exception e) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception e) {
                }
            }
        }

        android.content.res.AssetFileDescriptor afd = null;
        try {
            afd = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd != null && afd.getLength() > 0) {
                return afd.getLength();
            }
        } catch (Exception e) {
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Exception e) {
                }
            }
        }
        return -1;
    }
    private void openComments(MusicItem item) {
        if (item == null || item.id == null || item.id.length() == 0) {
            return;
        }
        Intent intent = new Intent(this, MusicCommentsActivity.class);
        intent.putExtra("item_id", item.id);
        intent.putExtra("owner_uid", item.ownerUid == null ? "" : item.ownerUid);
        startActivity(intent);
    }

    private void shareMusicItem(MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        MusicShareActionHelper.ShareItem share = new MusicShareActionHelper.ShareItem();
        share.id = item.id;
        share.name = item.name;
        share.songUrl = item.songUrl;
        share.coverUrl = item.coverUrl;
        share.lyricsUrl = item.lyricsUrl;
        if ((share.coverUrl == null || share.coverUrl.length() == 0)
                && item.ownerAvatar != null && item.ownerAvatar.length() > 0) {
            share.coverUrl = item.ownerAvatar;
        }
        share.ownerUid = item.ownerUid;
        share.ownerName = item.ownerName;
        share.durationMs = item.durationMs;
        MusicShareActionHelper.showShareDialog(this, token, share);
    }

    private void playItem(MusicItem item) {
        if (item == null || item.songUrl == null || item.songUrl.length() == 0) {
            Toast.makeText(this, "歌曲链接不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        reportPlay(item);
        Intent intent = new Intent(this, MusicPlayerActivity.class);
        intent.putExtra("song_name", item.name == null ? "" : item.name);
        intent.putExtra("song_url", item.songUrl == null ? "" : item.songUrl);
        intent.putExtra("cover_url", item.coverUrl == null ? "" : item.coverUrl);
        intent.putExtra("owner_uid", item.ownerUid == null ? "" : item.ownerUid);
        intent.putExtra("owner_name", item.ownerName == null ? "" : item.ownerName);
        intent.putExtra("owner_title", item.ownerTitle == null ? "" : item.ownerTitle);
        intent.putExtra("owner_avatar", item.ownerAvatar == null ? "" : item.ownerAvatar);
        intent.putExtra("item_id", item.id == null ? "" : item.id);
        intent.putExtra("item_likes", Math.max(0, item.likes));
        intent.putExtra("item_comments", Math.max(0, item.comments));
        intent.putExtra("item_liked", item.liked);
        startActivity(intent);
    }

    private void reportPlay(final MusicItem item) {
        if (item == null || item.id == null || item.id.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", item.id);
            HttpUtil.post("/music/plaza/play", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        item.playCount = obj.optInt("play_count", item.playCount + 1);
                    } catch (Exception e) {
                        item.playCount = item.playCount + 1;
                    }
                    adapter.notifyDataSetChanged();
                }

                @Override
                public void onError(int code, String error) {
                    // ignore
                }
            });
        } catch (Exception e) {
            // ignore
        }
    }

    private void toggleLike(final MusicItem item) {
        if (item == null || item.id == null || item.id.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", item.id);
            String path = item.liked ? "/music/plaza/unlike" : "/music/plaza/like";
            HttpUtil.post(path, json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        item.liked = obj.optBoolean("liked", item.liked);
                        item.likes = obj.optInt("likes", item.likes);
                    } catch (Exception e) {
                    }
                    adapter.notifyDataSetChanged();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(MusicManageActivity.this, "操作失败", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show();
        }
    }

    private int parseDurationMs(JSONObject obj) {
        if (obj == null) {
            return 0;
        }
        long raw = obj.optLong("duration_ms", 0);
        if (raw <= 0) {
            raw = obj.optLong("duration", 0);
        }
        if (raw <= 0) {
            raw = obj.optLong("duration_sec", 0);
            if (raw > 0) {
                raw = raw * 1000L;
            }
        }
        if (raw <= 0) {
            String text = obj.optString("duration_text", "");
            int parsedText = parseDurationTextToMs(text);
            if (parsedText > 0) {
                return parsedText;
            }
        }
        if (raw <= 0) {
            JSONObject stats = obj.optJSONObject("stats");
            if (stats != null) {
                raw = stats.optLong("duration_ms", 0);
                if (raw <= 0) {
                    raw = stats.optLong("duration", 0);
                }
                if (raw <= 0) {
                    raw = stats.optLong("duration_sec", 0);
                    if (raw > 0) {
                        raw = raw * 1000L;
                    }
                }
                if (raw <= 0) {
                    int parsedStatsText = parseDurationTextToMs(stats.optString("duration_text", ""));
                    if (parsedStatsText > 0) {
                        return parsedStatsText;
                    }
                }
            }
        }
        if (raw <= 0) {
            return 0;
        }
        if (raw < 1000) {
            raw = raw * 1000L;
        }
        if (raw > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) raw;
    }

    private int parseDurationTextToMs(String text) {
        if (text == null) {
            return 0;
        }
        String raw = text.trim();
        if (raw.length() == 0) {
            return 0;
        }
        String[] parts = raw.split(":");
        if (parts.length < 2 || parts.length > 3) {
            return 0;
        }
        try {
            int seconds = 0;
            for (int i = 0; i < parts.length; i++) {
                String one = parts[i] == null ? "" : parts[i].trim();
                if (one.length() == 0) {
                    return 0;
                }
                int value;
                if (i == parts.length - 1 && one.contains(".")) {
                    value = (int) Double.parseDouble(one);
                } else {
                    value = Integer.parseInt(one);
                }
                if (value < 0) {
                    return 0;
                }
                seconds = seconds * 60 + value;
            }
            if (seconds <= 0) {
                return 0;
            }
            long ms = seconds * 1000L;
            if (ms > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
            return (int) ms;
        } catch (Exception e) {
            return 0;
        }
    }

    private String formatDuration(int durationMs) {
        if (durationMs <= 0) {
            return "--:--";
        }
        int totalSeconds = durationMs / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes > 99) {
            minutes = 99;
        }
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private String formatSize(long sizeBytes) {
        if (sizeBytes <= 0) {
            return "0B";
        }
        float size = sizeBytes;
        if (size < 1024) {
            return (int) size + "B";
        }
        size = size / 1024f;
        if (size < 1024) {
            return String.format(Locale.getDefault(), "%.1fKB", size);
        }
        size = size / 1024f;
        return String.format(Locale.getDefault(), "%.1fMB", size);
    }

    private String urlEncode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    private static class MusicItem {
        String id;
        String name;
        String songUrl;
        String coverUrl;
        String lyricsUrl;
        String ownerUid;
        String ownerName;
        String ownerTitle;
        String ownerAvatar;
        long sizeBytes;
        int durationMs;
        int likes;
        int comments;
        int playCount;
        boolean liked;
        boolean canDelete;
    }

    private class MusicAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            if (position < 0 || position >= items.size()) {
                return null;
            }
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(MusicManageActivity.this)
                        .inflate(R.layout.item_music_manage, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            final MusicItem item = (MusicItem) getItem(position);
            if (item == null) {
                return convertView;
            }
            String name = item.name == null || item.name.length() == 0 ? "未命名歌曲" : item.name;
            holder.tvName.setText(name);
            String owner = item.ownerName;
            if (owner == null || owner.length() == 0) {
                owner = item.ownerUid;
            }
            if (owner == null || owner.length() == 0) {
                owner = "我";
            }
            String title = item.ownerTitle == null ? "" : item.ownerTitle.trim();
            if (title.length() > 0) {
                holder.tvOwner.setText("上传者 · " + owner + " · " + title);
            } else {
                holder.tvOwner.setText("上传者 · " + owner);
            }
            holder.tvMeta.setText("时长 " + formatDuration(item.durationMs) + " · 大小 " + formatSize(item.sizeBytes));
            holder.tvStats.setText((item.liked ? "已赞 " : "赞 ") + item.likes + " · 评论 " + item.comments + " · 播放 " + item.playCount);

            holder.btnPlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playItem(item);
                }
            });
            holder.btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    deleteMusicItem(item);
                }
            });
            holder.btnMore.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showItemActions(item);
                }
            });
            boolean canDelete = item.canDelete;
            if (allListMode && !canDelete) {
                holder.btnDelete.setVisibility(View.GONE);
            } else {
                holder.btnDelete.setVisibility(View.VISIBLE);
                holder.btnDelete.setEnabled(canDelete);
                ViewCompat.setAlpha(holder.btnDelete, canDelete ? 1f : 0.55f);
            }

            if (item.coverUrl != null && item.coverUrl.length() > 0) {
                ImageLoader.load(holder.ivCover, item.coverUrl);
            } else if (item.ownerAvatar != null && item.ownerAvatar.length() > 0) {
                ImageLoader.load(holder.ivCover, item.ownerAvatar);
            } else {
                holder.ivCover.setImageResource(R.drawable.ic_avatar_placeholder);
            }
            return convertView;
        }
    }

    private static class ViewHolder {
        final ImageView ivCover;
        final TextView tvName;
        final TextView tvOwner;
        final TextView tvMeta;
        final TextView tvStats;
        final TextView btnPlay;
        final TextView btnDelete;
        final TextView btnMore;

        ViewHolder(View root) {
            ivCover = root.findViewById(R.id.ivMusicManageCover);
            tvName = root.findViewById(R.id.tvMusicManageName);
            tvOwner = root.findViewById(R.id.tvMusicManageOwner);
            tvMeta = root.findViewById(R.id.tvMusicManageMeta);
            tvStats = root.findViewById(R.id.tvMusicManageStats);
            btnPlay = root.findViewById(R.id.btnMusicManagePlay);
            btnDelete = root.findViewById(R.id.btnMusicManageDelete);
            btnMore = root.findViewById(R.id.btnMusicManageMore);
        }
    }
}
