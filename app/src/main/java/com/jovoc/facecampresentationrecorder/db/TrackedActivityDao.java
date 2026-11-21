package com.jovoc.facecampresentationrecorder.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface TrackedActivityDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(TrackedActivity row);

    /** Everything still waiting, whether or not it is already in a batch. */
    @Query("SELECT COUNT(*) FROM tracked_activities")
    int count();

    /** Drops the oldest rows that are in no batch, when the buffer is full. */
    @Query("DELETE FROM tracked_activities WHERE id IN "
            + "(SELECT id FROM tracked_activities WHERE batch_id IS NULL ORDER BY created_at LIMIT :n)")
    int deleteOldest(int n);

    /** The batch that was started but not confirmed yet, or null. */
    @Query("SELECT batch_id FROM tracked_activities WHERE batch_id IS NOT NULL LIMIT 1")
    String openBatchId();

    /** Puts the oldest {@code n} rows that are in no batch into {@code batchId}. */
    @Query("UPDATE tracked_activities SET batch_id = :batchId WHERE id IN "
            + "(SELECT id FROM tracked_activities WHERE batch_id IS NULL ORDER BY created_at LIMIT :n)")
    int assignBatch(String batchId, int n);

    @Query("SELECT * FROM tracked_activities WHERE batch_id = :batchId ORDER BY created_at")
    List<TrackedActivity> inBatch(String batchId);

    @Query("DELETE FROM tracked_activities WHERE batch_id = :batchId")
    int deleteBatch(String batchId);
}
