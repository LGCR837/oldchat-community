package com.im.oldchat.ui;

import android.support.v7.app.AlertDialog;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.bili.BiliApi;
import com.im.oldchat.bili.BiliAuthStore;
import com.im.oldchat.bili.BiliModels;

import java.util.ArrayList;
import java.util.List;

abstract class OldViewActivitySupport1 extends OldViewActivitySupport2 {
    @Override
    protected void loadRecommend(String accessToken) {
        showLoading(true);
        logStep("loadRecommend: start");
        String cookies = BiliAuthStore.getCookies(this);
        BiliApi.requestRecommendVideos(accessToken, cookies, new BiliApi.ApiCallback<List<BiliModels.RecommendItem>>() {
            @Override
            public void onSuccess(List<BiliModels.RecommendItem> data) {
                showLoading(false);
                logStep("loadRecommend: success size=" + (data != null ? data.size() : 0));
                if (adapter != null) {
                    adapter.update(filterRecommendItems(data));
                }
                searchMode = false;
                searchKeyword = "";
                searchPage = 0;
                searchHasMore = true;
                if (tvEmpty != null) {
                    tvEmpty.setText(guestMode ? R.string.old_view_empty_guest : R.string.old_view_empty);
                }
                if (tvStatus != null) {
                    tvStatus.setText(guestMode ? R.string.old_view_status_guest : R.string.old_view_status_login_ok);
                }
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                if (error != null && error.length() > 0) {
                    logError("推荐请求失败: " + error);
                    Toast.makeText(OldViewActivitySupport1.this, error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    protected void showSearchDialog() {
        final EditText input = new EditText(this);
        input.setHint(getString(R.string.old_view_search_hint));
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setText(searchKeyword != null ? searchKeyword : "");
        int pad = (int) (getResources().getDisplayMetrics().density * 16);
        input.setPadding(pad, pad, pad, pad);
        input.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(android.widget.TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                    String keyword = input.getText() != null ? input.getText().toString().trim() : "";
                    if (keyword.length() == 0) {
                        Toast.makeText(OldViewActivitySupport1.this, R.string.old_view_search_input_empty, Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    hideKeyboard(input);
                    startSearch(keyword);
                    return true;
                }
                return false;
            }
        });
        new AlertDialog.Builder(this)
                .setTitle(R.string.old_view_search_title)
                .setView(input)
                .setPositiveButton(R.string.old_view_action_search, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String keyword = input.getText() != null ? input.getText().toString().trim() : "";
                        if (keyword.length() == 0) {
                            Toast.makeText(OldViewActivitySupport1.this, R.string.old_view_search_input_empty, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        hideKeyboard(input);
                        startSearch(keyword);
                    }
                })
                .setNeutralButton(R.string.old_view_action_clear_search, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        hideKeyboard(input);
                        clearSearch();
                    }
                })
                .setNegativeButton(R.string.old_view_action_cancel, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        hideKeyboard(input);
                    }
                })
                .show();
    }

    protected void startSearch(String keyword) {
        searchMode = true;
        searchKeyword = keyword;
        searchPage = 0;
        searchHasMore = true;
        if (adapter != null) {
            adapter.update(null);
        }
        if (tvEmpty != null) {
            tvEmpty.setText(R.string.old_view_search_empty);
        }
        loadSearch(1, true);
    }

    protected void clearSearch() {
        searchMode = false;
        searchKeyword = "";
        searchPage = 0;
        searchHasMore = true;
        String token = BiliAuthStore.getAccessToken(this);
        boolean expired = BiliAuthStore.isExpired(this);
        if (token != null && token.length() > 0 && !expired) {
            loadRecommend(token);
        } else {
            showGuestUi();
        }
    }

    protected void loadSearch(final int page, final boolean clear) {
        if (searchLoading) {
            return;
        }
        if (searchKeyword == null || searchKeyword.length() == 0) {
            return;
        }
        searchLoading = true;
        showLoading(true);
        logStep("loadSearch: keyword=" + searchKeyword + " page=" + page);
        String cookies = BiliAuthStore.getCookies(this);
        BiliApi.requestSearchVideos(searchKeyword, page, cookies, new BiliApi.ApiCallback<BiliModels.SearchResult>() {
            @Override
            public void onSuccess(BiliModels.SearchResult response) {
                showLoading(false);
                searchLoading = false;
                List<BiliModels.RecommendItem> items = mapSearchResults(response);
                if (adapter != null) {
                    if (clear) {
                        adapter.update(items);
                    } else if (items != null && !items.isEmpty()) {
                        List<BiliModels.RecommendItem> merged = new ArrayList<BiliModels.RecommendItem>();
                        int count = adapter.getCount();
                        for (int i = 0; i < count; i++) {
                            Object obj = adapter.getItem(i);
                            if (obj instanceof BiliModels.RecommendItem) {
                                merged.add((BiliModels.RecommendItem) obj);
                            }
                        }
                        merged.addAll(items);
                        adapter.update(merged);
                    }
                }
                searchPage = page;
                searchHasMore = hasMoreSearch(response, items);
            }

            @Override
            public void onError(String error) {
                showLoading(false);
                searchLoading = false;
                if (error != null && error.length() > 0) {
                    logError("搜索失败: " + error);
                    Toast.makeText(OldViewActivitySupport1.this, error, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    protected List<BiliModels.RecommendItem> mapSearchResults(BiliModels.SearchResult response) {
        List<BiliModels.RecommendItem> result = new ArrayList<BiliModels.RecommendItem>();
        if (response == null || response.code != 0 || response.data == null || response.data.result == null) {
            return result;
        }
        for (int i = 0; i < response.data.result.size(); i++) {
            BiliModels.SearchItem item = response.data.result.get(i);
            if (item == null) {
                continue;
            }
            BiliModels.RecommendItem r = new BiliModels.RecommendItem();
            r.cardType = "search";
            r.cardGoto = "av";
            r.gotoType = "av";
            if (item.aid > 0) {
                r.param = String.valueOf(item.aid);
            } else if (item.bvid != null && item.bvid.length() > 0) {
                r.param = item.bvid;
            }
            r.cover = BiliApi.normalizeUrl(item.pic);
            r.title = stripHtml(item.title);
            r.duration = item.duration;
            if (item.play != null) {
                r.playCount = String.valueOf(item.play);
            }
            if (item.danmaku > 0) {
                r.danmakuCount = String.valueOf(item.danmaku);
            }
            r.args = new BiliModels.RecommendArgs();
            r.args.upName = item.author;
            if (item.aid > 0) {
                r.args.aid = item.aid;
            }
            result.add(r);
        }
        return result;
    }

    protected boolean hasMoreSearch(BiliModels.SearchResult response, List<BiliModels.RecommendItem> items) {
        if (response == null || response.data == null) {
            return items != null && items.size() >= 20;
        }
        int totalPages = response.data.numPages;
        if (totalPages <= 0) {
            return items != null && items.size() >= 20;
        }
        return response.data.page < totalPages;
    }
}
