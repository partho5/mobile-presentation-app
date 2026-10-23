package com.jovoc.facecampresentationrecorder.db;

/** {@link UploadSessionStore} over the audio table: adapts {@link AudioUploadSessionDao}. */
public final class AudioSessionStore implements UploadSessionStore {

    private final AudioUploadSessionDao dao;

    public AudioSessionStore(AudioUploadSessionDao dao) {
        this.dao = dao;
    }

    @Override
    public UploadSession get(long jobId) {
        AudioUploadSession a = dao.get(jobId);
        return a == null ? null : a.toSession();
    }

    @Override
    public void put(UploadSession session) {
        dao.put(AudioUploadSession.from(session));
    }

    @Override
    public void setParts(long jobId, String partsJson) {
        dao.setParts(jobId, partsJson);
    }

    @Override
    public void setStatus(long jobId, String status) {
        dao.setStatus(jobId, status);
    }

    @Override
    public void delete(long jobId) {
        dao.delete(jobId);
    }
}
