package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.db.AudioSessionStore;
import com.jovoc.facecampresentationrecorder.db.AudioUploadSessionDao;
import com.jovoc.facecampresentationrecorder.db.UploadSessionDao;
import com.jovoc.facecampresentationrecorder.db.UploadSessionStore;

import java.util.Locale;

/**
 * What a direct upload carries: the recording or the speech audio for AI captions. Decides the
 * {@code purpose} sent at create, the file type allowed and which session table holds the resume row.
 */
public enum UploadKind {
    /** The video: purpose "media" (the server default, so it is not sent), {@code upload_sessions}. */
    VIDEO(null, null),
    /** The speech WAV: purpose "transcript_audio", only ".wav", {@code audio_upload_sessions}. */
    AUDIO("transcript_audio", ".wav");

    /** Sent as {@code purpose} at create; null = not sent (server default "media"). */
    public final String purpose;
    /** The only extension allowed for this kind, or null for the video's own rules. */
    private final String onlyExtension;

    UploadKind(String purpose, String onlyExtension) {
        this.purpose = purpose;
        this.onlyExtension = onlyExtension;
    }

    /** True when a file of this name may be uploaded as this kind (".wav" for audio only). */
    public boolean allows(String filename) {
        if (filename == null || filename.isEmpty()) return false;
        boolean wav = filename.toLowerCase(Locale.ROOT).endsWith(".wav");
        return onlyExtension != null ? wav : !wav;
    }

    /** The session store of this kind. */
    public UploadSessionStore store(UploadSessionDao video, AudioUploadSessionDao audio) {
        return this == AUDIO ? new AudioSessionStore(audio) : video;
    }
}
