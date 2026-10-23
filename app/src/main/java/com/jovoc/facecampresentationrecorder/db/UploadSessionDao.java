package com.jovoc.facecampresentationrecorder.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

@Dao
public interface UploadSessionDao extends UploadSessionStore {

    @Override
    @Query("SELECT * FROM upload_sessions WHERE job_id = :jobId")
    UploadSession get(long jobId);

    /** One row per job: a new upload replaces the old one. */
    @Override
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void put(UploadSession session);

    @Override
    @Query("UPDATE upload_sessions SET parts_json = :partsJson WHERE job_id = :jobId")
    void setParts(long jobId, String partsJson);

    @Override
    @Query("UPDATE upload_sessions SET status = :status WHERE job_id = :jobId")
    void setStatus(long jobId, String status);

    @Override
    @Query("DELETE FROM upload_sessions WHERE job_id = :jobId")
    void delete(long jobId);
}
