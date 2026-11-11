package com.im.oldchat;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AppCompatDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LayoutAnimationController;
import android.widget.AbsListView;
import android.widget.TextView;
import android.widget.Toast;
import com.im.oldchat.ui.widget.FontAwesomeTextView;
import com.im.oldchat.ui.widget.SquarePressFeedback;
import com.im.oldchat.util.CrashHandler;
import com.im.oldchat.util.ScreenPerfTracer;
import com.im.oldchat.util.SettingsPrefs;
import com.im.oldchat.util.ThemeColorUtil;
import com.im.oldchat.util.ThemeStyleResolver;
import java.util.ArrayList;
import java.util.List;
public class BaseActivity extends AppCompatActivity {
    private static final int REQ_STARTUP_PERMISSIONS = 4101;
    private static final String FONT_TAG_MONO = "square_mono";
    private static final String FONT_SANS_ASSET = "fonts/inter_regular.ttf";
    private static final String FONT_MONO_ASSET = "fonts/jetbrains_mono_regular.ttf";
    private static boolean startupPermissionRequested;
    private static boolean startupPermissionRequesting;
    private static boolean typefaceLoadAttempted;
    private static Typeface sansTypeface;
    private static Typeface monoTypeface;
    @SuppressWarnings("unchecked")
    protected <T extends View> T findViewByIdCompat(int id) {
        return (T) super.findViewById(id);
    }
    protected boolean enableSwipeBack() {
        return false;
    }

    protected boolean hasSharedAvatarAnimation() {
        android.content.Intent intent = getIntent();
        return intent != null && com.im.oldchat.util.ChatHeaderEnterAnimHelper.hasAnimationData(intent);
    }

    protected int getOpenEnterAnimation() {
        return R.anim.chat_slide_in_right;
    }

    protected int getOpenExitAnimation() {
        return R.anim.chat_slide_out_left_parallax;
    }

    protected int getCloseEnterAnimation() {
        return R.anim.chat_slide_in_left_parallax;
    }

