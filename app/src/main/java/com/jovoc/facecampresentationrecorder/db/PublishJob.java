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

    public static final String QUEUED = "QUEUED";
    public static final String UPLOADING = "UPLOADING";
    public static final String PUBLISHING = "PUBLISHING";
    public static final String DONE = "DONE";
    public static final String FAILED = "FAILED";

    public static final String MODE_NOW = "now";
    public static final String MODE_SCHEDULE = "schedule";

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

    /** {@link #MODE_NOW} or {@link #MODE_SCHEDULE}. */
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

    public boolean isActive() {
        return QUEUED.equals(status) || UPLOADING.equals(status) || PUBLISHING.equals(status);
    }
}
