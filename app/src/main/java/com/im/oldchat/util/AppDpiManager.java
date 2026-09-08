package com.im.oldchat.util;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.support.v7.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.im.oldchat.R;

public final class AppDpiManager {
    private static final int MIN_PERCENT = 30;
    private static final int MAX_PERCENT = 160;
    private static boolean firstPromptShowing;

    private AppDpiManager() {
    }

    public static void applyCurrentDpi(Context context) {
        if (context == null) {
            return;
        }
        Resources resources = context.getResources();
        if (resources == null) {
            return;
        }
        AppDpiScaleApplier.apply(resources, SettingsPrefs.getAppDpiScale(context));
    }

    /** 预览专用：临时应用比例但不写入设置，预览页退出时调用 applyCurrentDpi 恢复。 */
    public static void applyPreviewDpi(Context context, int percent) {
        if (context == null || context.getResources() == null) return;
        int safe = Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
        AppDpiScaleApplier.apply(context.getResources(), safe / 100f);
    }

    public static void maybeShowFirstLaunchDialog(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (firstPromptShowing || SettingsPrefs.isAppDpiFirstPromptShown(activity)) {
            return;
        }
        View root = activity.findViewById(android.R.id.content);
        if (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() == 0) {
            return;
        }
        firstPromptShowing = true;
        showPickerDialog(activity, true, null);
    }

    public static void bindSettingRow(final Activity activity, View row, final TextView valueView) {
        if (activity == null) {
            return;
        }
        updateValueLabel(activity, valueView);
        if (row == null) {
            return;
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPickerDialog(activity, false, valueView);
            }
        });
    }

    private static void showPickerDialog(final Activity activity, final boolean fromFirstLaunch, final TextView valueView) {
        if (activity == null || activity.isFinishing()) {
            finishFirstPromptIfNeeded(fromFirstLaunch);
            return;
        }
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_app_dpi_scale, null);
        final SeekBar seekBar = (SeekBar) content.findViewById(R.id.seekDpiScale);
        final EditText input = (EditText) content.findViewById(R.id.etDpiScalePercent);
        final TextView titleValue = (TextView) content.findViewById(R.id.tvDpiScalePercentValue);
        if (seekBar == null || input == null || titleValue == null) {
            finishFirstPromptIfNeeded(fromFirstLaunch);
            return;
        }

        final boolean[] syncing = new boolean[]{false};
        int currentPercent = clampPercent(SettingsPrefs.getAppDpiScalePercent(activity));
        seekBar.setMax(MAX_PERCENT - MIN_PERCENT);
        seekBar.setProgress(currentPercent - MIN_PERCENT);
        setInputText(input, currentPercent);
        titleValue.setText(formatPercent(activity, currentPercent));
        setViewVisibility(content, R.id.layoutStartupThemeSection,
                fromFirstLaunch ? View.VISIBLE : View.GONE);
        setViewVisibility(content, R.id.layoutStartupShapeSection,
                fromFirstLaunch ? View.VISIBLE : View.GONE);
        StartupAppearanceOptionHelper.bind(activity, content);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = clampPercent(MIN_PERCENT + progress);
                if (!syncing[0]) {
                    syncing[0] = true;
                    setInputText(input, percent);
                    syncing[0] = false;
                }
                titleValue.setText(formatPercent(activity, percent));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (syncing[0]) {
                    return;
                }
                int percent = parsePercentOrDefault(s, MIN_PERCENT);
                percent = clampPercent(percent);
                syncing[0] = true;
                seekBar.setProgress(percent - MIN_PERCENT);
                syncing[0] = false;
                titleValue.setText(formatPercent(activity, percent));
            }
        });

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(fromFirstLaunch ? R.string.settings_dpi_first_title : R.string.settings_dpi_title);
        if (fromFirstLaunch) {
            builder.setMessage(R.string.settings_dpi_first_message);
        }
        builder.setView(content);
        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int percent = clampPercent(parsePercentOrDefault(input.getText(), SettingsPrefs.getAppDpiScalePercent(activity)));
                if (fromFirstLaunch) {
                    StartupAppearanceOptionHelper.applySelection(activity, content);
                }
                applySelection(activity, percent, fromFirstLaunch, valueView);
            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (fromFirstLaunch) {
                    applyFirstLaunchDefaults(activity);
                }
            }
        });
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                if (fromFirstLaunch) {
                    applyFirstLaunchDefaults(activity);
                }
            }
        });
        builder.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                finishFirstPromptIfNeeded(fromFirstLaunch);
            }
        });
        builder.show();
    }

    private static void applySelection(Activity activity, int percent, boolean fromFirstLaunch, TextView valueView) {
        if (activity == null) {
            finishFirstPromptIfNeeded(fromFirstLaunch);
            return;
        }
        SettingsPrefs.setAppDpiScalePercent(activity, percent);
        if (fromFirstLaunch) {
            SettingsPrefs.setAppDpiFirstPromptShown(activity, true);
        }
        applyCurrentDpi(activity);
        updateValueLabel(activity, valueView);
        Toast.makeText(activity, R.string.settings_dpi_apply_tip, Toast.LENGTH_SHORT).show();
        restartActivity(activity);
    }

    private static void updateValueLabel(Context context, TextView valueView) {
        if (context == null || valueView == null) {
            return;
        }
        int percent = clampPercent(SettingsPrefs.getAppDpiScalePercent(context));
        valueView.setText(formatPercent(context, percent));
    }

    private static String formatPercent(Context context, int percent) {
        return context.getString(R.string.settings_dpi_percent_format, Integer.valueOf(percent));
    }

    private static void restartActivity(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        Intent intent = activity.getIntent();
        if (intent == null) {
            activity.finish();
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.finish();
        activity.startActivity(intent);
    }

    private static int clampPercent(int percent) {
        if (percent < MIN_PERCENT) {
            return MIN_PERCENT;
        }
        if (percent > MAX_PERCENT) {
            return MAX_PERCENT;
        }
        return percent;
    }

    private static int parsePercentOrDefault(CharSequence value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        if (text.length() == 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void setInputText(EditText input, int percent) {
        if (input == null) {
            return;
        }
        String text = String.valueOf(percent);
        input.setText(text);
        input.setSelection(text.length());
    }

    private static void setViewVisibility(View root, int id, int visibility) {
        if (root == null) {
            return;
        }
        View view = root.findViewById(id);
        if (view != null) {
            view.setVisibility(visibility);
        }
    }

    private static void finishFirstPromptIfNeeded(boolean fromFirstLaunch) {
        if (!fromFirstLaunch) {
            return;
        }
        firstPromptShowing = false;
    }

    private static void applyFirstLaunchDefaults(Activity activity) {
        if (activity == null) {
            return;
        }
        StartupAppearanceOptionHelper.applyDefaultsIfMissing(activity);
        SettingsPrefs.setAppDpiFirstPromptShown(activity, true);
    }
}
