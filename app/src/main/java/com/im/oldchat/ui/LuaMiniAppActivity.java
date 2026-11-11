package com.im.oldchat.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.inputmethod.EditorInfo;

import com.im.oldchat.BaseActivity;
import com.im.oldchat.R;
import com.im.oldchat.api.HttpUtil;
import com.im.oldchat.lua.CipJsonEncoder;
import com.im.oldchat.lua.LuaAppStore;
import com.im.oldchat.lua.LuaExternalHttp;
import com.im.oldchat.lua.LuaMiniApp;
import com.im.oldchat.util.ImageLoader;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.BaseLib;
import org.luaj.vm2.lib.Bit32Lib;
import org.luaj.vm2.lib.CoroutineLib;
import org.luaj.vm2.lib.MathLib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.LoadState;

public class LuaMiniAppActivity extends BaseActivity {
    public static final String EXTRA_APP_ID = "lua_app_id";
    private static final int REQ_CAMERA_PERMISSION = 7301;
    private static final int REQ_CAMERA_CAPTURE = 7302;

    private LuaMiniApp miniApp;
    private LinearLayout container;
    private ProgressBar loading;
    private TextView title;
    private Globals globals;
    private LuaFunction cameraCallback;
    private volatile boolean destroyed;
    private Uri cameraOutputUri;
    private final java.util.Map<String, View> dynamicViews = new java.util.HashMap<String, View>();

    @Override protected void onCreate(Bundle state) {
        destroyed = false;
        super.onCreate(state);
        setContentView(R.layout.activity_lua_mini_app);
        container = findViewByIdCompat(R.id.luaPageContainer);
        loading = findViewByIdCompat(R.id.pbLuaLoading);
        title = findViewByIdCompat(R.id.tvLuaTitle);
        View back = findViewByIdCompat(R.id.btnLuaBack);
        if (back != null) back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        String id = getIntent().getStringExtra(EXTRA_APP_ID);
        miniApp = LuaAppStore.find(this, id);
        String script = LuaAppStore.readScript(this, id);
        if (miniApp == null || script == null) {
            showFatal("小程序尚未下载，请返回发现页重试");
            return;
        }
        title.setText(miniApp.name);
        executeScript(script);
    }

    private void executeScript(final String script) {
        loading.setVisibility(View.VISIBLE);
        new AsyncTask<Void, Void, EngineResult>() {
            @Override protected EngineResult doInBackground(Void... values) {
                try {
                    Globals g = createSandbox();
                    LuaValue page = g.load(script, "@" + miniApp.id).call();
                    if (!page.istable()) throw new IllegalArgumentException("脚本必须返回页面 table");
                    return new EngineResult(g, page, null);
                } catch (Throwable e) {
                    return new EngineResult(null, null, e.getMessage());
                }
            }
            @Override protected void onPostExecute(EngineResult result) {
                loading.setVisibility(View.GONE);
                if (isFinishing()) return;
                if (result.error != null) { showFatal("Lua 运行失败：" + result.error); return; }
                globals = result.globals;
                renderPage(result.page);
            }
        }.execute();
    }

    private Globals createSandbox() {
        final Globals g = new Globals();
        g.load(new BaseLib());
        // LuaJ 的标准库注册过程依赖 package.loaded；全部加载后立即从脚本环境移除。
        g.load(new PackageLib());
        g.load(new Bit32Lib());
        g.load(new CoroutineLib());
        g.load(new TableLib());
        g.load(new StringLib());
        g.load(new MathLib());
        LoadState.install(g);
        LuaC.install(g);
        g.set("dofile", LuaValue.NIL);
        g.set("loadfile", LuaValue.NIL);
        g.set("require", LuaValue.NIL);
        g.set("package", LuaValue.NIL);
        g.set("io", LuaValue.NIL);
        g.set("os", LuaValue.NIL);
        g.set("debug", LuaValue.NIL);
        g.set("luajava", LuaValue.NIL);
        installUiConstructors(g);
        installAppBridge(g);
        return g;
    }

    private void installUiConstructors(Globals g) {
        LuaTable ui = new LuaTable();
        String[] types = {"page", "text", "button", "image", "input", "checkbox",
                "list", "column", "row", "scroll", "card", "divider", "progress", "spacer"};
        for (int i = 0; i < types.length; i++) {
            final String type = types[i];
            ui.set(type, new OneArgFunction() {
                @Override public LuaValue call(LuaValue arg) {
                    LuaTable table = arg.istable() ? arg.checktable() : new LuaTable();
                    table.set("type", type);
                    return table;
                }
            });
        }
        g.set("ui", ui);
    }

