package com.jovoc.facecampresentationrecorder.tracker;

/** Every tunable number of the activity tracker lives here. */
public final class TrackerConfig {
    public static final int  SEND_EVERY_DAYS = 5;          // time rule
    public static final int  SEND_WHEN_MORE_THAN = 20;     // volume rule: send when pending > this
    public static final long MIN_GAP_MS = 15L * 60 * 1000; // volume rule fires at most every 15 min (0 = no limit)
    public static final int  BATCH_SIZE = 50;              // records per request (backend max 50)
    public static final int  MAX_BATCHES_PER_RUN = 10;     // then a follow-up run
    public static final int  LOCAL_MAX_ROWS = 3000;        // device buffer cap
    public static final int  TRIM_CHUNK = 100;             // rows dropped at once when full
    public static final int  QUEUE_CAP = 500;              // writer queue
    public static final long CRASH_WAIT_MS = 500;
    public static final long SESSION_GAP_MS = 30L * 60 * 1000;
    public static final int  MAX_UPLOAD_ATTEMPTS = 6;
    private TrackerConfig() { }
}
