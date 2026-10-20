package com.jovoc.facecampresentationrecorder.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One user action waiting to be uploaded (see the activitylog package). Rows are deleted once the
 * server accepted the batch they were sent in; {@link #batchId} is set before the first attempt so
 * a retry resends exactly the same batch.
 */
@Entity(tableName = "activity_events")
public class ActivityEvent {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Epoch millis (UTC) when the action happened. */
    public long ts;

    /** Minutes east of UTC on the device at that moment. */
    public int tz;

    /** Random id of the app session the action belongs to. */
    public String session;

    /** Action name, {@code ^[a-z0-9_]{1,48}$}. */
    public String action;

    /** Flat JSON object of details, or null. */
    @ColumnInfo(name = "meta_json")
    public String metaJson;

    /** The upload batch this row was put into, or null while it is not part of one. */
    @ColumnInfo(name = "batch_id")
    public String batchId;
}
