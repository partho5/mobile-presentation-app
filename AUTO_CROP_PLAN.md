# Auto-Crop — Final Implementation Plan + Technical Directions

> This document is the execution spec. Follow it step by step, in order.

---

## Step 1: Add FFmpegKit Dependency

**File:** [app/build.gradle](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/build.gradle)

**What to do:** Add this line inside the `dependencies {}` block (line ~58, after the CameraX lines):

```gradle
// FFmpegKit for post-recording video cropping
implementation 'com.arthenica:ffmpeg-kit-min:6.0-2'
```

**Then** add ProGuard keep rules.

**File:** `app/proguard-rules.pro` (find and append to it)

```
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }
```

**Verify:** Run `./gradlew app:dependencies` or just sync Gradle in Android Studio. FFmpegKit should resolve.

---

## Step 2: Add Auto-Crop Checkboxes to Record Settings Dialog

### 2A. Layout

**File:** [dialog_record_settings.xml](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/res/layout/dialog_record_settings.xml)

**Where:** After the `RadioGroup` with id `rg_audio` (line ~101, after its closing `</RadioGroup>`), and **before** the action buttons `LinearLayout` (line ~104).

**Insert this XML:**

```xml
<!-- Auto-Crop After Recording -->
<TextView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="After recording, auto-crop to:"
    android:textColor="#6200EE"
    android:textSize="14sp"
    android:textStyle="bold"
    android:layout_marginBottom="6dp" />

<CheckBox
    android:id="@+id/cb_crop_9_16"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="9:16 (vertical)"
    android:textSize="14sp" />

<CheckBox
    android:id="@+id/cb_crop_4_5"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="4:5 (portrait)"
    android:textSize="14sp" />

<CheckBox
    android:id="@+id/cb_crop_1_1"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="1:1 (square)"
    android:textSize="14sp"
    android:layout_marginBottom="20dp" />
```

### 2B. Java Logic

**File:** [MainActivity.java](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java)

**Add these constants** near the other `KEY_` constants (around line ~320):

```java
private static final String KEY_AUTOCROP_9_16 = "key_autocrop_9_16";
private static final String KEY_AUTOCROP_4_5 = "key_autocrop_4_5";
private static final String KEY_AUTOCROP_1_1 = "key_autocrop_1_1";
```

