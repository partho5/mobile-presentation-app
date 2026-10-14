package com.jovoc.facecampresentationrecorder.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PublishJobDao {

    @Insert
    long insert(PublishJob job);

    /** No-op when the row was deleted meanwhile (a cancelled job), which is what callers want. */
    @Update
    void update(PublishJob job);

    @Query("SELECT * FROM publish_jobs WHERE id = :id")
    PublishJob get(long id);

    /** Newest first, so the first row seen for a file is its current state. */
    @Query("SELECT * FROM publish_jobs ORDER BY id DESC")
    List<PublishJob> allNewestFirst();

    @Query("SELECT * FROM publish_jobs WHERE file_path = :path ORDER BY id DESC LIMIT 1")
    PublishJob latestFor(String path);

    @Query("SELECT * FROM publish_jobs WHERE status IN ('QUEUED', 'UPLOADING', 'PUBLISHING')")
    List<PublishJob> active();

    /** Auto-publish jobs still waiting for their recording's post-processing. */
    @Query("SELECT * FROM publish_jobs WHERE status = 'WAITING_PROCESSING' ORDER BY id")
    List<PublishJob> waitingProcessing();

    /** Accepted jobs whose posts have not been seen in a final state yet (the caller applies the 7-day window). */
    @Query("SELECT * FROM publish_jobs WHERE status = 'DONE' AND remote_state IS NULL "
            + "AND post_ids IS NOT NULL AND post_ids != '' ORDER BY id DESC")
    List<PublishJob> doneAwaitingRemote();

    /**
     * Stores what the server said. Matches on the post ids too, so a job that was retried (new
     * posts) meanwhile is not overwritten with the old posts' result.
     */
    @Query("UPDATE publish_jobs SET remote_state = :state, remote_channel = :channel, "
            + "remote_error = :error, remote_url = :url WHERE id = :id AND status = 'DONE' "
            + "AND post_ids = :postIds")
    int setRemote(long id, String postIds, String state, String channel, String error, String url);

    @Query("DELETE FROM publish_jobs WHERE id = :id")
    void deleteById(long id);

    /** Pins jobs made under the server's default workspace to it before the user picks another. */
    @Query("UPDATE publish_jobs SET workspace_id = :workspaceId WHERE workspace_id IS NULL")
    void pinUnpinned(String workspaceId);

    @Query("UPDATE publish_jobs SET file_path = :newPath, file_name = :newName WHERE file_path = :oldPath")
    void rename(String oldPath, String newPath, String newName);
}
