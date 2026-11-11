package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.support.v7.app.AlertDialog;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;
import com.im.oldchat.api.ChannelSyncManager;
import com.im.oldchat.models.ChannelPost;
import com.im.oldchat.ui.widget.ButtonFlowLayout;
import com.im.oldchat.ui.widget.CircleImageView;
import com.im.oldchat.util.ImageLoader;
import com.im.oldchat.util.ThemeColorUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 全宽频道消息：头像在分割线左侧，发送者显示在线上，正文和媒体占满内容区。 */
final class ChannelPostAdapter extends BaseAdapter {
    private final Context context;
    private List<ChannelPost> posts;

    ChannelPostAdapter(Context context,List<ChannelPost> posts){this.context=context;this.posts=posts;}
    void replace(List<ChannelPost> value){posts=value;notifyDataSetChanged();}
    @Override public int getCount(){return posts==null?0:posts.size();}
    @Override public ChannelPost getItem(int p){return posts.get(p);}
    @Override public long getItemId(int p){return getItem(p).postSeq;}

    @Override public View getView(int position,View convertView,ViewGroup parent){
        Holder h;
        if(convertView==null){
            LinearLayout outer=new LinearLayout(context);outer.setOrientation(LinearLayout.VERTICAL);outer.setBackgroundColor(0xFFFFFFFF);
            TextView date=new TextView(context);date.setGravity(Gravity.CENTER);date.setTextSize(12f);date.setTextColor(0xFF87949F);LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(30));outer.addView(date,dlp);
            LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),0,dp(12),0);outer.addView(header,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(46)));
            CircleImageView avatar=new CircleImageView(context);header.addView(avatar,new LinearLayout.LayoutParams(dp(38),dp(38)));
            TextView sender=new TextView(context);sender.setTextSize(14f);sender.setTextColor(0xFF168ACD);sender.setSingleLine(true);LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);slp.leftMargin=dp(10);header.addView(sender,slp);
            View line=new View(context);line.setBackgroundColor(0xFFE4E9ED);LinearLayout.LayoutParams llp=new LinearLayout.LayoutParams(0,dp(1),1f);llp.leftMargin=dp(10);header.addView(line,llp);
            LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(60),0,dp(14),dp(10));outer.addView(content,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout reply=new LinearLayout(context);reply.setOrientation(LinearLayout.HORIZONTAL);reply.setPadding(0,dp(4),0,dp(7));
            View replyLine=new View(context);replyLine.setBackgroundColor(0xFF168ACD);reply.addView(replyLine,new LinearLayout.LayoutParams(dp(3),ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout replyTexts=new LinearLayout(context);replyTexts.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams rtlp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);rtlp.leftMargin=dp(8);reply.addView(replyTexts,rtlp);
            TextView replyAuthor=new TextView(context);replyAuthor.setTextSize(13f);replyAuthor.setTextColor(0xFF168ACD);replyAuthor.setTypeface(null,android.graphics.Typeface.BOLD);replyTexts.addView(replyAuthor);
            TextView replyBody=new TextView(context);replyBody.setTextSize(13f);replyBody.setTextColor(0xFF7A8A99);replyBody.setMaxLines(2);replyTexts.addView(replyBody);content.addView(reply);
            TextView body=new TextView(context);body.setTextColor(0xFF17212B);body.setTextSize(16f);body.setLineSpacing(dp(2),1f);content.addView(body);
            ImageView image=new ImageView(context);image.setScaleType(ImageView.ScaleType.CENTER_CROP);LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(210));ilp.topMargin=dp(8);content.addView(image,ilp);
            TextView media=new TextView(context);media.setGravity(Gravity.CENTER_VERTICAL);media.setTextSize(15f);media.setTextColor(0xFF168ACD);media.setPadding(dp(12),dp(10),dp(12),dp(10));GradientDrawable mbg=new GradientDrawable();mbg.setColor(0xFFF0F6FA);mbg.setCornerRadius(dp(6));media.setBackgroundDrawable(mbg);LinearLayout.LayoutParams mlp0=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);mlp0.topMargin=dp(8);content.addView(media,mlp0);
            ButtonFlowLayout reactions=new ButtonFlowLayout(context);reactions.setMaxRowWidth(context.getResources().getDisplayMetrics().widthPixels-dp(82));LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);rlp.topMargin=dp(7);content.addView(reactions,rlp);
            TextView meta=new TextView(context);meta.setGravity(Gravity.RIGHT);meta.setTextColor(0xFF87949F);meta.setTextSize(11f);LinearLayout.LayoutParams mtp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);mtp.topMargin=dp(4);content.addView(meta,mtp);
            View divider=new View(context);divider.setBackgroundColor(0xFFE4E9ED);LinearLayout.LayoutParams divlp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(1));divlp.leftMargin=dp(60);outer.addView(divider,divlp);
            h=new Holder();h.outer=outer;h.date=date;h.avatar=avatar;h.sender=sender;h.body=body;h.image=image;h.media=media;h.reactions=reactions;h.meta=meta;h.content=content;h.reply=reply;h.replyLine=replyLine;h.replyAuthor=replyAuthor;h.replyBody=replyBody;h.headerLine=line;h.divider=divider;outer.setTag(h);convertView=outer;
        }else h=(Holder)convertView.getTag();
        final ChannelPost post=getItem(position);
        boolean dark=ThemeColorUtil.isDarkAppearance(context);
        h.outer.setBackgroundColor(dark?0xFF0E1621:0xFFFFFFFF);h.body.setTextColor(dark?0xFFF2F5F7:0xFF17212B);
        h.sender.setTextColor(dark?0xFF5EB5E5:0xFF168ACD);h.meta.setTextColor(dark?0xFF8E9BA7:0xFF87949F);h.date.setTextColor(dark?0xFF8E9BA7:0xFF87949F);
        h.headerLine.setBackgroundColor(dark?0xFF293845:0xFFE4E9ED);h.divider.setBackgroundColor(dark?0xFF293845:0xFFE4E9ED);
        GradientDrawable mediaBg=new GradientDrawable();mediaBg.setColor(dark?0xFF202E3A:0xFFF0F6FA);mediaBg.setCornerRadius(dp(6));h.media.setBackgroundDrawable(mediaBg);h.media.setTextColor(dark?0xFF5EB5E5:0xFF168ACD);
        h.replyLine.setBackgroundColor(dark?0xFF5EB5E5:0xFF168ACD);h.replyAuthor.setTextColor(dark?0xFF5EB5E5:0xFF168ACD);h.replyBody.setTextColor(dark?0xFF8E9BA7:0xFF7A8A99);
        String name=post.authorName==null||post.authorName.length()==0?post.authorUid:post.authorName;h.sender.setText(name);
        boolean hasReply=post.replyToPostId!=null&&post.replyToPostId.length()>0;h.reply.setVisibility(hasReply?View.VISIBLE:View.GONE);
        if(hasReply){h.replyAuthor.setText(post.replyAuthor==null||post.replyAuthor.length()==0?"Reply":post.replyAuthor);h.replyBody.setText(post.replyBody==null?"":post.replyBody);}
        if(post.authorAvatar==null||post.authorAvatar.length()==0)h.avatar.setImageResource(R.drawable.ic_avatar_placeholder);else{h.avatar.setTag(post.authorAvatar);ImageLoader.loadAvatar(h.avatar,post.authorAvatar);}
        h.body.setText(post.body==null?"":post.body);h.body.setVisibility(post.body==null||post.body.length()==0?View.GONE:View.VISIBLE);
        h.image.setVisibility("image".equals(post.msgType)&&post.mediaUrl!=null&&post.mediaUrl.length()>0?View.VISIBLE:View.GONE);
        h.media.setVisibility(("voice".equals(post.msgType)||"video".equals(post.msgType)||"resource".equals(post.msgType))&&post.mediaUrl!=null&&post.mediaUrl.length()>0?View.VISIBLE:View.GONE);
        if(h.image.getVisibility()==View.VISIBLE){ImageLoader.load(h.image,post.mediaUrl);h.image.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){openMedia(post.mediaUrl,post.msgType);}});}
        if(h.media.getVisibility()==View.VISIBLE){final String mType=post.msgType;if("resource".equals(mType)){h.media.setText("文件  ·  点击下载");}else if("voice".equals(mType)){h.media.setText(playingUrl!=null&&playingUrl.equals(post.mediaUrl)?"语音播放中...":"语音消息");}else{h.media.setText("视频");}h.media.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){openMedia(post.mediaUrl,mType);}});}
        h.meta.setText(post.viewCount+" 次观看   "+ChatTimeFormatter.formatHourMinute(post.createdAt));
        String day=dayKey(post.createdAt),prev=position>0?dayKey(getItem(position-1).createdAt):"";h.date.setVisibility(day.equals(prev)?View.GONE:View.VISIBLE);h.date.setText(formatDay(post.createdAt));
        renderReactions(h.reactions,post);h.content.setOnLongClickListener(new View.OnLongClickListener(){@Override public boolean onLongClick(View v){showPostActions(post);return true;}});return convertView;
    }

    private String playingUrl;
    private android.media.MediaPlayer mediaPlayer;

    private void openMedia(String url,String type){
        if(url==null||url.length()==0)return;
        String absolute=resolveAbsolute(url);
        if("voice".equals(type)){playVoice(absolute,url);return;}
        if("video".equals(type)){try{Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(absolute));context.startActivity(i);}catch(Exception ignored){}return;}
        if("resource".equals(type)){
            com.im.oldchat.service.FileDownloadService.enqueue(context,absolute,"channel-file","application/octet-stream");
            Toast.makeText(context,"已加入后台下载，可在 我的 → 文件 查看",Toast.LENGTH_SHORT).show();
            return;
        }
        try{Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(absolute));context.startActivity(i);}catch(Exception ignored){}
    }
    private String resolveAbsolute(String url){
        if(url==null)return "";
        if(url.startsWith("http://")||url.startsWith("https://"))return url;
        String host=com.im.oldchat.api.HttpUtil.BASE_HOST;
        if(host==null||host.length()==0)host=com.im.oldchat.api.HttpUtil.getBaseOrigin();
        return host+(url.startsWith("/")?"":"/")+url;
    }
    private void playVoice(final String absolute,final String displayKey){
        stopVoice();
        playingUrl=displayKey;
        notifyDataSetChanged();
        try{
            mediaPlayer=new android.media.MediaPlayer();
            mediaPlayer.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
            mediaPlayer.setDataSource(absolute);
            mediaPlayer.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener(){@Override public void onPrepared(android.media.MediaPlayer mp){mp.start();}});
            mediaPlayer.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener(){@Override public void onCompletion(android.media.MediaPlayer mp){stopVoice();}});
            mediaPlayer.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener(){@Override public boolean onError(android.media.MediaPlayer mp,int what,int extra){stopVoice();Toast.makeText(context,"语音播放失败",Toast.LENGTH_SHORT).show();return true;}});
            mediaPlayer.prepareAsync();
        }catch(Exception e){stopVoice();Toast.makeText(context,"语音播放失败",Toast.LENGTH_SHORT).show();}
    }
    private void stopVoice(){
        if(mediaPlayer!=null){try{mediaPlayer.stop();}catch(Exception ignored){}try{mediaPlayer.release();}catch(Exception ignored){}mediaPlayer=null;}
        if(playingUrl!=null){playingUrl=null;notifyDataSetChanged();}
    }
    @Override public void notifyDataSetChanged(){super.notifyDataSetChanged();}
    private void renderReactions(ButtonFlowLayout c,final ChannelPost p){
        c.removeAllViews();boolean dark=ThemeColorUtil.isDarkAppearance(context);
        try{JSONArray a=new JSONArray(p.reactionsJson==null?"[]":p.reactionsJson);for(int i=0;i<a.length();i++){
            JSONObject o=a.optJSONObject(i);if(o==null)continue;final String e=o.optString("emoji","");boolean mine=o.optBoolean("mine",false);
            TextView chip=new TextView(context);chip.setText(e+"  "+o.optInt("count",0));chip.setGravity(Gravity.CENTER);chip.setTextSize(13f);chip.setMinHeight(dp(30));chip.setMinWidth(dp(46));
            chip.setTextColor(mine?(dark?0xFF5EB5E5:0xFF168ACD):(dark?0xFFCED6DC:0xFF4E5D68));chip.setPadding(dp(9),dp(4),dp(9),dp(4));
            GradientDrawable bg=new GradientDrawable();bg.setColor(mine?(dark?0xFF1F4B66:0xFFDDF1FC):(dark?0xFF253542:0xFFF0F3F5));bg.setCornerRadius(dp(14));chip.setBackgroundDrawable(bg);
            c.addView(chip,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT));chip.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){toggle(p,e);}});
        }}catch(Exception ignored){}
        TextView add=new TextView(context);add.setText("添加");add.setTextSize(12f);add.setGravity(Gravity.CENTER);add.setMinWidth(dp(46));add.setMinHeight(dp(30));
        add.setTextColor(dark?0xFF8E9BA7:0xFF657580);GradientDrawable addBg=new GradientDrawable();addBg.setColor(dark?0xFF253542:0xFFF0F3F5);addBg.setCornerRadius(dp(15));add.setBackgroundDrawable(addBg);
        c.addView(add,new LinearLayout.LayoutParams(dp(36),dp(30)));add.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){showReactionPicker(p);}});c.setVisibility(View.VISIBLE);
    }
    private void showPostActions(final ChannelPost p){final String[] actions={"回复","添加 Reaction"};new AlertDialog.Builder(context).setItems(actions,new android.content.DialogInterface.OnClickListener(){@Override public void onClick(android.content.DialogInterface d,int which){if(which==0){Intent intent=new Intent(context,ChannelComposeActivity.class);intent.putExtra(ChannelComposeActivity.EXTRA_CHANNEL_ID,p.channelId);intent.putExtra(ChannelComposeActivity.EXTRA_REPLY_POST_ID,p.id);intent.putExtra(ChannelComposeActivity.EXTRA_REPLY_TEXT,p.body);context.startActivity(intent);}else showReactionPicker(p);}}).show();}
    private void showReactionPicker(final ChannelPost p){final String[] e={"👍","❤️","🔥","😂","😢","👎","👏","🎉"};new AlertDialog.Builder(context).setTitle("Reaction").setItems(e,new android.content.DialogInterface.OnClickListener(){@Override public void onClick(android.content.DialogInterface d,int w){toggle(p,e[w]);}}).show();}
    private void toggle(ChannelPost p,String e){String t=context.getSharedPreferences("auth",Context.MODE_PRIVATE).getString("access_token","");ChannelSyncManager.toggleReaction(context,t,p.channelId,p.id,e,new ChannelSyncManager.Callback(){@Override public void onComplete(boolean s){}});}
    private String dayKey(long t){return ChatTimeFormatter.formatDayKey(t);}private String formatDay(long t){return ChatTimeFormatter.formatChineseDay(t);}private int dp(int v){return Math.round(v*context.getResources().getDisplayMetrics().density);}
    private static final class Holder{LinearLayout outer,content,reply;TextView date,sender,body,media,meta,replyAuthor,replyBody;CircleImageView avatar;ImageView image;ButtonFlowLayout reactions;View headerLine,divider,replyLine;}
}
