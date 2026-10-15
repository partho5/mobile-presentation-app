package com.jovoc.facecampresentationrecorder.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface UploadSessionDao {

    @Query("SELECT * FROM upload_sessions WHERE job_id = :jobId")
    UploadSession get(long jobId);

    /** One row per job: a new upload replaces the old one. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void put(UploadSession session);

    @Query("UPDATE upload_sessions SET parts_json = :partsJson WHERE job_id = :jobId")
    void setParts(long jobId, String partsJson);

    @Query("UPDATE upload_sessions SET status = :status WHERE job_id = :jobId")
    void setStatus(long jobId, String status);

    @Query("DELETE FROM upload_sessions WHERE job_id = :jobId")
    void delete(long jobId);
}
