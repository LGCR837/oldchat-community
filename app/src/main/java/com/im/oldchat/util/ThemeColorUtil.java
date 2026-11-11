package com.im.oldchat.util;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.util.TypedValue;
import android.view.View;

import com.im.oldchat.R;

import java.util.HashMap;
import java.util.Map;

public final class ThemeColorUtil {
    public static final int THEME_CLASSIC = -1;

    public static final int THEME_MINIMAL_DARK = 6;

    private static final int[] PRIMARY_COLORS = new int[]{
            0xFF16A34A, // green
            0xFF2563EB, // blue
            0xFFEA580C, // orange
            0xFFDC2626, // red
            0xFF0F766E, // teal
            0xFF7C5A3A, // brown
            0xFF9CA3AF  // minimal dark (neutral gray)
    };
    private static final int[] PRIMARY_DARK_COLORS = new int[]{
            0xFF15803D, // green dark
            0xFF1D4ED8, // blue dark
            0xFFC2410C, // orange dark
            0xFFB91C1C, // red dark
            0xFF115E59, // teal dark
            0xFF5B422A, // brown dark
            0xFF08090A  // minimal dark background
    };
    private static final int[] LABEL_RES_IDS = new int[]{
            R.string.theme_color_green,
            R.string.theme_color_blue,
            R.string.theme_color_orange,
            R.string.theme_color_red,
            R.string.theme_color_teal,
            R.string.theme_color_brown,
            R.string.theme_color_dark
    };
    private static final int[] THEME_STYLE_RES_IDS = new int[]{
            R.style.AppTheme_Green,
            R.style.AppTheme_Blue,
            R.style.AppTheme_Orange,
            R.style.AppTheme_Red,
            R.style.AppTheme_Teal,
            R.style.AppTheme_Brown,
            R.style.AppTheme_Dark
    };
    private static final String CLASSIC_LABEL = "\u7ECF\u5178\u9ED1\u767D";
    private static final Object CACHE_LOCK = new Object();
    private static final int BUBBLE_CACHE_LIMIT = 24;
    private static final int COLOR_CACHE_LIMIT = 12;
    private static final Map<String, Drawable.ConstantState> BUBBLE_CACHE =
            new HashMap<String, Drawable.ConstantState>();
    private static final Map<String, Integer> COLOR_CACHE = new HashMap<String, Integer>();

    private ThemeColorUtil() {
    }

    public static int normalizeThemeIndex(int index) {
        if (index >= 0 && index < PRIMARY_COLORS.length) {
            return index;
        }
        return THEME_CLASSIC;
    }

    public static boolean hasCustomTheme(Context context) {
        if (context == null) {
            return false;
        }
        return normalizeThemeIndex(SettingsPrefs.getThemeColorIndex(context)) != THEME_CLASSIC;
    }

    public static boolean isMinimalDark(Context context) {
        return context != null
                && normalizeThemeIndex(SettingsPrefs.getThemeColorIndex(context)) == THEME_MINIMAL_DARK;
    }

    /** 普通深色模式或极简深色主题都应使用深色页面配色。 */
    public static boolean isDarkAppearance(Context context) {
        return context != null && (SettingsPrefs.isDarkModeEnabled(context) || isMinimalDark(context));
    }

    public static int getPrimaryColor(Context context) {
        if (context == null) {
            return 0xFF111111;
        }
        int index = normalizeThemeIndex(SettingsPrefs.getThemeColorIndex(context));
        String cacheKey = "primary#" + index;
        Integer cached = getCachedColor(cacheKey);
        if (cached != null) {
            return cached.intValue();
        }
        int resolved;
        if (index == THEME_CLASSIC) {
            resolved = context.getResources().getColor(R.color.colorPrimary);
        } else {
            resolved = resolveThemeAttrColor(context,
                    android.support.v7.appcompat.R.attr.colorPrimary,
                    PRIMARY_COLORS[index]);
        }
        putCachedColor(cacheKey, resolved);
        return resolved;
    }

