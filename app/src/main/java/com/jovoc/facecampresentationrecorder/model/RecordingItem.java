package com.jovoc.facecampresentationrecorder.model;

import java.io.File;

public class RecordingItem {
    private File file;
    private String fileName;
    private String filePath;
    private long durationMs;
    private String formattedDuration;
    private String formattedDate;
    private String formatTag;
    private boolean selected;

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

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }
}