    private void installAppBridge(Globals g) {
        LuaTable app = new LuaTable();
        app.set("asset", new OneArgFunction() {
            @Override public LuaValue call(LuaValue pathValue) {
                String relative = pathValue.optjstring("").replace('\\', '/');
                if (relative.startsWith("/") || relative.contains("..") || relative.contains(":") || relative.length() == 0)
                    return LuaValue.NIL;
                java.io.File local = new java.io.File(com.im.oldchat.lua.LuaAppStore.localAssetDir(
                        LuaMiniAppActivity.this, miniApp.id), relative);
                try {
                    String base = com.im.oldchat.lua.LuaAppStore.localAssetDir(
                            LuaMiniAppActivity.this, miniApp.id).getCanonicalPath();
                    if (local.exists() && local.getCanonicalPath().startsWith(base + java.io.File.separator))
                        return LuaValue.valueOf(Uri.fromFile(local).toString());
                } catch (Exception ignored) { }
                String apiBase = com.im.oldchat.api.HttpUtil.BASE_URL == null ? "" : com.im.oldchat.api.HttpUtil.BASE_URL;
                int index = apiBase.indexOf("/v1");
                String origin = index > 0 ? apiBase.substring(0, index) : apiBase;
                return LuaValue.valueOf(origin + "/lua-assets/" + miniApp.id + "/" + relative);
            }
        });
        app.set("toast", new OneArgFunction() {
            @Override public LuaValue call(final LuaValue value) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    if (!destroyed && !isFinishing()) Toast.makeText(getApplicationContext(),
                            value.optjstring(""), Toast.LENGTH_SHORT).show();
                }});
                return LuaValue.NIL;
            }
        });
        app.set("storage_get", new OneArgFunction() {
            @Override public LuaValue call(LuaValue key) {
                String value = getSharedPreferences("lua_" + miniApp.id, MODE_PRIVATE)
                        .getString(safeKey(key.optjstring("")), null);
                return value == null ? LuaValue.NIL : LuaValue.valueOf(value);
            }
        });
        app.set("storage_set", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue key, LuaValue value) {
                getSharedPreferences("lua_" + miniApp.id, MODE_PRIVATE).edit()
                        .putString(safeKey(key.optjstring("")), value.optjstring("")).apply();
                return LuaValue.TRUE;
            }
        });
        app.set("storage_remove", new OneArgFunction() {
            @Override public LuaValue call(LuaValue key) {
                getSharedPreferences("lua_" + miniApp.id, MODE_PRIVATE).edit().remove(safeKey(key.optjstring(""))).apply();
                return LuaValue.TRUE;
            }
        });
        app.set("storage_clear", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public LuaValue call() {
                getSharedPreferences("lua_" + miniApp.id, MODE_PRIVATE).edit().clear().apply();
                return LuaValue.TRUE;
            }
        });
        app.set("storage_keys", new ZeroArgFunction() {
            @Override public LuaValue call() {
                LuaTable table = new LuaTable();
                java.util.Map<String, ?> values = getSharedPreferences(
                        "lua_" + miniApp.id, MODE_PRIVATE).getAll();
                int index = 1;
                for (String key : values.keySet()) table.set(index++, LuaValue.valueOf(key));
                return table;
            }
        });
        app.set("storage_count", new ZeroArgFunction() {
            @Override public LuaValue call() {
                return LuaValue.valueOf(getSharedPreferences(
                        "lua_" + miniApp.id, MODE_PRIVATE).getAll().size());
            }
        });
        app.set("storage_get_json", new OneArgFunction() {
            @Override public LuaValue call(LuaValue key) {
                String value = getSharedPreferences("lua_" + miniApp.id, MODE_PRIVATE)
                        .getString(safeKey(key.optjstring("")), null);
                if (value == null) return LuaValue.NIL;
                try { return jsonToLua(new org.json.JSONTokener(value).nextValue(), 0); }
                catch (Throwable ignored) { return LuaValue.NIL; }
            }
        });
        app.set("storage_set_json", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue key, LuaValue value) {
                try {
                    String json = CipJsonEncoder.encode(value);
                    getSharedPreferences("lua_" + miniApp.id, MODE_PRIVATE).edit()
                            .putString(safeKey(key.optjstring("")), json).apply();
                    return LuaValue.TRUE;
                } catch (Throwable ignored) { return LuaValue.FALSE; }
            }
        });
        app.set("http_get", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue pathValue, LuaValue callbackValue) {
                if (!callbackValue.isfunction()) return LuaValue.FALSE;
                final LuaFunction callback = callbackValue.checkfunction();
                final String path = pathValue.optjstring("").trim();
                if (path.startsWith("https://") || path.startsWith("http://")) {
                    return LuaValue.valueOf(LuaExternalHttp.get(miniApp, path, new LuaExternalHttp.LegacyCallback() {
                        @Override public void complete(String body, String error) {
                            safeLuaCallback(callback, body == null ? LuaValue.NIL : LuaValue.valueOf(body),
                                    error == null ? LuaValue.NIL : LuaValue.valueOf(error));
                        }
                    }));
                }
                if (!allowed("network")) { safeLuaCallback(callback, LuaValue.NIL, LuaValue.valueOf("缺少 network 权限")); return LuaValue.FALSE; }
                if (!path.startsWith("/") || path.contains("://") || path.contains("..")) {
                    safeLuaCallback(callback, LuaValue.NIL, LuaValue.valueOf("API 路径不合法；外部地址必须使用 HTTP/HTTPS 并声明 network_external")); return LuaValue.FALSE;
                }
                String token = getSharedPreferences("auth", MODE_PRIVATE).getString("access_token", "");
                HttpUtil.get(path, token, new HttpUtil.Callback() {
                    @Override public void onSuccess(String response) { safeLuaCallback(callback, LuaValue.valueOf(response), LuaValue.NIL); }
                    @Override public void onError(int code, String error) { safeLuaCallback(callback, LuaValue.NIL, LuaValue.valueOf(error == null ? String.valueOf(code) : error)); }
                });
                return LuaValue.TRUE;
            }
        });
        app.set("http_request", new VarArgFunction() {
            @Override public Varargs invoke(Varargs args) {
                LuaValue optionsValue = args.arg(1);
                LuaValue callbackValue = args.arg(2);
                if (!optionsValue.istable() || !callbackValue.isfunction()) return LuaValue.FALSE;
                LuaTable options = optionsValue.checktable();
                final LuaFunction callback = callbackValue.checkfunction();
                final String url = safeOptString(options.get("url"), "").trim();
                final String method = safeOptString(options.get("method"), "GET");
                final java.util.Map<String, String> headers = luaHeaders(options.get("headers"));
                String bodyText = safeOptString(options.get("body"), "");
                if (bodyText.length() == 0 && !options.get("json").isnil()) {
                    try {
                        bodyText = CipJsonEncoder.encode(options.get("json"));
                        if (!headers.containsKey("Content-Type"))
                            headers.put("Content-Type", "application/json; charset=utf-8");
                    } catch (Throwable error) {
                        safeLuaCallback(callback, LuaValue.NIL,
                                LuaValue.valueOf("JSON 请求体编码失败"));
                        return LuaValue.FALSE;
                    }
                }
                final byte[] body;
                try { body = bodyText.length() == 0 ? null : bodyText.getBytes("UTF-8"); }
                catch (Throwable ignored) { return LuaValue.FALSE; }
                int connectTimeout = safeOptInt(options.get("connect_timeout"), 10000);
                int readTimeout = safeOptInt(options.get("read_timeout"), 25000);
                if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                    safeLuaCallback(callback, LuaValue.NIL,
                            LuaValue.valueOf("http_request 目前用于公网 HTTP/HTTPS；站内 API 请使用 http_get"));
                    return LuaValue.FALSE;
                }
                return LuaValue.valueOf(LuaExternalHttp.request(miniApp, url, method,
                        headers, body, connectTimeout, readTimeout, new LuaExternalHttp.Callback() {
                    @Override public void complete(LuaExternalHttp.Response response, String error) {
                        LuaValue result = response == null ? LuaValue.NIL : httpResponseToLua(response);
                        safeLuaCallback(callback, result,
                                error == null ? LuaValue.NIL : LuaValue.valueOf(error));
                    }
                }));
            }
        });
        app.set("json_decode", new OneArgFunction() {
            @Override public LuaValue call(LuaValue text) {
                try { return jsonToLua(new org.json.JSONTokener(text.optjstring("")).nextValue(), 0); }
                catch (Throwable ignored) { return LuaValue.NIL; }
            }
        });
        app.set("json_encode", new OneArgFunction() {
            @Override public LuaValue call(LuaValue value) {
                try { return LuaValue.valueOf(CipJsonEncoder.encode(value)); }
                catch (Throwable ignored) { return LuaValue.NIL; }
            }
        });
        app.set("delay", new TwoArgFunction() {
            @Override public LuaValue call(LuaValue millis, final LuaValue callback) {
                if (!callback.isfunction()) return LuaValue.FALSE;
                int delay = Math.max(0, Math.min(60000, safeOptInt(millis, 0)));
                container.postDelayed(new Runnable() { @Override public void run() { if (!isFinishing()) safeLuaCallback(callback.checkfunction()); }}, delay);
                return LuaValue.TRUE;
            }
        });
        app.set("get_text", new OneArgFunction() {
            @Override public LuaValue call(LuaValue id) {
                View target = dynamicViews.get(id.optjstring(""));
                return target instanceof TextView ? LuaValue.valueOf(((TextView) target).getText().toString()) : LuaValue.NIL;
            }
        });
        app.set("append_text", new TwoArgFunction() {
            @Override public LuaValue call(final LuaValue id, final LuaValue text) {
                runOnUiThread(new Runnable() { @Override public void run() { View target = dynamicViews.get(id.optjstring("")); if (target instanceof TextView) ((TextView) target).append(text.optjstring("")); }});
                return LuaValue.TRUE;
            }
        });
        app.set("get_checked", new OneArgFunction() {
            @Override public LuaValue call(LuaValue id) { View target = dynamicViews.get(id.optjstring("")); return target instanceof CompoundButton ? LuaValue.valueOf(((CompoundButton) target).isChecked()) : LuaValue.NIL; }
        });
        app.set("set_checked", new TwoArgFunction() {
            @Override public LuaValue call(final LuaValue id, final LuaValue checked) {
                runOnUiThread(new Runnable() { @Override public void run() { View target = dynamicViews.get(id.optjstring("")); if (target instanceof CompoundButton) ((CompoundButton) target).setChecked(checked.optboolean(false)); }});
                return LuaValue.TRUE;
            }
        });
        app.set("set_text", new TwoArgFunction() {
            @Override public LuaValue call(final LuaValue id, final LuaValue text) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    View target = dynamicViews.get(id.optjstring(""));
                    if (target instanceof TextView) { String value = text.optjstring(""); if (!value.contentEquals(((TextView) target).getText())) ((TextView) target).setText(value); }
                }});
                return LuaValue.TRUE;
            }
        });
        app.set("set_enabled", new TwoArgFunction() {
            @Override public LuaValue call(final LuaValue id, final LuaValue enabled) {
                runOnUiThread(new Runnable() { @Override public void run() { View target = dynamicViews.get(id.optjstring("")); if (target != null) target.setEnabled(enabled.optboolean(true)); }});
                return LuaValue.TRUE;
            }
        });
        app.set("get_visible", new OneArgFunction() {
            @Override public LuaValue call(LuaValue id) { View target = dynamicViews.get(id.optjstring("")); return LuaValue.valueOf(target != null && target.getVisibility() == View.VISIBLE); }
        });
        app.set("set_hint", new TwoArgFunction() {
            @Override public LuaValue call(final LuaValue id, final LuaValue hint) { runOnUiThread(new Runnable() { @Override public void run() { View target = dynamicViews.get(id.optjstring("")); if (target instanceof EditText) ((EditText) target).setHint(hint.optjstring("")); }}); return LuaValue.TRUE; }
        });
        app.set("focus", new OneArgFunction() {
            @Override public LuaValue call(final LuaValue id) { runOnUiThread(new Runnable() { @Override public void run() { View target = dynamicViews.get(id.optjstring("")); if (target != null) target.requestFocus(); }}); return LuaValue.TRUE; }
        });
        app.set("set_image", new TwoArgFunction() {
            @Override public LuaValue call(final LuaValue id, final LuaValue url) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    View target = dynamicViews.get(id.optjstring(""));
                    if (target instanceof ImageView) {
                        String value = url.optjstring("");
                        if (value.startsWith("content://") || value.startsWith("file://"))
                            ((ImageView) target).setImageURI(Uri.parse(value));
                        else ImageLoader.load((ImageView) target, value);
                    }
                }});
                return LuaValue.TRUE;
            }
        });
        app.set("set_visible", new TwoArgFunction() {
            @Override public LuaValue call(final LuaValue id, final LuaValue visible) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    View target = dynamicViews.get(id.optjstring(""));
                    if (target != null) target.setVisibility(visible.optboolean(true) ? View.VISIBLE : View.GONE);
                }});
                return LuaValue.TRUE;
            }
        });
        app.set("set_children", new TwoArgFunction() {
            @Override public LuaValue call(final LuaValue id, final LuaValue children) {
                if (!children.istable()) return LuaValue.FALSE;
                runOnUiThread(new Runnable() { @Override public void run() {
                    View target = dynamicViews.get(id.optjstring(""));
                    if (!(target instanceof LinearLayout) || destroyed || isFinishing()) return;
                    LinearLayout parent = (LinearLayout) target;
                    removeDynamicIds(parent);
                    parent.removeAllViews();
                    renderChildren(children.checktable(), parent);
                }});
                return LuaValue.TRUE;
            }
        });
        app.set("url_encode", new OneArgFunction() {
            @Override public LuaValue call(LuaValue text) {
                try {
                    return LuaValue.valueOf(java.net.URLEncoder.encode(
                            text.optjstring(""), "UTF-8").replace("+", "%20"));
                } catch (Throwable ignored) {
                    return LuaValue.NIL;
                }
            }
        });
        app.set("scroll_to_end", new OneArgFunction() {
            @Override public LuaValue call(final LuaValue id) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    final View target = dynamicViews.get(id.optjstring(""));
                    if (target instanceof ScrollView) {
                        target.post(new Runnable() { @Override public void run() {
                            ((ScrollView) target).fullScroll(View.FOCUS_DOWN);
                        }});
                    } else if (target instanceof HorizontalScrollView) {
                        target.post(new Runnable() { @Override public void run() {
                            ((HorizontalScrollView) target).fullScroll(View.FOCUS_RIGHT);
                        }});
                    }
                }});
                return LuaValue.TRUE;
            }
        });
        app.set("camera", new OneArgFunction() {
            @Override public LuaValue call(LuaValue callback) {
                if (!allowed("camera") || !callback.isfunction()) return LuaValue.FALSE;
                final LuaFunction function = callback.checkfunction();
                runOnUiThread(new Runnable() { @Override public void run() { requestCamera(function); }});
                return LuaValue.TRUE;
            }
        });
        app.set("back", new org.luaj.vm2.lib.ZeroArgFunction() {
            @Override public LuaValue call() { runOnUiThread(new Runnable() { @Override public void run() { finish(); }}); return LuaValue.NIL; }
        });
        g.set("app", app);
    }

    private java.util.Map<String, String> luaHeaders(LuaValue value) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        if (!value.istable()) return out;
        LuaTable table = value.checktable();
        LuaValue[] keys = table.keys();
        for (int i = 0; i < keys.length && i < 64; i++) {
            String key = safeOptString(keys[i], "").trim();
            String item = safeOptString(table.get(keys[i]), "");
            if (key.length() > 0) out.put(key, item);
        }
        return out;
    }

    private LuaValue httpResponseToLua(LuaExternalHttp.Response response) {
        LuaTable table = new LuaTable();
        table.set("status", LuaValue.valueOf(response.status));
        table.set("body", LuaValue.valueOf(response.body == null ? "" : response.body));
        table.set("url", LuaValue.valueOf(response.url == null ? "" : response.url));
        LuaTable headers = new LuaTable();
        for (java.util.Map.Entry<String, String> entry : response.headers.entrySet()) {
            headers.set(entry.getKey(), LuaValue.valueOf(entry.getValue()));
        }
        table.set("headers", headers);
        return table;
    }

    private void renderPage(LuaValue page) {
        try {
            container.removeAllViews();
            dynamicViews.clear();
            String pageTitle = page.get("title").optjstring(miniApp.name);
            title.setText(pageTitle);
            LuaValue children = page.get("children");
            if (children.istable()) renderChildren(children.checktable(), container);
        } catch (Throwable e) {
            Log.e("LuaMiniApp", "渲染页面失败", e);
            showFatal("页面渲染失败：" + safeMessage(e));
        }
    }

    /** 容错读取 int：table/boolean/nil/无法解析的字符串一律回退默认值，避免 LuaError 崩溃主线程 */
    private int safeOptInt(LuaValue value, int def) {
        try {
            if (value == null || value.isnil()) return def;
            if (value.isint()) return value.toint();
            if (value.isnumber()) return (int) Math.round(value.todouble());
            if (value.isstring()) {
                String s = value.tojstring().trim();
                if (s.length() == 0) return def;
                try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
            }
            return def;
        } catch (Throwable e) {
            return def;
        }
    }

    /** 容错读取 double */
    private double safeOptDouble(LuaValue value, double def) {
        try {
            if (value == null || value.isnil()) return def;
            if (value.isnumber()) return value.todouble();
            if (value.isstring()) {
                String s = value.tojstring().trim();
                if (s.length() == 0) return def;
                try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
            }
            return def;
        } catch (Throwable e) {
            return def;
        }
    }

    /** 容错读取 string */
    private String safeOptString(LuaValue value, String def) {
        try {
            if (value == null || value.isnil()) return def;
            if (value.isstring()) return value.tojstring();
            return value.toString();
        } catch (Throwable e) {
            return def;
        }
    }

    private String safeMessage(Throwable e) {
        String detail = e.getMessage();
        if (detail == null || detail.trim().length() == 0) detail = e.getClass().getSimpleName();
        if (detail.length() > 120) detail = detail.substring(0, 120);
        return detail;
    }

    /** 容错读取 boolean */
    private boolean safeOptBoolean(LuaValue value, boolean def) {
        try {
            if (value == null || value.isnil()) return def;
            if (value.isboolean()) return value.toboolean();
            return def;
        } catch (Throwable e) {
            return def;
        }
    }

    private void removeDynamicIds(View view) {
        if (view == null) return;
        java.util.Iterator<java.util.Map.Entry<String, View>> iterator =
                dynamicViews.entrySet().iterator();
        while (iterator.hasNext()) {
            java.util.Map.Entry<String, View> entry = iterator.next();
            View candidate = entry.getValue();
            if (candidate != view && isDescendantOf(candidate, view)) iterator.remove();
        }
    }

    private boolean isDescendantOf(View candidate, View ancestor) {
        if (candidate == null || ancestor == null) return false;
        android.view.ViewParent parent = candidate.getParent();
        while (parent instanceof View) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private void renderChildren(LuaTable children, LinearLayout parent) {
        int length = children.length();
        for (int i = 1; i <= length; i++) {
            try {
                LuaValue node = children.get(i);
                if (node.istable()) {
                    View view = buildView(node.checktable());
                    if (view != null) {
                        String id = safeOptString(node.get("id"), "");
                        if (id.matches("[a-zA-Z0-9_.-]{1,64}")) dynamicViews.put(id, view);
                        LuaValue visible = node.get("visible");
                        if (visible.isboolean()) view.setVisibility(
                                visible.toboolean() ? View.VISIBLE : View.GONE);
                        LuaValue enabled = node.get("enabled");
                        if (enabled.isboolean()) view.setEnabled(enabled.toboolean());
                        parent.addView(view);
                    }
                }
            } catch (Throwable e) {
                Log.e("LuaMiniApp", "渲染子节点失败 index=" + i, e);
            }
        }
    }

    private View buildView(final LuaTable node) {
        try {
            return buildViewInner(node);
        } catch (Throwable e) {
            Log.e("LuaMiniApp", "构建控件失败 type=" + node.get("type"), e);
            return null;
        }
    }

    private View buildViewInner(final LuaTable node) {
        String type = safeOptString(node.get("type"), "text");
        if ("text".equals(type)) {
            TextView view = new TextView(this);
            view.setText(safeOptString(node.get("text"), ""));
            view.setTextSize((float) safeOptDouble(node.get("size"), 15));
            view.setTextColor(parseColor(safeOptString(node.get("color"), ""), getResources().getColor(R.color.color_text_primary)));
            applyCommon(view, node);
            applyNodeBackground(view, node, false);
            return view;
        }
        if ("button".equals(type)) {
            Button button = new Button(this);
            button.setText(safeOptString(node.get("text"), "按钮"));
            final LuaValue callback = node.get("on_click");
            if (callback.isfunction()) button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { safeLuaCallback(callback.checkfunction()); }
            });
            applyCommon(button, node);
            applyNodeBackground(button, node, false);
            return button;
        }
        if ("image".equals(type)) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int height = dp(safeOptInt(node.get("height"), 180));
            image.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            String url = safeOptString(node.get("url"), "");
            if (url.startsWith("content://") || url.startsWith("file://")) image.setImageURI(Uri.parse(url));
            else if (url.length() > 0) ImageLoader.load(image, url);
            final LuaValue callback = node.get("on_click");
            if (callback.isfunction()) image.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { safeLuaCallback(callback.checkfunction()); }
            });
            applyMargins(image, node);
            return image;
        }
        if ("input".equals(type)) {
            final EditText input = new EditText(this);
            input.setHint(safeOptString(node.get("hint"), ""));
            input.setText(safeOptString(node.get("text"), ""));
            boolean singleLine = node.get("single_line").optboolean(false);
            input.setSingleLine(singleLine);
            if (singleLine) input.setImeOptions(EditorInfo.IME_ACTION_DONE);
            int maxLength = safeOptInt(node.get("max_length"), 0); if (maxLength > 0) input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Math.min(maxLength, 100000))});
            String inputType = safeOptString(node.get("input_type"), "text");
            if ("password".equals(inputType)) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            else if ("number".equals(inputType)) input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            else if ("email".equals(inputType)) input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            else if ("phone".equals(inputType)) input.setInputType(InputType.TYPE_CLASS_PHONE);
            final LuaValue onChange = node.get("on_change");
            if (onChange.isfunction()) input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override public void afterTextChanged(Editable value) { safeLuaCallback(onChange.checkfunction(), LuaValue.valueOf(value.toString())); }
            });
            final LuaValue onFocusLost = node.get("on_focus_lost");
            if (onFocusLost.isfunction()) input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean hasFocus) { if (!hasFocus) safeLuaCallback(onFocusLost.checkfunction(), LuaValue.valueOf(input.getText().toString())); }
            });
            final LuaValue onSubmit = node.get("on_submit");
            if (onSubmit.isfunction()) input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    boolean submitted = actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_SEND
                            || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
                    if (submitted) safeLuaCallback(onSubmit.checkfunction(), LuaValue.valueOf(input.getText().toString()));
                    return submitted;
                }
            });
            applyCommon(input, node);
            return input;
        }
        if ("checkbox".equals(type)) {
            final CheckBox checkbox = new CheckBox(this);
            checkbox.setText(safeOptString(node.get("text"), "")); checkbox.setChecked(node.get("checked").optboolean(false));
            final LuaValue onChange = node.get("on_change");
            if (onChange.isfunction()) checkbox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) { safeLuaCallback(onChange.checkfunction(), LuaValue.valueOf(isChecked)); }
            });
            applyCommon(checkbox, node);
            return checkbox;
        }
        if ("list".equals(type) || "page".equals(type) || "column".equals(type)
                || "row".equals(type) || "card".equals(type)) {
            LinearLayout layout = new LinearLayout(this);
            boolean horizontal = "row".equals(type)
                    || "horizontal".equals(safeOptString(node.get("orientation"), ""));
            layout.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            layout.setGravity(parseGravity(safeOptString(node.get("gravity"), "start")));
            layout.setPadding(dp(safeOptInt(node.get("padding"), "card".equals(type) ? 12 : 0)),
                    dp(safeOptInt(node.get("padding_vertical"), "card".equals(type) ? 10 : 0)),
                    dp(safeOptInt(node.get("padding"), "card".equals(type) ? 12 : 0)),
                    dp(safeOptInt(node.get("padding_vertical"), "card".equals(type) ? 10 : 0)));
            layout.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if ("card".equals(type)) applyNodeBackground(layout, node, true);
            LuaValue children = node.get("children");
            if (children.istable()) renderChildren(children.checktable(), layout);
            applyMargins(layout, node);
            return layout;
        }
        if ("scroll".equals(type)) {
            boolean horizontal = "horizontal".equals(safeOptString(node.get("orientation"), "vertical"));
            ViewGroup scroll = horizontal ? new HorizontalScrollView(this) : new ScrollView(this);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            LuaValue children = node.get("children");
            if (children.istable()) renderChildren(children.checktable(), content);
            scroll.addView(content, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    safeOptInt(node.get("height"), 0) > 0
                            ? dp(safeOptInt(node.get("height"), 200))
                            : ViewGroup.LayoutParams.WRAP_CONTENT));
            applyMargins(scroll, node);
            return scroll;
        }
        if ("divider".equals(type)) {
            View divider = new View(this);
            divider.setBackgroundColor(parseColor(safeOptString(node.get("color"), ""),
                    getResources().getColor(R.color.color_divider)));
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, dp(safeOptInt(node.get("height"), 1)))));
            applyMargins(divider, node);
            return divider;
        }
        if ("progress".equals(type)) {
            ProgressBar progress = new ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            progress.setMax(Math.max(1, safeOptInt(node.get("max"), 100)));
            progress.setProgress(Math.max(0, safeOptInt(node.get("value"), 0)));
            progress.setIndeterminate(safeOptBoolean(node.get("indeterminate"), false));
            progress.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            applyMargins(progress, node);
            return progress;
        }
        if ("spacer".equals(type)) {
            View spacer = new View(this);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(1, dp(safeOptInt(node.get("height"), 12))));
            return spacer;
        }
        return null;
    }

    private int parseGravity(String value) {
        if ("center".equals(value)) return Gravity.CENTER;
        if ("end".equals(value) || "right".equals(value)) return Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        if ("bottom".equals(value)) return Gravity.BOTTOM;
        return Gravity.LEFT | Gravity.CENTER_VERTICAL;
    }

    private void applyNodeBackground(View view, LuaTable node, boolean cardDefault) {
        String raw = safeOptString(node.get("background"), "");
        if (raw.length() == 0 && !cardDefault) return;
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(parseColor(raw, cardDefault
                ? getResources().getColor(R.color.color_surface) : Color.TRANSPARENT));
        float radius = dp(safeOptInt(node.get("radius"), cardDefault ? 8 : 0));
        shape.setCornerRadius(radius);
        int stroke = safeOptInt(node.get("stroke_width"), cardDefault ? 1 : 0);
        if (stroke > 0) shape.setStroke(dp(stroke), parseColor(
                safeOptString(node.get("stroke_color"), ""),
                getResources().getColor(R.color.color_divider)));
        view.setBackgroundDrawable(shape);
    }

    private void applyCommon(TextView view, LuaTable node) {
        String gravity = safeOptString(node.get("gravity"),
                safeOptBoolean(node.get("center"), false) ? "center" : "start");
        view.setGravity(parseGravity(gravity));
        int padding = safeOptInt(node.get("padding"), 10);
        int paddingVertical = safeOptInt(node.get("padding_vertical"), 8);
        view.setPadding(dp(padding), dp(paddingVertical), dp(padding), dp(paddingVertical));
        int width = "wrap".equals(safeOptString(node.get("width"), "match"))
                ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                width, safeOptInt(node.get("height"), 0) > 0
                ? dp(safeOptInt(node.get("height"), 0)) : ViewGroup.LayoutParams.WRAP_CONTENT);
        double weight = safeOptDouble(node.get("weight"), 0d);
        if (weight > 0d) { params.width = 0; params.weight = (float) weight; }
        view.setLayoutParams(params);
        applyMargins(view, node);
    }

    private void applyMargins(View view, LuaTable node) {
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        LinearLayout.LayoutParams lp = raw instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) raw
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = dp(safeOptInt(node.get("margin"), 4));
        lp.setMargins(0, margin, 0, margin);
        view.setLayoutParams(lp);
    }

    private void requestCamera(LuaFunction callback) {
        cameraCallback = callback;
        if (Build.VERSION.SDK_INT >= 23) {
            boolean cameraMissing = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED;
            boolean storageMissing = Build.VERSION.SDK_INT <= 28
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED;
            if (cameraMissing || storageMissing) {
                String[] permissions = storageMissing
                        ? new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}
                        : new String[]{Manifest.permission.CAMERA};
                ActivityCompat.requestPermissions(this, permissions, REQ_CAMERA_PERMISSION);
                return;
            }
        }
        launchCamera();
    }

    private void launchCamera() {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.TITLE, "oldchat_lua_" + System.currentTimeMillis());
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraOutputUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (cameraOutputUri != null) intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraOutputUri);
            startActivityForResult(intent, REQ_CAMERA_CAPTURE);
        } catch (Exception e) {
            deliverCamera(null, "无法启动相机");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA_PERMISSION) {
            boolean granted = results.length > 0;
            for (int i = 0; i < results.length; i++) {
                granted = granted && results[i] == PackageManager.PERMISSION_GRANTED;
            }
            if (granted) launchCamera();
            else deliverCamera(null, "相机或存储权限被拒绝");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA_CAPTURE) {
            if (resultCode == Activity.RESULT_OK && cameraOutputUri != null) deliverCamera(cameraOutputUri.toString(), null);
            else deliverCamera(null, "已取消拍照");
        }
    }

    private void deliverCamera(String uri, String error) {
        LuaFunction callback = cameraCallback;
        cameraCallback = null;
        if (callback != null) safeLuaCallback(callback,
                uri == null ? LuaValue.NIL : LuaValue.valueOf(uri),
                error == null ? LuaValue.NIL : LuaValue.valueOf(error));
    }

    private LuaValue jsonToLua(Object value, int depth) throws Exception {
        if (depth > 12 || value == null || value == JSONObject.NULL) return LuaValue.NIL;
        if (value instanceof Boolean) return LuaValue.valueOf(((Boolean) value).booleanValue());
        if (value instanceof Number) return LuaValue.valueOf(((Number) value).doubleValue());
        if (value instanceof String) return LuaValue.valueOf((String) value);
        LuaTable table = new LuaTable();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; if (array.length() > 4096) throw new Exception("JSON 数组过大");
            for (int i = 0; i < array.length(); i++) table.set(i + 1, jsonToLua(array.opt(i), depth + 1));
            return table;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; java.util.Iterator<String> keys = object.keys(); int count = 0;
            while (keys.hasNext()) { if (++count > 4096) throw new Exception("JSON 对象过大"); String key = keys.next(); table.set(key, jsonToLua(object.opt(key), depth + 1)); }
            return table;
        }
        return LuaValue.valueOf(String.valueOf(value));
    }

    private void safeLuaCallback(final LuaFunction callback, final LuaValue... args) {
        if (callback == null) return;
        runOnUiThread(new Runnable() { @Override public void run() {
            if (destroyed || isFinishing()) return;
            try { callback.invoke(LuaValue.varargsOf(args)); }
            catch (Throwable e) {
                Log.e("LuaMiniApp", "Lua 回调失败: " + (miniApp == null ? "unknown" : miniApp.id), e);
                String detail = e.getMessage(); if (detail == null || detail.trim().length() == 0) detail = e.getClass().getSimpleName();
                if (detail.length() > 120) detail = detail.substring(0, 120);
                Toast.makeText(getApplicationContext(),
                        "Lua 回调失败：" + detail, Toast.LENGTH_LONG).show();
            }
        }});
    }

    private boolean allowed(String permission) { return miniApp != null && miniApp.hasPermission(permission); }
    private String safeKey(String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.length() == 0) return "_";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < 120; i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c != 0x7f) out.append(c);
        }
        return out.length() == 0 ? "_" : out.toString();
    }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value + 0.5f); }
    private int parseColor(String value, int fallback) { try { return value.length() > 0 ? Color.parseColor(value) : fallback; } catch (Exception e) { return fallback; } }
    private void showFatal(String message) { if (loading != null) loading.setVisibility(View.GONE); container.removeAllViews(); TextView text = new TextView(this); text.setText(message); text.setTextColor(getResources().getColor(R.color.color_text_secondary)); text.setPadding(dp(16), dp(24), dp(16), dp(16)); container.addView(text); }

    @Override protected void onDestroy() {
        destroyed = true;
        dynamicViews.clear();
        globals = null;
        super.onDestroy();
    }

    private static final class EngineResult {
        final Globals globals; final LuaValue page; final String error;
        EngineResult(Globals globals, LuaValue page, String error) { this.globals = globals; this.page = page; this.error = error; }
    }
}
