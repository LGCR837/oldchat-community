package com.im.oldchat.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.support.v7.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 机器人 form 按钮的动态表单弹窗，兼容 Android 2.3+。 */
final class BotFormDialog {
    interface SubmitCallback {
        void onSubmit(String formData);
    }

    private static final class FieldView {
        String name;
        String type;
        boolean required;
        View input;
    }

    private BotFormDialog() { }

    static void show(final Context context, String definition, final SubmitCallback callback) {
        if (context == null) return;
        try {
            JSONObject form = new JSONObject(definition == null ? "{}" : definition);
            final List<FieldView> fields = new ArrayList<FieldView>();
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(context, 18);
            content.setPadding(pad, dp(context, 8), pad, dp(context, 8));
            JSONArray array = form.optJSONArray("fields");
            if (array == null || array.length() == 0) {
                Toast.makeText(context, "表单没有可填写项目", Toast.LENGTH_SHORT).show();
                return;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject spec = array.optJSONObject(i);
                if (spec == null) continue;
                String name = spec.optString("name", "field_" + i);
                String label = spec.optString("label", name);
                String type = spec.optString("type", "text").toLowerCase();
                boolean required = spec.optBoolean("required", false);
                TextView title = new TextView(context);
                title.setText(label + (required ? " *" : ""));
                title.setTextSize(13f);
                title.setTextColor(0xFF374151);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                titleLp.topMargin = dp(context, 10);
                content.addView(title, titleLp);

                FieldView field = new FieldView();
                field.name = name;
                field.type = type;
                field.required = required;
                if ("select".equals(type)) {
                    Spinner spinner = new Spinner(context);
                    JSONArray options = spec.optJSONArray("options");
                    ArrayList<String> values = new ArrayList<String>();
                    if (options != null) {
                        for (int j = 0; j < options.length(); j++) values.add(options.optString(j, ""));
                    }
                    spinner.setAdapter(new ArrayAdapter<String>(context,
                            android.R.layout.simple_spinner_dropdown_item, values));
                    field.input = spinner;
                } else if ("boolean".equals(type) || "checkbox".equals(type)) {
                    CheckBox check = new CheckBox(context);
                    check.setText(spec.optString("hint", "是"));
                    field.input = check;
                } else {
                    EditText edit = new EditText(context);
                    edit.setHint(spec.optString("hint", ""));
                    edit.setSingleLine(!"textarea".equals(type));
                    if ("textarea".equals(type)) edit.setMinLines(3);
                    if ("number".equals(type)) edit.setInputType(InputType.TYPE_CLASS_NUMBER);
                    else edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
                    field.input = edit;
                }
                content.addView(field.input, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                fields.add(field);
            }
            ScrollView scroll = new ScrollView(context);
            scroll.addView(content);
            final String submitText = form.optString("submit_text", "提交");
            new AlertDialog.Builder(context)
                    .setTitle(form.optString("title", "填写表单"))
                    .setView(scroll)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(submitText, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface dialog, int which) {
                            JSONObject values = collect(context, fields);
                            if (values != null && callback != null) callback.onSubmit(values.toString());
                        }
                    }).show();
        } catch (Exception e) {
            Toast.makeText(context, "表单格式错误", Toast.LENGTH_SHORT).show();
        }
    }

    private static JSONObject collect(Context context, List<FieldView> fields) {
        JSONObject result = new JSONObject();
        try {
            for (int i = 0; i < fields.size(); i++) {
                FieldView field = fields.get(i);
                Object value;
                if (field.input instanceof Spinner) {
                    value = ((Spinner) field.input).getSelectedItem();
                    if (value == null) value = "";
                } else if (field.input instanceof CheckBox) {
                    value = Boolean.valueOf(((CheckBox) field.input).isChecked());
                } else {
                    value = ((EditText) field.input).getText().toString().trim();
                }
                if (field.required && String.valueOf(value).length() == 0) {
                    Toast.makeText(context, "请填写必填项", Toast.LENGTH_SHORT).show();
                    return null;
                }
                result.put(field.name, value);
            }
            return result;
        } catch (Exception e) {
            Toast.makeText(context, "表单提交失败", Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
