package com.jovoc.facecampresentationrecorder.model;

import java.io.File;

public class RecordingItem {
    private File file;
    private String fileName;
    private String filePath;
    private long durationMs;
    private String formattedDuration;
    private String formattedDate;

    public RecordingItem(File file, String fileName, String filePath, long durationMs, String formattedDuration, String formattedDate) {
        this.file = file;
        this.fileName = fileName;
        this.filePath = filePath;
        this.durationMs = durationMs;
        this.formattedDuration = formattedDuration;
        this.formattedDate = formattedDate;
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
}
