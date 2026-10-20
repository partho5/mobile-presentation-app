package com.jovoc.facecampresentationrecorder.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ActivityEventDao {

    @Insert
    long insert(ActivityEvent event);

    /** Everything still waiting, whether or not it is already in a batch. */
    @Query("SELECT COUNT(*) FROM activity_events")
    int count();

    /** Drops the oldest rows when the buffer is full. */
    @Query("DELETE FROM activity_events WHERE id IN "
            + "(SELECT id FROM activity_events ORDER BY id ASC LIMIT :n)")
    void deleteOldest(int n);

    /** The batch that was started but not confirmed yet (the oldest one), or null. */
    @Query("SELECT batch_id FROM activity_events WHERE batch_id IS NOT NULL ORDER BY id ASC LIMIT 1")
    String openBatchId();

    /** Puts the oldest {@code n} rows that are in no batch into {@code batchId}. */
    @Query("UPDATE activity_events SET batch_id = :batchId WHERE id IN "
            + "(SELECT id FROM activity_events WHERE batch_id IS NULL ORDER BY id ASC LIMIT :n)")
    int assignBatch(String batchId, int n);

    @Query("SELECT * FROM activity_events WHERE batch_id = :batchId ORDER BY id ASC")
    List<ActivityEvent> inBatch(String batchId);

    @Query("DELETE FROM activity_events WHERE batch_id = :batchId")
    void deleteBatch(String batchId);
}
