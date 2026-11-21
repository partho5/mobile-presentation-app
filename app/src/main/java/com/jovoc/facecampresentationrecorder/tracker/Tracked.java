package com.jovoc.facecampresentationrecorder.tracker;

import java.util.concurrent.atomic.AtomicBoolean;

/** A span handle: started by {@link ActivityTracker#start}, stored once when it ends. */
public final class Tracked {

    /** Returned when tracking is off. Ending it does nothing. */
    public static final Tracked NOOP = new Tracked(null, null, 0L, null, null, true);

    final String name;
    final String description;
    final long startedAt;
    final Meta meta;
    final String screen;
    private final AtomicBoolean done;

    Tracked(String name, String description, long startedAt, Meta meta, String screen) {
        this(name, description, startedAt, meta, screen, false);
    }

    private Tracked(String name, String description, long startedAt, Meta meta, String screen, boolean noop) {
        this.name = name;
        this.description = description;
        this.startedAt = startedAt;
        this.meta = meta;
        this.screen = screen;
        this.done = new AtomicBoolean(noop);
    }

    /** Ends the span now. Only the first end counts. */
    public void end() {
        end(null);
    }

    /** Ends the span now and merges {@code extra} into its details. Only the first end counts. */
    public void end(Meta extra) {
        try {
            ActivityTracker.endSpan(this, extra);
        } catch (Throwable ignored) {
            // ending a span never throws
        }
    }

    /** True for exactly one caller: the one that gets to store this span. */
    boolean claim() {
        return done.compareAndSet(false, true);
    }
}