**Modify `openRecordSettingsDialog()`** (starts at [line 2169](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java#L2169)):

After the audio `RadioButton` setup (around line 2197), add:

```java
// Auto-crop checkboxes
CheckBox cbCrop916 = view.findViewById(R.id.cb_crop_9_16);
CheckBox cbCrop45 = view.findViewById(R.id.cb_crop_4_5);
CheckBox cbCrop11 = view.findViewById(R.id.cb_crop_1_1);

if (cbCrop916 != null) cbCrop916.setChecked(prefs.getBoolean(KEY_AUTOCROP_9_16, true));
if (cbCrop45 != null) cbCrop45.setChecked(prefs.getBoolean(KEY_AUTOCROP_4_5, false));
if (cbCrop11 != null) cbCrop11.setChecked(prefs.getBoolean(KEY_AUTOCROP_1_1, false));
```

In the `btnSave` click handler (around line 2207), before `prefs.edit()`, add these to the same editor:

```java
.putBoolean(KEY_AUTOCROP_9_16, cbCrop916 != null && cbCrop916.isChecked())
.putBoolean(KEY_AUTOCROP_4_5, cbCrop45 != null && cbCrop45.isChecked())
.putBoolean(KEY_AUTOCROP_1_1, cbCrop11 != null && cbCrop11.isChecked())
```

> **GOTCHA:** Chain these onto the existing `prefs.edit()` call that already saves countdown and audio. Don't create a separate editor.

---

## Step 3: Reduce Top Padding from 15% to Fixed 15px

**File:** [MainActivity.java](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java)

**Method:** `applyMediaTopMargin()` at [line 1218](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java#L1218)

**Replace the entire method body with:**

```java
private void applyMediaTopMargin(View view, int mediaHeight) {
    if (view == null) return;
    int topMargin = 15; // fixed 15px top padding for social media safe area

    ViewGroup.LayoutParams params = view.getLayoutParams();
    if (params instanceof RelativeLayout.LayoutParams) {
        RelativeLayout.LayoutParams relativeParams = (RelativeLayout.LayoutParams) params;
        if (relativeParams.topMargin != topMargin) {
            relativeParams.topMargin = topMargin;
            view.setLayoutParams(relativeParams);
        }
    }
}
```

> **NOTE:** The `mediaHeight` parameter is now unused but keep it in the signature — many callers pass it. Don't change the callers.

---

## Step 4: Add Guide Line Views to Layout

**File:** [activity_main.xml](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/res/layout/activity_main.xml)

**Where:** Inside `root_layout` (the `RelativeLayout`), **after** the camera wrapper `FrameLayout` (line ~195) and **before** the bottom nav `LinearLayout` (line ~198).

**Insert:**

```xml
<!-- Crop Guide Lines (visible only during pre-recording countdown, hidden before recording starts) -->
<View
    android:id="@+id/guide_line_9_16"
    android:layout_width="match_parent"
    android:layout_height="1dp"
    android:background="#33FFFFFF"
    android:elevation="20dp"
    android:visibility="gone" />

<TextView
    android:id="@+id/guide_label_9_16"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_alignParentEnd="true"
    android:elevation="20dp"
    android:paddingEnd="8dp"
    android:text="9:16"
    android:textColor="#55FFFFFF"
    android:textSize="10sp"
    android:visibility="gone" />

<View
    android:id="@+id/guide_line_4_5"
    android:layout_width="match_parent"
    android:layout_height="1dp"
    android:background="#33FFFFFF"
    android:elevation="20dp"
    android:visibility="gone" />

<TextView
    android:id="@+id/guide_label_4_5"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_alignParentEnd="true"
    android:elevation="20dp"
    android:paddingEnd="8dp"
    android:text="4:5"
    android:textColor="#55FFFFFF"
    android:textSize="10sp"
    android:visibility="gone" />

<View
    android:id="@+id/guide_line_1_1"
    android:layout_width="match_parent"
    android:layout_height="1dp"
    android:background="#33FFFFFF"
    android:elevation="20dp"
    android:visibility="gone" />

<TextView
    android:id="@+id/guide_label_1_1"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_alignParentEnd="true"
    android:elevation="20dp"
    android:paddingEnd="8dp"
    android:text="1:1"
    android:textColor="#55FFFFFF"
    android:textSize="10sp"
    android:visibility="gone" />
```

> **IMPORTANT:** These views use `android:elevation="20dp"` so they float above slide content but below the countdown overlay (`elevation="25dp"`) and the flash (`elevation="30dp"`).

> **POSITIONING NOTE:** These `View` elements are placed in the XML but their Y position is **set dynamically at runtime** via `view.setY(cropHeight)`. The XML `layout_alignParentTop` is NOT used — `setY()` overrides it. For the label TextViews, position them at `setY(cropHeight - labelHeight)` so the label sits just above its line.

---

## Step 5: Guide Lines + Face-Cam Clamp Logic in MainActivity

**File:** [MainActivity.java](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java)

### 5A. Declare guide line views as fields

Near the other UI field declarations (around line ~136):

```java
// Crop guide lines
private View guideLineView916, guideLineView45, guideLineView11;
private TextView guideLabel916, guideLabel45, guideLabel11;
```

### 5B. Find views in `initViews()`

At the end of `initViews()` (around line ~312):

```java
guideLineView916 = findViewById(R.id.guide_line_9_16);
guideLabel916 = findViewById(R.id.guide_label_9_16);
guideLineView45 = findViewById(R.id.guide_line_4_5);
guideLabel45 = findViewById(R.id.guide_label_4_5);
guideLineView11 = findViewById(R.id.guide_line_1_1);
guideLabel11 = findViewById(R.id.guide_label_1_1);
```

### 5C. Add `getSmallestCropHeight()` helper

Add this method anywhere in the class:

```java
/**
 * Returns the pixel height of the tightest (smallest) crop zone
 * based on currently selected auto-crop ratios in Settings.
 * If no ratio is selected, returns the full screen height (no constraint).
 */
private int getSmallestCropHeight() {
    SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
    int screenWidth = rootLayout.getWidth();
    int screenHeight = rootLayout.getHeight();
    if (screenWidth <= 0 || screenHeight <= 0) return screenHeight;

    int smallest = screenHeight;

    if (prefs.getBoolean(KEY_AUTOCROP_9_16, true)) {
        int h = screenWidth * 16 / 9;
        if (h < smallest) smallest = h;
    }
    if (prefs.getBoolean(KEY_AUTOCROP_4_5, false)) {
        int h = screenWidth * 5 / 4;
        if (h < smallest) smallest = h;
    }
    if (prefs.getBoolean(KEY_AUTOCROP_1_1, false)) {
        int h = screenWidth; // 1:1 means height = width
        if (h < smallest) smallest = h;
    }

    return Math.min(smallest, screenHeight);
}
```

> **MATH EXPLANATION:** For a 1080px wide screen:
> - 9:16 → `1080 * 16 / 9` = **1920px** height
> - 4:5 → `1080 * 5 / 4` = **1350px** height
> - 1:1 → `1080 * 1` = **1080px** height
>
> If 4:5 + 1:1 are both selected, `smallest = 1080` (1:1 wins, most restrictive).

### 5D. Add `getCropHeightForRatio()` helper

```java
/**
 * Returns the crop height in pixels for a given aspect ratio,
 * or -1 if that ratio is not selected or doesn't need cropping.
 */
private int getCropHeightForRatio(int ratioW, int ratioH) {
    int screenWidth = rootLayout.getWidth();
    int screenHeight = rootLayout.getHeight();
    if (screenWidth <= 0) return -1;

    int cropHeight = screenWidth * ratioH / ratioW;
    if (cropHeight >= screenHeight) return -1; // screen already fits, no crop
    return cropHeight;
}
```

### 5E. Add `showCropGuideLines()` and `hideCropGuideLines()`

```java
private void showCropGuideLines() {
    SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

    showOneGuideLine(guideLineView916, guideLabel916,
            prefs.getBoolean(KEY_AUTOCROP_9_16, true), 9, 16);
    showOneGuideLine(guideLineView45, guideLabel45,
            prefs.getBoolean(KEY_AUTOCROP_4_5, false), 4, 5);
    showOneGuideLine(guideLineView11, guideLabel11,
            prefs.getBoolean(KEY_AUTOCROP_1_1, false), 1, 1);
}

private void showOneGuideLine(View line, TextView label,
                               boolean isSelected, int ratioW, int ratioH) {
    if (line == null || label == null) return;

    if (!isSelected) {
        line.setVisibility(View.GONE);
        label.setVisibility(View.GONE);
        return;
    }

    int cropHeight = getCropHeightForRatio(ratioW, ratioH);
    if (cropHeight < 0) {
        // Screen already fits this ratio, no crop needed
        line.setVisibility(View.GONE);
        label.setVisibility(View.GONE);
        return;
    }

    line.setY(cropHeight);
    line.setVisibility(View.VISIBLE);

    // Position label just above the line
    label.post(() -> {
        label.setY(cropHeight - label.getHeight() - 4);
        label.setVisibility(View.VISIBLE);
    });
}

private void hideCropGuideLines() {
    if (guideLineView916 != null) guideLineView916.setVisibility(View.GONE);
    if (guideLabel916 != null) guideLabel916.setVisibility(View.GONE);
    if (guideLineView45 != null) guideLineView45.setVisibility(View.GONE);
    if (guideLabel45 != null) guideLabel45.setVisibility(View.GONE);
    if (guideLineView11 != null) guideLineView11.setVisibility(View.GONE);
    if (guideLabel11 != null) guideLabel11.setVisibility(View.GONE);
}
```

### 5F. Wire up show/hide timing

**SHOW guide lines when user taps Start Record (countdown begins):**

In `startTimedRecording()` at [line 2242](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java#L2242), add `showCropGuideLines();` at the beginning of the method (before the countdown UI shows).

For the **no-countdown path** (countdown == 0), in the `screenCaptureLauncher` result handler at [line 838](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java#L838):
- Call `showCropGuideLines();` before `triggerStartFlashEffect(...)`.
- Then inside `triggerStartFlashEffect`'s `onComplete` runnable, call `hideCropGuideLines();`.

> **CRITICAL TIMING:** For countdown > 0: show lines when countdown starts, hide them inside the countdown's `onFinish()` callback — specifically **before** `triggerStartFlashEffect()` runs. The flash effect (200ms white flash) provides a natural visual break, so the lines disappear → flash → clean recording starts. Perfect transition.

**In `startTimedRecording()` `onFinish()` callback** ([line 2269](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java#L2269)):

```java
@Override
public void onFinish() {
    if (countdownOverlayContainer != null) {
        countdownOverlayContainer.setVisibility(View.GONE);
    }
    hideCropGuideLines(); // <-- ADD THIS LINE
    triggerStartFlashEffect(() -> {
        // ... existing code ...
    });
}
```

**Also hide when countdown is cancelled** — in `cancelCountdown()` at [line 2284](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java#L2284):

```java
private void cancelCountdown() {
    // ... existing code ...
    hideCropGuideLines(); // <-- ADD THIS LINE
    Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show();
}
```

### 5G. Face-cam vertical clamp — ALWAYS active

**In `cameraCardContainer.setOnTouchListener`** `ACTION_MOVE` handler at [line 476](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java#L476):

Replace:
```java
newY = Math.max(0, Math.min(parentHeight - cameraRootWrapper.getHeight(), newY));
```

With:
```java
int maxYBound = parentHeight - cameraRootWrapper.getHeight();
int cropMaxY = getSmallestCropHeight() - cameraRootWrapper.getHeight();
if (cropMaxY < maxYBound) maxYBound = cropMaxY;
newY = Math.max(0, Math.min(maxYBound, newY));
```

**Also in `restoreCameraPositionOrCenter()`** at [line 367](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java#L367):

Where it does `float boundedY = Math.max(0, Math.min(parentHeight - wrapperHeight, posY));` — apply the same crop height clamp:

```java
int cropLimit = getSmallestCropHeight();
int maxYRestore = Math.min(parentHeight - wrapperHeight, cropLimit - wrapperHeight);
float boundedY = Math.max(0, Math.min(maxYRestore, posY));
```

> **WHY ALWAYS:** If user sets 4:5 in settings, then browses slides without recording, the cam should already be constrained. This trains their muscle memory. When they start recording, the cam is already in the safe zone.

---

## Step 6: Create `VideoCropHelper.java`

**File:** `app/src/main/java/com/jovoc/facecampresentationrecorder/util/VideoCropHelper.java` (NEW FILE)

```java
package com.jovoc.facecampresentationrecorder.util;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executors;

public class VideoCropHelper {

    private static final String TAG = "VideoCropHelper";

    public interface CropCallback {
        void onProgress(String ratioLabel, boolean success);
        void onAllComplete(int successCount, int failCount);
    }

    /**
     * Asynchronously crops a video to multiple aspect ratios using FFmpegKit.
     *
     * @param context       Application context
     * @param inputPath     Absolute path to the original recorded video
     * @param ratios        List of int[2] arrays, each {ratioWidth, ratioHeight}.
     *                      Example: {9,16} for 9:16, {4,5} for 4:5, {1,1} for 1:1
     * @param callback      Callback for progress and completion (called on main thread)
     */
    public static void cropAsync(Context context, String inputPath,
                                  List<int[]> ratios, CropCallback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());

        Executors.newSingleThreadExecutor().execute(() -> {
            int successCount = 0;
            int failCount = 0;

            // Step 1: Get video dimensions from the original file
            int videoWidth = 0;
            int videoHeight = 0;
            try {
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(inputPath);
                String w = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String h = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                retriever.release();
                if (w != null) videoWidth = Integer.parseInt(w);
                if (h != null) videoHeight = Integer.parseInt(h);
            } catch (Exception e) {
                Log.e(TAG, "Failed to read video dimensions", e);
                // If we can't even read dimensions, abort all crops
                final int fc = ratios.size();
                mainHandler.post(() -> {
                    if (callback != null) callback.onAllComplete(0, fc);
                });
                return;
            }

            if (videoWidth <= 0 || videoHeight <= 0) {
                final int fc = ratios.size();
                mainHandler.post(() -> {
                    if (callback != null) callback.onAllComplete(0, fc);
                });
                return;
            }

            // Step 2: Process each ratio sequentially
            for (int[] ratio : ratios) {
                int ratioW = ratio[0];
                int ratioH = ratio[1];

                // Calculate crop dimensions
                // Crop width = full video width (no horizontal crop)
                // Crop height = videoWidth * ratioH / ratioW
                int cropW = videoWidth;
                int cropH = videoWidth * ratioH / ratioW;

                // Ensure even numbers (required by H264 encoder)
                if (cropW % 2 != 0) cropW--;
                if (cropH % 2 != 0) cropH--;

                String ratioLabel = ratioW + "x" + ratioH;

                // If crop height >= video height, no crop needed for this ratio
                if (cropH >= videoHeight) {
                    Log.d(TAG, "Skipping " + ratioLabel +
                          ": crop height " + cropH +
                          " >= video height " + videoHeight);
                    successCount++;
                    final String label = ratioLabel;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onProgress(label, true);
                    });
                    continue;
                }

                // Build output file path
                // "recording.mp4" -> "recording_9x16.mp4"
                String outputPath = inputPath.replace(".mp4",
                        "_" + ratioLabel + ".mp4");

                // Build FFmpeg command
                // -y = overwrite if exists
                // -i = input
                // -vf crop=W:H:X:Y = crop filter (X=0, Y=0 = from top-left)
                // -c:a copy = copy audio stream without re-encoding
                String command = String.format(
                        "-y -i \"%s\" -vf \"crop=%d:%d:0:0\" -c:a copy \"%s\"",
                        inputPath, cropW, cropH, outputPath);

                Log.d(TAG, "FFmpeg command for " + ratioLabel + ": " + command);

                // Execute FFmpeg synchronously (we're already on background thread)
                FFmpegSession session = FFmpegKit.execute(command);

                if (ReturnCode.isSuccess(session.getReturnCode())) {
                    Log.d(TAG, "Crop " + ratioLabel + " succeeded: " + outputPath);
                    successCount++;

                    // Register with MediaStore so it appears in gallery
                    try {
                        File outFile = new File(outputPath);
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Video.Media.TITLE, outFile.getName());
                        values.put(MediaStore.Video.Media.DISPLAY_NAME, outFile.getName());
                        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                        values.put(MediaStore.Video.Media.DATA, outFile.getAbsolutePath());
                        context.getContentResolver().insert(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                    } catch (Exception e) {
                        Log.w(TAG, "MediaStore insert failed for " + ratioLabel, e);
                    }

                    // Scan with MediaScanner
                    MediaScannerConnection.scanFile(context,
                            new String[]{outputPath},
                            new String[]{"video/mp4"}, null);

                    final String label = ratioLabel;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onProgress(label, true);
                    });
                } else {
                    Log.e(TAG, "Crop " + ratioLabel + " FAILED. Return code: " +
                          session.getReturnCode() +
                          ", output: " + session.getOutput());
                    failCount++;

                    final String label = ratioLabel;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onProgress(label, false);
                    });
                }
            }

            // Step 3: Notify completion
            final int s = successCount;
            final int f = failCount;
            mainHandler.post(() -> {
                if (callback != null) callback.onAllComplete(s, f);
            });
        });
    }
}
```

> **GOTCHAS FOR THE AGENT:**
> - Import `com.arthenica.ffmpegkit.FFmpegKit`, `FFmpegSession`, `ReturnCode`.
> - The command uses escaped quotes around file paths (`\"%s\"`) because paths may contain spaces.
> - `FFmpegKit.execute()` is synchronous — that's intentional since we're on a background executor.
> - Always make crop dimensions even numbers (H264 requirement).
> - The `context` passed must be `getApplicationContext()` — NOT an Activity context — to avoid leaks.

---

## Step 7: Add Crop Status TextView to Video Player Dialog

### 7A. Layout

**File:** [dialog_video_player.xml](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/res/layout/dialog_video_player.xml)

**Where:** Find `tv_player_metadata` in the layout. Add this **immediately after** it:

```xml
<TextView
    android:id="@+id/tv_crop_status"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:textColor="#B0BEC5"
    android:textSize="13sp"
    android:paddingTop="6dp"
    android:visibility="gone" />
```

### 7B. VideoPlayerDialog.java

**File:** [VideoPlayerDialog.java](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/ui/VideoPlayerDialog.java)

**No functional changes needed.** The caller (MainActivity) will access the status view via `dialog.findViewById(R.id.tv_crop_status)` after `VideoPlayerDialog.show()` returns the `Dialog` object. The method already returns a `Dialog` — see [line 400](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/ui/VideoPlayerDialog.java#L400).

---

## Step 8: Trigger Auto-Crop After Recording Finishes

**File:** [MainActivity.java](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java)

**Where:** The `recordingFinishedReceiver` at [line 2315](file:///home/haku/projects/AndroidStudioProjects/FaceCam-Presentation-Recorder/app/src/main/java/com/jovoc/facecampresentationrecorder/MainActivity.java#L2315).

**Replace the entire `onReceive` body with:**

```java
@Override
public void onReceive(Context context, Intent intent) {
    if (intent != null && ScreenRecordService.ACTION_RECORDING_FINISHED.equals(intent.getAction())) {
        String path = intent.getStringExtra(ScreenRecordService.EXTRA_VIDEO_PATH);
        if (path != null) {
            File file = new File(path);
            if (file.exists() && file.length() > 0) {
                // Show video player dialog with original recording
                Dialog dialog = VideoPlayerDialog.show(MainActivity.this, file, null);

                // Check for auto-crop ratios
                SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                List<int[]> ratios = new ArrayList<>();
                if (prefs.getBoolean(KEY_AUTOCROP_9_16, true)) ratios.add(new int[]{9, 16});
                if (prefs.getBoolean(KEY_AUTOCROP_4_5, false)) ratios.add(new int[]{4, 5});
                if (prefs.getBoolean(KEY_AUTOCROP_1_1, false)) ratios.add(new int[]{1, 1});

                if (!ratios.isEmpty() && dialog != null) {
                    // Show crop status message
                    TextView tvCropStatus = dialog.findViewById(R.id.tv_crop_status);
                    if (tvCropStatus != null) {
                        tvCropStatus.setText("⏳ Auto-cropping as per your settings…");
                        tvCropStatus.setVisibility(View.VISIBLE);
                    }

                    // Kick off async crop (use applicationContext to avoid activity leak)
                    VideoCropHelper.cropAsync(
                        getApplicationContext(), path, ratios,
                        new VideoCropHelper.CropCallback() {
                            @Override
                            public void onProgress(String ratioLabel, boolean success) {
                                // Optional: update status per ratio
                            }

                            @Override
                            public void onAllComplete(int successCount, int failCount) {
                                if (tvCropStatus != null) {
                                    if (failCount == 0) {
                                        tvCropStatus.setText("✓ Cropped versions saved");
                                    } else {
                                        tvCropStatus.setText("⚠ " + failCount +
                                            " crop(s) failed, " + successCount + " saved");
                                    }
                                }
                            }
                        }
                    );
                }
            }
        }
    }
}
```

> **ADD IMPORT** at top of file: `import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;`
>
> **ADD IMPORT**: `import android.app.Dialog;` (if not already imported)
>
> **GOTCHA:** Pass `getApplicationContext()` (not `this` or `context`) to `VideoCropHelper.cropAsync()`. The broadcast receiver's `context` parameter is not the Activity.

---

## Execution Order Checklist

```
1. [ ] Step 1: build.gradle + proguard (Gradle sync)
2. [ ] Step 2: Settings dialog XML + Java (compile check)
3. [ ] Step 3: Reduce top padding (compile check)
4. [ ] Step 4: Guide line XML views (compile check)
5. [ ] Step 5: Guide line Java logic + cam clamp (compile check)
6. [ ] Step 6: VideoCropHelper.java (compile check)
7. [ ] Step 7: Video player status TextView (compile check)
8. [ ] Step 8: Recording finished receiver wiring (compile check)
9. [ ] Full build + test on device
```

---

## Common Pitfalls for the Agent

| Pitfall | How to Avoid |
|---|---|
| FFmpegKit import not found after adding to build.gradle | Make sure to Gradle sync. The import is `com.arthenica.ffmpegkit.FFmpegKit` |
| `getSmallestCropHeight()` returns 0 on first launch | Guard: `if (screenWidth <= 0 \|\| screenHeight <= 0) return screenHeight;` — the rootLayout may not be measured yet |
| Guide lines at wrong Y after rotation | Only calculate in immersive mode when `rootLayout` is fully measured. Use `rootLayout.post(() -> ...)` if needed |
| Crop produces 0-byte file | Check FFmpeg return code AND file size. Log `session.getOutput()` on failure |
| App crashes on `dialog.findViewById` after dialog dismissed | Check `dialog.isShowing()` before updating the status text |
| Even-number requirement for video dimensions | Always `cropW % 2 != 0 → cropW--` and same for height |
| Face-cam position not clamped on app restart | The clamp in `restoreCameraPositionOrCenter()` handles this |
| Multiple SharedPreferences editors | Chain all `.put*()` calls into a single `prefs.edit()...apply()` — don't create separate editors for crop prefs vs audio/countdown prefs |

---

## 🚫 WRONG DIRECTIONS — Do NOT Do These

This section exists because a less intelligent agent will likely try one of these. Every item below is a **wrong path**. Read all of them before writing any code.

---

### ❌ WRONG: Cropping DURING recording (changing VirtualDisplay or MediaRecorder dimensions)

The agent might think: *"Why not just record at the target aspect ratio directly? I'll change the VirtualDisplay width/height to match 9:16."*

**DO NOT** touch `ScreenRecordService.java`'s recording parameters. Do not change `mediaRecorder.setVideoSize()`. Do not change `VirtualDisplay` dimensions. The recording must remain full-screen. Cropping happens **AFTER** recording finishes, using FFmpegKit on the saved `.mp4` file.

**WHY:** Changing recording dimensions mid-pipeline breaks the screen mirror. The user would see a distorted or letterboxed live preview. Multiple ratios would require multiple simultaneous recordings — impossible.

---

### ❌ WRONG: Using a system overlay window (`WindowManager.addView()`) for guide lines

The agent might think: *"I'll use WindowManager to add an overlay so the guide lines float above everything."*

**DO NOT** use `WindowManager.addView()` or `TYPE_APPLICATION_OVERLAY` for guide lines. These system overlays are **excluded** from MediaProjection screen capture on Android 12+. But more importantly — the guide lines are hidden before recording starts anyway, so it doesn't matter. Just use regular `View` elements inside `activity_main.xml`'s `root_layout`.

---

### ❌ WRONG: Using Media3 Transformer, MediaCodec, MediaMuxer, or any library other than FFmpegKit

The user explicitly chose FFmpegKit for stability. **DO NOT** use:
- `androidx.media3:media3-transformer`
- `android.media.MediaCodec` + `MediaExtractor` + `MediaMuxer`
- Any OpenGL-based cropping
- Any bitmap-based frame-by-frame processing

**USE ONLY:** `com.arthenica:ffmpeg-kit-min:6.0-2` with `FFmpegKit.execute()`.

---

### ❌ WRONG: Creating a new Activity or Service for cropping

The agent might create a `CropActivity` or `CropService`.

**DO NOT.** Cropping is a simple background task handled by `VideoCropHelper.cropAsync()` running on `Executors.newSingleThreadExecutor()`. No new Activity. No new Service. No new entry in `AndroidManifest.xml`.

---

### ❌ WRONG: Re-encoding audio during crop

The agent might write an FFmpeg command without `-c:a copy`, causing audio to be re-encoded.

**ALWAYS** include `-c:a copy` in the FFmpeg command. This copies the audio stream bit-for-bit without re-encoding — it's faster and lossless. Only the video stream needs re-encoding for the crop.

---

### ❌ WRONG: Deleting or replacing the original video after cropping

The agent might think: *"The user only wants cropped versions, so I'll delete the original."*

**DO NOT** delete the original. The original full-screen recording is ALWAYS kept. Cropped versions are additional files alongside it. The user sees the original in the VideoPlayerDialog.

---

### ❌ WRONG: Clamping face-cam ONLY during recording

The agent might add the clamp check inside an `if (isRecording)` block.

**DO NOT** wrap the cam clamp in `if (isRecording)`. The clamp must be **ALWAYS active** whenever any crop ratio is selected in settings — even when the user is just browsing slides, not recording. This is so the cam is already in the safe zone before recording starts.

---

### ❌ WRONG: Showing guide lines DURING recording (leaving them visible)

The agent might call `showCropGuideLines()` when recording starts and leave them visible.

**DO NOT** leave guide lines visible during recording. The timing is:
1. Guide lines **appear** when the user taps Start Record (countdown begins).
2. Guide lines **disappear** the moment the actual recording starts (before the flash effect, inside `onFinish()` of the countdown timer).
3. During the actual recording, the screen is **clean** — no guide lines.

---

### ❌ WRONG: Using `dp` for the 15px top padding

The agent might write `topMargin = (int) (15 * density)` thinking 15 means dp.

**DO NOT** convert to dp. The value is literally **15 pixels**. Set `topMargin = 15;` directly. It's intentionally a very small, nearly imperceptible gap.

---

### ❌ WRONG: Using a percentage-based top padding formula

The agent might keep the old percentage logic or create a new one like `screenHeight * 0.05`.

**DO NOT** use any percentage. Replace the entire old formula with a flat `int topMargin = 15;`. The `mediaHeight` parameter in `applyMediaTopMargin()` is now unused — keep it in the method signature (callers still pass it) but ignore it in the body.

---

### ❌ WRONG: Creating a separate SharedPreferences file for crop settings

The agent might call `getSharedPreferences("crop_prefs", ...)`.

**DO NOT** create a new prefs file. Use the existing `"app_prefs"` (accessed via `PREF_NAME` constant) for all three new keys: `key_autocrop_9_16`, `key_autocrop_4_5`, `key_autocrop_1_1`.

---

### ❌ WRONG: Cropping horizontally (changing width)

The agent might calculate crop width based on the ratio and reduce it.

**DO NOT** change the crop width. The crop is **always full-width**. Only the height changes. The FFmpeg crop filter should always be `crop=<fullVideoWidth>:<calculatedHeight>:0:0`. The X offset is always 0. The Y offset is always 0. We crop from the top.

---

### ❌ WRONG: Running FFmpeg crops in parallel (multiple threads)

The agent might use a thread pool or `Executors.newFixedThreadPool(3)` to crop all ratios simultaneously.

**DO NOT.** Process ratios **sequentially** on a single background thread (`Executors.newSingleThreadExecutor()`). Parallel FFmpeg sessions can cause OOM, ANR, or file corruption on mobile devices.

---

### ❌ WRONG: Modifying `ScreenRecordService.java` for any reason

The agent might think it needs to add crop logic, change the broadcast, or modify the save path in the service.

**DO NOT** modify `ScreenRecordService.java`. It stays exactly as it is. The service records, saves the original, broadcasts `ACTION_RECORDING_FINISHED`, and stops. All crop logic lives in `MainActivity` (trigger) and `VideoCropHelper` (execution).

---

### ❌ WRONG: Showing cropped videos in the VideoPlayerDialog instead of the original

The agent might try to play the cropped version in the dialog once it's ready.

**DO NOT.** The VideoPlayerDialog always shows the **original** recording. The cropped versions are background outputs. The dialog just shows a text status update: *"⏳ Auto-cropping…"* → *"✓ Cropped versions saved"*. The user finds cropped videos in Saved Recordings or their gallery.

---

### ❌ WRONG: Adding guide lines as `ImageView` with a drawable, or as a `Canvas` draw call

The agent might create a custom drawable or override `onDraw()`.

**DO NOT.** Guide lines are plain `View` elements with `layout_height="1dp"` and `background="#33FFFFFF"`. Labels are plain `TextView` elements. Position them with `view.setY(cropHeight)` at runtime. No custom drawing needed.
