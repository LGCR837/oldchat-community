package com.im.oldchat.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import com.im.oldchat.R;
import com.im.oldchat.util.NetworkStateManager;

public class TopStatusBar extends FrameLayout implements NetworkStateManager.NetworkStateListener {
    private static final long STARTUP_GRACE_MS = 5000;
    private static final int MAX_BAR_HEIGHT_DP = 40;
    public interface RetryClickListener {
        void onRetry();
    }
    private ProgressBar progressBar;
    private TextView statusText;
    private View statusContainer;
    private boolean loading;
    private RetryClickListener retryClickListener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable delayedUpdate = new Runnable() {
        @Override
        public void run() {
            updateStatus(NetworkStateManager.getInstance().isServerAvailable());
        }
    };

    public TopStatusBar(Context context) {
        super(context);
        init(context);
    }

    public TopStatusBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TopStatusBar(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.view_top_status_bar, this, true);
        progressBar = (ProgressBar) findViewById(R.id.progressBar);
        statusText = (TextView) findViewById(R.id.statusText);
        statusContainer = (View) findViewById(R.id.statusContainer);
        if (statusContainer != null) {
            statusContainer.setClickable(true);
            statusContainer.setFocusable(true);
            statusContainer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (retryClickListener != null) {
                        retryClickListener.onRetry();
                    }
                }
            });
        }

        NetworkStateManager.getInstance().addListener(this);
        updateStatus(NetworkStateManager.getInstance().isServerAvailable());
    }

    @Override
    public void onServerStateChanged(boolean available) {
        updateStatus(available);
    }

    private void updateStatus(boolean available) {
        handler.removeCallbacks(delayedUpdate);
        if (available) {
            statusContainer.setVisibility(View.GONE);
        } else {
            long remaining = NetworkStateManager.getInstance().getStartupGraceRemaining(STARTUP_GRACE_MS);
            if (remaining > 0) {
                statusContainer.setVisibility(View.GONE);
                handler.postDelayed(delayedUpdate, remaining);
                return;
            }
            statusContainer.setVisibility(View.VISIBLE);
        }
        boolean showProgress = loading || statusContainer.getVisibility() == View.VISIBLE;
        progressBar.setVisibility(showProgress ? View.VISIBLE : View.GONE);
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
        updateStatus(NetworkStateManager.getInstance().isServerAvailable());
    }

    public void setOnRetryClickListener(RetryClickListener listener) {
        this.retryClickListener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int maxHeight = (int) (MAX_BAR_HEIGHT_DP * getResources().getDisplayMetrics().density + 0.5f);
        if (maxHeight > 0 && getMeasuredHeight() > maxHeight) {
            setMeasuredDimension(getMeasuredWidth(), maxHeight);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(delayedUpdate);
        NetworkStateManager.getInstance().removeListener(this);
    }
}
