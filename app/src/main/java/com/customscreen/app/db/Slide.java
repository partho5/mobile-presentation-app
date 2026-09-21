package com.customscreen.app.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "slides")
public class Slide {

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_IMAGE = "IMAGE";

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "type")
    private String type; // "TEXT" or "IMAGE"

    @ColumnInfo(name = "order_index")
    private int orderIndex;

    @ColumnInfo(name = "text_content")
    private String textContent;

    @ColumnInfo(name = "image_path")
    private String imagePath;

    public Slide() {
    }

    @Ignore
    public Slide(String type, int orderIndex, String textContent, String imagePath) {
        this.type = type;
        this.orderIndex = orderIndex;
        this.textContent = textContent;
        this.imagePath = imagePath;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public void setOrderIndex(int orderIndex) {
        this.orderIndex = orderIndex;
    }

    public String getTextContent() {
        return textContent;
    }

    public void setTextContent(String textContent) {
        this.textContent = textContent;
    }

    public String getImagePath() {
        return imagePath;
    }

    public void setImagePath(String imagePath) {
        this.imagePath = imagePath;
    }
}
