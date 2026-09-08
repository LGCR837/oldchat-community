package com.im.oldchat.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.ClipboardUtil;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.FileReader;

public class CrashActivity extends Activity {
    public static final String EXTRA_LOG_PATH = "log_path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crash);

        TextView tvLog = (TextView) findViewById(R.id.tvCrashLog);
        View btnCopy = findViewById(R.id.btnCopyLog);
        View btnSubmit = findViewById(R.id.btnSubmitReport);
        View btnClose = findViewById(R.id.btnCloseApp);

        String log = getIntent().getStringExtra("log");
        String path = getIntent().getStringExtra(EXTRA_LOG_PATH);
        if ((log == null || log.isEmpty()) && path != null && !path.isEmpty()) {
            log = readFile(path);
        }
        if (log == null) {
            log = "";
        }
        tvLog.setText(log);

        final String finalLog = log;
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToClipboard(finalLog);
            }
        });
        btnSubmit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitReport(finalLog);
            }
        });
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void copyToClipboard(String text) {
        ClipboardUtil.copyText(this, text);
    }

    private void submitReport(String log) {
        final String logFinal = log;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs = getSharedPreferences("auth", Context.MODE_PRIVATE);
                    String token = prefs.getString("access_token", "");

                    JSONObject json = new JSONObject();
                    json.put("crash_log", logFinal);
                    json.put("timestamp", System.currentTimeMillis());
                    json.put("device_model", android.os.Build.MODEL);
                    json.put("android_version", android.os.Build.VERSION.RELEASE);

                    HttpUtil.post("/admins/crash-reports", json, token, new HttpUtil.Callback() {
                        @Override
                        public void onSuccess(String response) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(CrashActivity.this, "报告已提交", Toast.LENGTH_SHORT).show();
                                    finish();
                                }
                            });
                        }

                        @Override
                        public void onError(int code, String error) {
                            final int codeFinal = code;
                            final String errorFinal = error;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(CrashActivity.this, formatSubmitError(codeFinal, errorFinal), Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(CrashActivity.this, "提交失败", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    private String readFile(String path) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(path));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String formatSubmitError(int code, String error) {
        String msg = null;
        if (error != null && error.length() > 0) {
            try {
                JSONObject obj = new JSONObject(error);
                msg = obj.optString("message", "");
                if (msg == null || msg.length() == 0) {
                    msg = obj.optString("error", "");
                }
            } catch (Exception e) {
                msg = error;
            }
        }
        if (msg == null || msg.length() == 0) {
            msg = String.valueOf(code);
        } else if (msg.length() > 80) {
            msg = msg.substring(0, 80);
        }
        return "提交失败: " + code + " " + msg;
    }
}
