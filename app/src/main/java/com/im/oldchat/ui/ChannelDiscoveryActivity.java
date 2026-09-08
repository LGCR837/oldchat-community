package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.ChannelSyncManager;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.data.ChannelCacheStore;
import com.im.oldchat.models.ChannelState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** 公开频道搜索与订阅入口。 */
public class ChannelDiscoveryActivity extends BaseActivity {
    private String token;
    private EditText search;
    private ProgressBar progress;
    private ChannelDiscoveryAdapter adapter;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_discovery);
        token=getSharedPreferences("auth", Context.MODE_PRIVATE).getString("access_token", "");
        search=(EditText)findViewById(R.id.etChannelSearch);
        progress=(ProgressBar)findViewById(R.id.channelDiscoveryProgress);
        adapter=new ChannelDiscoveryAdapter(this,new ArrayList<ChannelState>());
        ListView list=(ListView)findViewById(R.id.listChannelDiscovery); list.setAdapter(adapter);
        findViewById(R.id.btnChannelDiscoveryBack).setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){finish();}});
        findViewById(R.id.btnChannelSearch).setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){load();}});
        list.setOnItemClickListener(new AdapterView.OnItemClickListener(){
            @Override public void onItemClick(AdapterView<?> parent,View view,int position,long id){
                ChannelState channel=adapter.getItem(position);
                open(channel, isSubscribed(channel.channelId));
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener(){
            @Override public boolean onItemLongClick(AdapterView<?> parent,View view,int position,long id){
                ChannelState channel=adapter.getItem(position);
                if(isSubscribed(channel.channelId)) Toast.makeText(ChannelDiscoveryActivity.this,"已经关注该频道",Toast.LENGTH_SHORT).show();
                else follow(channel);
                return true;
            }
        });
        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        String query=search.getText()==null?"":search.getText().toString().trim();
        try{query=URLEncoder.encode(query,"UTF-8");}catch(Exception ignored){}
        HttpUtil.get("/v2/channels/discover?q="+query+"&limit=50",token,new HttpUtil.Callback(){
            @Override public void onSuccess(String response){
                progress.setVisibility(View.GONE);
                ArrayList<ChannelState> result=new ArrayList<ChannelState>();
                try{
                    JSONArray array=new JSONObject(response).optJSONArray("channels");
                    for(int i=0;array!=null&&i<array.length();i++){
                        JSONObject one=array.optJSONObject(i); if(one==null)continue;
                        ChannelState state=new ChannelState();
                        state.channelId=one.optString("id",""); state.name=one.optString("name","");
                        state.handle=one.optString("handle",""); state.avatarUrl=one.optString("avatar_url","");
                        state.description=one.optString("description",""); state.visibility=one.optString("visibility","public");
                        state.subscriberCount=one.optInt("subscriber_count",0); result.add(state);
                    }
                }catch(Exception ignored){}
                adapter.replace(result);
            }
            @Override public void onError(int code,String error){progress.setVisibility(View.GONE);Toast.makeText(ChannelDiscoveryActivity.this,"加载频道失败",Toast.LENGTH_SHORT).show();}
        });
    }

    private boolean isSubscribed(String channelId){
        List<ChannelState> states=ChannelCacheStore.get(this).listStates(this);
        for(int i=0;i<states.size();i++)if(channelId.equals(states.get(i).channelId))return true;
        return false;
    }

    private void follow(final ChannelState channel){
        try{
            JSONObject body=new JSONObject(); body.put("channel_id",channel.channelId);
            HttpUtil.post("/v2/channels/subscribe",body,token,new HttpUtil.Callback(){
                @Override public void onSuccess(String response){
                    com.im.oldchat.api.WSManager.getInstance().refreshChannelSubscriptions();
                    ChannelSyncManager.syncStates(ChannelDiscoveryActivity.this,token,true,new ChannelSyncManager.Callback(){
                        @Override public void onComplete(boolean success){
                            Toast.makeText(ChannelDiscoveryActivity.this,success?"已关注，频道已添加到主页":"关注成功，主页稍后同步",Toast.LENGTH_SHORT).show();
                            if(success) setResult(RESULT_OK);
                        }
                    });
                }
                @Override public void onError(int code,String error){Toast.makeText(ChannelDiscoveryActivity.this,code==403?"该频道需要邀请":"关注失败",Toast.LENGTH_SHORT).show();}
            });
        }catch(Exception ignored){}
    }

    private void open(ChannelState channel, boolean subscribed){
        Intent intent=new Intent(this,ChannelActivity.class);
        intent.putExtra(ChannelActivity.EXTRA_CHANNEL_ID,channel.channelId);
        intent.putExtra(ChannelActivity.EXTRA_CHANNEL_NAME,channel.name);
        intent.putExtra(ChannelActivity.EXTRA_CHANNEL_HANDLE,channel.handle);
        intent.putExtra(ChannelActivity.EXTRA_CHANNEL_AVATAR,channel.avatarUrl);
        intent.putExtra(ChannelActivity.EXTRA_CHANNEL_SUBSCRIBERS,channel.subscriberCount);
        intent.putExtra(ChannelActivity.EXTRA_SUBSCRIBED,subscribed);
        startActivity(intent);
    }
}
