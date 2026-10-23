package com.jovoc.facecampresentationrecorder.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface AudioUploadSessionDao {

    @Query("SELECT * FROM audio_upload_sessions WHERE job_id = :jobId")
    AudioUploadSession get(long jobId);

    /** One row per job: a new upload replaces the old one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void put(AudioUploadSession session);

    @Query("UPDATE audio_upload_sessions SET parts_json = :partsJson WHERE job_id = :jobId")
    void setParts(long jobId, String partsJson);

    @Query("UPDATE audio_upload_sessions SET status = :status WHERE job_id = :jobId")
    void setStatus(long jobId, String status);

    @Query("DELETE FROM audio_upload_sessions WHERE job_id = :jobId")
    void delete(long jobId);
}
