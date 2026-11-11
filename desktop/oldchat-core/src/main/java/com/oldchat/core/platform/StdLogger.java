package com.oldchat.core.platform;

public class StdLogger implements Logger {
    private final String name;

    public StdLogger(String name) {
        this.name = name;
    }

    @Override
    public void info(String msg) {
        System.out.println("[INFO][" + name + "] " + msg);
    }

    @Override
    public void warn(String msg) {
        System.out.println("[WARN][" + name + "] " + msg);
    }

    @Override
    public void error(String msg, Throwable t) {
        System.err.println("[ERROR][" + name + "] " + msg);
        if (t != null) t.printStackTrace();
    }
}
