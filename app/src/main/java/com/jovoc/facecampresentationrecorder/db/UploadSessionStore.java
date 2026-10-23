package com.jovoc.facecampresentationrecorder.db;

/**
 * Where one kind of direct upload keeps its resume row (one row per publish job). Video uploads use
 * {@link UploadSessionDao} ({@code upload_sessions}); the speech audio uses {@link AudioSessionStore}
 * ({@code audio_upload_sessions}), so the two never overwrite each other's row.
 */
public interface UploadSessionStore {

    UploadSession get(long jobId);

    /** One row per job: a new upload replaces the old one. */
    void put(UploadSession session);

    void setParts(long jobId, String partsJson);

    void setStatus(long jobId, String status);

    void delete(long jobId);
}
