package com.jovoc.facecampresentationrecorder.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * The direct (multipart, straight to R2) upload of one publish job: which server-side upload it
 * is, how the file was cut and which parts are done. Survives the app being killed, so a restart
 * resumes the upload instead of sending the file again. One row per job; deleted when the job
 * finishes or is cancelled.
 */
@Entity(tableName = "upload_sessions")
public class UploadSession {

    /** Created, parts are being sent (or waiting to be). */
    public static final String PENDING = "pending";
    /** The server accepted the complete call; kept until the job saved the media id. */
    public static final String COMPLETED = "completed";

    /** The publish job this upload belongs to ({@link PublishJob#id}). */
    @PrimaryKey
    @ColumnInfo(name = "job_id")
    public long jobId;

    /** The server's upload id (the id of POST /upload/multipart), never an R2 key. */
    @ColumnInfo(name = "upload_id")
    public String uploadId;

    /** Size of the file when the upload was created; a different size invalidates the row. */
    public long size;

    /** Bytes of every part but the last, as the server answered; never hardcoded. */
    @ColumnInfo(name = "part_size")
    public long partSize;

    @ColumnInfo(name = "part_count")
    public int partCount;

    /** JSON: [{"n":1,"etag":"\"abc\""}] of the parts whose ETag was received. */
    @ColumnInfo(name = "parts_json")
    public String partsJson;

    /** {@link #PENDING} or {@link #COMPLETED}. */
    public String status;
}
