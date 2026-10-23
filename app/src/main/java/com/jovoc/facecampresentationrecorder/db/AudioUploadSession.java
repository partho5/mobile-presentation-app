package com.jovoc.facecampresentationrecorder.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * The direct upload of the speech audio (WAV) of one publish job, for AI captions. Same columns and
 * meaning as {@link UploadSession}, in its own table so the video's resumable session is never
 * touched by it. One row per job; deleted once the caption was created or the job is cancelled.
 */
@Entity(tableName = "audio_upload_sessions")
public class AudioUploadSession {

    @PrimaryKey
    @ColumnInfo(name = "job_id")
    public long jobId;

    @ColumnInfo(name = "upload_id")
    public String uploadId;

    public long size;

    @ColumnInfo(name = "part_size")
    public long partSize;

    @ColumnInfo(name = "part_count")
    public int partCount;

    @ColumnInfo(name = "parts_json")
    public String partsJson;

    /** {@link UploadSession#PENDING} or {@link UploadSession#COMPLETED}. */
    public String status;

    public UploadSession toSession() {
        UploadSession s = new UploadSession();
        s.jobId = jobId;
        s.uploadId = uploadId;
        s.size = size;
        s.partSize = partSize;
        s.partCount = partCount;
        s.partsJson = partsJson;
        s.status = status;
        return s;
    }

    public static AudioUploadSession from(UploadSession s) {
        AudioUploadSession a = new AudioUploadSession();
        a.jobId = s.jobId;
        a.uploadId = s.uploadId;
        a.size = s.size;
        a.partSize = s.partSize;
        a.partCount = s.partCount;
        a.partsJson = s.partsJson;
        a.status = s.status;
        return a;
    }
}