    protected int getCloseExitAnimation() {
        return R.anim.chat_slide_out_right;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.im.oldchat.util.AppDpiManager.applyCurrentDpi(this);
        ensurePreferredNightMode();
        applyPreferredThemeStyle();
        super.onCreate(savedInstanceState);
        com.im.oldchat.util.ActivityTaskCompat.register(this);
        if (!com.im.oldchat.util.UiPerformance.shouldReduceMotion(this)) {
            int enterAnim = getOpenEnterAnimation();
            int exitAnim = getOpenExitAnimation();
            overridePendingTransition(enterAnim, exitAnim);
        }
        ScreenPerfTracer.onActivityCreate(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        try {
            super.onSaveInstanceState(outState);
        } catch (Exception ignored) {
        }
        if (outState != null) {
            try {
                android.os.Parcel parcel = android.os.Parcel.obtain();
                outState.writeToParcel(parcel, 0);
                int size = parcel.dataSize();
                parcel.recycle();
                // 阈值设为 40KB，坚决避免跨进程 Binder 传输超过 512KB 触发 TransactionTooLargeException
                if (size > 40 * 1024) {
                    outState.clear();
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        com.im.oldchat.util.ActivityTaskCompat.unregister(this);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        if (!com.im.oldchat.util.UiPerformance.shouldReduceMotion(this)) {
            int enterAnim = getCloseEnterAnimation();
            int exitAnim = getCloseExitAnimation();
            if (enterAnim != 0 || exitAnim != 0) {
                overridePendingTransition(enterAnim, exitAnim);
            }
        }
    }

    @Override
    public void setContentView(int layoutResID) {
        super.setContentView(layoutResID);
        applySquareEnhancements();
        applyMinimalDarkBackground();
        if (enableSwipeBack()) {
            com.im.oldchat.ui.widget.SwipeBackLayout.attach(this);
        }
    }
    @Override
    public void setContentView(View view) {
        super.setContentView(view);
        applySquareEnhancements();
        applyMinimalDarkBackground();
        if (enableSwipeBack()) {
            com.im.oldchat.ui.widget.SwipeBackLayout.attach(this);
        }
    }
    @Override
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        super.setContentView(view, params);
        applySquareEnhancements();
        applyMinimalDarkBackground();
        if (enableSwipeBack()) {
            com.im.oldchat.ui.widget.SwipeBackLayout.attach(this);
        }
    }
    @Override
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        super.addContentView(view, params);
        applySquareEnhancements();
        applyMinimalDarkBackground();
    }
    /** 极简深主题：运行时把页面根背景置为近纯黑，避免依赖自定义 attr 的布局解析。 */
    private void applyMinimalDarkBackground() {
        try {
            if (ThemeColorUtil.isMinimalDark(this)) {
                View content = findViewById(android.R.id.content);
                if (content != null) {
                    content.setBackgroundColor(0xFF08090A);
                }
                View chatRoot = findViewById(R.id.chat_page_root);
                if (chatRoot != null) {
                    // 聊天消息区使用克制的深蓝；顶部工具栏仍保持中性黑灰。
                    chatRoot.setBackgroundColor(0xFF0B1B33);
                    View inputContainer = chatRoot.findViewById(R.id.input_container);
                    View inputArea = chatRoot.findViewById(R.id.input_area);
                    if (inputContainer != null) inputContainer.setBackgroundColor(0xFF0B1B33);
                    if (inputArea != null) inputArea.setBackgroundColor(0xFF0B1B33);
                }
            }
            // 强制工具栏/群聊头背景与当前亮暗模式一致（DayNight 资源解析在部分设备不可靠）。
            boolean dark = SettingsPrefs.isDarkModeEnabled(this) || ThemeColorUtil.isMinimalDark(this);
            int toolbarColor = dark ? 0xFF1F2937 : 0xFFFFFFFF;
            View toolbar = findViewById(R.id.chat_toolbar);
            if (toolbar != null) {
                toolbar.setBackgroundColor(toolbarColor);
            }
            View groupHeader = findViewById(R.id.group_header);
            if (groupHeader != null) {
                groupHeader.setBackgroundColor(toolbarColor);
            }
        } catch (Throwable ignored) {
        }
    }
    private void applySquareEnhancements() {
        // Android 2.3/4 和低内存设备优先保证输入与消息列表流畅：不加载自定义字体，
        // 不递归安装内容/列表动画和按压缩放，避免发送时触发全树遍历与额外图层。
        boolean reduced = com.im.oldchat.util.UiPerformance.shouldReduceMotion(this);
        if (!reduced) {
            applySquareTypography();
            applySquareEntranceAnimation();
            applySquareListAnimations();
            if (useSquarePressFeedback()) {
                SquarePressFeedback.install(findViewById(android.R.id.content));
            }
        }
        ScreenPerfTracer.onContentReady(this);
    }
    /** 子页面可关闭全局按压缩放反馈，例如需要稳定光标和布局的代码 IDE。 */
    protected boolean useSquarePressFeedback() {
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        ensurePreferredNightMode();
        ensureStartupPermissions();
        if (!(this instanceof com.im.oldchat.ui.WelcomeGuideActivity)) {
            com.im.oldchat.util.AppDpiManager.maybeShowFirstLaunchDialog(this);
        }
    }
    private void ensurePreferredNightMode() {
        if (CrashHandler.isCrashProcess()) {
            return;
        }
        if (!SettingsPrefs.isDarkModeSupported()) {
            SettingsPrefs.setDarkModeEnabled(this, false);
            if (SettingsPrefs.getThemeColorIndex(this) == ThemeColorUtil.THEME_MINIMAL_DARK) {
                SettingsPrefs.setThemeColorIndex(this, ThemeColorUtil.THEME_CLASSIC);
            }
        }
        boolean darkModeEnabled = SettingsPrefs.isDarkModeEnabled(this);
        if (ThemeColorUtil.isMinimalDark(this)) {
            darkModeEnabled = true;
        }
        int preferredMode = darkModeEnabled
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO;
        if (AppCompatDelegate.getDefaultNightMode() != preferredMode) {
            AppCompatDelegate.setDefaultNightMode(preferredMode);
        }
        try {
            getDelegate().setLocalNightMode(preferredMode);
            int currentMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            boolean darkNow = currentMode == Configuration.UI_MODE_NIGHT_YES;
            if (darkNow != darkModeEnabled) {
                getDelegate().applyDayNight();
            }
        } catch (Throwable ignored) {
        }
    }
    private void applyPreferredThemeStyle() {
        if (CrashHandler.isCrashProcess()) {
            return;
        }
        try {
            setTheme(ThemeStyleResolver.resolve(this));
        } catch (Throwable ignored) {
        }
    }
    private void ensureStartupPermissions() {
        if (startupPermissionRequested) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            startupPermissionRequested = true;
            return;
        }
        if (CrashHandler.isCrashProcess()) {
            startupPermissionRequested = true;
            return;
        }
        List<String> missing = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33) {
            addIfMissing(missing, Manifest.permission.READ_MEDIA_IMAGES);
            addIfMissing(missing, Manifest.permission.READ_MEDIA_VIDEO);
            addIfMissing(missing, Manifest.permission.READ_MEDIA_AUDIO);
            addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        } else {
            addIfMissing(missing, Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (missing.isEmpty()) {
            startupPermissionRequested = true;
            return;
        }
        if (startupPermissionRequesting) {
            return;
        }
        startupPermissionRequesting = true;
        ActivityCompat.requestPermissions(this, missing.toArray(new String[missing.size()]), REQ_STARTUP_PERMISSIONS);
    }
    private void addIfMissing(List<String> missing, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }
    private void applySquareTypography() {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        applySquareTypefaceRecursive(root);
    }
    private void applySquareEntranceAnimation() {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        try {
            Animation animation = AnimationUtils.loadAnimation(this, R.anim.square_content_enter);
            root.clearAnimation();
            root.startAnimation(animation);
        } catch (Throwable ignored) {
        }
    }
    private void applySquareListAnimations() {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        LayoutAnimationController controller = null;
        try {
            controller = AnimationUtils.loadLayoutAnimation(this, R.anim.square_list_layout);
        } catch (Throwable ignored) {
            controller = null;
        }
        if (controller == null) {
            return;
        }
        applySquareListAnimationsRecursive(root, controller);
    }
    private void applySquareListAnimationsRecursive(View view, LayoutAnimationController controller) {
        if (view instanceof AbsListView) {
            AbsListView listView = (AbsListView) view;
            if (!shouldSkipListAnimation(listView)) {
                listView.setLayoutAnimation(controller);
            }
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            applySquareListAnimationsRecursive(group.getChildAt(i), controller);
        }
    }
    private boolean shouldSkipListAnimation(View view) {
        if (view == null) {
            return true;
        }
        int id = view.getId();
        return id == R.id.lvMessages;
    }
    private void applySquareTypefaceRecursive(View view) {
        if (view instanceof TextView && !(view instanceof FontAwesomeTextView)) {
            TextView textView = (TextView) view;
            Typeface typeface = shouldUseMonoTypeface(textView)
                    ? getMonoTypeface()
                    : getSansTypeface();
            if (typeface != null) {
                Typeface current = textView.getTypeface();
                int style = current != null ? current.getStyle() : Typeface.NORMAL;
                textView.setTypeface(typeface, style);
            }
        }
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            applySquareTypefaceRecursive(group.getChildAt(i));
        }
    }
    private boolean shouldUseMonoTypeface(TextView textView) {
        Object tag = textView.getTag();
        return tag != null && FONT_TAG_MONO.equals(String.valueOf(tag));
    }
    private Typeface getSansTypeface() {
        ensureTypefaceLoaded();
        return sansTypeface != null ? sansTypeface : Typeface.SANS_SERIF;
    }
    private Typeface getMonoTypeface() {
        ensureTypefaceLoaded();
        return monoTypeface != null ? monoTypeface : Typeface.MONOSPACE;
    }
    private void ensureTypefaceLoaded() {
        if (typefaceLoadAttempted) {
            return;
        }
        typefaceLoadAttempted = true;
        try {
            sansTypeface = Typeface.createFromAsset(getAssets(), FONT_SANS_ASSET);
        } catch (Throwable ignored) {
            sansTypeface = null;
        }
        try {
            monoTypeface = Typeface.createFromAsset(getAssets(), FONT_MONO_ASSET);
        } catch (Throwable ignored) {
            monoTypeface = null;
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STARTUP_PERMISSIONS) {
            return;
        }
        startupPermissionRequesting = false;
        startupPermissionRequested = true;
        if (grantResults == null || grantResults.length == 0) {
            return;
        }
        boolean deniedStorage = false;
        boolean deniedNotification = false;
        boolean dontAskAgain = false;
        for (int i = 0; i < grantResults.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                continue;
            }
            String permission = permissions != null && i < permissions.length ? permissions[i] : "";
            if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
                deniedNotification = true;
            } else {
                deniedStorage = true;
            }
            if (permission != null && permission.length() > 0
                    && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                dontAskAgain = true;
            }
        }
        if (deniedStorage || deniedNotification) {
            String msg;
            if (deniedStorage && deniedNotification) {
                msg = "\u901A\u77E5\u548C\u5A92\u4F53\u6743\u9650\u672A\u6388\u4E88\uFF0C\u90E8\u5206\u529F\u80FD\u53EF\u80FD\u53D7\u9650";
            } else if (deniedNotification) {
                msg = "\u901A\u77E5\u6743\u9650\u672A\u6388\u4E88\uFF0C\u53EF\u80FD\u6536\u4E0D\u5230\u6D88\u606F\u63D0\u9192";
            } else {
                msg = "\u5A92\u4F53\u8BFB\u53D6\u6743\u9650\u672A\u6388\u4E88\uFF0C\u56FE\u7247/\u89C6\u9891/\u97F3\u9891\u529F\u80FD\u53D7\u9650";
            }
            if (dontAskAgain) {
                msg = msg + "\uFF0C\u53EF\u5728\u7CFB\u7EDF\u8BBE\u7F6E\u4E2D\u624B\u52A8\u5F00\u542F";
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }
}
