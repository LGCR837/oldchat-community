package com.im.oldchat.ui;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ThemeColorUtil;

import org.json.JSONObject;

import java.io.InputStream;
import java.security.MessageDigest;

/** 频道发布器：媒体预览、文件秒传（SHA-256 查重）、上传进度和回复引用。 */
public class ChannelComposeActivity extends BaseActivity {
    public static final String EXTRA_CHANNEL_ID="channel_id";
    public static final String EXTRA_REPLY_POST_ID="reply_post_id";
    public static final String EXTRA_REPLY_TEXT="reply_text";
    private static final int PICK_MEDIA=7101;
    private static final long MAX_MEDIA_BYTES=50L<<20;
    private static final long MAX_FILE_BYTES=500L<<20;
    private String channelId,token,expectedMime,replyPostId;
    private EditText body;private TextView mediaLabel,progressText,sendButton;private ImageView mediaPreview;private ProgressBar progress;
    private Uri mediaUri;
    private String fileUrl;
    private boolean sending;

    @Override protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);setContentView(R.layout.activity_channel_compose);
        channelId=getIntent().getStringExtra(EXTRA_CHANNEL_ID);replyPostId=getIntent().getStringExtra(EXTRA_REPLY_POST_ID);
        token=getSharedPreferences("auth",Context.MODE_PRIVATE).getString("access_token","");
        body=(EditText)findViewById(R.id.etChannelPostBody);mediaLabel=(TextView)findViewById(R.id.tvChannelSelectedMedia);
        mediaPreview=(ImageView)findViewById(R.id.ivChannelMediaPreview);progress=(ProgressBar)findViewById(R.id.channelUploadProgress);
        progressText=(TextView)findViewById(R.id.tvChannelUploadProgress);sendButton=(TextView)findViewById(R.id.btnChannelComposeSend);
        if(replyPostId!=null&&replyPostId.length()>0){findViewById(R.id.channelReplyPreview).setVisibility(View.VISIBLE);((TextView)findViewById(R.id.tvChannelReplyText)).setText(getIntent().getStringExtra(EXTRA_REPLY_TEXT));}
        applyTheme();
        findViewById(R.id.btnChannelComposeBack).setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){finish();}});
        findViewById(R.id.btnChannelChooseImage).setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){pickMedia("image/*");}});
        findViewById(R.id.btnChannelChooseVoice).setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){pickMedia("audio/*");}});
        findViewById(R.id.btnChannelChooseVideo).setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){pickMedia("video/*");}});
        findViewById(R.id.btnChannelChooseFile).setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){pickFile();}});
        findViewById(R.id.btnChannelClearMedia).setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){clearMedia();}});
        sendButton.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View v){send();}});
    }

    private void applyTheme(){boolean dark=ThemeColorUtil.isDarkAppearance(this);int bg=dark?0xFF0E1621:0xFFFFFFFF,surface=dark?0xFF17212B:0xFFFFFFFF;int primary=dark?0xFFF2F5F7:0xFF17212B,secondary=dark?0xFF8E9BA7:0xFF7A8A99,accent=dark?0xFF5EB5E5:0xFF168ACD;findViewById(android.R.id.content).setBackgroundColor(bg);body.setTextColor(primary);body.setHintTextColor(secondary);mediaLabel.setTextColor(secondary);progressText.setTextColor(secondary);TextView title=(TextView)findViewById(R.id.tvChannelComposeTitle);title.setTextColor(primary);((View)title.getParent()).setBackgroundColor(surface);findViewById(R.id.dividerChannelCompose).setBackgroundColor(dark?0xFF293845:0xFFE4E9ED);sendButton.setTextColor(accent);}
    private void pickMedia(String mime){expectedMime=mime;Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.setType(mime);i.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(Intent.createChooser(i,"选择媒体"),PICK_MEDIA);}catch(Exception e){Toast.makeText(this,"无法打开文件选择器",Toast.LENGTH_SHORT).show();}}
    private void pickFile(){expectedMime="application/octet-stream";Intent i=new Intent(Intent.ACTION_GET_CONTENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);try{startActivityForResult(Intent.createChooser(i,"选择文件"),PICK_MEDIA);}catch(Exception e){Toast.makeText(this,"无法打开文件选择器",Toast.LENGTH_SHORT).show();}}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode!=PICK_MEDIA||resultCode!=RESULT_OK||data==null)return;Uri selected=data.getData();long size=contentLength(selected);if(size>MAX_MEDIA_BYTES&&!expectedMime.equals("application/octet-stream")){Toast.makeText(this,"文件不能超过50MB",Toast.LENGTH_LONG).show();return;}if(size>MAX_FILE_BYTES){Toast.makeText(this,"文件不能超过500MB",Toast.LENGTH_LONG).show();return;}String name=fileName(selected),mime=mimeType(selected,name);if(expectedMime.equals("application/octet-stream")||!(mime.startsWith("image/")||mime.startsWith("audio/")||mime.startsWith("video/"))){prepareFile(selected,name,mime,size);return;}mediaUri=selected;expectedMime=mime;fileUrl=null;mediaLabel.setText(name+"  ·  "+formatSize(size));findViewById(R.id.rowChannelSelectedMedia).setVisibility(View.VISIBLE);if(mime.startsWith("image/")){mediaPreview.setImageURI(selected);mediaPreview.setVisibility(View.VISIBLE);}else mediaPreview.setVisibility(View.GONE);}
    private void prepareFile(final Uri uri,final String name,final String mime,final long declaredSize){
        mediaLabel.setText("正在校验文件...");findViewById(R.id.rowChannelSelectedMedia).setVisibility(View.VISIBLE);mediaPreview.setVisibility(View.GONE);
        new Thread(new Runnable(){@Override public void run(){
            HashResult h=calculateSHA256(uri,declaredSize);
            if(h.error!=null){runOnUiThread(new Runnable(){@Override public void run(){failed(h.error);}});return;}
            final String sha=h.sha256;final long size=h.size;
            runOnUiThread(new Runnable(){@Override public void run(){checkThenUpload(uri,name,mime,size,sha);}});
        }}).start();
    }
    private void checkThenUpload(final Uri uri,final String name,final String mime,final long size,final String sha){
        mediaLabel.setText("正在检查是否可秒传  "+name);setUploadingUi(true);
        JSONObject json=new JSONObject();
        try{json.put("sha256",sha);json.put("size_bytes",size);}catch(Exception e){startUpload(uri,name,mime,size,sha);return;}
        HttpUtil.post("/v2/files/check",json,token,new HttpUtil.Callback(){@Override public void onSuccess(String response){
            try{JSONObject obj=new JSONObject(response==null?"{}":response);if(obj.optBoolean("exists",false)){String url=obj.optString("url","");if(url!=null&&url.length()>0){onFileReady(name,size,url);return;}}}catch(Exception ignored){}
            startUpload(uri,name,mime,size,sha);
        }@Override public void onError(int code,String error){startUpload(uri,name,mime,size,sha);}});
    }
    private void startUpload(final Uri uri,final String name,final String mime,final long size,final String sha){
        mediaLabel.setText("正在上传  "+name);setUploadingUi(true);
        final String apiBase=ChatFileUploadHttpClient.resolveFilesApiBaseUrl(ChannelComposeActivity.this);
        final String uploadUrl=ChatFileUploadHttpClient.buildUploadUrl(apiBase);
        new Thread(new Runnable(){@Override public void run(){
            final ChatFileUploadHttpClient.UploadResult result=ChatFileUploadHttpClient.upload(ChannelComposeActivity.this,uploadUrl,uri,name,mime,size,sha,new ChatFileUploadHttpClient.ProgressListener(){@Override public void onProgress(final int percent){runOnUiThread(new Runnable(){@Override public void run(){progress.setProgress(percent);progressText.setText(percent+"%");}});}});
            runOnUiThread(new Runnable(){@Override public void run(){
                setUploadingUi(false);
                if(result==null){failed("上传失败");return;}
                if(result.code>=200&&result.code<300){try{JSONObject obj=new JSONObject(result.body==null?"{}":result.body);String url=obj.optString("url","");if(url!=null&&url.length()>0){onFileReady(name,size,url);return;}}catch(Exception ignored){}
                    failed("上传响应无下载链接");return;}
                failed(result.code==413?"文件不能超过500MB":"上传失败");
            }});
        }}).start();
    }
    private void onFileReady(String name,long size,String url){
        fileUrl=url;mediaUri=null;mediaLabel.setText(name+"  ·  "+formatSize(size)+"  ·  已就绪");findViewById(R.id.rowChannelSelectedMedia).setVisibility(View.VISIBLE);mediaPreview.setVisibility(View.GONE);setUploadingUi(false);
    }
    private void setUploadingUi(boolean value){findViewById(R.id.channelUploadProgressBox).setVisibility(value?View.VISIBLE:View.GONE);if(!value){progress.setProgress(0);progressText.setText("0%");}}
    private void clearMedia(){mediaUri=null;fileUrl=null;expectedMime=null;mediaPreview.setImageDrawable(null);mediaPreview.setVisibility(View.GONE);findViewById(R.id.rowChannelSelectedMedia).setVisibility(View.GONE);setUploadingUi(false);}
    private void setSending(boolean value){sending=value;sendButton.setEnabled(!value);sendButton.setText(value?"WAIT":"SEND");}
    private void send(){if(sending)return;final String text=body.getText()==null?"":body.getText().toString().trim();if(text.length()==0&&mediaUri==null&&fileUrl==null){Toast.makeText(this,"消息不能为空",Toast.LENGTH_SHORT).show();return;}setSending(true);if(fileUrl!=null){createPost(text,"resource",fileUrl);return;}if(mediaUri==null){createPost(text,"text","");return;}final Uri selected=mediaUri;final String name=fileName(selected),mime=expectedMime;final long length=contentLength(selected);if(length>MAX_MEDIA_BYTES){failed("文件不能超过50MB");return;}HttpUtil.StreamProvider provider=new HttpUtil.StreamProvider(){@Override public InputStream open()throws Exception{InputStream in=getContentResolver().openInputStream(selected);if(in==null)throw new IllegalStateException();return in;}@Override public long length(){return length;}};HttpUtil.postMultipartStream("/v1/channels/media/upload",provider,name,mime,token,"channel_id",channelId,new HttpUtil.ProgressCallback(){@Override public void onProgress(final long written,final long total){runOnUiThread(new Runnable(){@Override public void run(){int percent=total>0?(int)Math.min(100,written*100/total):0;progress.setProgress(percent);progressText.setText(percent+"%");}});}},new HttpUtil.Callback(){@Override public void onSuccess(String response){try{JSONObject result=new JSONObject(response);createPost(text,result.optString("msg_type","text"),result.optString("media_ref",""));}catch(Exception e){failed("上传结果错误");}}@Override public void onError(int code,String error){failed(code==413?"文件不能超过50MB":"媒体上传失败");}});}
    private void createPost(String text,String type,String ref){try{JSONObject json=new JSONObject();json.put("channel_id",channelId);json.put("body",text);json.put("msg_type",type);json.put("media_url",ref);if(replyPostId!=null)json.put("reply_to_post_id",replyPostId);HttpUtil.post("/v2/channels/posts/send",json,token,new HttpUtil.Callback(){@Override public void onSuccess(String response){setSending(false);setResult(RESULT_OK);Toast.makeText(ChannelComposeActivity.this,"已发布",Toast.LENGTH_SHORT).show();finish();}@Override public void onError(int code,String error){failed(code==403?"没有频道发言权":"发布失败");}});}catch(Exception e){failed("发布失败");}}
    private void failed(String text){setSending(false);setUploadingUi(false);Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    private String fileName(Uri uri){String value="channel-media";Cursor c=null;try{c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null);if(c!=null&&c.moveToFirst())value=c.getString(0);}catch(Exception ignored){}finally{if(c!=null)c.close();}return value==null?"channel-media":value;}
    private String mimeType(Uri uri,String name){String type=getContentResolver().getType(uri);if(type!=null)return type;String lower=name.toLowerCase();if(lower.endsWith(".jpg")||lower.endsWith(".jpeg"))return"image/jpeg";if(lower.endsWith(".png"))return"image/png";if(lower.endsWith(".mp4"))return"video/mp4";if(lower.endsWith(".mp3"))return"audio/mpeg";if(lower.endsWith(".m4a"))return"audio/mp4";return expectedMime==null?"application/octet-stream":expectedMime.replace("*","mpeg");}
    private long contentLength(Uri uri){android.content.res.AssetFileDescriptor fd=null;try{fd=getContentResolver().openAssetFileDescriptor(uri,"r");return fd==null?-1:fd.getLength();}catch(Exception e){return-1;}finally{try{if(fd!=null)fd.close();}catch(Exception ignored){}}}
    private String formatSize(long size){if(size<0)return"unknown size";if(size>=1024*1024)return String.format(java.util.Locale.US,"%.1f MB",size/(1024f*1024f));return(size/1024)+" KB";}

    private HashResult calculateSHA256(Uri uri,long declaredSize){
        InputStream input=null;
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");input=getContentResolver().openInputStream(uri);if(input==null)return new HashResult("",0,"无法读取文件");byte[] buffer=new byte[32*1024];long readBytes=0;int len;while((len=input.read(buffer))!=-1){if(readBytes>MAX_FILE_BYTES-len)return new HashResult("",readBytes,"文件不能超过500MB");digest.update(buffer,0,len);readBytes+=len;}if(readBytes<=0)return new HashResult("",0,"文件为空");return new HashResult(toHex(digest.digest()),readBytes,null);}catch(Exception e){return new HashResult("",0,"无法读取文件");}finally{if(input!=null){try{input.close();}catch(Exception ignored){}}}
    }
    private static String toHex(byte[] data){char[] out=new char[data.length*2];char[] digits="0123456789abcdef".toCharArray();for(int i=0;i<data.length;i++){int v=data[i]&0xff;out[i*2]=digits[v>>>4];out[i*2+1]=digits[v&0x0f];}return new String(out);}
    private static class HashResult{final String sha256;final long size;final String error;HashResult(String sha256,long size,String error){this.sha256=sha256;this.size=size;this.error=error;}}
}