    public static int getPrimaryDarkColor(Context context) {
        if (context == null) {
            return 0xFF000000;
        }
        int index = normalizeThemeIndex(SettingsPrefs.getThemeColorIndex(context));
        String cacheKey = "primary_dark#" + index;
        Integer cached = getCachedColor(cacheKey);
        if (cached != null) {
            return cached.intValue();
        }
        int resolved;
        if (index == THEME_CLASSIC) {
            resolved = context.getResources().getColor(R.color.colorPrimaryDark);
        } else {
            resolved = resolveThemeAttrColor(context,
                    android.support.v7.appcompat.R.attr.colorPrimaryDark,
                    PRIMARY_DARK_COLORS[index]);
        }
        putCachedColor(cacheKey, resolved);
        return resolved;
    }

    public static CharSequence[] buildThemeColorLabels(Context context) {
        CharSequence[] labels = new CharSequence[LABEL_RES_IDS.length + 1];
        labels[0] = CLASSIC_LABEL;
        for (int i = 0; i < LABEL_RES_IDS.length; i++) {
            labels[i + 1] = context.getString(LABEL_RES_IDS[i]);
        }
        return labels;
    }

    public static String getThemeColorLabel(Context context) {
        return getThemeColorLabel(context, SettingsPrefs.getThemeColorIndex(context));
    }

    public static String getThemeColorLabel(Context context, int storedIndex) {
        int index = normalizeThemeIndex(storedIndex);
        if (index == THEME_CLASSIC) {
            return CLASSIC_LABEL;
        }
        if (context == null) {
            return CLASSIC_LABEL;
        }
        return context.getString(LABEL_RES_IDS[index]);
    }

    public static boolean applyPrimaryBubbleBackground(Context context, View bubble, int drawableRes) {
        if (context == null || bubble == null) {
            return false;
        }
        int themeIndex = normalizeThemeIndex(SettingsPrefs.getThemeColorIndex(context));
        boolean mine = drawableRes == R.drawable.bg_msg_me
                || drawableRes == R.drawable.bg_msg_music_me;
        boolean other = drawableRes == R.drawable.bg_msg_other
                || drawableRes == R.drawable.bg_msg_music_other;
        boolean burnMine = drawableRes == R.drawable.bg_msg_burn_me;
        boolean burnOther = drawableRes == R.drawable.bg_msg_burn_other;
        // 阅后即焚气泡：跟随圆角设置（头像侧直角 + 其余 14dp 圆角），
        // 直接复用 XML 圆角资源（与普通气泡 round 系列同构，编译期校验不会越界）。
        if (burnMine || burnOther) {
            boolean rounded = SettingsPrefs.isBubbleRoundedEnabled(context);
            if (rounded) {
                bubble.setBackgroundResource(burnMine
                        ? R.drawable.bg_msg_burn_me_round
                        : R.drawable.bg_msg_burn_other_round);
                return true;
            }
        }
        // 普通文字/音乐气泡始终由“聊天气泡形状”单独决定，不能再借用
        // buttonCornerRadius 所在的 Activity 主题，否则按钮直角会错误地把气泡也变直角。
        if (mine || other) {
            boolean rounded = SettingsPrefs.isBubbleRoundedEnabled(context);
            int normalColor;
            int pressedColor;
            if (themeIndex == THEME_MINIMAL_DARK) {
                normalColor = mine ? 0xFF4A4D52 : 0xFF0B0C0D;
                pressedColor = mine ? 0xFF5A5D62 : 0xFF17191B;
            } else if (mine && themeIndex != THEME_CLASSIC) {
                normalColor = PRIMARY_COLORS[themeIndex];
                pressedColor = PRIMARY_DARK_COLORS[themeIndex];
            } else if (mine && themeIndex == THEME_CLASSIC && isNightConfiguration(context)) {
                // 保持暗色经典主题原有深蓝气泡，白色正文才能有足够对比度。
                normalColor = 0xFF3256AA;
                pressedColor = 0xFF3B63BF;
            } else {
                normalColor = context.getResources().getColor(
                        mine ? R.color.colorBubbleOut : R.color.colorBubbleIn);
                pressedColor = adjustColor(normalColor, mine ? 0.86f : 0.94f);
            }
            bubble.setBackgroundDrawable(buildIndependentBubbleDrawable(context,
                    normalColor, pressedColor, rounded, mine));
            return true;
        }
        if (themeIndex == THEME_MINIMAL_DARK && (mine || other)) {
            boolean rounded = SettingsPrefs.isBubbleRoundedEnabled(context);
            if (rounded) {
                bubble.setBackgroundResource(mine
                        ? R.drawable.bg_msg_me_round_dark
                        : R.drawable.bg_msg_other_round_dark);
                return true;
            }
            int normalColor = mine ? 0xFF4A4D52 : 0xFF0B0C0D;
            int pressedColor = mine ? 0xFF5A5D62 : 0xFF17191B;
            bubble.setBackgroundDrawable(obtainBubbleDrawable(context,
                    buildBubbleCacheKey(context, drawableRes, themeIndex, rounded),
                    drawableRes, normalColor, pressedColor));
            return true;
        }
        if (mine && themeIndex != THEME_CLASSIC) {
            boolean rounded = SettingsPrefs.isBubbleRoundedEnabled(context);
            if (rounded) {
                bubble.setBackgroundResource(resolveRoundedThemeBubbleRes(themeIndex));
                return true;
            }
            bubble.setBackgroundDrawable(obtainBubbleDrawable(context,
                    buildBubbleCacheKey(context, drawableRes, themeIndex, false),
                    drawableRes, PRIMARY_COLORS[themeIndex], PRIMARY_DARK_COLORS[themeIndex]));
            return true;
        }
        return false;
    }

