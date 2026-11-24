package com.jovoc.facecampresentationrecorder.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

@Entity(tableName = "slides")
public class Slide {

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_VIDEO = "VIDEO";
    public static final String TYPE_WEBSITE = "WEBSITE";
    public static final String TYPE_YOUTUBE = "YOUTUBE";

    /** Prefix of a set id that holds the topics of one content-plan day. */
    public static final String PLAN_SET_PREFIX = "plan:";

    /** The set id of one plan day's topics. A null set id means the user's own topics. */
    public static String planSetId(String dayId) {
        return PLAN_SET_PREFIX + dayId;
    }

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

    @ColumnInfo(name = "is_disabled")
    private boolean disabled;

    /** Null = the user's own topics; "plan:&lt;dayId&gt;" = the topics of one plan day. */
    @ColumnInfo(name = "set_id")
    private String setId;

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

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public String getSetId() {
        return setId;
    }

    public void setSetId(String setId) {
        this.setId = setId;
    }
}
