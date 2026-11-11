package com.im.oldchat.ui.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.support.v7.widget.AppCompatTextView;

public class FontAwesomeTextView extends AppCompatTextView {
    private static final String FONT_AWESOME_PATH = "fonts/fontawesome-webfont.ttf";
    private static Typeface cachedTypeface;

    public FontAwesomeTextView(Context context) {
        super(context);
        applyFont(context);
    }

    public FontAwesomeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        applyFont(context);
    }

    public FontAwesomeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        applyFont(context);
    }

    private void applyFont(Context context) {
        if (isInEditMode() || context == null) {
            return;
        }
        if (cachedTypeface == null) {
            try {
                cachedTypeface = Typeface.createFromAsset(context.getAssets(), FONT_AWESOME_PATH);
            } catch (Throwable ignore) {
                cachedTypeface = null;
            }
        }
        if (cachedTypeface != null) {
            setTypeface(cachedTypeface);
        }
    }
}
