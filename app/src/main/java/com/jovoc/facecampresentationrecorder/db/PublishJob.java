package com.jovoc.facecampresentationrecorder.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One publish attempt of a recording: what to post, where, and how far it got. Survives the app
 * being killed; the idempotency key is what stops a retry from posting twice.
 */
@Entity(tableName = "publish_jobs", indices = {@Index(value = {"file_path"})})
public class PublishJob {

    /**
     * Auto-publish only: the recording stopped and its post-processing (background replace,
     * crops) is still running; it is released to QUEUED when that is done.
     */
    public static final String WAITING_PROCESSING = "WAITING_PROCESSING";
    public static final String QUEUED = "QUEUED";
    public static final String UPLOADING = "UPLOADING";
    public static final String PUBLISHING = "PUBLISHING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    public static final String MODE_NOW = "now";
    public static final String MODE_SCHEDULE = "schedule";
    /** Auto-publish: the server's preset decides channels, text and timing (publish now). */
    public static final String MODE_AUTO = "auto";
    /** Auto-publish whose preset posts at the next free slot (learnt from the pre-flight). */
    public static final String MODE_AUTO_SLOT = "auto_slot";

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Absolute path of the recording; kept in step when the file is renamed. */
    @ColumnInfo(name = "file_path")
    public String filePath;

    @ColumnInfo(name = "file_name")
    public String fileName;

    public String caption;

    /** JSON: [{id, identifier, name}] of the chosen channels. */
    @ColumnInfo(name = "channels_json")
    public String channelsJson;

    /** {@link #MODE_NOW}, {@link #MODE_SCHEDULE}, {@link #MODE_AUTO} or {@link #MODE_AUTO_SLOT}. */
    public String mode;

    /** Epoch millis for {@link #MODE_SCHEDULE}, else 0. */
    @ColumnInfo(name = "schedule_at")
    public long scheduleAt;

    /** Sent as Idempotency-Key; replaced after the server gave a final 4xx answer. */
    @ColumnInfo(name = "idempotency_key")
    public String idempotencyKey;

    /** Set once the video is uploaded, so a retry never uploads (or duplicates) it again. */
    @ColumnInfo(name = "media_id")
    public String mediaId;

    @ColumnInfo(name = "media_path")
    public String mediaPath;

    /**
     * Workspace the job was made in (sent as x-workspace-id), so switching workspace later cannot
     * move a queued post. Null = whatever the server picks by default.
     */
    @ColumnInfo(name = "workspace_id")
    public String workspaceId;

    public String status;

    /** Upload progress, 0-100. */
    public int progress;

    /** {@code PublishException.Kind} name of the last failure, or null. */
    @ColumnInfo(name = "error_kind")
    public String errorKind;

    @ColumnInfo(name = "error_detail")
    public String errorDetail;

    /** Comma separated post ids returned by the backend. */
    @ColumnInfo(name = "post_ids")
    public String postIds;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    /** True for jobs made by the auto-publish setting (not by the Publish screen). */
    public boolean isAuto() {
        return MODE_AUTO.equals(mode) || MODE_AUTO_SLOT.equals(mode);
    }

    /** In progress or waiting to start: the card offers Cancel, not Publish. */
    public boolean isActive() {
        return WAITING_PROCESSING.equals(status) || QUEUED.equals(status)
                || UPLOADING.equals(status) || PUBLISHING.equals(status);
    }
}