    /** 真实界面预览专用：使用与聊天适配器相同的颜色和气泡构造逻辑，但不写设置。 */
    public static void applyPreviewBubbleBackground(Context context, View bubble,
                                                    boolean rounded, boolean mine) {
        if (context == null || bubble == null) return;
        int themeIndex = normalizeThemeIndex(SettingsPrefs.getThemeColorIndex(context));
        int normalColor;
        int pressedColor;
        if (themeIndex == THEME_MINIMAL_DARK) {
            normalColor = mine ? 0xFF4A4D52 : 0xFF0B0C0D;
            pressedColor = mine ? 0xFF5A5D62 : 0xFF17191B;
        } else if (mine && themeIndex != THEME_CLASSIC) {
            normalColor = PRIMARY_COLORS[themeIndex];
            pressedColor = PRIMARY_DARK_COLORS[themeIndex];
        } else if (mine && themeIndex == THEME_CLASSIC && isNightConfiguration(context)) {
            normalColor = 0xFF3256AA;
            pressedColor = 0xFF3B63BF;
        } else {
            normalColor = context.getResources().getColor(
                    mine ? R.color.colorBubbleOut : R.color.colorBubbleIn);
            pressedColor = adjustColor(normalColor, mine ? 0.86f : 0.94f);
        }
        bubble.setBackgroundDrawable(buildIndependentBubbleDrawable(context,
                normalColor, pressedColor, rounded, mine));
    }

    private static int resolveRoundedThemeBubbleRes(int themeIndex) {
        switch (themeIndex) {
            case 0:
                return R.drawable.bg_msg_me_round_green;
            case 1:
                return R.drawable.bg_msg_me_round_blue;
            case 2:
                return R.drawable.bg_msg_me_round_orange;
            case 3:
                return R.drawable.bg_msg_me_round_red;
            case 4:
                return R.drawable.bg_msg_me_round_teal;
            case 5:
                return R.drawable.bg_msg_me_round_brown;
            case THEME_MINIMAL_DARK:
                return R.drawable.bg_msg_me_round_dark;
            default:
                return R.drawable.bg_msg_me;
        }
    }

    private static Drawable buildIndependentBubbleDrawable(Context context,
                                                            int normalColor,
                                                            int pressedColor,
                                                            boolean rounded,
                                                            boolean mine) {
        StateListDrawable selector = new StateListDrawable();
        selector.addState(new int[]{android.R.attr.state_pressed},
                buildIndependentBubbleShape(context, pressedColor, rounded, mine));
        selector.addState(new int[]{},
                buildIndependentBubbleShape(context, normalColor, rounded, mine));
        return selector;
    }

