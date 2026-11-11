package com.im.oldchat.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.GridView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.EmojiBackupManager;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.MediaUrlResolver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class EmojiPlazaActivity extends BaseActivity {
    private static final int REQ_PICK_UPLOAD = 4201;
    private static final int REQ_PICK_FROM_LIBRARY = 4202;
    private static final int REQ_PICK_UPLOAD_PERMISSION = 4203;
    private static final long MAX_EMOJI_BYTES = 3L * 1024L * 1024L;
    private static final long MAX_EMOJI_PACKAGE_BYTES = 30L * 1024L * 1024L;
    private static final int PAGE_SIZE = 50;
    private static final int MAX_OFFSET_MISMATCH_RETRY = 1;

    private TextView etKeyword;
    private TextView btnSearch;
    private TextView btnUpload;
    private TextView btnTabAll;
    private TextView btnTabMine;
    private TextView tvEmpty;
    private TextView tvPageInfo;
    private TextView btnPrev;
    private TextView btnNext;
    private ListView lv;

    private String token;
    private String myUid;
    private boolean mineOnly = false;
    private final List<PlazaItem> items = new ArrayList<PlazaItem>();
    private PlazaAdapter adapter;
    private int currentOffset = 0;
    private int totalCount = 0;
    private boolean hasMore = false;
    private boolean isPageLoading = false;
    private int pageRequestSeq = 0;
    private int emojiSelectTargetPx;
    private boolean searchMode;

    protected boolean isSearchMode() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        searchMode = isSearchMode();
        setContentView(searchMode ? R.layout.activity_emoji_plaza_search : R.layout.activity_emoji_plaza);

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        myUid = prefs.getString("my_uid", "");

        etKeyword = findViewByIdCompat(R.id.etEmojiPlazaKeyword);
        btnSearch = findViewByIdCompat(R.id.btnEmojiPlazaSearch);
        btnUpload = findViewByIdCompat(R.id.btnEmojiPlazaUpload);
        btnTabAll = findViewByIdCompat(R.id.btnEmojiPlazaTabAll);
        btnTabMine = findViewByIdCompat(R.id.btnEmojiPlazaTabMine);
        tvEmpty = findViewByIdCompat(R.id.tvEmojiPlazaEmpty);
        tvPageInfo = findViewByIdCompat(R.id.tvEmojiPlazaPageInfo);
        btnPrev = findViewByIdCompat(R.id.btnEmojiPlazaPrev);
        btnNext = findViewByIdCompat(R.id.btnEmojiPlazaNext);
        lv = findViewByIdCompat(R.id.lvEmojiPlaza);
        emojiSelectTargetPx = dpToPx(72);

        View btnBack = findViewByIdCompat(R.id.btnEmojiPlazaBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        adapter = new PlazaAdapter();
        lv.setAdapter(adapter);
        lv.setEmptyView(tvEmpty);
        lv.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= items.size()) {
                    return;
                }
                PlazaItem item = items.get(position);
                boolean isMineItem = myUid != null && myUid.length() > 0
                        && item.ownerUid != null && myUid.equalsIgnoreCase(item.ownerUid);
                showPlazaItemActions(item, isMineItem);
            }
        });

        if (btnSearch != null) {
            btnSearch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (searchMode) {
                        submitSearch();
                    } else {
                        openSearchPage();
                    }
                }
            });
        }
        if (btnUpload != null) {
            btnUpload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showUploadSourceChooser();
                }
            });
        }
        if (btnTabAll != null) {
            btnTabAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mineOnly) {
                        mineOnly = false;
                        currentOffset = 0;
                        applyTabState();
                        loadItems();
                    }
                }
            });
        }
        if (btnTabMine != null) {
            btnTabMine.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!mineOnly) {
                        mineOnly = true;
                        currentOffset = 0;
                        applyTabState();
                        loadItems();
                    }
                }
            });
        }
        if (etKeyword != null && !searchMode) {
            etKeyword.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openSearchPage();
                }
            });
        }
        if (etKeyword != null && searchMode) {
            etKeyword.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                        submitSearch();
                        return true;
                    }
                    if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                        submitSearch();
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
                    if (isPageLoading) {
                        return;
                    }
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
                    if (isPageLoading) {
                        return;
                    }
                    if (!hasMore) {
                        return;
                    }
                    if (totalCount > 0 && (currentOffset + PAGE_SIZE) >= totalCount) {
                        return;
                    }
                    currentOffset += PAGE_SIZE;
                    loadItems();
                }
            });
        }

        applyTabState();
        if (!searchMode) {
            loadItems();
        } else {
            updatePagingInfo();
        }
    }

    private void openSearchPage() {
        startActivity(new Intent(this, EmojiPlazaSearchActivity.class));
    }

    private void submitSearch() {
        String keyword = etKeyword == null || etKeyword.getText() == null
                ? "" : etKeyword.getText().toString().trim();
        if (keyword.length() == 0) {
            Toast.makeText(this, "请输入搜索关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        currentOffset = 0;
        loadItems();
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && etKeyword != null) {
                imm.hideSoftInputFromWindow(etKeyword.getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
    }

    private void applyTabState() {
        if (btnTabAll != null) {
            btnTabAll.setBackgroundResource(mineOnly ? R.drawable.flat_button_bg : R.drawable.bg_primary_button);
            btnTabAll.setTextColor(getResources().getColor(mineOnly
                    ? R.color.color_text_primary : R.color.color_on_primary));
        }
        if (btnTabMine != null) {
            btnTabMine.setBackgroundResource(mineOnly ? R.drawable.bg_primary_button : R.drawable.flat_button_bg);
            btnTabMine.setTextColor(getResources().getColor(mineOnly
                    ? R.color.color_on_primary : R.color.color_text_primary));
        }
    }

    private void loadItems() {
        loadItems(false, 0);
    }

    private void loadItems(boolean bypassCache, int offsetMismatchRetry) {
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        final int requestSeq = ++pageRequestSeq;
        final int requestOffset = currentOffset;
        final int requestOffsetMismatchRetry = offsetMismatchRetry;
        final String q = !searchMode || etKeyword == null || etKeyword.getText() == null
                ? "" : etKeyword.getText().toString().trim();
        String listPath = mineOnly ? "/emoji/plaza/mine" : "/emoji/plaza";
        StringBuilder path = new StringBuilder(listPath).append("?limit=").append(PAGE_SIZE)
                .append("&offset=").append(requestOffset);
        if (q.length() > 0) {
            path.append("&q=").append(urlEncode(q));
        }
        if (bypassCache) {
            path.append("&nonce=").append(System.currentTimeMillis());
        }
        setPageLoading(true);
        HttpUtil.get(path.toString(), token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                if (requestSeq != pageRequestSeq) {
                    return;
                }
                setPageLoading(false);
                try {
                    JSONObject obj = new JSONObject(response);
                    JSONArray arr = obj.optJSONArray("items");
                    int responseOffset = obj.optInt("offset", requestOffset);
                    if (responseOffset < 0) {
                        responseOffset = 0;
                    }
                    if (responseOffset != requestOffset
                            && requestOffsetMismatchRetry < MAX_OFFSET_MISMATCH_RETRY) {
                        currentOffset = requestOffset;
                        loadItems(true, requestOffsetMismatchRetry + 1);
                        return;
                    }
                    totalCount = Math.max(0, obj.optInt("total", 0));
                    int maxOffset = getMaxOffset(totalCount);
                    if (responseOffset > maxOffset) {
                        currentOffset = maxOffset;
                        hasMore = currentOffset < maxOffset;
                        updatePagingInfo();
                        loadItems(true, 0);
                        return;
                    }
                    currentOffset = responseOffset;
                    hasMore = obj.optBoolean("has_more", false);
                    items.clear();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject one = arr.optJSONObject(i);
                            if (one == null) {
                                continue;
                            }
                            PlazaItem item = new PlazaItem();
                            item.id = one.optString("id", "");
                            item.name = one.optString("name", "");
                            item.mediaUrl = one.optString("media_url", "");
                            item.isGif = one.optBoolean("is_gif", false);
                            item.sizeBytes = one.optLong("size_bytes", 0);
                            item.ownerUid = one.optString("owner_uid", "");
                            item.ownerName = one.optString("owner_name", "");
                            item.ownerTitle = one.optString("owner_title", "");
                            item.packageUrl = one.optString("package_url", "");
                            item.coverUrl = one.optString("cover_url", "");
                            item.itemCount = one.optInt("item_count", 1);
                            if (item.id.length() == 0 || item.mediaUrl.length() == 0) {
                                continue;
                            }
                            items.add(item);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    if (tvEmpty != null) {
                        if (items.isEmpty() && q.length() > 0) {
                            tvEmpty.setText("没有找到相关表情");
                        } else if (items.isEmpty()) {
                            tvEmpty.setText(mineOnly ? "你还没有上传表情" : "暂无表情，快来上传第一个吧");
                        }
                    }
                    updatePagingInfo();
                } catch (Exception e) {
                    Toast.makeText(EmojiPlazaActivity.this, "解析广场数据失败", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(int code, String error) {
                if (requestSeq != pageRequestSeq) {
                    return;
                }
                setPageLoading(false);
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(EmojiPlazaActivity.this, "加载失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int getMaxOffset(int count) {
        if (count <= 0) {
            return 0;
        }
        return ((count - 1) / PAGE_SIZE) * PAGE_SIZE;
    }

    private void setPageLoading(boolean loading) {
        isPageLoading = loading;
        updatePagingInfo();
    }

    private void updatePagingInfo() {
        int currentPage = (currentOffset / PAGE_SIZE) + 1;
        int totalPages = totalCount <= 0 ? 1 : ((totalCount - 1) / PAGE_SIZE) + 1;
        if (tvPageInfo != null) {
            tvPageInfo.setText("第 " + currentPage + " / " + totalPages + " 页 · 共 " + totalCount + " 项");
        }
        boolean canPrev = currentOffset > 0 && !isPageLoading;
        boolean canNext = hasMore && !isPageLoading;
        if (totalCount > 0 && (currentOffset + PAGE_SIZE) >= totalCount) {
            canNext = false;
        }
        if (btnPrev != null) {
            btnPrev.setEnabled(canPrev);
            ViewCompat.setAlpha(btnPrev, canPrev ? 1f : 0.5f);
        }
        if (btnNext != null) {
            btnNext.setEnabled(canNext);
            ViewCompat.setAlpha(btnNext, canNext ? 1f : 0.5f);
        }
    }

    private void pickUploadImage() {
        if (!ensureUploadPickPermission()) {
            return;
        }
        openUploadImagePicker();
    }

    private void openUploadImagePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(Intent.createChooser(intent, "选择要上传的表情"), REQ_PICK_UPLOAD);
    }

    private boolean ensureUploadPickPermission() {
        String permission = resolveReadImagePermission();
        if (permission == null || permission.length() == 0) {
            return true;
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        ActivityCompat.requestPermissions(this, new String[]{permission}, REQ_PICK_UPLOAD_PERMISSION);
        return false;
    }

    private String resolveReadImagePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return "";
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return Manifest.permission.READ_MEDIA_IMAGES;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void pickFromMyEmojiLibrary() {
        Intent intent = new Intent(this, EmojiPickerActivity.class);
        startActivityForResult(intent, REQ_PICK_FROM_LIBRARY);
    }

    private void showUploadSourceChooser() {
        final String[] options = new String[]{"从相册选择", "从我的表情包选择", "从我的表情包多选打包上传"};
        new AlertDialog.Builder(this)
                .setTitle("选择上传来源")
                .setItems(options, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 2) {
                            showBatchUploadDialog();
                        } else if (which == 1) {
                            pickFromMyEmojiLibrary();
                        } else {
                            pickUploadImage();
                        }
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_UPLOAD && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                tryPersistReadPermission(uri, data);
                promptUploadName(uri);
            }
            return;
        }
        if (requestCode == REQ_PICK_FROM_LIBRARY && resultCode == Activity.RESULT_OK && data != null) {
            String emojiPath = data.getStringExtra(EmojiPickerActivity.EXTRA_EMOJI_PATH);
            boolean isGif = data.getBooleanExtra(EmojiPickerActivity.EXTRA_EMOJI_IS_GIF, false);
            if (emojiPath == null || emojiPath.length() == 0) {
                Toast.makeText(this, "未选择表情", Toast.LENGTH_SHORT).show();
                return;
            }
            promptUploadNameFromLocalEmoji(emojiPath, isGif);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PICK_UPLOAD_PERMISSION) {
            return;
        }
        boolean granted = grantResults != null
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            openUploadImagePicker();
            return;
        }
        Toast.makeText(this, "未授予媒体读取权限，无法上传表情", Toast.LENGTH_SHORT).show();
    }

    private void tryPersistReadPermission(Uri uri, Intent data) {
        if (uri == null || data == null) {
            return;
        }
        if (Build.VERSION.SDK_INT < 19) {
            return;
        }
        if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
        }
    }

    private void promptUploadName(final Uri uri) {
        if (uri == null) {
            return;
        }
        final String rawName = resolveFileName(uri);
        final String defaultName = buildDefaultEmojiName(rawName);
        final EditText input = new EditText(this);
        input.setHint("请输入表情名称");
        input.setText(defaultName);
        input.setSelection(defaultName.length());
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        new AlertDialog.Builder(this)
                .setTitle("上传表情")
                .setMessage("给这个表情取个名字，方便别人搜索")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("上传", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String name = input.getText() == null ? "" : input.getText().toString().trim();
                        if (name.length() == 0) {
                            Toast.makeText(EmojiPlazaActivity.this, "请填写表情名称", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        uploadEmoji(uri, name);
                    }
                })
                .show();
    }

    private void promptUploadNameFromLocalEmoji(final String path, final boolean isGif) {
        if (path == null || path.length() == 0) {
            return;
        }
        final String defaultName = buildDefaultEmojiName(new File(path).getName());
        final EditText input = new EditText(this);
        input.setHint("请输入表情名称");
        input.setText(defaultName);
        input.setSelection(defaultName.length());
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        new AlertDialog.Builder(this)
                .setTitle("上传表情")
                .setMessage("给这个表情取个名字，方便别人搜索")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("上传", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String name = input.getText() == null ? "" : input.getText().toString().trim();
                        if (name.length() == 0) {
                            Toast.makeText(EmojiPlazaActivity.this, "请填写表情名称", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        uploadEmojiFromLocalPath(path, isGif, name);
                    }
                })
                .show();
    }

    private void uploadEmoji(final Uri uri, final String name) {
        if (uri == null) {
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        final String fileName = resolveFileName(uri);
        final String mimeType = resolveMimeType(uri, fileName);
        if (mimeType == null || !mimeType.startsWith("image/")) {
            Toast.makeText(this, "仅支持图片/动图", Toast.LENGTH_SHORT).show();
            return;
        }
        long size = resolveContentLength(uri);
        if (size > MAX_EMOJI_BYTES) {
            Toast.makeText(this, "表情包不能超过3MB", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在上传...", Toast.LENGTH_SHORT).show();

        final Uri uploadUri = uri;
        HttpUtil.StreamProvider provider = new HttpUtil.StreamProvider() {
            @Override
            public InputStream open() throws Exception {
                InputStream is = getContentResolver().openInputStream(uploadUri);
                if (is == null) {
                    throw new IllegalStateException("open input stream failed");
                }
                return is;
            }

            @Override
            public long length() {
                return resolveContentLength(uploadUri);
            }
        };

        HttpUtil.postMultipartStream(
                "/emoji/plaza/upload",
                provider,
                fileName,
                mimeType,
                token,
                "name",
                name,
                null,
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        Toast.makeText(EmojiPlazaActivity.this, "上传成功", Toast.LENGTH_SHORT).show();
                        loadItems();
                    }

                    @Override
                    public void onError(int code, String error) {
                        handleUploadError(code, error);
                    }
                }
        );
    }

    private void uploadEmojiFromLocalPath(final String path, final boolean isGif, final String name) {
        if (path == null || path.length() == 0) {
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        final File file = new File(path);
        if (!file.exists() || !file.isFile()) {
            Toast.makeText(this, "本地表情文件不存在", Toast.LENGTH_SHORT).show();
            return;
        }
        if (file.length() > MAX_EMOJI_BYTES) {
            Toast.makeText(this, "表情包不能超过3MB", Toast.LENGTH_SHORT).show();
            return;
        }
        final String fileName = file.getName();
        final String mimeType = resolveMimeTypeForLocal(fileName, isGif);
        Toast.makeText(this, "正在上传...", Toast.LENGTH_SHORT).show();

        HttpUtil.StreamProvider provider = new HttpUtil.StreamProvider() {
            @Override
            public InputStream open() throws Exception {
                return new FileInputStream(file);
            }

            @Override
            public long length() {
                return file.length();
            }
        };

        HttpUtil.postMultipartStream(
                "/emoji/plaza/upload",
                provider,
                fileName,
                mimeType,
                token,
                "name",
                name,
                null,
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        Toast.makeText(EmojiPlazaActivity.this, "上传成功", Toast.LENGTH_SHORT).show();
                        loadItems();
                    }

                    @Override
                    public void onError(int code, String error) {
                        handleUploadError(code, error);
                    }
                }
        );
    }

    private String resolveMimeTypeForLocal(String fileName, boolean isGif) {
        if (isGif) {
            return "image/gif";
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    private String buildDefaultEmojiName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() == 0) {
            return "我的表情";
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        if (name.length() == 0) {
            name = "我的表情";
        }
        if (name.length() > 32) {
            name = name.substring(0, 32);
        }
        return name;
    }

    private void handleUploadError(int code, String error) {
        if (HttpUtil.shouldSuppressAuthToast(code, error)) {
            return;
        }
        String lower = error == null ? "" : error.toLowerCase(Locale.US);
        if (code == 403 || lower.contains("permission denied") || lower.contains("eacces")) {
            Toast.makeText(EmojiPlazaActivity.this, "无权限读取所选文件，请在系统设置中开启存储权限后重试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code == 409 || (error != null && error.contains("duplicate_emoji"))) {
            Toast.makeText(EmojiPlazaActivity.this, "你已经上传过同一个表情", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code == 413 || (error != null && error.contains("package_too_large"))) {
            Toast.makeText(EmojiPlazaActivity.this, "表情包压缩包不能超过30MB", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code == 413 || (error != null && error.contains("image_too_large"))) {
            Toast.makeText(EmojiPlazaActivity.this, "表情包不能超过3MB", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code == 400 && error != null && error.contains("invalid_package")) {
            Toast.makeText(EmojiPlazaActivity.this, "压缩包内容无效，需包含图片/动图", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code == 400 && error != null && error.contains("missing_cover")) {
            Toast.makeText(EmojiPlazaActivity.this, "请设置封面后再上传", Toast.LENGTH_SHORT).show();
            return;
        }
        if (code == 400 && error != null && error.contains("invalid_name")) {
            Toast.makeText(EmojiPlazaActivity.this, "表情名称无效", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(EmojiPlazaActivity.this, "上传失败: " + code, Toast.LENGTH_SHORT).show();
    }

    private void confirmSave(final PlazaItem item) {
        if (item == null) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("保存表情")
                .setMessage("保存“" + item.name + "”到我的表情包？")
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        saveToMyEmoji(item);
                    }
                })
                .show();
    }

    private void confirmDeleteUpload(final PlazaItem item) {
        if (item == null || item.id == null || item.id.length() == 0) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除上传")
                .setMessage("确定删除你上传的“" + item.name + "”？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        deleteUploadedEmoji(item.id);
                    }
                })
                .show();
    }

    private void showPlazaItemActions(final PlazaItem item, final boolean isMineItem) {
        if (item == null) {
            return;
        }
        final ArrayList<String> actions = new ArrayList<String>();
        actions.add("收藏");
        actions.add("举报");
        if (isMineItem) {
            actions.add("删除上传");
        }
        final CharSequence[] arr = actions.toArray(new CharSequence[actions.size()]);
        new AlertDialog.Builder(this)
                .setTitle(item.name == null || item.name.length() == 0 ? "表情操作" : item.name)
                .setItems(arr, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which < 0 || which >= actions.size()) {
                            return;
                        }
                        String action = actions.get(which);
                        if ("收藏".equals(action)) {
                            String owner = item.ownerName;
                            if (owner == null || owner.length() == 0) {
                                owner = item.ownerUid;
                            }
                            FavoriteHelper.addEmojiFavorite(EmojiPlazaActivity.this,
                                    item.id,
                                    item.name,
                                    owner,
                                    item.mediaUrl,
                                    item.packageUrl);
                            return;
                        }
                        if ("举报".equals(action)) {
                            showEmojiReportDialog(item);
                            return;
                        }
                        if ("删除上传".equals(action)) {
                            confirmDeleteUpload(item);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEmojiReportDialog(final PlazaItem item) {
        if (item == null) {
            return;
        }
        final String targetUid = item.ownerUid == null ? "" : item.ownerUid.trim();
        if (targetUid.length() == 0) {
            Toast.makeText(this, "无法举报：缺少上传者信息", Toast.LENGTH_SHORT).show();
            return;
        }
        if (myUid != null && myUid.length() > 0 && myUid.equalsIgnoreCase(targetUid)) {
            Toast.makeText(this, "不能举报自己上传的表情", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("如：违规内容、引战、广告等");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(180)});
        int pad = dpToPx(10);
        input.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("举报表情")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("提交", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String reason = input.getText() == null ? "" : input.getText().toString().trim();
                        StringBuilder detail = new StringBuilder();
                        detail.append("[表情广场举报]");
                        if (item.id != null && item.id.length() > 0) {
                            detail.append(" item_id=").append(item.id);
                        }
                        if (item.name != null && item.name.length() > 0) {
                            detail.append(" name=").append(item.name);
                        }
                        String full = reason;
                        if (full.length() > 0) {
                            full += "\n";
                        }
                        full += detail.toString();
                        submitUserReport(targetUid, full);
                    }
                })
                .show();
    }

    private void submitUserReport(String targetUid, String reason) {
        if (targetUid == null || targetUid.length() == 0) {
            Toast.makeText(this, "举报失败：缺少目标用户", Toast.LENGTH_SHORT).show();
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("target_uid", targetUid);
            json.put("reason", reason == null ? "" : reason);
            HttpUtil.post("/reports/user", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(EmojiPlazaActivity.this, "举报已提交，已进入公开法庭", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(EmojiPlazaActivity.this, "举报失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "举报失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void deleteUploadedEmoji(String itemId) {
        if (itemId == null || itemId.length() == 0) {
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", itemId);
            HttpUtil.post("/emoji/plaza/delete", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    Toast.makeText(EmojiPlazaActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                    loadItems();
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (code == 403) {
                        Toast.makeText(EmojiPlazaActivity.this, "只能删除自己上传的表情", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Toast.makeText(EmojiPlazaActivity.this, "删除失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void showBatchUploadDialog() {
        final List<EmojiStore.EmojiItem> localItems = EmojiStore.load(this);
        if (localItems == null || localItems.isEmpty()) {
            Toast.makeText(this, "你的表情包为空", Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<EmojiStore.EmojiItem> validItems = new ArrayList<EmojiStore.EmojiItem>();
        for (int i = 0; i < localItems.size(); i++) {
            EmojiStore.EmojiItem item = localItems.get(i);
            if (item == null || !EmojiStore.isValidEmojiFile(item.path)) {
                continue;
            }
            validItems.add(item);
        }
        if (validItems.isEmpty()) {
            Toast.makeText(this, "没有可上传的本地表情", Toast.LENGTH_SHORT).show();
            return;
        }

        final boolean[] checked = new boolean[validItems.size()];
        final EditText nameInput = new EditText(this);
        nameInput.setHint("请输入表情包名称");
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        nameInput.setText("我的表情包");
        nameInput.setSelection(nameInput.getText().length());

        final TextView coverHint = new TextView(this);
        coverHint.setText("封面使用你勾选列表中的第一个表情");
        coverHint.setTextSize(12f);
        coverHint.setTextColor(getResources().getColor(R.color.color_text_secondary));
        coverHint.setPadding(6, 8, 6, 4);

        final android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(18, 8, 18, 0);
        root.addView(nameInput);
        root.addView(coverHint);
        final GridView gridView = new GridView(this);
        gridView.setNumColumns(4);
        gridView.setVerticalSpacing(dpToPx(8));
        gridView.setHorizontalSpacing(dpToPx(8));
        gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gridView.setSelector(android.R.color.transparent);
        android.widget.LinearLayout.LayoutParams gridLp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(300)
        );
        root.addView(gridView, gridLp);

        final EmojiSelectGridAdapter gridAdapter = new EmojiSelectGridAdapter(validItems, checked, true, 0);
        gridView.setAdapter(gridAdapter);
        gridView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= checked.length) {
                    return;
                }
                checked[position] = !checked[position];
                gridAdapter.notifyDataSetChanged();
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("选择要打包上传的表情")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("打包上传", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String packName = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                        if (packName.length() == 0) {
                            Toast.makeText(EmojiPlazaActivity.this, "请填写表情包名称", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        ArrayList<EmojiStore.EmojiItem> selected = new ArrayList<EmojiStore.EmojiItem>();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) {
                                selected.add(validItems.get(i));
                            }
                        }
                        if (selected.isEmpty()) {
                            Toast.makeText(EmojiPlazaActivity.this, "请至少选择一个表情", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        promptChooseCoverThenUpload(selected, packName);
                    }
                })
                .show();
    }

    private void promptChooseCoverThenUpload(final ArrayList<EmojiStore.EmojiItem> selected, final String packageName) {
        if (selected == null || selected.isEmpty()) {
            return;
        }
        if (selected.size() == 1) {
            uploadEmojiPackageFromSelected(selected, packageName, selected.get(0));
            return;
        }
        final boolean[] singleChecked = new boolean[selected.size()];
        singleChecked[0] = true;
        final int[] selectedIndex = new int[]{0};

        final GridView gridView = new GridView(this);
        gridView.setNumColumns(4);
        gridView.setVerticalSpacing(dpToPx(8));
        gridView.setHorizontalSpacing(dpToPx(8));
        gridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gridView.setSelector(android.R.color.transparent);
        final EmojiSelectGridAdapter gridAdapter = new EmojiSelectGridAdapter(selected, singleChecked, false, 0);
        gridView.setAdapter(gridAdapter);
        gridView.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= singleChecked.length) {
                    return;
                }
                for (int i = 0; i < singleChecked.length; i++) {
                    singleChecked[i] = i == position;
                }
                selectedIndex[0] = position;
                gridAdapter.setSingleSelectedIndex(position);
            }
        });

        final android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(18, 8, 18, 0);
        TextView tip = new TextView(this);
        tip.setText("请选择封面表情");
        tip.setTextSize(12f);
        tip.setTextColor(getResources().getColor(R.color.color_text_secondary));
        tip.setPadding(6, 0, 6, 8);
        root.addView(tip);
        root.addView(gridView, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(300)
        ));

        new AlertDialog.Builder(this)
                .setTitle("选择封面")
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("继续上传", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        int idx = selectedIndex[0];
                        if (idx < 0 || idx >= selected.size()) {
                            idx = 0;
                        }
                        uploadEmojiPackageFromSelected(selected, packageName, selected.get(idx));
                    }
                })
                .show();
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density + 0.5f);
    }

    private void uploadEmojiPackageFromSelected(final ArrayList<EmojiStore.EmojiItem> selected,
                                                final String packageName,
                                                final EmojiStore.EmojiItem coverItem) {
        if (selected == null || selected.isEmpty()) {
            return;
        }
        final File zipFile = buildEmojiPackageZip(selected, packageName);
        if (zipFile == null || !zipFile.exists()) {
            Toast.makeText(this, "打包失败", Toast.LENGTH_SHORT).show();
            return;
        }
        if (zipFile.length() > MAX_EMOJI_PACKAGE_BYTES) {
            zipFile.delete();
            Toast.makeText(this, "表情包压缩包不能超过30MB", Toast.LENGTH_SHORT).show();
            return;
        }

        EmojiStore.EmojiItem cover = coverItem == null ? selected.get(0) : coverItem;
        File coverFile = new File(cover.path);
        byte[] coverBytes = readFileBytesSafe(coverFile, MAX_EMOJI_BYTES);
        if (coverBytes == null || coverBytes.length == 0) {
            zipFile.delete();
            Toast.makeText(this, "封面读取失败", Toast.LENGTH_SHORT).show();
            return;
        }
        String coverType = cover.isGif ? "image/gif" : resolveMimeTypeForLocal(coverFile.getName(), false);
        if (coverType == null || coverType.length() == 0) {
            coverType = "image/jpeg";
        }

        Toast.makeText(this, "正在上传表情包...", Toast.LENGTH_SHORT).show();
        final File finalZip = zipFile;
        HttpUtil.StreamProvider provider = new HttpUtil.StreamProvider() {
            @Override
            public InputStream open() throws Exception {
                return new FileInputStream(finalZip);
            }

            @Override
            public long length() {
                return finalZip.length();
            }
        };

        HttpUtil.postMultipartStreamWithThumb(
                "/emoji/plaza/upload?name=" + urlEncode(packageName)
                        + "&item_count=" + selected.size(),
                provider,
                finalZip.getName(),
                "application/zip",
                coverBytes,
                coverFile.getName(),
                coverType,
                token,
                null,
                new HttpUtil.Callback() {
                    @Override
                    public void onSuccess(String response) {
                        finalZip.delete();
                        Toast.makeText(EmojiPlazaActivity.this, "上传成功", Toast.LENGTH_SHORT).show();
                        currentOffset = 0;
                        loadItems();
                    }

                    @Override
                    public void onError(int code, String error) {
                        finalZip.delete();
                        if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                            return;
                        }
                        if (code == 413 || (error != null && error.contains("package_too_large"))) {
                            Toast.makeText(EmojiPlazaActivity.this, "表情包压缩包不能超过30MB", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (code == 400 && error != null && error.contains("invalid_package")) {
                            Toast.makeText(EmojiPlazaActivity.this, "压缩包内容无效，需包含图片/动图", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(EmojiPlazaActivity.this, "上传失败: " + code, Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private File buildEmojiPackageZip(ArrayList<EmojiStore.EmojiItem> selected, String packageName) {
        if (selected == null || selected.isEmpty()) {
            return null;
        }
        String safeName = packageName == null ? "emoji_package" : packageName.trim();
        if (safeName.length() == 0) {
            safeName = "emoji_package";
        }
        safeName = safeName.replaceAll("[^0-9a-zA-Z_\\-\\u4e00-\\u9fa5]", "_");
        if (safeName.length() > 24) {
            safeName = safeName.substring(0, 24);
        }
        File zipFile = new File(getCacheDir(), safeName + "_" + System.currentTimeMillis() + ".zip");
        ZipOutputStream zos = null;
        boolean built = false;
        try {
            zos = new ZipOutputStream(new FileOutputStream(zipFile));
            byte[] buffer = new byte[8192];
            long written = 0L;
            int index = 0;
            for (int i = 0; i < selected.size(); i++) {
                EmojiStore.EmojiItem item = selected.get(i);
                if (item == null || item.path == null || item.path.length() == 0) {
                    continue;
                }
                File file = new File(item.path);
                if (!file.exists() || !file.isFile()) {
                    continue;
                }
                String ext = EmojiStore.normalizeEmojiExt(item.path, item.isGif);
                String entryName = String.format(Locale.US, "%03d%s", index + 1, ext);
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);

                FileInputStream fis = null;
                try {
                    fis = new FileInputStream(file);
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        zos.write(buffer, 0, len);
                        written += len;
                        if (written > MAX_EMOJI_PACKAGE_BYTES) {
                            return null;
                        }
                    }
                } finally {
                    if (fis != null) {
                        try {
                            fis.close();
                        } catch (Exception e) {
                        }
                    }
                }
                zos.closeEntry();
                index++;
            }
            zos.finish();
            if (index <= 0 || zipFile.length() <= 0) {
                return null;
            }
            built = true;
            return zipFile;
        } catch (Exception e) {
            return null;
        } finally {
            if (zos != null) {
                try {
                    zos.close();
                } catch (Exception e) {
                }
            }
            if (!built || !zipFile.exists() || zipFile.length() <= 0) {
                zipFile.delete();
            }
        }
    }

    private byte[] readFileBytesSafe(File file, long maxBytes) {
        if (file == null || !file.exists() || !file.isFile()) {
            return null;
        }
        if (maxBytes > 0 && file.length() > maxBytes) {
            return null;
        }
        FileInputStream fis = null;
        java.io.ByteArrayOutputStream bos = null;
        try {
            fis = new FileInputStream(file);
            bos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            long total = 0;
            while ((len = fis.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    return null;
                }
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
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

    private void saveToMyEmoji(final PlazaItem item) {
        if (item == null || item.id == null || item.id.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("item_id", item.id);
            HttpUtil.post("/emoji/plaza/save", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    try {
                        JSONObject obj = new JSONObject(response);
                        String packageUrl = obj.optString("package_url", item.packageUrl);
                        if (packageUrl != null && packageUrl.length() > 0) {
                            saveEmojiPackageLocally(packageUrl);
                            return;
                        }
                        String mediaUrl = obj.optString("media_url", item.mediaUrl);
                        boolean isGif = obj.optBoolean("is_gif", item.isGif);
                        saveEmojiLocally(mediaUrl, isGif);
                    } catch (Exception e) {
                        if (item.packageUrl != null && item.packageUrl.length() > 0) {
                            saveEmojiPackageLocally(item.packageUrl);
                        } else {
                            saveEmojiLocally(item.mediaUrl, item.isGif);
                        }
                    }
                }

                @Override
                public void onError(int code, String error) {
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    if (item.packageUrl != null && item.packageUrl.length() > 0) {
                        saveEmojiPackageLocally(item.packageUrl);
                    } else {
                        saveEmojiLocally(item.mediaUrl, item.isGif);
                    }
                }
            });
        } catch (Exception e) {
            if (item.packageUrl != null && item.packageUrl.length() > 0) {
                saveEmojiPackageLocally(item.packageUrl);
            } else {
                saveEmojiLocally(item.mediaUrl, item.isGif);
            }
        }
    }

    private void saveEmojiLocally(String mediaUrl, boolean isGif) {
        if (mediaUrl == null || mediaUrl.length() == 0) {
            Toast.makeText(this, "表情地址无效", Toast.LENGTH_SHORT).show();
            return;
        }
        EmojiStore.saveFromUrlAsync(this, mediaUrl, isGif, new EmojiStore.SaveCallback() {
            @Override
            public void onResult(boolean success, String message) {
                if (message == null || message.length() == 0) {
                    message = success ? "已保存到表情包" : "保存失败";
                }
                Toast.makeText(EmojiPlazaActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveEmojiPackageLocally(String packageUrl) {
        if (packageUrl == null || packageUrl.length() == 0) {
            Toast.makeText(this, "表情包地址无效", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在保存合集...", Toast.LENGTH_SHORT).show();
        final String finalUrl = packageUrl;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File temp = new File(getCacheDir(), "emoji_pkg_" + System.currentTimeMillis() + ".zip");
                boolean ok = downloadToFile(finalUrl, temp, MAX_EMOJI_PACKAGE_BYTES);
                if (!ok) {
                    if (temp.exists()) {
                        temp.delete();
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(EmojiPlazaActivity.this, "下载表情包合集失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;
                }

                final Uri uri = Uri.fromFile(temp);
                EmojiBackupManager.importEmojis(EmojiPlazaActivity.this, uri, new EmojiBackupManager.RestoreCallback() {
                    @Override
                    public void onSuccess(String message, int count) {
                        if (temp.exists()) {
                            temp.delete();
                        }
                        Toast.makeText(EmojiPlazaActivity.this,
                                message == null ? ("已导入 " + count + " 个表情") : message,
                                Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String message) {
                        if (temp.exists()) {
                            temp.delete();
                        }
                        Toast.makeText(EmojiPlazaActivity.this,
                                message == null ? "导入表情包失败" : message,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    private boolean downloadToFile(String url, File outFile, long maxBytes) {
        if (url == null || url.length() == 0 || outFile == null) {
            return false;
        }
        InputStream is = null;
        FileOutputStream os = null;
        HttpURLConnection conn = null;
        try {
            String resolved = resolveAbsoluteUrl(url);
            conn = (HttpURLConnection) new URL(resolved).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            if (token != null && token.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            conn.connect();
            if (conn.getResponseCode() != 200) {
                return false;
            }
            int contentLen = conn.getContentLength();
            if (contentLen > 0 && maxBytes > 0 && contentLen > maxBytes) {
                return false;
            }
            is = conn.getInputStream();
            os = new FileOutputStream(outFile);
            byte[] buffer = new byte[8192];
            int len;
            long total = 0;
            while ((len = is.read(buffer)) != -1) {
                total += len;
                if (maxBytes > 0 && total > maxBytes) {
                    return false;
                }
                os.write(buffer, 0, len);
            }
            os.flush();
            return total > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                }
            }
            if (os != null) {
                try {
                    os.close();
                } catch (Exception e) {
                }
            }
            if (conn != null) {
                conn.disconnect();
            }
            if (outFile.exists() && outFile.length() <= 0) {
                outFile.delete();
            }
        }
    }

    private String resolveAbsoluteUrl(String url) {
        if (url == null || url.length() == 0) {
            return "";
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return MediaUrlResolver.resolve(url);
        }
        if (url.startsWith("/")) {
            return MediaUrlResolver.resolve(url);
        }
        return MediaUrlResolver.resolve("/" + url);
    }

    private String resolveFileName(Uri uri) {
        if (uri == null) {
            return "emoji.jpg";
        }
        String name = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
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
        if (name == null || name.length() == 0) {
            String path = uri.getLastPathSegment();
            if (path != null && path.length() > 0) {
                name = path;
            }
        }
        if (name == null || name.length() == 0) {
            name = "emoji.jpg";
        }
        return name;
    }

    private String resolveMimeType(Uri uri, String fileName) {
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
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return "image/jpeg";
    }

    private long resolveContentLength(Uri uri) {
        if (uri == null) {
            return -1;
        }
        AssetFileDescriptor afd = null;
        try {
            afd = getContentResolver().openAssetFileDescriptor(uri, "r");
            if (afd == null) {
                return -1;
            }
            return afd.getLength();
        } catch (Exception e) {
            return -1;
        } finally {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Exception e) {
                }
            }
        }
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

    private static class PlazaItem {
        String id;
        String name;
        String mediaUrl;
        String packageUrl;
        String coverUrl;
        int itemCount;
        boolean isGif;
        long sizeBytes;
        String ownerUid;
        String ownerName;
        String ownerTitle;
    }

    private class EmojiSelectGridAdapter extends BaseAdapter {
        private final List<EmojiStore.EmojiItem> data;
        private final boolean[] checked;
        private final boolean multiMode;
        private int singleSelectedIndex;

        EmojiSelectGridAdapter(List<EmojiStore.EmojiItem> data, boolean[] checked,
                              boolean multiMode, int singleSelectedIndex) {
            this.data = data;
            this.checked = checked;
            this.multiMode = multiMode;
            this.singleSelectedIndex = singleSelectedIndex;
        }

        void setSingleSelectedIndex(int index) {
            this.singleSelectedIndex = index;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data == null ? 0 : data.size();
        }

        @Override
        public Object getItem(int position) {
            if (data == null || position < 0 || position >= data.size()) {
                return null;
            }
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            EmojiSelectViewHolder holder;
            if (convertView == null) {
                convertView = LayoutInflater.from(EmojiPlazaActivity.this)
                        .inflate(R.layout.item_emoji_batch_select, parent, false);
                holder = new EmojiSelectViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (EmojiSelectViewHolder) convertView.getTag();
            }

            EmojiStore.EmojiItem item = (EmojiStore.EmojiItem) getItem(position);
            if (item != null) {
                EmojiBitmapLoader.load(EmojiPlazaActivity.this, holder.ivPreview, item.path, emojiSelectTargetPx);
            } else {
                holder.ivPreview.setTag(null);
                holder.ivPreview.setImageResource(R.drawable.ic_avatar_placeholder);
            }

            boolean selected;
            if (multiMode) {
                selected = checked != null && position >= 0 && position < checked.length && checked[position];
            } else {
                selected = position == singleSelectedIndex;
            }
            holder.mask.setVisibility(selected ? View.VISIBLE : View.GONE);
            holder.check.setVisibility(selected ? View.VISIBLE : View.GONE);
            return convertView;
        }
    }

    private static class EmojiSelectViewHolder {
        final ImageView ivPreview;
        final View mask;
        final TextView check;

        EmojiSelectViewHolder(View root) {
            ivPreview = root.findViewById(R.id.ivEmojiBatchPreview);
            mask = root.findViewById(R.id.viewEmojiBatchMask);
            check = root.findViewById(R.id.tvEmojiBatchCheck);
        }
    }

    private class PlazaAdapter extends BaseAdapter {
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
                convertView = LayoutInflater.from(EmojiPlazaActivity.this)
                        .inflate(R.layout.item_emoji_plaza, parent, false);
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            final PlazaItem item = (PlazaItem) getItem(position);
            if (item == null) {
                return convertView;
            }
            holder.tvName.setText(item.name == null || item.name.length() == 0 ? "未命名表情" : item.name);
            String owner = item.ownerName;
            if (owner == null || owner.length() == 0) {
                owner = item.ownerUid;
            }
            if (owner == null || owner.length() == 0) {
                owner = "匿名用户";
            }
            String title = item.ownerTitle == null ? "" : item.ownerTitle.trim();
            if (title.length() > 0) {
                holder.tvOwner.setText("上传者: " + owner + " · " + title);
            } else {
                holder.tvOwner.setText("上传者: " + owner);
            }
            String typeText = (item.packageUrl != null && item.packageUrl.length() > 0)
                    ? ("合集(" + Math.max(1, item.itemCount) + "个)")
                    : (item.isGif ? "动图" : "静态");
            holder.tvMeta.setText(typeText + " · " + formatSize(item.sizeBytes));
            // 是否本人上传只取决于所有权，不能依赖当前是否位于“我上传的”Tab。
            final boolean isMineItem = myUid != null && myUid.length() > 0
                    && item.ownerUid != null && myUid.equalsIgnoreCase(item.ownerUid);
            holder.btnSave.setVisibility(isMineItem ? View.GONE : View.VISIBLE);
            holder.btnDelete.setVisibility(isMineItem ? View.VISIBLE : View.GONE);
            holder.btnSave.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmSave(item);
                }
            });
            holder.btnDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmDeleteUpload(item);
                }
            });
            convertView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showPlazaItemActions(item, isMineItem);
                    return true;
                }
            });
            String previewUrl = (item.coverUrl != null && item.coverUrl.length() > 0) ? item.coverUrl : item.mediaUrl;
            ImageLoader.load(holder.ivPreview, previewUrl);
            return convertView;
        }
    }

    private static class ViewHolder {
        final ImageView ivPreview;
        final TextView tvName;
        final TextView tvOwner;
        final TextView tvMeta;
        final TextView btnSave;
        final TextView btnDelete;

        ViewHolder(View root) {
            ivPreview = root.findViewById(R.id.ivEmojiPlazaPreview);
            tvName = root.findViewById(R.id.tvEmojiPlazaName);
            tvOwner = root.findViewById(R.id.tvEmojiPlazaOwner);
            tvMeta = root.findViewById(R.id.tvEmojiPlazaMeta);
            btnSave = root.findViewById(R.id.btnEmojiPlazaSave);
            btnDelete = root.findViewById(R.id.btnEmojiPlazaDelete);
        }
    }
}
