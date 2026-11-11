package com.im.oldchat.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.PopupMenu;
import android.view.MenuItem;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.ChannelSyncManager;
import com.im.oldchat.data.ChannelCacheStore;
import com.im.oldchat.models.ChannelPost;
import com.im.oldchat.models.ChannelState;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.ThemeColorUtil;

import java.util.List;

/** 频道只读 MVP：缓存秒开、after_seq 增量补偿、按最大可见帖子序号标记已读。 */
public class ChannelActivity extends BaseActivity {
    public static final String EXTRA_CHANNEL_ID = "channel_id";
    public static final String EXTRA_CHANNEL_NAME = "channel_name";
    public static final String EXTRA_CHANNEL_HANDLE = "channel_handle";
    public static final String EXTRA_CHANNEL_AVATAR = "channel_avatar";
    public static final String EXTRA_CHANNEL_SUBSCRIBERS = "channel_subscribers";
    public static final String EXTRA_SUBSCRIBED = "channel_subscribed";
    private static final int REQUEST_COMPOSE = 7201;

    private String channelId;
    private String token;
    private ListView listView;
    private TextView title;
    private TextView subtitle;
    private ChannelPostAdapter adapter;
    private long lastReadSent;
    private boolean firstRender = true;
    private ChannelState currentState;
    private boolean subscribed;
    private final BroadcastReceiver channelReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && channelId != null && channelId.equals(
                    intent.getStringExtra(ChannelSyncManager.EXTRA_CHANNEL_ID))) {
                adapter.replace(ChannelCacheStore.get(ChannelActivity.this)
                        .listPosts(ChannelActivity.this, channelId));
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel);
        channelId = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        subscribed = getIntent().getBooleanExtra(EXTRA_SUBSCRIBED, true);
        token = getSharedPreferences("auth", Context.MODE_PRIVATE).getString("access_token", "");
        title = (TextView) findViewById(R.id.tvChannelTitle);
        subtitle = (TextView) findViewById(R.id.tvChannelSubtitle);
        listView = (ListView) findViewById(R.id.listChannelPosts);
        listView.setEmptyView(findViewById(R.id.tvChannelEmpty));
        applyChannelTheme();
        title.setText(getIntent().getStringExtra(EXTRA_CHANNEL_NAME));
        String initialHandle = getIntent().getStringExtra(EXTRA_CHANNEL_HANDLE);
        int initialSubscribers = getIntent().getIntExtra(EXTRA_CHANNEL_SUBSCRIBERS, 0);
        subtitle.setText((initialHandle == null || initialHandle.length() == 0 ? "" : "@" + initialHandle + " · ")
                + initialSubscribers + " subscribers");
        String initialAvatar = getIntent().getStringExtra(EXTRA_CHANNEL_AVATAR);
        android.widget.ImageView avatar = (android.widget.ImageView) findViewById(R.id.ivChannelAvatar);
        if (initialAvatar != null && initialAvatar.length() > 0) {
            avatar.setTag(initialAvatar);
            ImageLoader.loadAvatar(avatar, initialAvatar);
        }
        ((TextView)findViewById(R.id.tvChannelMute)).setText(subscribed ? "MUTE" : "JOIN");
        findViewById(R.id.btnChannelBack).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        findViewById(R.id.tvChannelMute).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleMute(); }
        });
        findViewById(R.id.tvChannelShare).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { shareChannel(); }
        });
        findViewById(R.id.btnChannelMore).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMoreMenu(v); }
        });
        findViewById(R.id.tvChannelPost).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent=new Intent(ChannelActivity.this,ChannelComposeActivity.class);
                intent.putExtra(ChannelComposeActivity.EXTRA_CHANNEL_ID,channelId);
                startActivityForResult(intent,REQUEST_COMPOSE);
            }
        });
        adapter = new ChannelPostAdapter(this, ChannelCacheStore.get(this).listPosts(this, channelId));
        listView.setAdapter(adapter);
        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_IDLE) submitVisibleRead();
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { }
        });
        registerReceiver(channelReceiver, new IntentFilter(ChannelSyncManager.ACTION_CHANNEL_POSTS_UPDATED));
        renderState();
        refreshPosts();
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(channelReceiver); } catch (Exception ignored) { }
        if (!subscribed && channelId != null) {
            ChannelCacheStore.get(this).clearPreviewPosts(this, channelId);
        }
        super.onDestroy();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQUEST_COMPOSE&&resultCode==RESULT_OK)refreshPosts();
    }

    private void refreshPosts() {
        ChannelSyncManager.syncPosts(this, token, channelId, new ChannelSyncManager.Callback() {
            @Override public void onComplete(boolean success) {
                if (!success || isFinishing()) return;
                final List<ChannelPost> posts = ChannelCacheStore.get(ChannelActivity.this)
                        .listPosts(ChannelActivity.this, channelId);
                adapter.replace(posts);
                if (firstRender && !posts.isEmpty()) {
                    firstRender = false;
                    listView.post(new Runnable() {
                        @Override public void run() {
                            listView.setSelection(posts.size() - 1);
                            listView.post(new Runnable() {
                                @Override public void run() { submitVisibleRead(); }
                            });
                        }
                    });
                }
                ChannelSyncManager.syncStates(ChannelActivity.this, token, true,
                        new ChannelSyncManager.Callback() {
                            @Override public void onComplete(boolean ok) { if (ok) renderState(); }
                        });
            }
        });
    }

    private void renderState() {
        List<ChannelState> states = ChannelCacheStore.get(this).listStates(this);
        for (int i = 0; i < states.size(); i++) {
            ChannelState state = states.get(i);
            if (channelId != null && channelId.equals(state.channelId)) {
                currentState = state;
                title.setText(state.name);
                String handle = state.handle == null || state.handle.length() == 0 ? "" : "@" + state.handle + " · ";
                subtitle.setText(handle + state.subscriberCount + " subscribers");
                android.widget.ImageView avatar = (android.widget.ImageView) findViewById(R.id.ivChannelAvatar);
                if (state.avatarUrl != null && state.avatarUrl.length() > 0) ImageLoader.loadAvatar(avatar, state.avatarUrl);
                ((TextView)findViewById(R.id.tvChannelMute)).setText("none".equals(state.notificationLevel) ? "UNMUTE" : "MUTE");
                boolean canPost="owner".equals(state.role)||"admin".equals(state.role)||"publisher".equals(state.role);
                findViewById(R.id.tvChannelPost).setVisibility(canPost?View.VISIBLE:View.GONE);
                findViewById(R.id.dividerChannelPost).setVisibility(canPost?View.VISIBLE:View.GONE);
                lastReadSent = Math.max(lastReadSent, state.lastReadPostSeq);
                return;
            }
        }
    }

    private void applyChannelTheme() {
        boolean dark=ThemeColorUtil.isDarkAppearance(this);
        int bg=dark?0xFF0E1621:0xFFFFFFFF, surface=dark?0xFF17212B:0xFFFFFFFF;
        int primary=dark?0xFFF2F5F7:0xFF17212B, secondary=dark?0xFF8E9BA7:0xFF7A8A99;
        int accent=dark?0xFF5EB5E5:0xFF168ACD;
        findViewById(android.R.id.content).setBackgroundColor(bg); listView.setBackgroundColor(bg);
        title.setTextColor(primary); subtitle.setTextColor(secondary);
        View appbar=(View)title.getParent().getParent(); appbar.setBackgroundColor(surface);
        View bottom=(View)findViewById(R.id.tvChannelMute).getParent(); bottom.setBackgroundColor(surface);
        ((TextView)findViewById(R.id.btnChannelMore)).setTextColor(secondary);
        ((TextView)findViewById(R.id.btnChannelBack)).setTextColor(accent);
        ((TextView)findViewById(R.id.tvChannelMute)).setTextColor(accent);
        ((TextView)findViewById(R.id.tvChannelShare)).setTextColor(accent);
        ((TextView)findViewById(R.id.tvChannelPost)).setTextColor(accent);
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu=new PopupMenu(this,anchor);
        menu.getMenu().add(0,1,0,"频道信息"); menu.getMenu().add(0,2,1,"刷新");
        menu.getMenu().add(0,3,2,"分享频道");
        if(subscribed&&currentState!=null&&!"owner".equals(currentState.role))menu.getMenu().add(0,4,3,"退出频道");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener(){
            @Override public boolean onMenuItemClick(MenuItem item){
                if(item.getItemId()==1){showChannelInfo();return true;}
                if(item.getItemId()==2){refreshPosts();ChannelSyncManager.syncStates(ChannelActivity.this,token,true,null);return true;}
                if(item.getItemId()==3){shareChannel();return true;}
                if(item.getItemId()==4){leaveChannel();return true;}return false;
            }
        });menu.show();
    }

    private void showChannelInfo(){
        if(currentState==null)return;
        String handle=currentState.handle==null||currentState.handle.length()==0?"":("@"+currentState.handle+"\n");
        new AlertDialog.Builder(this).setTitle(currentState.name).setMessage(handle+currentState.subscriberCount+" subscribers\nID: "+currentState.channelId).setPositiveButton(android.R.string.ok,null).show();
    }

    private void leaveChannel(){
        try{org.json.JSONObject body=new org.json.JSONObject();body.put("channel_id",channelId);
            com.im.oldchat.api.HttpUtil.post("/v2/channels/unsubscribe",body,token,new com.im.oldchat.api.HttpUtil.Callback(){
                @Override public void onSuccess(String response){ChannelSyncManager.syncStates(ChannelActivity.this,token,true,null);finish();}
                @Override public void onError(int code,String error){Toast.makeText(ChannelActivity.this,"退出失败",Toast.LENGTH_SHORT).show();}
            });
        }catch(Exception ignored){}
    }

    private void toggleMute() {
        if (!subscribed) { joinChannel(); return; }
        if (currentState == null) return;
        final String level = "none".equals(currentState.notificationLevel) ? "all" : "none";
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("channel_id", channelId); body.put("notification_level", level);
            com.im.oldchat.api.HttpUtil.post("/v2/channels/notifications", body, token,
                    new com.im.oldchat.api.HttpUtil.Callback() {
                        @Override public void onSuccess(String response) {
                            currentState.notificationLevel = level;
                            ((TextView)findViewById(R.id.tvChannelMute)).setText("none".equals(level) ? "UNMUTE" : "MUTE");
                            Toast.makeText(ChannelActivity.this, "none".equals(level) ? "频道已静音" : "频道通知已开启", Toast.LENGTH_SHORT).show();
                        }
                        @Override public void onError(int code, String error) { Toast.makeText(ChannelActivity.this, "设置失败", Toast.LENGTH_SHORT).show(); }
                    });
        } catch (Exception ignored) { }
    }

    private void joinChannel() {
        try {
            org.json.JSONObject body = new org.json.JSONObject(); body.put("channel_id", channelId);
            com.im.oldchat.api.HttpUtil.post("/v2/channels/subscribe", body, token,
                    new com.im.oldchat.api.HttpUtil.Callback() {
                        @Override public void onSuccess(String response) {
                            subscribed = true;
                            com.im.oldchat.api.WSManager.getInstance().refreshChannelSubscriptions();
                            ChannelSyncManager.syncStates(ChannelActivity.this, token, true,
                                    new ChannelSyncManager.Callback() {
                                        @Override public void onComplete(boolean success) {
                                            if (success) { renderState(); refreshPosts(); }
                                        }
                                    });
                        }
                        @Override public void onError(int code, String error) { Toast.makeText(ChannelActivity.this, "加入失败", Toast.LENGTH_SHORT).show(); }
                    });
        } catch (Exception ignored) { }
    }

    private void shareChannel() {
        if (currentState == null) return;
        String target = currentState.handle == null || currentState.handle.length() == 0
                ? currentState.channelId : "https://oc.mcl0.dpdns.org/c/" + currentState.handle;
        Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, currentState.name + "\n" + target);
        startActivity(Intent.createChooser(share, "分享频道"));
    }

    private void submitVisibleRead() {
        int last = listView.getLastVisiblePosition();
        if (last < 0 || last >= adapter.getCount()) return;
        ChannelPost post = adapter.getItem(last);
        if (post == null || post.postSeq <= lastReadSent) return;
        final long seq = post.postSeq;
        lastReadSent = seq;
        ChannelSyncManager.markRead(this, token, channelId, seq, new ChannelSyncManager.Callback() {
            @Override public void onComplete(boolean success) {
                if (!success && lastReadSent == seq) lastReadSent = Math.max(0L, seq - 1L);
            }
        });
    }
}
