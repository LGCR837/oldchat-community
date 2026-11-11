package com.im.oldchat.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;

public class CipDevelopmentModeActivity extends BaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_cip_mode);
        View back = findViewByIdCompat(R.id.btnCipModeBack);
        View ide = findViewByIdCompat(R.id.btnCipIdeMode);
        View vibe = findViewByIdCompat(R.id.btnCipVibeMode);
        if (back != null) back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); }});
        if (ide != null) ide.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(CipDevelopmentModeActivity.this, CipDeveloperActivity.class)); }});
        if (vibe != null) vibe.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startActivity(new Intent(CipDevelopmentModeActivity.this, CipVibeCodingActivity.class)); }});
    }
}
