package com.im.oldchat.ui;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.view.ViewCompat;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.util.DeviceInfoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class DeviceManagementActivity extends BaseActivity {
    private ListView lvDevices;
    private TextView tvEmpty;
    private TextView btnCleanupUnknownDevices;
    private DeviceListAdapter adapter;
    private final List<DeviceListAdapter.DeviceItem> items = new ArrayList<DeviceListAdapter.DeviceItem>();
    private String token;
    private String currentDeviceId;
    private boolean cleanupRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_device_management);

        View btnBack = (View) findViewByIdCompat(R.id.btnDeviceBack);
        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }

        lvDevices = findViewByIdCompat(R.id.lvDevices);
        tvEmpty = findViewByIdCompat(R.id.tvEmpty);
        btnCleanupUnknownDevices = findViewByIdCompat(R.id.btnCleanupUnknownDevices);

        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        token = prefs.getString("access_token", "");
        currentDeviceId = DeviceInfoUtil.getDeviceId(this);

        adapter = new DeviceListAdapter(this, items, currentDeviceId);
        if (lvDevices != null) {
            lvDevices.setAdapter(adapter);
        }

        if (btnCleanupUnknownDevices != null) {
            btnCleanupUnknownDevices.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmCleanupUnknownDevices();
                }
            });
            setCleanupRunning(false);
        }

        loadDevices();
    }

    private void loadDevices() {
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "未登录", Toast.LENGTH_SHORT).show();
            return;
        }
        if (tvEmpty != null) {
            tvEmpty.setVisibility(View.GONE);
        }
        HttpUtil.get("/me/devices", token, new HttpUtil.Callback() {
            @Override
            public void onSuccess(String response) {
                parseDevices(response);
            }

            @Override
            public void onError(int code, String error) {
                if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                    return;
                }
                Toast.makeText(DeviceManagementActivity.this, "加载失败: " + code, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void parseDevices(String response) {
        try {
            JSONObject obj = new JSONObject(response);
            JSONArray arr = obj.optJSONArray("devices");
            items.clear();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject d = arr.getJSONObject(i);
                    DeviceListAdapter.DeviceItem item = new DeviceListAdapter.DeviceItem();
                    item.deviceId = d.optString("device_id", "");
                    item.deviceName = d.optString("device_name", "");
                    item.platform = d.optString("platform", "");
                    item.appVersion = d.optString("app_version", "");
                    item.lastSeen = d.optLong("last_seen", 0);
                    items.add(item);
                }
            }
            if (adapter != null) {
                adapter.notifyDataSetChanged();
            }
            if (tvEmpty != null) {
                tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            }
        } catch (Exception e) {
            Toast.makeText(this, "解析失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmCleanupUnknownDevices() {
        if (cleanupRunning) {
            return;
        }
        if (token == null || token.length() == 0) {
            Toast.makeText(this, "未登录", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentDeviceId == null || currentDeviceId.trim().length() == 0) {
            Toast.makeText(this, "本机设备标识不可用", Toast.LENGTH_SHORT).show();
            return;
        }
        final int unknownCount = getUnknownDeviceCount();
        if (unknownCount <= 0) {
            Toast.makeText(this, "没有陌生登录设备", Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AppDialogTheme);
        builder.setTitle("安全提醒");
        builder.setMessage("将删除并下线其他设备（" + unknownCount + " 台），是否继续？");
        builder.setNegativeButton("取消", null);
        builder.setPositiveButton("继续", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                cleanupUnknownDevices();
            }
        });
        builder.show();
    }

    private int getUnknownDeviceCount() {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < items.size(); i++) {
            DeviceListAdapter.DeviceItem item = items.get(i);
            String did = item == null ? "" : item.deviceId;
            if (did == null) {
                did = "";
            }
            if (!did.equals(currentDeviceId)) {
                count++;
            }
        }
        return count;
    }

    private void cleanupUnknownDevices() {
        try {
            JSONObject json = new JSONObject();
            json.put("device_id", currentDeviceId == null ? "" : currentDeviceId);
            json.put("device_name", DeviceInfoUtil.getDeviceName());
            json.put("imei", DeviceInfoUtil.getImei(this));
            json.put("platform", "android");
            json.put("app_version", DeviceInfoUtil.getAppVersion(this));
            setCleanupRunning(true);
            HttpUtil.post("/me/devices/cleanup-others", json, token, new HttpUtil.Callback() {
                @Override
                public void onSuccess(String response) {
                    setCleanupRunning(false);
                    handleCleanupSuccess(response);
                }

                @Override
                public void onError(int code, String error) {
                    setCleanupRunning(false);
                    if (HttpUtil.shouldSuppressAuthToast(code, error)) {
                        return;
                    }
                    Toast.makeText(DeviceManagementActivity.this, "操作失败: " + code, Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            setCleanupRunning(false);
            Toast.makeText(this, "请求失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void handleCleanupSuccess(String response) {
        try {
            JSONObject obj = new JSONObject(response);
            saveNewTokens(obj.optString("access_token", ""), obj.optString("refresh_token", ""));
            long removedCount = obj.optLong("removed_count", 0);
            if (removedCount > 0) {
                Toast.makeText(this, "已下线 " + removedCount + " 台陌生设备", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "未发现陌生设备", Toast.LENGTH_SHORT).show();
            }
            loadDevices();
        } catch (Exception e) {
            Toast.makeText(this, "解析失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveNewTokens(String accessToken, String refreshToken) {
        if (accessToken == null || accessToken.length() == 0) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences("auth", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("access_token", accessToken);
        if (refreshToken != null && refreshToken.length() > 0) {
            editor.putString("refresh_token", refreshToken);
        }
        editor.apply();
        token = accessToken;
    }

    private void setCleanupRunning(boolean running) {
        cleanupRunning = running;
        if (btnCleanupUnknownDevices == null) {
            return;
        }
        btnCleanupUnknownDevices.setEnabled(!running);
        ViewCompat.setAlpha(btnCleanupUnknownDevices, running ? 0.6f : 1f);
    }
}
