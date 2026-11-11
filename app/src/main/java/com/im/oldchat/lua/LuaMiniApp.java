package com.im.oldchat.lua;

import java.util.ArrayList;
import java.util.List;

public class LuaMiniApp {
    public String id = "";
    public String name = "";
    public String description = "";
    public int version = 1;
    public String icon_url = "";
    public boolean enabled = true;
    public int order;
    public String sha256 = "";
    public String script_url = "";
    public List<String> permissions = new ArrayList<String>();
    public List<String> allowedHosts = new ArrayList<String>();

    public boolean hasPermission(String permission) {
        return permission != null && permissions != null && permissions.contains(permission);
    }

    public boolean allowsHost(String host) {
        if (host == null) return false;
        // network_external 默认允许任意公网；allowed_hosts 非空时可继续作为收紧白名单。
        if (allowedHosts == null || allowedHosts.isEmpty()) return true;
        String value = host.toLowerCase(java.util.Locale.US);
        for (String item : allowedHosts) {
            String allowed = item == null ? "" : item.trim().toLowerCase(java.util.Locale.US);
            if ("*".equals(allowed)) return true;
            if (allowed.equals(value)) return true;
            if (allowed.startsWith("*.") && value.endsWith(allowed.substring(1)) && value.length() > allowed.length() - 1) return true;
        }
        return false;
    }
}