    private static GradientDrawable buildIndependentBubbleShape(Context context,
                                                                 int color,
                                                                 boolean rounded,
                                                                 boolean mine) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(color);
        if (!rounded) {
            shape.setCornerRadius(0f);
            return shape;
        }
        float density = context.getResources().getDisplayMetrics().density;
        float dr = 16f * density;
        float mr = 0f;
        float topLeft = mine ? dr : mr;
        float topRight = mine ? mr : dr;
        float bottomRight = dr;
        float bottomLeft = dr;
        shape.setCornerRadii(new float[]{
                topLeft, topLeft,
                topRight, topRight,
                bottomRight, bottomRight,
                bottomLeft, bottomLeft
        });
        return shape;
    }

    private static Drawable obtainBubbleDrawable(Context context, String key,
                                                 int drawableRes, int normalColor, int pressedColor) {
        Drawable.ConstantState constantState = getCachedBubble(key);
        if (constantState != null) {
            return constantState.newDrawable(context.getResources());
        }
        Drawable drawable = context.getResources().getDrawable(drawableRes);
        if (drawable == null) {
            return null;
        }
        drawable = DrawableCompat.wrap(drawable.mutate());
        DrawableCompat.setTintList(drawable, new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_pressed},
                        new int[]{}
                },
                new int[]{pressedColor, normalColor}));
        Drawable.ConstantState builtState = drawable == null ? null : drawable.getConstantState();
        if (builtState != null) {
            putCachedBubble(key, builtState);
            return builtState.newDrawable(context.getResources());
        }
        return drawable;
    }

    private static String buildBubbleCacheKey(Context context, int drawableRes,
                                              int themeIndex, boolean rounded) {
        int densityDpi = context == null || context.getResources() == null
                ? 0 : context.getResources().getDisplayMetrics().densityDpi;
        return drawableRes + "#" + themeIndex + "#" + (rounded ? 1 : 0) + "#" + densityDpi;
    }

    private static Drawable.ConstantState getCachedBubble(String key) {
        synchronized (CACHE_LOCK) {
            return BUBBLE_CACHE.get(key);
        }
    }

    private static void putCachedBubble(String key, Drawable.ConstantState state) {
        if (key == null || state == null) {
            return;
        }
        synchronized (CACHE_LOCK) {
            if (BUBBLE_CACHE.size() >= BUBBLE_CACHE_LIMIT) {
                BUBBLE_CACHE.clear();
            }
            BUBBLE_CACHE.put(key, state);
        }
    }

    private static Integer getCachedColor(String key) {
        synchronized (CACHE_LOCK) {
            return COLOR_CACHE.get(key);
        }
    }

    private static void putCachedColor(String key, int value) {
        synchronized (CACHE_LOCK) {
            if (COLOR_CACHE.size() >= COLOR_CACHE_LIMIT) {
                COLOR_CACHE.clear();
            }
            COLOR_CACHE.put(key, Integer.valueOf(value));
        }
    }

    public static int getThemeStyleRes(Context context) {
        if (context == null) {
            return R.style.AppTheme;
        }
        return getThemeStyleRes(SettingsPrefs.getThemeColorIndex(context));
    }

    public static int getThemeStyleRes(int storedIndex) {
        int index = normalizeThemeIndex(storedIndex);
        if (index == THEME_CLASSIC) {
            return R.style.AppTheme;
        }
        return THEME_STYLE_RES_IDS[index];
    }

    private static int resolveThemeAttrColor(Context context, int attrResId, int fallback) {
        if (context == null) {
            return fallback;
        }
        TypedValue value = new TypedValue();
        if (context.getTheme() != null && context.getTheme().resolveAttribute(attrResId, value, true)) {
            if (value.resourceId != 0) {
                try {
                    return context.getResources().getColor(value.resourceId);
                } catch (Throwable ignored) {
                }
            }
            return value.data;
        }
        return fallback;
    }

    private static boolean isNightConfiguration(Context context) {
        if (context == null || context.getResources() == null
                || context.getResources().getConfiguration() == null) {
            return false;
        }
        int mode = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private static int adjustColor(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = clamp((int) (((color >> 16) & 0xFF) * factor));
        int g = clamp((int) (((color >> 8) & 0xFF) * factor));
        int b = clamp((int) ((color & 0xFF) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return value;
    }

}
