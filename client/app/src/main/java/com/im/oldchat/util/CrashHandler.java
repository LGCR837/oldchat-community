package com.im.oldchat.util;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;
import com.im.oldchat.ui.CrashActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String EXTRA_LOG = "log";
    private static final String CRASH_PROCESS_SUFFIX = ":crash";
    private static CrashHandler instance;

    private final Context context;

    private CrashHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized void install(Context context) {
        if (instance == null) {
            instance = new CrashHandler(context);
            Thread.setDefaultUncaughtExceptionHandler(instance);
        }
    }

    public static boolean isCrashProcess() {
        String name = ProcessUtil.getProcessName();
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        return name.contains(CRASH_PROCESS_SUFFIX);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        String log = buildLog(throwable);
        String path = persistLog(log);
        Intent intent = new Intent(context, CrashActivity.class);
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK;
        if (Build.VERSION.SDK_INT >= 11) {
            flags |= Intent.FLAG_ACTIVITY_CLEAR_TASK;
        } else {
            flags |= Intent.FLAG_ACTIVITY_CLEAR_TOP;
        }
        intent.addFlags(flags);
        intent.putExtra(EXTRA_LOG, log);
        intent.putExtra(CrashActivity.EXTRA_LOG_PATH, path);
        context.startActivity(intent);
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    private String buildLog(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Thread: " + Thread.currentThread().getName());
        pw.println("Time: " + System.currentTimeMillis());
        pw.println();
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private String persistLog(String log) {
        try {
            File dir = new File(context.getFilesDir(), "crash");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, "last_crash.txt");
            FileOutputStream fos = new FileOutputStream(file, false);
            fos.write(log.getBytes("UTF-8"));
            fos.flush();
            fos.close();
            return file.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }
}
