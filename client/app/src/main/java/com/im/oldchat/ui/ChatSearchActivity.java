package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ChatSearchActivity extends BaseActivity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_FRIEND_UID = "friend_uid";
    public static final String EXTRA_FRIEND_NCUID = "friend_ncuid";
    public static final String EXTRA_FRIEND_NAME = "friend_name";
    public static final String EXTRA_FRIEND_AVATAR = "friend_avatar";
    public static final String EXTRA_GROUP_ID = "group_id";
    public static final String EXTRA_GROUP_NAME = "group_name";
    public static final String EXTRA_GROUP_AVATAR = "group_avatar";
    public static final String EXTRA_GROUP_ROLE = "group_role";
    public static final String EXTRA_INITIAL_QUERY = "initial_query";

    public static final String MODE_DIRECT = "direct";
    public static final String MODE_GROUP = "group";

    private static final int PAGE_LIMIT = 50;

    private EditText etKeyword;
    private TextView tvContext;
    private TextView btnSearch;
    private TextView btnSearchClear;
    private TextView btnLoadMore;
    private TextView tvEmpty;
    private TextView tvSummary;
    private TextView btnFilterAll;
    private TextView btnFilterText;
    private TextView btnFilterMedia;
    private ListView lvResults;

    private ChatSearchResultAdapter adapter;
    private final List<ChatSearchResultAdapter.Item> items = new ArrayList<ChatSearchResultAdapter.Item>();
    private final HashSet<String> itemIds = new HashSet<String>();

    private String token;
    private String mode = MODE_DIRECT;
    private String friendUid;
    private String friendNcuid;
    private String friendName;
    private String friendAvatar;
    private String groupId;
    private String groupName;
    private String groupAvatar;
    private int groupRole;

    private String currentQuery = "";
    private int currentOffset = 0;
    private boolean hasMore = false;
    private boolean loading = false;
    private String currentKind = "all";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_search);

        etKeyword = findViewByIdCompat(R.id.etSearchKeyword);
        tvContext = findViewByIdCompat(R.id.tvSearchContext);
        btnSearch = findViewByIdCompat(R.id.btnSearchAction);
        btnSearchClear = findViewByIdCompat(R.id.btnSearchClear);
        btnLoadMore = findViewByIdCompat(R.id.btnSearchLoadMore);
        tvEmpty = findViewByIdCompat(R.id.tvSearchEmpty);
        tvSummary = findViewByIdCompat(R.id.tvSearchSummary);
        btnFilterAll = findViewByIdCompat(R.id.btnFilterAll);
        btnFilterText = findViewByIdCompat(R.id.btnFilterText);
        btnFilterMedia = findViewByIdCompat(R.id.btnFilterMedia);
        lvResults = findViewByIdCompat(R.id.lvSearchMessages);

        SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
        token = prefs.getString("access_token", "");

        readIntent();
        bindHeader();
        bindFilters();
        bindList();
        bindSearchAction();

        String initial = getIntent().getStringExtra(EXTRA_INITIAL_QUERY);
        if (initial != null && initial.trim().length() > 0) {
            etKeyword.setText(initial.trim());
            performSearch();
        }
    }

    private void readIntent() {
        Intent intent = getIntent();
        if (intent == null) {
            return;
        }
        mode = intent.getStringExtra(EXTRA_MODE);
        if (!MODE_GROUP.equals(mode)) {
            mode = MODE_DIRECT;
        }
        friendUid = intent.getStringExtra(EXTRA_FRIEND_UID);
        friendNcuid = intent.getStringExtra(EXTRA_FRIEND_NCUID);
        friendName = intent.getStringExtra(EXTRA_FRIEND_NAME);
        friendAvatar = intent.getStringExtra(EXTRA_FRIEND_AVATAR);

        groupId = intent.getStringExtra(EXTRA_GROUP_ID);
        if (groupId != null) {
            groupId = groupId.trim().toUpperCase();
        }
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME);
        groupAvatar = intent.getStringExtra(EXTRA_GROUP_AVATAR);
        groupRole = intent.getIntExtra(EXTRA_GROUP_ROLE, 0);
    }

    private void bindHeader() {
        View btnBack = findViewByIdCompat(R.id.btnSearchBack);
        if (btnBack instanceof ImageView) {
            ((ImageView) btnBack).setColorFilter(getResources().getColor(R.color.color_text_primary));
        }
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        TextView tvTitle = findViewByIdCompat(R.id.tvSearchTitle);
        if (tvTitle != null) {
            tvTitle.setText("查找聊天记录");
        }
        if (tvContext != null) {
            if (MODE_GROUP.equals(mode)) {
                String title = (groupName == null || groupName.length() == 0) ? groupId : groupName;
                tvContext.setText(title == null ? "" : title);
            } else {
                String title = (friendName == null || friendName.length() == 0) ? friendUid : friendName;
                tvContext.setText(title == null ? "" : title);
            }
        }
    }

    private void bindList() {
        adapter = new ChatSearchResultAdapter(this, items, MODE_GROUP.equals(mode));
        adapter.setKeyword(currentQuery);
        lvResults.setAdapter(adapter);
        lvResults.setEmptyView(tvEmpty);
        lvResults.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= items.size()) {
                    return;
                }
                openChatByResult(items.get(position));
            }
        });
        lvResults.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                if (!loading && hasMore && currentQuery.length() > 0 && totalItemCount > 0
                        && firstVisibleItem + visibleItemCount >= totalItemCount - 2) {
                    loadSearchPage(false);
                }
            }
        });
        btnLoadMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!loading && hasMore && currentQuery.length() > 0) {
                    loadSearchPage(false);
                }
            }
        });
        refreshLoadMore();
        updateSummary();
    }

    private void bindFilters() {
        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (v == btnFilterText) {
                    currentKind = "text";
                } else if (v == btnFilterMedia) {
                    currentKind = "media";
                } else {
                    currentKind = "all";
                }
                updateFilterState();
                if (currentQuery.length() > 0) {
                    loadSearchPage(true);
                }
            }
        };
        btnFilterAll.setOnClickListener(listener);
        btnFilterText.setOnClickListener(listener);
        btnFilterMedia.setOnClickListener(listener);
        updateFilterState();
    }

    private void updateFilterState() {
        setFilterState(btnFilterAll, "all".equals(currentKind));
        setFilterState(btnFilterText, "text".equals(currentKind));
        setFilterState(btnFilterMedia, "media".equals(currentKind));
    }

    private void setFilterState(TextView view, boolean selected) {
        if (view == null) {
            return;
        }
        view.setSelected(selected);
        view.setTextColor(getResources().getColor(selected ? R.color.color_on_primary : R.color.color_text_primary));
    }

    private void bindSearchAction() {
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                performSearch();
            }
        });
        if (btnSearchClear != null) {
            btnSearchClear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (etKeyword != null) {
                        etKeyword.setText("");
                        etKeyword.requestFocus();
                    }
                    clearSearchState();
                    updateClearButtonVisibility();
                }
            });
        }
        etKeyword.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                    performSearch();
                    return true;
                }
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                    performSearch();
                    return true;
                }
                return false;
            }
        });
        etKeyword.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateClearButtonVisibility();
                if (s == null || s.toString().trim().length() == 0) {
                    clearSearchState();
                }
            }
        });
        updateClearButtonVisibility();
    }

    private void performSearch() {
        String query = etKeyword.getText() == null ? "" : etKeyword.getText().toString().trim();
        if (query.length() == 0) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show();
            return;
        }
        currentQuery = query;
        if (adapter != null) {
            adapter.setKeyword(currentQuery);
        }
        loadSearchPage(true);
    }

    private void loadSearchPage(final boolean reset) {
        if (loading) {
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, getString(R.string.error_not_logged_in), Toast.LENGTH_SHORT).show();
            return;
        }
        if (MODE_GROUP.equals(mode) && (groupId == null || groupId.length() == 0)) {
            Toast.makeText(this, "群ID无效", Toast.LENGTH_SHORT).show();
            return;
        }
        if (MODE_DIRECT.equals(mode) && (friendUid == null || friendUid.length() == 0)) {
            Toast.makeText(this, "好友ID无效", Toast.LENGTH_SHORT).show();
            return;
        }

        if (reset) {
            currentOffset = 0;
            hasMore = false;
            items.clear();
            itemIds.clear();
            adapter.notifyDataSetChanged();
        }
        loading = true;
        refreshLoadMore();

        String path = buildSearchPath(currentOffset, currentQuery);
        HttpUtil.get(path, token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                try {
                    List<ChatSearchResultAdapter.Item> parsed = parseItems(response);
                    for (int i = 0; i < parsed.size(); i++) {
                        ChatSearchResultAdapter.Item item = parsed.get(i);
                        if (item == null || item.id == null || item.id.length() == 0) {
                            continue;
                        }
                        if (itemIds.contains(item.id)) {
                            continue;
                        }
                        itemIds.add(item.id);
                        items.add(item);
                    }
                    currentOffset += parsed.size();
                    hasMore = parsed.size() >= PAGE_LIMIT;
                    adapter.notifyDataSetChanged();
                    if (items.isEmpty()) {
                        tvEmpty.setText("未找到相关消息");
                    }
                } catch (Exception e) {
                    Toast.makeText(ChatSearchActivity.this, "解析搜索结果失败", Toast.LENGTH_SHORT).show();
                }
                loading = false;
                refreshLoadMore();
                updateSummary();
            }

            @Override
            public void onError(int code, String error) {
                loading = false;
                refreshLoadMore();
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(ChatSearchActivity.this, "搜索失败: " + code, Toast.LENGTH_SHORT).show();
                updateSummary();
            }
        });
    }

    private String buildSearchPath(int offset, String query) {
        StringBuilder path = new StringBuilder();
        if (MODE_GROUP.equals(mode)) {
            path.append("/groups/messages/search?group_id=").append(urlEncode(groupId));
        } else {
            path.append("/direct/messages/search?with_uid=").append(urlEncode(friendUid));
            if (friendNcuid != null && friendNcuid.length() > 0) {
                path.append("&with_ncuid=").append(urlEncode(friendNcuid));
            }
        }
        path.append("&q=").append(urlEncode(query));
        path.append("&kind=").append(currentKind);
        path.append("&limit=").append(PAGE_LIMIT);
        path.append("&offset=").append(offset);
        return path.toString();
    }

    private List<ChatSearchResultAdapter.Item> parseItems(String response) throws Exception {
        List<ChatSearchResultAdapter.Item> result = new ArrayList<ChatSearchResultAdapter.Item>();
        JSONObject obj = new JSONObject(response);
        JSONArray arr = obj.optJSONArray("messages");
        if (arr == null) {
            return result;
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m == null) {
                continue;
            }
            ChatSearchResultAdapter.Item item = new ChatSearchResultAdapter.Item();
            item.id = m.optString("id", "");
            item.fromUid = m.optString("from_uid", "");
            item.msgType = m.optString("msg_type", "text");
            item.body = m.optString("body", "");
            item.createdAt = ChatMessageUtil.sanitizeTimestamp(m.optLong("created_at", 0));
            if (item.id == null || item.id.length() == 0) {
                continue;
            }
            result.add(item);
        }
        return result;
    }

    private void refreshLoadMore() {
        if (btnLoadMore == null) {
            return;
        }
        if (loading) {
            btnLoadMore.setVisibility(View.VISIBLE);
            btnLoadMore.setEnabled(false);
            btnLoadMore.setText("搜索中...");
            return;
        }
        if (items.isEmpty()) {
            btnLoadMore.setVisibility(View.GONE);
            return;
        }
        btnLoadMore.setVisibility(View.VISIBLE);
        if (hasMore) {
            btnLoadMore.setEnabled(true);
            btnLoadMore.setText("加载更多");
        } else {
            btnLoadMore.setEnabled(false);
            btnLoadMore.setText("没有更多了");
        }
    }

    private void updateSummary() {
        if (tvSummary == null) {
            return;
        }
        if (currentQuery.length() == 0) {
            tvSummary.setText("请输入关键词开始搜索");
            return;
        }
        String kind = "全部";
        if ("text".equals(currentKind)) {
            kind = "文字";
        } else if ("media".equals(currentKind)) {
            kind = "媒体";
        }
        tvSummary.setText("关键词: " + currentQuery + " · 类型: " + kind + " · 已加载 " + items.size() + " 条");
    }

    private void updateClearButtonVisibility() {
        if (btnSearchClear == null || etKeyword == null) {
            return;
        }
        String keyword = etKeyword.getText() == null ? "" : etKeyword.getText().toString().trim();
        btnSearchClear.setVisibility(keyword.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void clearSearchState() {
        currentQuery = "";
        currentOffset = 0;
        hasMore = false;
        loading = false;
        items.clear();
        itemIds.clear();
        if (adapter != null) {
            adapter.setKeyword("");
            adapter.notifyDataSetChanged();
        }
        if (tvEmpty != null) {
            tvEmpty.setText("暂无搜索结果");
        }
        refreshLoadMore();
        updateSummary();
    }

    private void openChatByResult(ChatSearchResultAdapter.Item item) {
        if (item == null || item.id == null || item.id.length() == 0) {
            return;
        }
        if (MODE_GROUP.equals(mode)) {
            Intent intent = new Intent(this, GroupChatActivity.class);
            intent.putExtra("group_id", groupId);
            intent.putExtra("group_name", groupName);
            intent.putExtra("group_avatar", groupAvatar);
            intent.putExtra("group_role", groupRole);
            intent.putExtra("jump_message_id", item.id);
            startActivity(intent);
            return;
        }
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("friend_uid", friendUid);
        intent.putExtra("friend_ncuid", friendNcuid == null ? "" : friendNcuid);
        intent.putExtra("friend_name", friendName);
        intent.putExtra("friend_avatar", friendAvatar);
        intent.putExtra("jump_message_id", item.id);
        startActivity(intent);
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
}
