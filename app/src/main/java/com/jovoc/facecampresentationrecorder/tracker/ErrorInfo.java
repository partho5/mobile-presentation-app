package com.jovoc.facecampresentationrecorder.tracker;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** The error block of an error record. Pure Java: the stack is built by hand, never with android.util.Log. */
public final class ErrorInfo {

    static final int MAX_STACK = 8000;
    static final int MAX_CAUSES = 5;

    /** One entry of the cause chain. */
    static final class Cause {
        final String type;
        final String message;

        Cause(String type, String message) {
            this.type = type;
            this.message = message;
        }
    }

    final String type;
    final String message;
    final String stack;
    final List<Cause> causes;
    final String thread;
    final boolean fatal;
    final String lastActivity;

    private ErrorInfo(String type, String message, String stack, List<Cause> causes, String thread,
                      boolean fatal, String lastActivity) {
        this.type = type;
        this.message = message;
        this.stack = stack;
        this.causes = causes;
        this.thread = thread;
        this.fatal = fatal;
        this.lastActivity = lastActivity;
    }

    /** Null when {@code t} is null. */
    public static ErrorInfo from(Throwable t, boolean fatal, String thread, String lastActivity) {
        if (t == null) return null;

        StringBuilder sb = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable c = t; c != null && seen.add(c) && sb.length() < MAX_STACK; c = c.getCause()) {
            if (c != t) {
                sb.append("Caused by: ").append(c.getClass().getName()).append(": ")
                        .append(c.getMessage()).append('\n');
            }
            StackTraceElement[] frames = c.getStackTrace();
            if (frames != null) {
                for (StackTraceElement f : frames) {
                    sb.append("at ").append(f).append('\n');
                    if (sb.length() >= MAX_STACK) break;
                }
            }
        }

        List<Cause> causes = new ArrayList<>();
        Set<Throwable> chain = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        chain.add(t);
        for (Throwable c = t.getCause(); c != null && causes.size() < MAX_CAUSES && chain.add(c); c = c.getCause()) {
            causes.add(new Cause(TrackedRecord.cut(c.getClass().getName(), 120),
                    TrackedRecord.cut(c.getMessage(), 300)));
        }

        return new ErrorInfo(
                TrackedRecord.cut(t.getClass().getName(), 120),
                TrackedRecord.cut(t.getMessage(), 1000),
                TrackedRecord.cut(sb.toString(), MAX_STACK),
                causes,
                TrackedRecord.cut(thread, 60),
                fatal,
                TrackedRecord.cut(lastActivity, 120));
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        putIfNotNull(o, "type", type);
        putIfNotNull(o, "message", message);
        putIfNotNull(o, "stack", stack);
        JSONArray arr = new JSONArray();
        for (Cause c : causes) {
            JSONObject co = new JSONObject();
            putIfNotNull(co, "type", c.type);
            putIfNotNull(co, "message", c.message);
            arr.put(co);
        }
        o.put("causes", arr);
        putIfNotNull(o, "thread", thread);
        o.put("fatal", fatal);
        putIfNotNull(o, "lastActivity", lastActivity);
        return o;
    }

    private static void putIfNotNull(JSONObject o, String key, String value) throws JSONException {
        if (value != null) o.put(key, value);
    }
}
