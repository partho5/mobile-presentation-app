package com.jovoc.facecampresentationrecorder.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One activity-tracker record waiting to be uploaded (see the tracker package). {@link #json} is the
 * record in the backend contract's shape; {@link #batchId} is set before the first attempt so a retry
 * resends exactly the same batch. Rows are deleted once the server answered for their batch.
 */
@Entity(tableName = "tracked_activities")
public class TrackedActivity {

    @PrimaryKey
    @NonNull
    public String id;

    /** The record's sequence number on this device. */
    public long seq;

    /** Epoch millis when the row was stored. */
    @ColumnInfo(name = "created_at")
    public long createdAt;

    /** The record as JSON. */
    @NonNull
    public String json;

    /** The upload batch this row was put into, or null while it is not part of one. */
    @ColumnInfo(name = "batch_id")
    public String batchId;
}
