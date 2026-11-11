package com.im.oldchat.ui.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import com.im.oldchat.R;

/**
 * 旧视独立播放器控件。它直接属于 Activity 顶层，不依赖 ListView Header，
 * 因此评论/推荐切换和列表回收不会销毁 VideoView Surface。
 */
public class OldViewPlayerView extends FrameLayout {
    public OldViewPlayerView(Context context) {
        super(context);
        init(context);
    }

    public OldViewPlayerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public OldViewPlayerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        setBackgroundColor(0xFF000000);
        LayoutInflater.from(context).inflate(R.layout.view_old_view_player_content, this, true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width > 0) {
            int height = Math.max(1, (int) (width * 9f / 16f));
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    public View getPlayerLayout() { return findViewById(R.id.layoutOldViewPlayer); }
    public ImageView getCoverView() { return (ImageView) findViewById(R.id.ivOldViewDetailCover); }
    public VideoView getVideoView() { return (VideoView) findViewById(R.id.vvOldViewPlayer); }
    public View getPlayButton() { return findViewById(R.id.btnOldViewPlay); }
    public View getFullscreenButton() { return findViewById(R.id.btnOldViewFullscreen); }
    public View getRetryButton() { return findViewById(R.id.btnOldViewRetry); }
    public View getLoadingLayout() { return findViewById(R.id.layoutOldViewPlayerLoading); }
    public ProgressBar getLoadingView() { return (ProgressBar) findViewById(R.id.pbOldViewPlayerLoading); }
    public TextView getHintView() { return (TextView) findViewById(R.id.tvOldViewPlayerHint); }
    public View getControlsLayout() { return findViewById(R.id.layoutOldViewPlayerControls); }
    public ImageView getPlayPauseButton() { return (ImageView) findViewById(R.id.btnOldViewPlayPause); }
    public SeekBar getSeekBar() { return (SeekBar) findViewById(R.id.sbOldViewSeek); }
    public TextView getTimeView() { return (TextView) findViewById(R.id.tvOldViewTime); }
}
