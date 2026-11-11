package com.im.oldchat.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.telephony.TelephonyManager;

public class DeviceInfoUtil {
    public static String getDeviceId(Context context) {
        if (context == null) {
            return "";
        }
        try {
            String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            return id != null ? id : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static String getImei(Context context) {
        if (context == null) {
            return "";
        }
        if (Build.VERSION.SDK_INT >= 29) {
            return "";
        }
        try {
            if (Build.VERSION.SDK_INT >= 23
                    && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                    != PackageManager.PERMISSION_GRANTED) {
                return "";
            }
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                return "";
            }
            // 反射避免新 targetSdk 下 lint 将旧接口误判为特权权限，也避免链接差异。
            Object legacy = TelephonyManager.class.getMethod("getDeviceId").invoke(tm);
            return legacy instanceof String ? (String) legacy : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static String getDeviceName() {
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (manufacturer.length() == 0) {
            return model;
        }
        if (model.length() == 0) {
            return manufacturer;
        }
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return model;
        }
        return manufacturer + " " + model;
    }

    public static String getAppVersion(Context context) {
        if (context == null) {
            return "";
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }
}
