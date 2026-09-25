package com.jovoc.facecampresentationrecorder.model;

import java.io.File;

public class RecordingItem {

    /** What the card is allowed to do, and what it should say it is. */
    public enum Status {
        /** A complete, playable file. */
        READY,
        /** An auto-crop that FFmpeg is still encoding; no file on disk yet. */
        PROCESSING,
        /** The file exists but has no readable duration, so it cannot be played. */
        UNAVAILABLE
    }

    private File file;
    private String fileName;
    private String filePath;
    private long durationMs;
    private String formattedDuration;
    private String formattedDate;
    private String formatTag;
    private boolean selected;
    private Status status = Status.READY;
    private String statusLabel;

    public RecordingItem(File file, String fileName, String filePath, long durationMs,
                         String formattedDuration, String formattedDate, String formatTag) {
        this.file = file;
        this.fileName = fileName;
        this.filePath = filePath;
        this.durationMs = durationMs;
        this.formattedDuration = formattedDuration;
        this.formattedDate = formattedDate;
        this.formatTag = formatTag;
    }

    /**
     * Placeholder for an auto-crop that has not finished encoding. It stands in for
     * a file that does not exist yet, so the user can see the crop is coming instead
     * of meeting an unplayable 00:00 card.
     */
    public static RecordingItem processing(String fileName, String filePath,
                                           String formattedDate, String formatTag,
                                           String statusLabel) {
        RecordingItem item = new RecordingItem(
                new File(filePath), fileName, filePath, 0, "--:--", formattedDate, formatTag);
        item.status = Status.PROCESSING;
        item.statusLabel = statusLabel;
        return item;
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
        if (file != null) {
            this.fileName = file.getName();
            this.filePath = file.getAbsolutePath();
        }
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getFormattedDuration() {
        return formattedDuration;
    }

    public String getFormattedDate() {
        return formattedDate;
    }

    /** Card tag describing the recording's aspect ratio, e.g. "Original" or "9:16". */
    public String getFormatTag() {
        return formatTag;
    }

    public void setFormatTag(String formatTag) {
        this.formatTag = formatTag;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    /** Short line shown in place of the recording date while not READY. */
    public String getStatusLabel() {
        return statusLabel;
    }

    public void setStatusLabel(String statusLabel) {
        this.statusLabel = statusLabel;
    }

    /** True when the file is complete and safe to play, share, rename or select. */
    public boolean isReady() {
        return status == Status.READY;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }
}
