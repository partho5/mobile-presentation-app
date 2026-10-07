package com.jovoc.facecampresentationrecorder;

import android.Manifest;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import com.jovoc.facecampresentationrecorder.ui.DrawingBoardController;
import com.jovoc.facecampresentationrecorder.ui.VideoPlayerDialog;
import com.jovoc.facecampresentationrecorder.util.VideoCropHelper;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.widget.CheckBox;
import java.util.ArrayList;
import java.util.List;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.content.SharedPreferences;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import android.widget.MediaController;
import android.widget.RelativeLayout;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.graphics.Color;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.Observer;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.jovoc.facecampresentationrecorder.adapter.SlideAdapter;
import com.jovoc.facecampresentationrecorder.db.Slide;
import com.jovoc.facecampresentationrecorder.db.SlideRepository;
import com.jovoc.facecampresentationrecorder.service.ScreenRecordService;
import com.jovoc.facecampresentationrecorder.util.ImageStorageHelper;
import com.jovoc.facecampresentationrecorder.util.SlideLogger;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.navigation.NavigationView;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SlideAdapter.SlideActionListener {

    private static final String TAG = "MainActivity";

    private SlideRepository repository;
    private List<Slide> slides = new ArrayList<>();
    private int currentSlideIndex = 0;

    // UI Components
    private View rootLayout;
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private ImageView imageSlideView;
    private FrameLayout textSlideContainer;
    private TextView textSlideView;
    private FrameLayout videoSlideContainer;
    private VideoView videoSlideView;
    private FrameLayout webSlideContainer;
    private WebView webSlideView;
    private SwipeRefreshLayout swipeRefreshWebSlide;
    private ImageButton btnReloadWebSlide;
    private MediaController mediaController;
    private TextView emptyStateView;
    private TextView toolbarTitle;
    private LinearLayout topMenuBar;
    private LinearLayout bottomNavContainer;
    private FrameLayout zonePrev;
    private FrameLayout zoneNext;
    private ImageButton btnPrev;
    private ImageButton btnNext;
    private Button btnRecord;
    private ImageButton btnStopRecordFloating;
    private ImageView ivStopArrowHint;

    // Drawing board (shown only while recording)
    private ImageButton btnDrawingBoard;
    private View drawingBoardContainer;
    private DrawingBoardController drawingBoardController;
    private boolean boardTouchActive = false;
    private boolean isDrawingBoardVisible = false;
    private boolean isDrawingBoardAnimating = false;
    private static final long DRAWING_BOARD_ANIM_MS = 250L;

    // First-run swipe tutorial
    private View swipeHintContainer;
    private ImageView ivSwipeHintHand;
    private TextView tvSwipeHintLabel;
    private ValueAnimator swipeHintAnimator;
    private boolean swipeHintShowingNext;
    private final Runnable swipeHintPositionRunnable = this::positionAndStartSwipeHint;

    // Countdown & Start Flash Components
    private View countdownOverlayContainer;
    private TextView tvCountdownNumber;
    private Button btnCancelCountdown;
    private View flashOverlayView;
    private CountDownTimer countDownTimer;

    // Crop guide lines
    private View guideLineView916, guideLineView45, guideLineView11;
    private TextView guideLabel916, guideLabel45, guideLabel11;

    /**
     * Transient collision feedback for the crop guide lines.
     *
     * The camera may be dragged anywhere on screen, so a line is flashed for
     * {@link #GUIDE_FLASH_DURATION_MS} the moment the camera starts overlapping it.
     * The flash is edge-triggered: parking the camera on a line shows it once, not
     * continuously. Index order is {9:16, 4:5, 1:1}.
     */
    private static final long GUIDE_FLASH_DURATION_MS = 300L;
    private static final int GUIDE_9_16 = 0, GUIDE_4_5 = 1, GUIDE_1_1 = 2;
    private final boolean[] guideOverlapState = new boolean[3];
    private final Runnable[] guideFlashHideRunnables = new Runnable[3];
    private final Handler guideFlashHandler = new Handler(Looper.getMainLooper());

    // Draggable & Resizable Camera Components
    private FrameLayout cameraRootWrapper;
    private MaterialCardView cameraCardContainer;
    private PreviewView cameraPreviewView;
    private ImageView btnResizeHandle;
    private ImageView btnFlipCamera;
    private ImageView ivCameraFreezeFrame;
    private TextView tvCameraPermissionHint;
    private boolean flipAvailable = true;
    private Observer<PreviewView.StreamState> flipStreamObserver;
    private final Runnable flipFreezeTimeoutRunnable = this::hideFreezeFrame;

    private ScaleGestureDetector scaleGestureDetector;
    private float dX, dY;
    private float initialResizeTouchX, initialResizeTouchY;
    private int initialCardWidth;

    private final Handler hideHandleHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideHandleRunnable = () -> {
        if (btnResizeHandle != null) {
            btnResizeHandle.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction(() -> btnResizeHandle.setVisibility(View.GONE))
                    .start();
        }
        if (btnFlipCamera != null) {
            btnFlipCamera.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction(() -> btnFlipCamera.setVisibility(View.GONE))
                    .start();
        }
    };

    private GestureDetector gestureDetector;
    private boolean isMenuBarVisible = false;

    /** Height of the top menu bar as declared in XML, before any status-bar inset. */
    private int topMenuBarBaseHeight = -1;
    private boolean isRecording = false;
    private boolean overlayRequested = false;
    private boolean leftAppWithoutOverlayPermission = false;

    // Launchers
    private ActivityResultLauncher<PickVisualMediaRequest> photoPickerLauncher;
    private ActivityResultLauncher<PickVisualMediaRequest> updateImagePickerLauncher;
    private ActivityResultLauncher<PickVisualMediaRequest> videoPickerLauncher;
    private ActivityResultLauncher<PickVisualMediaRequest> updateVideoPickerLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;
    /** Single-permission requests started from the Settings dialog. */
    private ActivityResultLauncher<String> settingsPermissionLauncher;
    private String pendingSettingsPermission;
    /** Rebuilds the Settings dialog's permission rows; non-null only while that dialog is open. */
    private Runnable refreshPermissionRows;
    private ActivityResultLauncher<Intent> screenCaptureLauncher;

    private Slide slideToUpdateImage = null;
    private Slide slideToUpdateVideo = null;
    private int targetSlidePosition = -1;

    private SlideAdapter slideAdapter;
    private RecyclerView recyclerSlides;
    private BottomSheetDialog managerDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SlideLogger.init(this);
        SlideLogger.log("LIFECYCLE", "MainActivity onCreate started");

        // Edge-to-edge on every API level, not just 15+. Without it the content view
        // is physically inset by the visible system bars, so hiding them for
        // presentation/recording mode re-lays out root_layout: the face cam jumps and
        // the crop guide lines stop lining up with the recorded frame. Laid out
        // full-bleed, root_layout's pixels are the same pixels MediaProjection
        // captures, and bar visibility changes nothing.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        setContentView(R.layout.activity_main);

        repository = new SlideRepository(this);

        initViews();
        applySystemBarInsets();
        setupDrawer();
        setupGestureDetector();
        setupPhotoPicker();
        setupTop30PercentLayout();
        setupBottom40PercentLayout();
        setupSlideZoomTouchListeners();

        setupDraggableCameraContainer();
        setupRecordButton();
        setupPermissionsAndCamera();

        // Track app opened count (first launch also arms the swipe tutorial)
        SharedPreferences launchPrefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        if (launchPrefs.getInt(KEY_APP_OPENED_TIMES, 0) == 0) {
            launchPrefs.edit().putBoolean(KEY_SWIPE_TUTORIAL_ACTIVE, true).apply();
        }
        incrementAppOpenedTimes();

        // Start directly in Presentation Mode (system bars hidden)
        setPresentationMode(true);

        // Load slides from DB
        loadSlidesFromDb();

        // Register post-recording completion receiver to launch VideoPlayerDialog
        registerRecordingFinishedReceiver();

        if (rootLayout != null) {
            rootLayout.post(this::showCropGuideLines);
        }
    }

    private void initViews() {
        rootLayout = findViewById(R.id.root_layout);
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);

        imageSlideView = findViewById(R.id.image_slide_view);
        textSlideContainer = findViewById(R.id.text_slide_container);
        textSlideView = findViewById(R.id.text_slide_view);
        videoSlideContainer = findViewById(R.id.video_slide_container);
        videoSlideView = findViewById(R.id.video_slide_view);
        webSlideContainer = findViewById(R.id.web_slide_container);
        webSlideView = findViewById(R.id.web_slide_view);
        swipeRefreshWebSlide = findViewById(R.id.swipe_refresh_web_slide);
        btnReloadWebSlide = findViewById(R.id.btn_reload_web_slide);

        if (btnReloadWebSlide != null) {
            btnReloadWebSlide.setVisibility(View.GONE);
        }

        if (swipeRefreshWebSlide != null) {
            swipeRefreshWebSlide.setOnRefreshListener(() -> {
                if (webSlideView != null) {
                    webSlideView.reload();
                    Toast.makeText(this, "Reloading page...", Toast.LENGTH_SHORT).show();
                } else if (swipeRefreshWebSlide != null) {
                    swipeRefreshWebSlide.setRefreshing(false);
                }
            });
        }

        if (webSlideView != null) {
            WebSettings settings = webSlideView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setDatabaseEnabled(true);
            settings.setCacheMode(WebSettings.LOAD_DEFAULT);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setAllowFileAccess(true);
            settings.setAllowContentAccess(true);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            webSlideView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return false;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (swipeRefreshWebSlide != null) {
                        swipeRefreshWebSlide.setRefreshing(false);
                    }
                }

                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    super.onReceivedError(view, errorCode, description, failingUrl);
                    if (swipeRefreshWebSlide != null) {
                        swipeRefreshWebSlide.setRefreshing(false);
                    }
                }
            });
            webSlideView.setWebChromeClient(new WebChromeClient());
        }
        emptyStateView = findViewById(R.id.empty_state_view);
        toolbarTitle = findViewById(R.id.toolbar_title);
        topMenuBar = findViewById(R.id.top_menu_bar);
        bottomNavContainer = findViewById(R.id.bottom_nav_container);
        zonePrev = findViewById(R.id.zone_previous);
        zoneNext = findViewById(R.id.zone_next);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);

        countdownOverlayContainer = findViewById(R.id.countdown_overlay_container);
        tvCountdownNumber = findViewById(R.id.tv_countdown_number);
        btnCancelCountdown = findViewById(R.id.btn_cancel_countdown);
        flashOverlayView = findViewById(R.id.flash_overlay_view);
        ivStopArrowHint = findViewById(R.id.iv_stop_arrow_hint);
        swipeHintContainer = findViewById(R.id.swipe_hint_container);
        ivSwipeHintHand = findViewById(R.id.iv_swipe_hint_hand);
        tvSwipeHintLabel = findViewById(R.id.tv_swipe_hint_label);

        if (btnCancelCountdown != null) {
            btnCancelCountdown.setOnClickListener(v -> cancelCountdown());
        }

        Button btnSlideManager = findViewById(R.id.btn_slide_manager);
        btnSlideManager.setOnClickListener(v -> openSlideManagerDialog());

        zonePrev.setOnClickListener(v -> goToPreviousSlide());
        zoneNext.setOnClickListener(v -> goToNextSlide());

        guideLineView916 = findViewById(R.id.guide_line_9_16);
        guideLabel916 = findViewById(R.id.guide_label_9_16);
        guideLineView45 = findViewById(R.id.guide_line_4_5);
        guideLabel45 = findViewById(R.id.guide_label_4_5);
        guideLineView11 = findViewById(R.id.guide_line_1_1);
        guideLabel11 = findViewById(R.id.guide_label_1_1);
    }

    private static final String PREF_NAME = "app_prefs";
    private static final String KEY_CAM_SIZE = "key_cam_size";
    private static final String KEY_CAM_POS_X = "key_cam_pos_x";
    private static final String KEY_CAM_POS_Y = "key_cam_pos_y";
    private static final String KEY_CAM_LENS_FACING = "key_cam_lens_facing";
    private static final String KEY_COUNTDOWN_SECONDS = "key_countdown_seconds";
    private static final String KEY_RECORD_AUDIO = "key_record_audio";
    private static final String KEY_AUTOCROP_9_16 = "key_autocrop_9_16";
    private static final String KEY_AUTOCROP_4_5 = "key_autocrop_4_5";
    private static final String KEY_AUTOCROP_1_1 = "key_autocrop_1_1";

    private void saveCameraState() {
        if (cameraCardContainer == null || cameraRootWrapper == null) return;
        int size = cameraCardContainer.getWidth();
        float posX = cameraRootWrapper.getX();
        float posY = cameraRootWrapper.getY();
        if (size > 0) {
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putInt(KEY_CAM_SIZE, size)
                    .putFloat(KEY_CAM_POS_X, posX)
                    .putFloat(KEY_CAM_POS_Y, posY)
                    .apply();
        }
    }

    private void updateResizeHandlePosition(int cardSize) {
        if (btnResizeHandle == null) return;
        int handleSize = (int) (40 * getResources().getDisplayMetrics().density);
        if (btnResizeHandle.getWidth() > 0) {
            handleSize = btnResizeHandle.getWidth();
        }
        // Center of circle is at (cardSize / 2, cardSize / 2).
        // 45-degree angle on circle border is at (cardSize * 0.85355f, cardSize * 0.85355f).
        float borderPos = cardSize * 0.85355f;
        btnResizeHandle.setTranslationX(borderPos - (handleSize / 2f));
        btnResizeHandle.setTranslationY(borderPos - (handleSize / 2f));

        if (btnFlipCamera != null) {
            // Mirror of the resize handle: bottom-left 45 degrees on the circle border.
            float flipSize = btnFlipCamera.getWidth() > 0 ? btnFlipCamera.getWidth() : handleSize;
            btnFlipCamera.setTranslationX(cardSize * 0.14645f - (flipSize / 2f));
            btnFlipCamera.setTranslationY(borderPos - (flipSize / 2f));
        }
    }

    private void restoreCameraState() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int version = prefs.getInt("key_cam_size_v2", 0);
        if (version < 1) {
            // Upgrade preference version: clear legacy saved camera size so the new 240dp default size is applied
            prefs.edit().remove(KEY_CAM_SIZE).putInt("key_cam_size_v2", 1).apply();
        }
        int savedSize = prefs.getInt(KEY_CAM_SIZE, -1);
        if (savedSize > 0 && cameraCardContainer != null) {
            ViewGroup.LayoutParams params = cameraCardContainer.getLayoutParams();
            params.width = savedSize;
            params.height = savedSize;
            cameraCardContainer.setLayoutParams(params);
            cameraCardContainer.setRadius(savedSize / 2f);
            updateResizeHandlePosition(savedSize);
        }
    }

    private void restoreCameraPositionOrCenter() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        float posX = prefs.getFloat(KEY_CAM_POS_X, -1f);
        float posY = prefs.getFloat(KEY_CAM_POS_Y, -1f);

        int parentWidth = rootLayout.getWidth();
        int parentHeight = rootLayout.getHeight();
        int wrapperWidth = cameraRootWrapper.getWidth();
        int wrapperHeight = cameraRootWrapper.getHeight();

        if (posX >= 0 && posY >= 0 && parentWidth > 0 && parentHeight > 0) {
            // Only screen bounds constrain the camera; crop zones no longer restrict it.
            float boundedX = Math.max(0, Math.min(parentWidth - wrapperWidth, posX));
            float boundedY = Math.max(0, Math.min(parentHeight - wrapperHeight, posY));
            cameraRootWrapper.setX(boundedX);
            cameraRootWrapper.setY(boundedY);
        } else {
            centerCameraContainer();
        }
        if (cameraCardContainer != null) {
            updateResizeHandlePosition(cameraCardContainer.getWidth());
        }
    }

    /**
     * Returns the crop height in pixels for a given aspect ratio,
     * or -1 if that ratio is not selected or doesn't need cropping.
     */
    private int getCropHeightForRatio(int ratioW, int ratioH) {
        if (rootLayout == null) return -1;
        int screenWidth = rootLayout.getWidth();
        int screenHeight = rootLayout.getHeight();
        if (screenWidth <= 0 || screenHeight <= 0) return -1;

        int cropHeight = screenWidth * ratioH / ratioW;
        if (cropHeight > screenHeight) return -1; // screen height is shorter than target ratio
        return cropHeight;
    }

    private void showCropGuideLines() {
        if (isRecording) {
            hideCropGuideLines();
            return;
        }
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
            line.setVisibility(View.GONE);
            label.setVisibility(View.GONE);
            return;
        }

        int screenHeight = rootLayout.getHeight();
        int lineY = Math.min(cropHeight, screenHeight - 2);

        line.setY(lineY);
        line.bringToFront();
        line.setVisibility(View.VISIBLE);

        // Position label just above the line
        label.post(() -> {
            int labelY = Math.max(0, lineY - label.getHeight() - 4);
            label.setY(labelY);
            label.bringToFront();
            label.setVisibility(View.VISIBLE);
        });
    }

    private void hideCropGuideLines() {
        cancelGuideLineFlashes();
        if (guideLineView916 != null) guideLineView916.setVisibility(View.GONE);
        if (guideLabel916 != null) guideLabel916.setVisibility(View.GONE);
        if (guideLineView45 != null) guideLineView45.setVisibility(View.GONE);
        if (guideLabel45 != null) guideLabel45.setVisibility(View.GONE);
        if (guideLineView11 != null) guideLineView11.setVisibility(View.GONE);
        if (guideLabel11 != null) guideLabel11.setVisibility(View.GONE);
    }

    /**
     * Flashes any crop guide line the camera has just started overlapping.
     *
     * Only runs while recording — outside recording the lines are already
     * permanently visible, so there is nothing to reveal. Safe to call on every
     * drag/resize frame: it does nothing unless an overlap state actually changes.
     */
    private void flashOverlappedGuideLines() {
        if (!isRecording || cameraRootWrapper == null || rootLayout == null) {
            // Reset so the first overlap after recording starts always flashes.
            guideOverlapState[GUIDE_9_16] = false;
            guideOverlapState[GUIDE_4_5] = false;
            guideOverlapState[GUIDE_1_1] = false;
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

        checkGuideLineOverlap(GUIDE_9_16, guideLineView916, guideLabel916,
                prefs.getBoolean(KEY_AUTOCROP_9_16, true), 9, 16);
        checkGuideLineOverlap(GUIDE_4_5, guideLineView45, guideLabel45,
                prefs.getBoolean(KEY_AUTOCROP_4_5, false), 4, 5);
        checkGuideLineOverlap(GUIDE_1_1, guideLineView11, guideLabel11,
                prefs.getBoolean(KEY_AUTOCROP_1_1, false), 1, 1);
    }

    private void checkGuideLineOverlap(int index, View line, TextView label,
                                       boolean isSelected, int ratioW, int ratioH) {
        if (line == null || label == null) return;

        if (!isSelected) {
            guideOverlapState[index] = false;
            return;
        }

        int cropHeight = getCropHeightForRatio(ratioW, ratioH);
        if (cropHeight < 0) {
            guideOverlapState[index] = false;
            return;
        }

        int lineY = Math.min(cropHeight, rootLayout.getHeight() - 2);
        int lineHeight = Math.max(1, line.getHeight());

        // Lines span the full width, so only the vertical span matters.
        float camTop = cameraRootWrapper.getY();
        float camBottom = camTop + cameraRootWrapper.getHeight();
        boolean overlapping = camBottom >= lineY && camTop <= lineY + lineHeight;

        // Edge-triggered: flash only on entering the overlap, never while held there.
        if (overlapping && !guideOverlapState[index]) {
            flashGuideLine(index, line, label, lineY);
        }
        guideOverlapState[index] = overlapping;
    }

    private void flashGuideLine(int index, View line, TextView label, int lineY) {
        if (guideFlashHideRunnables[index] != null) {
            guideFlashHandler.removeCallbacks(guideFlashHideRunnables[index]);
        }

        line.setY(lineY);
        line.bringToFront();
        line.setVisibility(View.VISIBLE);

        int labelY = Math.max(0, lineY - label.getHeight() - 4);
        label.setY(labelY);
        label.bringToFront();
        label.setVisibility(View.VISIBLE);

        Runnable hide = () -> {
            if (isRecording) {
                line.setVisibility(View.GONE);
                label.setVisibility(View.GONE);
            } else {
                // Recording ended mid-flash — restore the persistent idle lines.
                showCropGuideLines();
            }
        };
        guideFlashHideRunnables[index] = hide;
        guideFlashHandler.postDelayed(hide, GUIDE_FLASH_DURATION_MS);
    }

    private void cancelGuideLineFlashes() {
        for (int i = 0; i < guideFlashHideRunnables.length; i++) {
            if (guideFlashHideRunnables[i] != null) {
                guideFlashHandler.removeCallbacks(guideFlashHideRunnables[i]);
                guideFlashHideRunnables[i] = null;
            }
            guideOverlapState[i] = false;
        }
    }

    private void setupDrawer() {
        ImageButton btnOpenDrawer = findViewById(R.id.btn_open_drawer);

        if (btnOpenDrawer != null) {
            btnOpenDrawer.setOnClickListener(v -> {
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            });
        }

        if (navigationView != null) {
            navigationView.setNavigationItemSelectedListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.nav_edit_slides) {
                    openSlideManagerDialog();
                } else if (itemId == R.id.nav_record_settings) {
                    openRecordSettingsDialog();
                } else if (itemId == R.id.nav_saved_recordings) {
                    openSavedRecordings();
                } else if (itemId == R.id.nav_help) {
                    openHowToUseDialog();
                } else if (itemId == R.id.nav_about) {
                    openAboutDialog();
                }
                if (drawerLayout != null) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                }
                return true;
            });
        }
    }

    private void setupDraggableCameraContainer() {
        cameraRootWrapper = findViewById(R.id.camera_root_wrapper);
        cameraCardContainer = findViewById(R.id.camera_card_container);
        cameraPreviewView = findViewById(R.id.camera_preview_view);
        btnResizeHandle = findViewById(R.id.btn_resize_handle);
        btnFlipCamera = findViewById(R.id.btn_flip_camera);
        ivCameraFreezeFrame = findViewById(R.id.iv_camera_freeze_frame);
        tvCameraPermissionHint = findViewById(R.id.tv_camera_permission_hint);

        restoreCameraState();

        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                rootLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                restoreCameraPositionOrCenter();
            }
        });

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                int currentWidth = cameraCardContainer.getWidth();
                int newSize = (int) (currentWidth * scaleFactor);

                int minSize = (int) (90 * getResources().getDisplayMetrics().density);  // 90dp min
                int maxSize = (int) (320 * getResources().getDisplayMetrics().density); // 320dp max

                newSize = Math.max(minSize, Math.min(maxSize, newSize));

                ViewGroup.LayoutParams params = cameraCardContainer.getLayoutParams();
                params.width = newSize;
                params.height = newSize;
                cameraCardContainer.setLayoutParams(params);
                cameraCardContainer.setRadius(newSize / 2f);
                updateResizeHandlePosition(newSize);
                flashOverlappedGuideLines();
                saveCameraState();
                return true;
            }
        });

        cameraCardContainer.setOnTouchListener((view, event) -> {
            scaleGestureDetector.onTouchEvent(event);

            if (scaleGestureDetector.isInProgress() || event.getPointerCount() > 1) {
                return true;
            }

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX = cameraRootWrapper.getX() - event.getRawX();
                    dY = cameraRootWrapper.getY() - event.getRawY();
                    showResizeHandleFor3Seconds();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float newX = event.getRawX() + dX;
                    float newY = event.getRawY() + dY;

                    int parentWidth = rootLayout.getWidth();
                    int parentHeight = rootLayout.getHeight();

                    // Free movement: only the screen edges constrain the camera.
                    newX = Math.max(0, Math.min(parentWidth - cameraRootWrapper.getWidth(), newX));
                    newY = Math.max(0, Math.min(parentHeight - cameraRootWrapper.getHeight(), newY));

                    cameraRootWrapper.setX(newX);
                    cameraRootWrapper.setY(newY);
                    flashOverlappedGuideLines();
                    return true;

                case MotionEvent.ACTION_UP:
                    showResizeHandleFor3Seconds();
                    saveCameraState();
                    return true;

                default:
                    return false;
            }
        });

        setupResizeHandleTouch();
        setupFlipCameraButton();
    }

    private void showResizeHandleFor3Seconds() {
        if (btnResizeHandle == null) return;
        btnResizeHandle.animate().cancel();
        btnResizeHandle.setAlpha(1f);
        btnResizeHandle.setVisibility(View.VISIBLE);
        if (btnFlipCamera != null && flipAvailable) {
            btnFlipCamera.animate().cancel();
            btnFlipCamera.setAlpha(1f);
            btnFlipCamera.setVisibility(View.VISIBLE);
        }
        hideHandleHandler.removeCallbacks(hideHandleRunnable);
        hideHandleHandler.postDelayed(hideHandleRunnable, 3000);
    }

    private void setupFlipCameraButton() {
        if (btnFlipCamera == null) return;
        btnFlipCamera.setOnClickListener(v -> flipCamera());
    }

    private int getSavedLensFacing() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getInt(KEY_CAM_LENS_FACING, CameraSelector.LENS_FACING_FRONT);
    }

    private void flipCamera() {
        if (!flipAvailable) return;
        // Tapping flip keeps the icons around for another 3s
        showResizeHandleFor3Seconds();

        int next = getSavedLensFacing() == CameraSelector.LENS_FACING_FRONT
                ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putInt(KEY_CAM_LENS_FACING, next).apply();

        btnFlipCamera.animate().rotationBy(180f).setDuration(300).start();

        // Freeze the last frame so the swap doesn't flash black
        if (ivCameraFreezeFrame != null && cameraPreviewView != null) {
            android.graphics.Bitmap frame = null;
            try {
                frame = cameraPreviewView.getBitmap();
            } catch (Exception e) {
                Log.w(TAG, "Could not grab freeze frame", e);
            }
            if (frame != null) {
                ivCameraFreezeFrame.setImageBitmap(frame);
                ivCameraFreezeFrame.animate().cancel();
                ivCameraFreezeFrame.setAlpha(1f);
                ivCameraFreezeFrame.setVisibility(View.VISIBLE);
                watchForStreamingThenHideFreezeFrame();
            }
        }
        startCameraPreview();
    }

    private void watchForStreamingThenHideFreezeFrame() {
        clearFlipStreamObserver();
        final boolean[] sawNotStreaming = {false};
        flipStreamObserver = state -> {
            if (state != PreviewView.StreamState.STREAMING) {
                sawNotStreaming[0] = true;
            } else if (sawNotStreaming[0]) {
                hideFreezeFrame();
            }
        };
        cameraPreviewView.getPreviewStreamState().observe(this, flipStreamObserver);
        hideHandleHandler.removeCallbacks(flipFreezeTimeoutRunnable);
        hideHandleHandler.postDelayed(flipFreezeTimeoutRunnable, 1500);
    }

    private void clearFlipStreamObserver() {
        if (flipStreamObserver != null && cameraPreviewView != null) {
            cameraPreviewView.getPreviewStreamState().removeObserver(flipStreamObserver);
        }
        flipStreamObserver = null;
    }

    private void hideFreezeFrame() {
        hideHandleHandler.removeCallbacks(flipFreezeTimeoutRunnable);
        clearFlipStreamObserver();
        if (ivCameraFreezeFrame == null || ivCameraFreezeFrame.getVisibility() != View.VISIBLE) return;
        ivCameraFreezeFrame.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction(() -> {
                    ivCameraFreezeFrame.setVisibility(View.GONE);
                    ivCameraFreezeFrame.setImageDrawable(null);
                })
                .start();
    }

    private void setupResizeHandleTouch() {
        if (btnResizeHandle == null) return;

        btnResizeHandle.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    initialResizeTouchX = event.getRawX();
                    initialResizeTouchY = event.getRawY();
                    initialCardWidth = cameraCardContainer.getWidth();
                    hideHandleHandler.removeCallbacks(hideHandleRunnable);
                    btnResizeHandle.animate().cancel();
                    btnResizeHandle.setAlpha(1f);
                    btnResizeHandle.setVisibility(View.VISIBLE);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialResizeTouchX;
                    float dy = event.getRawY() - initialResizeTouchY;
                    float delta = (dx + dy) / 2f;

                    int minSize = (int) (90 * getResources().getDisplayMetrics().density);
                    int maxSize = (int) (320 * getResources().getDisplayMetrics().density);

                    int parentWidth = rootLayout.getWidth();
                    int parentHeight = rootLayout.getHeight();
                    if (parentWidth > 0 && parentHeight > 0) {
                        float posX = cameraRootWrapper.getX();
                        float posY = cameraRootWrapper.getY();
                        int maxAvailableWidth = (int) (parentWidth - posX);
                        int maxAvailableHeight = (int) (parentHeight - posY);
                        int maxAllowedByScreen = Math.min(maxAvailableWidth, maxAvailableHeight);
                        maxSize = Math.max(minSize, Math.min(maxSize, maxAllowedByScreen));
                    }

                    int newSize = (int) (initialCardWidth + delta);
                    newSize = Math.max(minSize, Math.min(maxSize, newSize));

                    ViewGroup.LayoutParams params = cameraCardContainer.getLayoutParams();
                    params.width = newSize;
                    params.height = newSize;
                    cameraCardContainer.setLayoutParams(params);
                    cameraCardContainer.setRadius(newSize / 2f);
                    updateResizeHandlePosition(newSize);
                    flashOverlappedGuideLines();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    hideHandleHandler.removeCallbacks(hideHandleRunnable);
                    hideHandleHandler.postDelayed(hideHandleRunnable, 3000);
                    saveCameraState();
                    return true;
            }
            return false;
        });
    }

    private void centerCameraContainer() {
        int parentWidth = rootLayout.getWidth();
        int parentHeight = rootLayout.getHeight();
        int wrapperWidth = cameraRootWrapper.getWidth();
        int wrapperHeight = cameraRootWrapper.getHeight();

        if (parentWidth > 0 && parentHeight > 0) {
            float centerX = (parentWidth - wrapperWidth) / 2f;
            float centerY = (parentHeight - wrapperHeight) / 2f;
            cameraRootWrapper.setX(centerX);
            cameraRootWrapper.setY(centerY);
        }
    }

    private void setupRecordButton() {
        btnRecord = findViewById(R.id.btn_record);
        btnStopRecordFloating = findViewById(R.id.btn_stop_record_floating);

        if (btnRecord != null) {
            btnRecord.setOnClickListener(v -> startRecordingFlow());
        }

        if (btnStopRecordFloating != null) {
            btnStopRecordFloating.setOnClickListener(v -> stopRecordingFlow());
        }

        btnDrawingBoard = findViewById(R.id.btn_drawing_board);
        drawingBoardContainer = findViewById(R.id.drawing_board_container);
        if (drawingBoardContainer != null) {
            drawingBoardController = new DrawingBoardController(drawingBoardContainer);
        }
        if (btnDrawingBoard != null) {
            btnDrawingBoard.setOnClickListener(v -> toggleDrawingBoard());
        }
        if (rootLayout != null) {
            // Keep the board square if the window changes size (rotation, bars) while it is open.
            rootLayout.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (isDrawingBoardVisible && !isDrawingBoardAnimating) sizeDrawingBoard();
            });
        }
    }

    /** Board side = screen width in portrait, min(width, height) in landscape. */
    private void sizeDrawingBoard() {
        if (drawingBoardContainer == null || rootLayout == null) return;
        int side = Math.min(rootLayout.getWidth(), rootLayout.getHeight());
        if (side <= 0) return;
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) drawingBoardContainer.getLayoutParams();
        if (lp.width == side && lp.height == side) return;
        lp.width = side;
        lp.height = side;
        lp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        lp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        drawingBoardContainer.setLayoutParams(lp);
    }

    private void toggleDrawingBoard() {
        if (drawingBoardContainer == null || isDrawingBoardAnimating) return;
        if (isDrawingBoardVisible) {
            hideDrawingBoard(true);
        } else {
            showDrawingBoard();
        }
    }

    private void showDrawingBoard() {
        sizeDrawingBoard();
        int side = drawingBoardContainer.getLayoutParams().height;
        drawingBoardContainer.setTranslationY(-side);
        drawingBoardContainer.setVisibility(View.VISIBLE);
        isDrawingBoardVisible = true;
        isDrawingBoardAnimating = true;
        drawingBoardContainer.animate()
                .translationY(0f)
                .setDuration(DRAWING_BOARD_ANIM_MS)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> isDrawingBoardAnimating = false)
                .start();
    }

    private void hideDrawingBoard(boolean animate) {
        if (drawingBoardContainer == null) return;
        if (drawingBoardController != null) drawingBoardController.onBoardHidden();
        drawingBoardContainer.animate().cancel();
        isDrawingBoardVisible = false;
        if (!animate || drawingBoardContainer.getVisibility() != View.VISIBLE) {
            isDrawingBoardAnimating = false;
            drawingBoardContainer.setVisibility(View.GONE);
            drawingBoardContainer.setTranslationY(0f);
            return;
        }
        isDrawingBoardAnimating = true;
        drawingBoardContainer.animate()
                .translationY(-drawingBoardContainer.getHeight())
                .setDuration(DRAWING_BOARD_ANIM_MS)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    isDrawingBoardAnimating = false;
                    drawingBoardContainer.setVisibility(View.GONE);
                    drawingBoardContainer.setTranslationY(0f);
                })
                .start();
    }

    private List<String> getMissingPermissions() {
        List<String> neededPermissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        return neededPermissions;
    }

    private boolean isNetworkConnected() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            Network network = cm.getActiveNetwork();
            if (network != null) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(network);
                return nc != null && (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            }
        }
        return false;
    }

    private String extractYouTubeVideoId(String input) {
        if (input == null || input.trim().isEmpty()) return null;
        String trimmed = input.trim();
        if (trimmed.length() == 11 && trimmed.matches("[a-zA-Z0-9_-]{11}")) {
            return trimmed;
        }
        Pattern pattern = Pattern.compile("(?:youtube\\.com\\/(?:[^\\/]+\\/.+\\/|(?:v|e(?:mbed)?)\\/*|.*[?&]v=)|youtu\\.be\\/|youtube\\.com\\/shorts\\/)([a-zA-Z0-9_-]{11})");
        Matcher matcher = pattern.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return trimmed;
    }

    private static final Pattern YOUTUBE_URL_PATTERN = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/(?:watch\\?(?:.*&)?v=|embed/|v/|e/|shorts/|live/)|youtu\\.be/)([a-zA-Z0-9_-]{11})(?:[?&#/].*)?$",
            Pattern.CASE_INSENSITIVE);

    /** Strict YouTube parse: returns the 11-char video ID, or null if input is not a valid ID/YouTube URL. */
    private String parseYouTubeVideoId(String input) {
        if (input == null) return null;
        String trimmed = input.trim();
        if (trimmed.matches("[a-zA-Z0-9_-]{11}")) return trimmed;
        Matcher matcher = YOUTUBE_URL_PATTERN.matcher(trimmed);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** Returns a normalized http(s) URL, or null if input is not a valid web address. */
    private String normalizeWebsiteUrl(String input) {
        if (input == null) return null;
        String url = input.trim();
        if (url.isEmpty() || url.matches(".*\\s.*")) return null;
        if (!url.matches("(?i)^https?://.*")) {
            if (url.contains("://")) return null; // unsupported scheme
            url = "https://" + url;
        }
        if (!android.util.Patterns.WEB_URL.matcher(url).matches()) return null;
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null || !(host.contains(".") || host.equalsIgnoreCase("localhost"))) return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
        return url;
    }

    private void startRecordingFlow() {
        if (isRecording) return;

        // Check for website/youtube slides requiring internet before proceeding
        for (int i = 0; i < slides.size(); i++) {
            Slide s = slides.get(i);
            if (s.isDisabled()) continue;
            if (Slide.TYPE_WEBSITE.equals(s.getType())) {
                if (!isNetworkConnected()) {
                    Toast.makeText(this, "Slide " + (i + 1) + " is website, so please turn ON the internet", Toast.LENGTH_LONG).show();
                    return;
                }
            } else if (Slide.TYPE_YOUTUBE.equals(s.getType())) {
                if (!isNetworkConnected()) {
                    Toast.makeText(this, "Slide " + (i + 1) + " is YouTube video, so please turn ON the internet", Toast.LENGTH_LONG).show();
                    return;
                }
            }
        }

        List<String> missingPermissions = getMissingPermissions();
        if (!missingPermissions.isEmpty()) {
            showPermissionRationaleDialog(missingPermissions);
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCameraPreview();
            }
            proceedToScreenCapture();
        }
    }

    private void proceedToScreenCapture() {
        if (!isRecording) {
            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager != null) {
                Intent captureIntent;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Skip the "single app" option so leaving the app keeps recording the whole screen
                    captureIntent = projectionManager.createScreenCaptureIntent(
                            MediaProjectionConfig.createConfigForDefaultDisplay());
                } else {
                    captureIntent = projectionManager.createScreenCaptureIntent();
                }
                screenCaptureLauncher.launch(captureIntent);
            }
        }
    }

    private void showPermissionRationaleDialog(List<String> missingPermissions) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_permission_rationale, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        View containerCamera = dialogView.findViewById(R.id.container_perm_camera);
        View containerMic = dialogView.findViewById(R.id.container_perm_mic);
        View containerNotification = dialogView.findViewById(R.id.container_perm_notification);
        Button btnGrant = dialogView.findViewById(R.id.btn_grant_permissions);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel_permissions);

        if (containerCamera != null) {
            containerCamera.setVisibility(missingPermissions.contains(Manifest.permission.CAMERA) ? View.VISIBLE : View.GONE);
        }
        if (containerMic != null) {
            containerMic.setVisibility(missingPermissions.contains(Manifest.permission.RECORD_AUDIO) ? View.VISIBLE : View.GONE);
        }
        if (containerNotification != null) {
            boolean showNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU 
                    && missingPermissions.contains(Manifest.permission.POST_NOTIFICATIONS);
            containerNotification.setVisibility(showNotif ? View.VISIBLE : View.GONE);
        }

        boolean isPermanentlyDenied = false;
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        for (String perm : missingPermissions) {
            if (prefs.getBoolean("asked_perm_" + perm, false)
                    && !ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
                isPermanentlyDenied = true;
                break;
            }
        }

        if (isPermanentlyDenied) {
            btnGrant.setText("Open Settings");
            btnGrant.setOnClickListener(v -> {
                dialog.dismiss();
                openAppSettings();
            });
        } else {
            btnGrant.setText("Grant Permissions");
            btnGrant.setOnClickListener(v -> {
                dialog.dismiss();
                SharedPreferences.Editor editor = prefs.edit();
                for (String perm : missingPermissions) {
                    editor.putBoolean("asked_perm_" + perm, true);
                }
                editor.apply();
                permissionLauncher.launch(missingPermissions.toArray(new String[0]));
            });
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getPackageName(), null);
        intent.setData(uri);
        startActivity(intent);
    }

    private void stopRecordingFlow() {
        if (isRecording) {
            Intent serviceIntent = new Intent(this, ScreenRecordService.class);
            serviceIntent.setAction(ScreenRecordService.ACTION_STOP);
            startService(serviceIntent);
            onRecordingStoppedUi();
        }
    }

    /** Resets the UI to Edit Mode after a recording ends (stop button, notification, or system chip). */
    private void onRecordingStoppedUi() {
        isRecording = false;
        showCropGuideLines();

        incrementSuccessfulRecordingsCount();

        // Return to Edit Mode UI
        isMenuBarVisible = true;
        setPresentationMode(false);
        updateUIState();
    }

    private void updateUIState() {
        if (toolbarTitle != null) {
            toolbarTitle.setText(isRecording ? "Recording Mode" : "Edit Mode");
        }

        if (isRecording) {
            // In Recording Mode: hide top bar, hide red start button, show ONLY floating gray stop button
            topMenuBar.setVisibility(View.GONE);
            hideSwipeHint();
            if (btnRecord != null) btnRecord.setVisibility(View.GONE);
            if (btnStopRecordFloating != null) btnStopRecordFloating.setVisibility(View.VISIBLE);
            if (btnDrawingBoard != null) btnDrawingBoard.setVisibility(View.VISIBLE);

            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            int successfulRecordings = prefs.getInt(KEY_SUCCESSFUL_RECORDINGS, 0);
            if (successfulRecordings < 2 && ivStopArrowHint != null) {
                ivStopArrowHint.setVisibility(View.VISIBLE);
                ivStopArrowHint.setTranslationX(0f);

                ObjectAnimator bounceAnim = ObjectAnimator.ofFloat(ivStopArrowHint, "translationX", 0f, -16f, 0f);
                bounceAnim.setDuration(600);
                bounceAnim.setRepeatCount(2); // 3 bounce cycles total
                bounceAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                bounceAnim.start();
            } else if (ivStopArrowHint != null) {
                ivStopArrowHint.setVisibility(View.GONE);
            }
        } else {
            // Non-recording state: show top bar (if menu bar is visible), keep red start button visible, hide floating stop button
            topMenuBar.setVisibility(isMenuBarVisible ? View.VISIBLE : View.GONE);
            if (btnRecord != null) btnRecord.setVisibility(View.VISIBLE);
            if (btnStopRecordFloating != null) btnStopRecordFloating.setVisibility(View.GONE);
            if (btnDrawingBoard != null) btnDrawingBoard.setVisibility(View.GONE);
            hideDrawingBoard(false);

            if (ivStopArrowHint != null) {
                ivStopArrowHint.clearAnimation();
                ivStopArrowHint.setVisibility(View.GONE);
            }
        }

        updateNavigationButtonsState();
    }

    private void setupPermissionsAndCamera() {
        permissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    boolean allGranted = true;
                    for (Boolean granted : result.values()) {
                        if (!Boolean.TRUE.equals(granted)) {
                            allGranted = false;
                            break;
                        }
                    }
                    updateCameraPermissionHint();
                    if (allGranted) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            startCameraPreview();
                        }
                        proceedToScreenCapture();
                    } else {
                        Toast.makeText(this, "All permissions are required to start recording.", Toast.LENGTH_LONG).show();
                    }
                }
        );

        settingsPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    String permission = pendingSettingsPermission;
                    pendingSettingsPermission = null;
                    if (granted) {
                        if (Manifest.permission.CAMERA.equals(permission)) startCameraPreview();
                    } else if (permission != null
                            && !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                        // Denied for good: the system dialog won't appear again, only app settings can help.
                        openAppSettings();
                    }
                    updateCameraPermissionHint();
                    if (refreshPermissionRows != null) refreshPermissionRows.run();
                }
        );

        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent serviceIntent = new Intent(this, ScreenRecordService.class);
                        serviceIntent.setAction(ScreenRecordService.ACTION_START);
                        serviceIntent.putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.getResultCode());
                        serviceIntent.putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.getData());

                        int countdown = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(KEY_COUNTDOWN_SECONDS, 3);
                        if (countdown > 0) {
                            startTimedRecording(serviceIntent, countdown);
                        } else {
                            showCropGuideLines();
                            triggerStartFlashEffect(() -> {
                                hideCropGuideLines();
                                ContextCompat.startForegroundService(this, serviceIntent);
                                isRecording = true;
                                isMenuBarVisible = false;
                                setPresentationMode(true);
                                updateUIState();
                            });
                        }
                    } else {
                        Toast.makeText(this, "Screen recording permission is required to start recording.", Toast.LENGTH_LONG).show();
                    }
                }
        );

        // Do not request permissions on app launch.
        // Only start camera preview if camera permission is already granted.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview();
        }
    }

    /** The empty circle means nothing to a first-time user, so explain it until all permissions are in. */
    private void updateCameraPermissionHint() {
        if (tvCameraPermissionHint == null) return;
        tvCameraPermissionHint.setVisibility(getMissingPermissions().isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void startCameraPreview() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();

                CameraSelector frontSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
                CameraSelector backSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
                boolean hasFront = cameraProvider.hasCamera(frontSelector);
                boolean hasBack = cameraProvider.hasCamera(backSelector);
                flipAvailable = hasFront && hasBack;
                if (!flipAvailable && btnFlipCamera != null) {
                    btnFlipCamera.setVisibility(View.GONE);
                }

                CameraSelector cameraSelector =
                        (getSavedLensFacing() == CameraSelector.LENS_FACING_BACK && hasBack)
                                ? backSelector : (hasFront ? frontSelector : backSelector);

                preview.setSurfaceProvider(cameraPreviewView.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview);
            } catch (Exception e) {
                Log.e(TAG, "Error starting camera preview", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Insets the chrome, never the canvas.
     *
     * The window is edge-to-edge so root_layout always spans the whole physical
     * display: what you see is exactly what gets recorded, and the crop guide lines
     * mean the same thing in every mode. That leaves the top menu bar sitting under
     * the status bar in Edit Mode, so the bar (and the drawer) pad themselves by the
     * live inset instead. When the bars are hidden the insets are zero and the
     * padding collapses on its own.
     */
    private void applySystemBarInsets() {
        if (topMenuBar != null) {
            if (topMenuBarBaseHeight < 0) {
                ViewGroup.LayoutParams lp = topMenuBar.getLayoutParams();
                topMenuBarBaseHeight = (lp != null && lp.height > 0)
                        ? lp.height
                        : (int) (56 * getResources().getDisplayMetrics().density);
            }
            ViewCompat.setOnApplyWindowInsetsListener(topMenuBar, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(v.getPaddingLeft(), bars.top,
                        v.getPaddingRight(), v.getPaddingBottom());
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                int wanted = topMenuBarBaseHeight + bars.top;
                if (lp != null && lp.height != wanted) {
                    lp.height = wanted;
                    v.setLayoutParams(lp);
                }
                return insets;
            });
        }

        if (navigationView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(navigationView, (v, insets) -> {
                Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(v.getPaddingLeft(), bars.top,
                        v.getPaddingRight(), bars.bottom);
                return insets;
            });
        }
    }

    /**
     * Full physical display height in pixels — the same source ScreenRecordService
     * measures the capture from, so slide sizing agrees with the recorded frame
     * regardless of whether the system bars happen to be showing.
     */
    private int getRealScreenHeightPx() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return metrics.heightPixels;
    }

    private void setupTop30PercentLayout() {
        int screenHeight = getRealScreenHeightPx();

        // Set Top 30% container height dynamically
        int top30Height = (int) (screenHeight * 0.30);
        ViewGroup.LayoutParams params = textSlideContainer.getLayoutParams();
        params.height = top30Height;
        textSlideContainer.setLayoutParams(params);
    }

    private void setupBottom40PercentLayout() {
        int screenHeight = getRealScreenHeightPx();

        // Set Bottom 40% container height dynamically
        int bottom40Height = (int) (screenHeight * 0.40);
        ViewGroup.LayoutParams params = bottomNavContainer.getLayoutParams();
        if (params != null) {
            params.height = bottom40Height;
            bottomNavContainer.setLayoutParams(params);
        }
    }

    private void setupGestureDetector() {
        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                toggleMenuBarAndSystemBars();
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                if (drawerLayout != null && drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
                    return false;
                }
                if (isTouchInsideView(e1, cameraRootWrapper)) {
                    return false;
                }
                if (isSlideZoomed()) {
                    return false;
                }
                if (isDrawingBoardVisible) {
                    // With the board open, only the board itself blocks swipes; the slide may extend under it.
                    if (isTouchInsideView(e1, drawingBoardContainer)) return false;
                } else {
                    View activeSlideView = getActiveSlideView();
                    if (activeSlideView != null && isTouchInsideView(e1, activeSlideView)) {
                        return false;
                    }
                }

                float diffX = e2.getX() - e1.getX();
                float diffY = e2.getY() - e1.getY();

                // Check if horizontal swipe is dominant and exceeds thresholds
                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > 100 && Math.abs(velocityX) > 100) {
                        if (isDrawingBoardVisible) hideDrawingBoard(true);
                        if (diffX > 0) {
                            // Swipe Right -> Previous Slide
                            markSwipeGestureDone(false);
                            goToPreviousSlide();
                        } else {
                            // Swipe Left -> Next Slide
                            markSwipeGestureDone(true);
                            goToNextSlide();
                        }
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private boolean isTouchInsideView(MotionEvent ev, View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= location[0] && x <= location[0] + view.getWidth() &&
               y >= location[1] && y <= location[1] + view.getHeight();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            boardTouchActive = isDrawingBoardVisible && isTouchInsideView(ev, drawingBoardContainer);
        }
        // Touches on the board only draw: no double-tap menu toggle, no slide swipe.
        if (!boardTouchActive) gestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void toggleMenuBarAndSystemBars() {
        isMenuBarVisible = !isMenuBarVisible;
        setPresentationMode(!isMenuBarVisible);
        updateUIState();
    }

    private void setPresentationMode(boolean enableImmersive) {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            if (enableImmersive) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                controller.show(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
            }
        }

        if (drawerLayout != null) {
            if (enableImmersive) {
                drawerLayout.closeDrawer(GravityCompat.START);
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
            } else {
                drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
            }
        }
    }

    private void setupPhotoPicker() {
        photoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null) {
                        String localPath = ImageStorageHelper.saveImageToInternalStorage(this, uri);
                        if (localPath != null) {
                            Slide newSlide = new Slide(Slide.TYPE_IMAGE, slides.size(), null, localPath);
                            repository.insert(newSlide, id -> loadSlidesFromDb(true));
                        } else {
                            Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        updateImagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null && slideToUpdateImage != null) {
                        String localPath = ImageStorageHelper.saveImageToInternalStorage(this, uri);
                        if (localPath != null) {
                            slideToUpdateImage.setImagePath(localPath);
                            Slide slideToSave = slideToUpdateImage;
                            int posToUpdate = targetSlidePosition;
                            repository.update(slideToSave, () -> {
                                Toast.makeText(this, "Image updated successfully", Toast.LENGTH_SHORT).show();
                                if (slideAdapter != null && posToUpdate != -1) {
                                    slideAdapter.notifyItemChanged(posToUpdate);
                                }
                                renderCurrentSlide();
                            });
                        } else {
                            Toast.makeText(this, "Failed to save new image", Toast.LENGTH_SHORT).show();
                        }
                    }
                    slideToUpdateImage = null;
                    targetSlidePosition = -1;
                }
        );

        videoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null) {
                        String localPath = ImageStorageHelper.saveVideoToInternalStorage(this, uri);
                        if (localPath != null) {
                            Slide newSlide = new Slide(Slide.TYPE_VIDEO, slides.size(), null, localPath);
                            repository.insert(newSlide, id -> loadSlidesFromDb(true));
                        } else {
                            Toast.makeText(this, "Failed to save video", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );

        updateVideoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(),
                uri -> {
                    if (uri != null && slideToUpdateVideo != null) {
                        String localPath = ImageStorageHelper.saveVideoToInternalStorage(this, uri);
                        if (localPath != null) {
                            slideToUpdateVideo.setImagePath(localPath);
                            Slide slideToSave = slideToUpdateVideo;
                            int posToUpdate = targetSlidePosition;
                            repository.update(slideToSave, () -> {
                                Toast.makeText(this, "Video updated successfully", Toast.LENGTH_SHORT).show();
                                if (slideAdapter != null && posToUpdate != -1) {
                                    slideAdapter.notifyItemChanged(posToUpdate);
                                }
                                renderCurrentSlide();
                            });
                        } else {
                            Toast.makeText(this, "Failed to save new video", Toast.LENGTH_SHORT).show();
                        }
                    }
                    slideToUpdateVideo = null;
                    targetSlidePosition = -1;
                }
        );
    }

    private void launchImageUpdater(Slide slide, int position) {
        slideToUpdateImage = slide;
        targetSlidePosition = position;
        updateImagePickerLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    private void launchVideoUpdater(Slide slide, int position) {
        slideToUpdateVideo = slide;
        targetSlidePosition = position;
        updateVideoPickerLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                .build());
    }

    private void showImageSlideOptionsDialog(Slide slide, int position) {
        String[] options = {"Update / Replace Image", "View Slide"};
        new AlertDialog.Builder(this)
                .setTitle("Image Slide Options")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        launchImageUpdater(slide, position);
                    } else if (which == 1) {
                        currentSlideIndex = position;
                        renderCurrentSlide();
                        if (managerDialog != null) managerDialog.dismiss();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void incrementAppOpenedTimes() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int openedTimes = prefs.getInt(KEY_APP_OPENED_TIMES, 0);
        prefs.edit().putInt(KEY_APP_OPENED_TIMES, openedTimes + 1).apply();
    }

    private void incrementSuccessfulRecordingsCount() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int count = prefs.getInt(KEY_SUCCESSFUL_RECORDINGS, 0);
        prefs.edit().putInt(KEY_SUCCESSFUL_RECORDINGS, count + 1).apply();
    }

    private static final String KEY_APP_OPENED_TIMES = "appOpenedTimes";
    private static final String KEY_SWIPE_TUTORIAL_ACTIVE = "swipeTutorialActive";
    private static final String KEY_SWIPE_HINT_NEXT_DONE = "swipeHintNextDone";
    private static final String KEY_SWIPE_HINT_PREV_DONE = "swipeHintPrevDone";
    private static final String KEY_DOUBLE_TAP_TIP_SHOWN = "doubleTapTipShown";
    private static final String KEY_SUCCESSFUL_RECORDINGS = "successfulRecordingsCount";
    private static final String SEED_IMAGE_ASSET = "seed_slide_deep_breath.webp";
    private boolean isInitialAppLaunchCheck = true;

    private void loadSlidesFromDb() {
        loadSlidesFromDb(false);
    }

    private void loadSlidesFromDb(boolean scrollToBottom) {
        repository.getAllOrdered(result -> {
            slides = result;
            if (slideAdapter != null) {
                slideAdapter.setSlides(new ArrayList<>(slides));
                if (scrollToBottom && recyclerSlides != null && !slides.isEmpty()) {
                    recyclerSlides.smoothScrollToPosition(slides.size() - 1);
                }
            }

            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            int openedTimes = prefs.getInt(KEY_APP_OPENED_TIMES, 0);

            if (slides.isEmpty()) {
                if (openedTimes <= 1) {
                    seedInitialSlides();
                    return;
                }
                if (isInitialAppLaunchCheck) {
                    isInitialAppLaunchCheck = false;
                    Toast.makeText(this, "Double tap to add slides", Toast.LENGTH_SHORT).show();
                }
                renderCurrentSlide();
            } else {
                isInitialAppLaunchCheck = false;
                if (currentSlideIndex >= slides.size()) {
                    currentSlideIndex = Math.max(0, slides.size() - 1);
                }
                // Resolve to nearest active (non-disabled) slide
                resolveCurrentSlideToActive();
                renderCurrentSlide();
            }
        });
    }

    private void seedInitialSlides() {
        Slide textSlide = new Slide(Slide.TYPE_TEXT, 0, "(Example topic)\nHow to get rid of anxiety ?", null);

        // The example image ships in assets/ so the first run works offline. It is
        // copied in like a picked photo, so editing or deleting it behaves the same.
        String imagePath = ImageStorageHelper.copyAssetImageToInternalStorage(this, SEED_IMAGE_ASSET);

        repository.insert(textSlide, id1 -> {
            if (imagePath == null) {
                loadSlidesFromDb();
                return;
            }
            Slide imageSlide = new Slide(Slide.TYPE_IMAGE, 1, null, imagePath);
            repository.insert(imageSlide, id2 -> loadSlidesFromDb());
        });
    }

    private void stopVideoIfPlaying() {
        if (videoSlideView != null) {
            try {
                videoSlideView.setOnPreparedListener(null);
                videoSlideView.setOnErrorListener(null);
                if (videoSlideView.isPlaying()) {
                    videoSlideView.pause();
                }
                videoSlideView.stopPlayback();
                videoSlideView.suspend();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping video playback", e);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopVideoIfPlaying();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCameraPermissionHint();
        if (refreshPermissionRows != null) refreshPermissionRows.run();
        if (slides != null && !slides.isEmpty()) {
            renderCurrentSlide();
        }
        if (!isRecording && rootLayout != null) {
            rootLayout.post(this::showCropGuideLines);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideFloatingCamOverlay();
        maybeShowOverlayPermissionPrompt();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (drawingBoardController != null) drawingBoardController.flush();
        stopVideoIfPlaying();
        if (isRecording && !isChangingConfigurations()) {
            if (Settings.canDrawOverlays(this)) {
                showFloatingCamOverlay();
            } else {
                leftAppWithoutOverlayPermission = true;
            }
        }
    }

    /**
     * Explains the missing face cam once per failure: every time the user leaves the app
     * mid-recording without the overlay permission and comes back. Never asked at record start,
     * and held back while recording so the dialog doesn't end up in the video.
     */
    private void maybeShowOverlayPermissionPrompt() {
        if (!leftAppWithoutOverlayPermission || isRecording) return;
        leftAppWithoutOverlayPermission = false;
        if (Settings.canDrawOverlays(this)) return;

        new AlertDialog.Builder(this)
                .setTitle("Face cam wasn't visible")
                .setMessage("Your face cam wasn't visible while you were in other apps. "
                        + "Allow \"Display over other apps\" so it follows you.")
                .setPositiveButton("Allow", (d, w) -> startActivity(new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.fromParts("package", getPackageName(), null))))
                .setNegativeButton("Not now", null)
                .show();
    }

    /** Hands the face cam over to the service-owned bubble while the user is in another app. */
    private void showFloatingCamOverlay() {
        if (cameraRootWrapper == null || cameraCardContainer == null
                || !Settings.canDrawOverlays(this)) {
            return;
        }
        int size = cameraCardContainer.getWidth();
        if (size <= 0) return;
        Intent intent = new Intent(this, ScreenRecordService.class);
        intent.setAction(ScreenRecordService.ACTION_SHOW_OVERLAY);
        intent.putExtra(ScreenRecordService.EXTRA_OVERLAY_X, Math.round(cameraRootWrapper.getX()));
        intent.putExtra(ScreenRecordService.EXTRA_OVERLAY_Y, Math.round(cameraRootWrapper.getY()));
        intent.putExtra(ScreenRecordService.EXTRA_OVERLAY_SIZE, size);
        try {
            startService(intent);
            overlayRequested = true;
        } catch (Exception e) {
            Log.w(TAG, "Could not request camera overlay", e);
        }
    }

    private void hideFloatingCamOverlay() {
        if (!overlayRequested) return;
        overlayRequested = false;
        Intent intent = new Intent(this, ScreenRecordService.class);
        intent.setAction(ScreenRecordService.ACTION_HIDE_OVERLAY);
        try {
            startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "Could not hide camera overlay", e);
        }
    }

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

    private void animateMediaSlideExit(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        int screenWidth = rootLayout.getWidth() > 0 ? rootLayout.getWidth() : getResources().getDisplayMetrics().widthPixels;

        view.animate().cancel();
        view.animate()
                .translationX(-screenWidth)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setTranslationX(0f);
                })
                .start();
    }

    private void animateMediaSlideEntry(View view, Runnable onAnimationEnd) {
        if (view == null) return;
        view.animate().cancel();
        int screenWidth = rootLayout.getWidth() > 0 ? rootLayout.getWidth() : getResources().getDisplayMetrics().widthPixels;

        view.setTranslationX(screenWidth);
        view.setAlpha(1f);
        view.setVisibility(View.VISIBLE);

        view.animate()
                .translationX(0f)
                .setDuration(350)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    if (onAnimationEnd != null) {
                        onAnimationEnd.run();
                    }
                })
                .start();
    }

    /**
     * Resolves currentSlideIndex to the nearest active (non-disabled) slide.
     * Searches forward first, then backward. If no active slides exist, index stays as-is
     * and renderCurrentSlide will handle the blank state.
     */
    private void resolveCurrentSlideToActive() {
        if (slides.isEmpty()) return;
        if (currentSlideIndex >= slides.size()) {
            currentSlideIndex = slides.size() - 1;
        }
        // If current slide is already active, nothing to do
        if (!slides.get(currentSlideIndex).isDisabled()) return;
        // Search forward
        for (int i = currentSlideIndex + 1; i < slides.size(); i++) {
            if (!slides.get(i).isDisabled()) {
                currentSlideIndex = i;
                return;
            }
        }
        // Search backward
        for (int i = currentSlideIndex - 1; i >= 0; i--) {
            if (!slides.get(i).isDisabled()) {
                currentSlideIndex = i;
                return;
            }
        }
        // All slides are disabled — renderCurrentSlide will handle blank state
    }

    private void renderCurrentSlide() {
        // Check if all slides are empty or all disabled
        boolean hasActiveSlide = false;
        for (Slide s : slides) {
            if (!s.isDisabled()) {
                hasActiveSlide = true;
                break;
            }
        }

        if (slides.isEmpty() || !hasActiveSlide) {
            SlideLogger.log("RENDER", "renderCurrentSlide: no active slides");
            emptyStateView.setVisibility(View.GONE);
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);
            if (imageSlideView != null) animateMediaSlideExit(imageSlideView);
            if (videoSlideContainer != null) {
                stopVideoIfPlaying();
                animateMediaSlideExit(videoSlideContainer);
            }
            if (webSlideContainer != null) {
                webSlideContainer.setVisibility(View.GONE);
                if (webSlideView != null) webSlideView.loadUrl("about:blank");
            }
            if (swipeRefreshWebSlide != null) {
                swipeRefreshWebSlide.setRefreshing(false);
            }
            updateNavigationButtonsState();
            return;
        }

        emptyStateView.setVisibility(View.GONE);
        Slide slide = slides.get(currentSlideIndex);
        resetAllSlideZoom();
        SlideLogger.log("RENDER", String.format(Locale.US,
                "renderCurrentSlide: index=%d/%d, type=%s, id=%d, imagePath=%s, textContent=%s",
                currentSlideIndex, slides.size(), slide.getType(), slide.getId(),
                slide.getImagePath(), slide.getTextContent()));

        if (Slide.TYPE_TEXT.equals(slide.getType())) {
            if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
                stopVideoIfPlaying();
                animateMediaSlideExit(videoSlideContainer);
            }
            if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
                animateMediaSlideExit(imageSlideView);
            }
            if (webSlideContainer != null) webSlideContainer.setVisibility(View.GONE);
            textSlideContainer.setVisibility(View.VISIBLE);

            textSlideView.setText(slide.getTextContent());
            applyTextSlideFontScaling(textSlideView, slide.getTextContent());
            animateTextSlideEntry();
            SlideLogger.log("RENDER_TEXT", "Text slide rendered: " + slide.getTextContent());
        } else if (Slide.TYPE_IMAGE.equals(slide.getType())) {
            if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
                stopVideoIfPlaying();
                animateMediaSlideExit(videoSlideContainer);
            }
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);
            if (webSlideContainer != null) webSlideContainer.setVisibility(View.GONE);

            if (slide.getImagePath() != null) {
                File imgFile = new File(slide.getImagePath());
                boolean exists = imgFile.exists();
                long length = exists ? imgFile.length() : 0;
                SlideLogger.log("RENDER_IMAGE", String.format(Locale.US,
                        "Attempting image load: path=%s, exists=%b, size=%d bytes",
                        slide.getImagePath(), exists, length));

                if (exists) {
                    imageSlideView.animate().cancel();
                    imageSlideView.setVisibility(View.VISIBLE);

                    Glide.with(this)
                            .load(imgFile)
                            .listener(new RequestListener<Drawable>() {
                                @Override
                                public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                                    SlideLogger.log("IMAGE_ERROR", "Glide failed to load image: " + (e != null ? e.getMessage() : "unknown error"));
                                    if (e != null) {
                                        for (Throwable t : e.getRootCauses()) {
                                            SlideLogger.log("IMAGE_ERROR_CAUSE", "Root cause: " + t.getMessage());
                                        }
                                    }
                                    imageSlideView.setImageDrawable(null);
                                    applyMediaTopMargin(imageSlideView, 0);
                                    imageSlideView.setVisibility(View.GONE);
                                    return false;
                                }

                                @Override
                                public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                                    int intrinsicWidth = resource.getIntrinsicWidth();
                                    int intrinsicHeight = resource.getIntrinsicHeight();
                                    int displayWidth = rootLayout.getWidth() > 0 ? rootLayout.getWidth() : getResources().getDisplayMetrics().widthPixels;
                                    int calculatedHeight = 0;
                                    if (intrinsicWidth > 0 && intrinsicHeight > 0) {
                                        calculatedHeight = (int) (((float) displayWidth / intrinsicWidth) * intrinsicHeight);
                                    }
                                    applyMediaTopMargin(imageSlideView, calculatedHeight);
                                    SlideLogger.log("IMAGE_SUCCESS", String.format(Locale.US,
                                            "Image loaded: intrinsicW=%d, intrinsicH=%d, displayW=%d, calculatedH=%d",
                                            intrinsicWidth, intrinsicHeight, displayWidth, calculatedHeight));

                                    if (imageSlideView.getVisibility() == View.VISIBLE && imageSlideView.getTranslationX() == 0f) {
                                        imageSlideView.setAlpha(1f);
                                    } else {
                                        animateMediaSlideEntry(imageSlideView, null);
                                    }
                                    return false;
                                }
                            })
                            .fitCenter()
                            .into(imageSlideView);
                } else {
                    SlideLogger.log("IMAGE_ERROR", "Image file does not exist on disk: " + slide.getImagePath());
                    imageSlideView.setImageDrawable(null);
                    applyMediaTopMargin(imageSlideView, 0);
                    imageSlideView.setVisibility(View.GONE);
                }
            } else {
                SlideLogger.log("IMAGE_ERROR", "Slide imagePath is NULL for slide id=" + slide.getId());
                imageSlideView.setImageDrawable(null);
                applyMediaTopMargin(imageSlideView, 0);
                imageSlideView.setVisibility(View.GONE);
            }
        } else if (Slide.TYPE_VIDEO.equals(slide.getType())) {
            if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
                animateMediaSlideExit(imageSlideView);
            }
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);
            if (webSlideContainer != null) webSlideContainer.setVisibility(View.GONE);

            if (slide.getImagePath() != null && videoSlideView != null) {
                File vidFile = new File(slide.getImagePath());
                boolean exists = vidFile.exists();
                long length = exists ? vidFile.length() : 0;
                SlideLogger.log("RENDER_VIDEO", String.format(Locale.US,
                        "Attempting video load: path=%s, exists=%b, size=%d bytes",
                        slide.getImagePath(), exists, length));

                if (exists) {
                    videoSlideContainer.animate().cancel();
                    videoSlideContainer.setVisibility(View.VISIBLE);

                    videoSlideView.setVideoPath(slide.getImagePath());
                    if (mediaController == null) {
                        mediaController = new MediaController(this);
                    }
                    mediaController.setAnchorView(videoSlideView);
                    videoSlideView.setMediaController(mediaController);

                    videoSlideView.setOnPreparedListener(mp -> {
                        int videoWidth = mp.getVideoWidth();
                        int videoHeight = mp.getVideoHeight();
                        int duration = mp.getDuration();
                        int displayWidth = rootLayout.getWidth() > 0 ? rootLayout.getWidth() : getResources().getDisplayMetrics().widthPixels;
                        int calculatedHeight = 0;
                        if (videoWidth > 0 && videoHeight > 0) {
                            calculatedHeight = (int) (((float) displayWidth / videoWidth) * videoHeight);
                        }
                        applyMediaTopMargin(videoSlideContainer, calculatedHeight);
                        mp.setLooping(true);

                        SlideLogger.log("VIDEO_SUCCESS", String.format(Locale.US,
                                "Video prepared: videoW=%d, videoH=%d, duration=%d ms, displayW=%d, calculatedH=%d",
                                videoWidth, videoHeight, duration, displayWidth, calculatedHeight));

                        if (videoSlideContainer.getVisibility() == View.VISIBLE && videoSlideContainer.getTranslationX() == 0f) {
                            mp.start();
                        } else {
                            animateMediaSlideEntry(videoSlideContainer, () -> mp.start());
                        }
                    });

                    videoSlideView.setOnErrorListener((mp, what, extra) -> {
                        SlideLogger.log("VIDEO_ERROR", String.format(Locale.US,
                                "Video playback error: what=%d, extra=%d, path=%s",
                                what, extra, slide.getImagePath()));
                        stopVideoIfPlaying();
                        applyMediaTopMargin(videoSlideContainer, 0);
                        if (videoSlideContainer != null) videoSlideContainer.setVisibility(View.GONE);
                        return true;
                    });
                } else {
                    SlideLogger.log("VIDEO_ERROR", "Video file does not exist on disk: " + slide.getImagePath());
                    stopVideoIfPlaying();
                    applyMediaTopMargin(videoSlideContainer, 0);
                    if (videoSlideContainer != null) videoSlideContainer.setVisibility(View.GONE);
                }
            } else {
                SlideLogger.log("VIDEO_ERROR", "Slide imagePath is NULL or videoSlideView is null for slide id=" + slide.getId());
                stopVideoIfPlaying();
                if (videoSlideContainer != null) {
                    applyMediaTopMargin(videoSlideContainer, 0);
                    videoSlideContainer.setVisibility(View.GONE);
                }
            }
        } else if (Slide.TYPE_WEBSITE.equals(slide.getType())) {
            if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
                stopVideoIfPlaying();
                animateMediaSlideExit(videoSlideContainer);
            }
            if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
                animateMediaSlideExit(imageSlideView);
            }
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);

            int displayWidth = rootLayout.getWidth() > 0 ? rootLayout.getWidth() : getResources().getDisplayMetrics().widthPixels;
            if (webSlideContainer != null) {
                ViewGroup.LayoutParams params = webSlideContainer.getLayoutParams();
                params.height = displayWidth;
                webSlideContainer.setLayoutParams(params);
                webSlideContainer.setVisibility(View.VISIBLE);
            }

            if (swipeRefreshWebSlide != null) {
                swipeRefreshWebSlide.setRefreshing(false);
                swipeRefreshWebSlide.setEnabled(true);
            }

            String url = slide.getTextContent();
            if (url != null && !url.trim().isEmpty()) {
                url = url.trim();
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                if (webSlideView != null) {
                    webSlideView.getSettings().setUserAgentString(null);
                    if (!url.equals(webSlideView.getUrl())) {
                        webSlideView.loadUrl(url);
                    }
                }
            }
        } else if (Slide.TYPE_YOUTUBE.equals(slide.getType())) {
            if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
                stopVideoIfPlaying();
                animateMediaSlideExit(videoSlideContainer);
            }
            if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
                animateMediaSlideExit(imageSlideView);
            }
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);

            int displayWidth = rootLayout.getWidth() > 0 ? rootLayout.getWidth() : getResources().getDisplayMetrics().widthPixels;
            if (webSlideContainer != null) {
                ViewGroup.LayoutParams params = webSlideContainer.getLayoutParams();
                params.height = displayWidth;
                webSlideContainer.setLayoutParams(params);
                webSlideContainer.setVisibility(View.VISIBLE);
            }

            if (swipeRefreshWebSlide != null) {
                swipeRefreshWebSlide.setRefreshing(false);
                swipeRefreshWebSlide.setEnabled(false);
            }

            String videoId = extractYouTubeVideoId(slide.getTextContent());
            if (videoId != null && !videoId.isEmpty() && webSlideView != null) {
                webSlideView.getSettings().setUserAgentString(
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                );
                String html = "<!DOCTYPE html><html><head>"
                        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">"
                        + "<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;}"
                        + "iframe{width:100%;height:100%;border:0;}</style></head><body>"
                        + "<iframe src=\"https://www.youtube-nocookie.com/embed/" + videoId
                        + "?enablejsapi=1&origin=https://localhost&autoplay=1&rel=0&playsinline=1\" "
                        + "referrerpolicy=\"strict-origin-when-cross-origin\" "
                        + "allow=\"autoplay; encrypted-media; picture-in-picture\" allowfullscreen></iframe>"
                        + "</body></html>";
                webSlideView.loadDataWithBaseURL("https://localhost", html, "text/html", "utf-8", null);
            }
        }

        updateNavigationButtonsState();
    }

    /**
     * Entry animation: text slides in from top edge of 30% zone to vertical middle of 30% zone
     */
    private void animateTextSlideEntry() {
        textSlideView.setAlpha(0f);
        textSlideView.post(() -> {
            int containerHeight = textSlideContainer.getHeight();
            int textHeight = textSlideView.getHeight();
            float startY = -((containerHeight + textHeight) / 2f);
            float endY = 0f;

            textSlideView.setTranslationY(startY);
            textSlideView.setAlpha(1f);

            ObjectAnimator animator = ObjectAnimator.ofFloat(textSlideView, "translationY", startY, endY);
            animator.setDuration(350);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.start();
        });
    }

    private void goToPreviousSlide() {
        for (int i = currentSlideIndex - 1; i >= 0; i--) {
            if (!slides.get(i).isDisabled()) {
                currentSlideIndex = i;
                SlideLogger.log("NAV", "Navigated to previous slide index: " + currentSlideIndex);
                renderCurrentSlide();
                return;
            }
        }
    }

    private void goToNextSlide() {
        for (int i = currentSlideIndex + 1; i < slides.size(); i++) {
            if (!slides.get(i).isDisabled()) {
                currentSlideIndex = i;
                SlideLogger.log("NAV", "Navigated to next slide index: " + currentSlideIndex);
                renderCurrentSlide();
                return;
            }
        }
    }

    private void updateNavigationButtonsState() {
        boolean hasPrev = false;
        for (int i = currentSlideIndex - 1; i >= 0; i--) {
            if (!slides.get(i).isDisabled()) { hasPrev = true; break; }
        }
        boolean hasNext = false;
        for (int i = currentSlideIndex + 1; i < slides.size(); i++) {
            if (!slides.get(i).isDisabled()) { hasNext = true; break; }
        }

        zonePrev.setEnabled(hasPrev);
        zoneNext.setEnabled(hasNext);

        btnPrev.setEnabled(hasPrev);
        btnNext.setEnabled(hasNext);

        if (!isRecording) {
            if (btnRecord != null) btnRecord.setVisibility(View.VISIBLE);
        } else {
            if (btnRecord != null) btnRecord.setVisibility(View.GONE);
        }

        if (isMenuBarVisible && !isRecording) {
            btnPrev.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
            btnPrev.setAlpha(hasPrev ? 1.0f : 0.3f);
            btnNext.setAlpha(hasNext ? 1.0f : 0.3f);
        } else {
            // Presentation mode / Recording mode: hide visual chrome while keeping tap zones functional
            btnPrev.setVisibility(View.INVISIBLE);
            btnNext.setVisibility(View.INVISIBLE);
        }

        updateSwipeHint(hasPrev, hasNext);
    }

    // --- First-run swipe tutorial ---

    private int countActiveSlides() {
        int n = 0;
        for (Slide s : slides) if (!s.isDisabled()) n++;
        return n;
    }

    private void markSwipeGestureDone(boolean next) {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_SWIPE_TUTORIAL_ACTIVE, false)) return;
        prefs.edit().putBoolean(next ? KEY_SWIPE_HINT_NEXT_DONE : KEY_SWIPE_HINT_PREV_DONE, true).apply();
        boolean nextDone = next || prefs.getBoolean(KEY_SWIPE_HINT_NEXT_DONE, false);
        boolean prevDone = !next || prefs.getBoolean(KEY_SWIPE_HINT_PREV_DONE, false);
        if (nextDone && prevDone) {
            prefs.edit().putBoolean(KEY_SWIPE_TUTORIAL_ACTIVE, false).apply();
            if (!prefs.getBoolean(KEY_DOUBLE_TAP_TIP_SHOWN, false)) {
                prefs.edit().putBoolean(KEY_DOUBLE_TAP_TIP_SHOWN, true).apply();
                Toast.makeText(this, "Double-tap anywhere for menu & slide editing", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void updateSwipeHint(boolean hasPrev, boolean hasNext) {
        if (swipeHintContainer == null) return;
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        boolean countdownVisible = countdownOverlayContainer != null
                && countdownOverlayContainer.getVisibility() == View.VISIBLE;
        boolean show = prefs.getBoolean(KEY_SWIPE_TUTORIAL_ACTIVE, false)
                && !isRecording && !countdownVisible && countActiveSlides() >= 2;
        Boolean wantNext = null;
        if (show) {
            if (!prefs.getBoolean(KEY_SWIPE_HINT_NEXT_DONE, false) && hasNext) wantNext = true;
            else if (!prefs.getBoolean(KEY_SWIPE_HINT_PREV_DONE, false) && hasPrev) wantNext = false;
        }
        if (wantNext == null) {
            hideSwipeHint();
            return;
        }
        if (swipeHintAnimator != null && swipeHintShowingNext == wantNext) {
            // Same hint already running; just re-centre it (slide size may have changed)
            swipeHintContainer.removeCallbacks(swipeHintPositionRunnable);
            swipeHintContainer.postDelayed(swipeHintPositionRunnable, 400);
            return;
        }
        hideSwipeHint();
        swipeHintShowingNext = wantNext;
        tvSwipeHintLabel.setText(wantNext ? "Swipe left for next slide" : "Swipe right to go back");
        swipeHintContainer.setVisibility(View.INVISIBLE);
        swipeHintContainer.postDelayed(swipeHintPositionRunnable, 400);
    }

    private void hideSwipeHint() {
        if (swipeHintContainer == null) return;
        swipeHintContainer.removeCallbacks(swipeHintPositionRunnable);
        if (swipeHintAnimator != null) {
            swipeHintAnimator.cancel();
            swipeHintAnimator = null;
        }
        swipeHintContainer.setVisibility(View.GONE);
    }

    private void positionAndStartSwipeHint() {
        if (swipeHintContainer == null || rootLayout == null || btnRecord == null) return;
        if (isRecording) return;
        if (swipeHintAnimator != null) {
            swipeHintAnimator.cancel();
            swipeHintAnimator = null;
        }
        swipeHintContainer.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int hintW = swipeHintContainer.getMeasuredWidth();
        final int hintH = swipeHintContainer.getMeasuredHeight();
        final float density = getResources().getDisplayMetrics().density;

        int[] rootLoc = new int[2];
        rootLayout.getLocationOnScreen(rootLoc);
        int[] tmp = new int[2];

        // Free band: bottom of active slide -> top of the record button
        View slideView = getActiveSlideView();
        int bandTop = rootLoc[1];
        if (slideView != null) {
            slideView.getLocationOnScreen(tmp);
            bandTop = tmp[1] + slideView.getHeight();
        }
        btnRecord.getLocationOnScreen(tmp);
        int bandBottom = tmp[1];
        int bandLeft = rootLoc[0];
        int bandRight = rootLoc[0] + rootLayout.getWidth();

        // If the camera overlaps the band, use the larger sub-band above/below it
        if (cameraRootWrapper != null && cameraRootWrapper.getVisibility() == View.VISIBLE) {
            cameraRootWrapper.getLocationOnScreen(tmp);
            int camTop = tmp[1];
            int camBottom = tmp[1] + cameraRootWrapper.getHeight();
            if (camBottom > bandTop && camTop < bandBottom) {
                int above = camTop - bandTop;
                int below = bandBottom - camBottom;
                if (above >= below) bandBottom = camTop; else bandTop = camBottom;
            }
        }

        float cx = (bandLeft + bandRight) / 2f;
        float cy = (bandTop + bandBottom) / 2f;
        swipeHintContainer.setX(cx - hintW / 2f - rootLoc[0]);
        swipeHintContainer.setY(cy - hintH / 2f - rootLoc[1]);
        swipeHintContainer.setAlpha(0f);
        swipeHintContainer.setVisibility(View.VISIBLE);

        final float dir = swipeHintShowingNext ? -1f : 1f;
        final float travel = 56f * density;
        swipeHintAnimator = ValueAnimator.ofFloat(0f, 1f);
        swipeHintAnimator.setDuration(1800);
        swipeHintAnimator.setRepeatCount(ValueAnimator.INFINITE);
        swipeHintAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float alpha, scale, move;
            if (t < 0.15f) {                       // fade in + press
                float p = t / 0.15f;
                alpha = p;
                scale = 1.1f - 0.15f * p;
                move = -travel / 2f;
            } else if (t < 0.75f) {                // swipe
                float p = (t - 0.15f) / 0.6f;
                float e = 1f - (1f - p) * (1f - p); // decelerate
                alpha = 1f;
                scale = 0.95f;
                move = -travel / 2f + travel * e;
            } else if (t < 0.9f) {                 // fade out
                float p = (t - 0.75f) / 0.15f;
                alpha = 1f - p;
                scale = 0.95f + 0.15f * p;
                move = travel / 2f;
            } else {                               // pause
                alpha = 0f;
                scale = 1.1f;
                move = travel / 2f;
            }
            ivSwipeHintHand.setAlpha(alpha);
            ivSwipeHintHand.setScaleX(scale);
            ivSwipeHintHand.setScaleY(scale);
            ivSwipeHintHand.setTranslationX(dir * move);
            tvSwipeHintLabel.setAlpha(0.6f + 0.4f * alpha);
        });
        swipeHintContainer.setAlpha(1f);
        swipeHintAnimator.start();
    }

    // --- Slide Manager Dialog ---

    private void openSlideManagerDialog() {
        managerDialog = new BottomSheetDialog(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_slide_manager, null);
        managerDialog.setContentView(dialogView);

        recyclerSlides = dialogView.findViewById(R.id.recycler_slides);
        recyclerSlides.setLayoutManager(new LinearLayoutManager(this));

        slideAdapter = new SlideAdapter(this);
        recyclerSlides.setAdapter(slideAdapter);
        slideAdapter.setSlides(new ArrayList<>(slides));

        ItemTouchHelper.SimpleCallback itemTouchCallback = new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                int fromPos = viewHolder.getBindingAdapterPosition();
                int toPos = target.getBindingAdapterPosition();
                if (fromPos != RecyclerView.NO_POSITION && toPos != RecyclerView.NO_POSITION && slideAdapter != null) {
                    slideAdapter.onItemMove(fromPos, toPos);
                    return true;
                }
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // Swiping not used for deletion
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.setAlpha(0.7f);
                    viewHolder.itemView.setScaleX(1.02f);
                    viewHolder.itemView.setScaleY(1.02f);
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                viewHolder.itemView.setAlpha(1.0f);
                viewHolder.itemView.setScaleX(1.0f);
                viewHolder.itemView.setScaleY(1.0f);

                if (slideAdapter != null) {
                    List<Slide> updatedList = slideAdapter.getSlides();
                    Slide currentSlide = (slides != null && currentSlideIndex >= 0 && currentSlideIndex < slides.size())
                            ? slides.get(currentSlideIndex) : null;

                    slides.clear();
                    slides.addAll(updatedList);

                    if (currentSlide != null) {
                        int newIndex = slides.indexOf(currentSlide);
                        if (newIndex != -1) {
                            currentSlideIndex = newIndex;
                        }
                    }

                    repository.updateAll(slides, () -> {
                        if (slideAdapter != null) {
                            slideAdapter.setSlides(slides);
                        }
                        renderCurrentSlide();
                    });
                }
            }
        };

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(itemTouchCallback);
        itemTouchHelper.attachToRecyclerView(recyclerSlides);

        Button btnAddText = dialogView.findViewById(R.id.btn_add_text_slide);
        Button btnAddImage = dialogView.findViewById(R.id.btn_add_image_slide);
        Button btnAddVideo = dialogView.findViewById(R.id.btn_add_video_slide);
        Button btnAddMore = dialogView.findViewById(R.id.btn_add_more_slide);
        Button btnClose = dialogView.findViewById(R.id.btn_close_manager);

        btnAddText.setOnClickListener(v -> showAddTextSlideDialog(null, -1));

        btnAddImage.setOnClickListener(v -> {
            photoPickerLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });

        if (btnAddVideo != null) {
            btnAddVideo.setOnClickListener(v -> {
                videoPickerLauncher.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                        .build());
            });
        }

        if (btnAddMore != null) {
            btnAddMore.setOnClickListener(v -> showMoreSlideTypesPopup());
        }

        btnClose.setOnClickListener(v -> managerDialog.dismiss());

        managerDialog.setOnDismissListener(dialog -> {
            slideAdapter = null;
            recyclerSlides = null;
            managerDialog = null;
            loadSlidesFromDb();
        });
        managerDialog.show();
    }

    private void showVideoSlideOptionsDialog(Slide slide, int position) {
        String[] options = {"Update / Replace Video", "View Slide"};
        new AlertDialog.Builder(this)
                .setTitle("Video Slide Options")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        launchVideoUpdater(slide, position);
                    } else if (which == 1) {
                        currentSlideIndex = position;
                        renderCurrentSlide();
                        if (managerDialog != null) managerDialog.dismiss();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddTextSlideDialog(Slide slideToEdit, int position) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_text, null);
        TextView title = dialogView.findViewById(R.id.dialog_title);
        EditText editText = dialogView.findViewById(R.id.edit_slide_text);
        TextView tvCharCount = dialogView.findViewById(R.id.tv_char_count);
        TextView tvLimitWarning = dialogView.findViewById(R.id.tv_limit_warning);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel_text);
        Button btnSave = dialogView.findViewById(R.id.btn_save_text);

        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});

        TextWatcher textWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                int length = s != null ? s.length() : 0;
                if (tvCharCount != null) {
                    tvCharCount.setText(length + "/200");
                    if (length >= 200) {
                        tvCharCount.setTextColor(Color.parseColor("#FF5252"));
                    } else if (length >= 180) {
                        tvCharCount.setTextColor(Color.parseColor("#FFB74D"));
                    } else {
                        tvCharCount.setTextColor(Color.parseColor("#AAAAAA"));
                    }
                }
                if (tvLimitWarning != null) {
                    tvLimitWarning.setVisibility(length >= 200 ? View.VISIBLE : View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        };
        editText.addTextChangedListener(textWatcher);

        if (slideToEdit != null) {
            title.setText("Edit Text Slide");
            editText.setText(slideToEdit.getTextContent());
        }

        int initialLen = editText.getText() != null ? editText.getText().length() : 0;
        if (tvCharCount != null) {
            tvCharCount.setText(initialLen + "/200");
        }
        if (tvLimitWarning != null) {
            tvLimitWarning.setVisibility(initialLen >= 200 ? View.VISIBLE : View.GONE);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSave.setOnClickListener(v -> {
            String text = editText.getText().toString().trim();
            if (text.isEmpty()) {
                Toast.makeText(this, "Text content cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }

            if (slideToEdit != null) {
                slideToEdit.setTextContent(text);
                repository.update(slideToEdit, () -> {
                    dialog.dismiss();
                    if (slideAdapter != null) slideAdapter.notifyItemChanged(position);
                    renderCurrentSlide();
                });
            } else {
                Slide newSlide = new Slide(Slide.TYPE_TEXT, slides.size(), text, null);
                repository.insert(newSlide, id -> {
                    dialog.dismiss();
                    loadSlidesFromDb(true);
                });
            }
        });

        dialog.show();
    }

    private void showMoreSlideTypesPopup() {
        View popupView = LayoutInflater.from(this).inflate(R.layout.dialog_more_slide_types, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(popupView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setWindowAnimations(R.style.DialogAnimation);
        }

        View cardWebsite = popupView.findViewById(R.id.card_type_website);
        View cardYouTube = popupView.findViewById(R.id.card_type_youtube);
        Button btnCancel = popupView.findViewById(R.id.btn_cancel_more);

        if (cardWebsite != null) {
            cardWebsite.setOnClickListener(v -> {
                dialog.dismiss();
                showAddWebsiteSlideDialog(null, -1);
            });
        }

        if (cardYouTube != null) {
            cardYouTube.setOnClickListener(v -> {
                dialog.dismiss();
                showAddYouTubeSlideDialog(null, -1);
            });
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void showAddWebsiteSlideDialog(Slide slideToEdit, int position) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_website, null);
        TextView title = dialogView.findViewById(R.id.dialog_website_title);
        EditText editUrl = dialogView.findViewById(R.id.edit_website_url);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel_website);
        Button btnSave = dialogView.findViewById(R.id.btn_save_website);

        if (slideToEdit != null) {
            if (title != null) title.setText("Edit Website Slide");
            if (editUrl != null) editUrl.setText(slideToEdit.getTextContent());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setWindowAnimations(R.style.DialogAnimation);
        }

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                String inputUrl = editUrl != null ? editUrl.getText().toString().trim() : "";
                if (inputUrl.isEmpty()) {
                    Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }

                inputUrl = normalizeWebsiteUrl(inputUrl);
                if (inputUrl == null) {
                    if (editUrl != null) editUrl.setError("Enter a valid website URL (e.g. example.com)");
                    return;
                }

                if (slideToEdit != null) {
                    slideToEdit.setTextContent(inputUrl);
                    repository.update(slideToEdit, () -> {
                        dialog.dismiss();
                        if (slideAdapter != null && position != -1) slideAdapter.notifyItemChanged(position);
                        renderCurrentSlide();
                    });
                } else {
                    Slide newSlide = new Slide(Slide.TYPE_WEBSITE, slides.size(), inputUrl, null);
                    repository.insert(newSlide, id -> {
                        dialog.dismiss();
                        loadSlidesFromDb(true);
                    });
                }
            });
        }

        dialog.show();
    }

    private void showAddYouTubeSlideDialog(Slide slideToEdit, int position) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_youtube, null);
        TextView title = dialogView.findViewById(R.id.dialog_youtube_title);
        EditText editUrl = dialogView.findViewById(R.id.edit_youtube_url);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel_youtube);
        Button btnSave = dialogView.findViewById(R.id.btn_save_youtube);

        if (slideToEdit != null) {
            if (title != null) title.setText("Edit YouTube Slide");
            if (editUrl != null) editUrl.setText(slideToEdit.getTextContent());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setWindowAnimations(R.style.DialogAnimation);
        }

        if (btnCancel != null) btnCancel.setOnClickListener(v -> dialog.dismiss());

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                String input = editUrl != null ? editUrl.getText().toString().trim() : "";
                if (input.isEmpty()) {
                    Toast.makeText(this, "YouTube URL or Video ID cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (parseYouTubeVideoId(input) == null) {
                    if (editUrl != null) editUrl.setError("Enter a valid YouTube link or 11-character video ID");
                    return;
                }

                if (slideToEdit != null) {
                    slideToEdit.setTextContent(input);
                    repository.update(slideToEdit, () -> {
                        dialog.dismiss();
                        if (slideAdapter != null && position != -1) slideAdapter.notifyItemChanged(position);
                        renderCurrentSlide();
                    });
                } else {
                    Slide newSlide = new Slide(Slide.TYPE_YOUTUBE, slides.size(), input, null);
                    repository.insert(newSlide, id -> {
                        dialog.dismiss();
                        loadSlidesFromDb(true);
                    });
                }
            });
        }

        dialog.show();
    }

    // --- SlideAdapter.SlideActionListener implementations ---

    @Override
    public void onSlideClick(Slide slide, int position) {
        if (slide.isDisabled()) {
            // Offer to re-enable the disabled slide
            new AlertDialog.Builder(this)
                    .setTitle("Slide Disabled")
                    .setMessage("This slide is currently disabled and hidden during presentation. Would you like to enable it?")
                    .setPositiveButton("Enable", (dialog, which) -> {
                        slide.setDisabled(false);
                        repository.update(slide, () -> {
                            if (slideAdapter != null) slideAdapter.notifyDataSetChanged();
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        if (Slide.TYPE_TEXT.equals(slide.getType())) {
            showAddTextSlideDialog(slide, position);
        } else if (Slide.TYPE_IMAGE.equals(slide.getType())) {
            showImageSlideOptionsDialog(slide, position);
        } else if (Slide.TYPE_VIDEO.equals(slide.getType())) {
            showVideoSlideOptionsDialog(slide, position);
        } else if (Slide.TYPE_WEBSITE.equals(slide.getType())) {
            showAddWebsiteSlideDialog(slide, position);
        } else if (Slide.TYPE_YOUTUBE.equals(slide.getType())) {
            showAddYouTubeSlideDialog(slide, position);
        }
    }

    @Override
    public void onMoveUp(int position) {
        if (position > 0) {
            Collections.swap(slides, position, position - 1);
            repository.updateAll(slides, () -> {
                slideAdapter.setSlides(slides);
                if (currentSlideIndex == position) currentSlideIndex = position - 1;
                else if (currentSlideIndex == position - 1) currentSlideIndex = position;
                renderCurrentSlide();
            });
        }
    }

    @Override
    public void onMoveDown(int position) {
        if (position < slides.size() - 1) {
            Collections.swap(slides, position, position + 1);
            repository.updateAll(slides, () -> {
                slideAdapter.setSlides(slides);
                if (currentSlideIndex == position) currentSlideIndex = position + 1;
                else if (currentSlideIndex == position + 1) currentSlideIndex = position;
                renderCurrentSlide();
            });
        }
    }

    @Override
    public void onDelete(Slide slide, int position) {
        if (!slide.isDisabled()) {
            // First click: Disable the slide
            new AlertDialog.Builder(this)
                    .setTitle("Disable Slide?")
                    .setMessage("This slide will be hidden during presentation mode. You can re-enable it later.")
                    .setPositiveButton("Disable", (dialog, which) -> {
                        slide.setDisabled(true);
                        repository.update(slide, () -> {
                            if (slideAdapter != null) slideAdapter.notifyDataSetChanged();
                            // If the currently displayed slide was disabled, resolve to next active
                            resolveCurrentSlideToActive();
                            renderCurrentSlide();
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            // Second click: Permanently delete the disabled slide
            new AlertDialog.Builder(this)
                    .setTitle("Permanently Delete?")
                    .setMessage("This slide will be permanently deleted. This action cannot be undone.")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        repository.delete(slide, () -> {
                            slides.remove(position);
                            slideAdapter.setSlides(slides);
                            if (currentSlideIndex >= slides.size()) {
                                currentSlideIndex = Math.max(0, slides.size() - 1);
                            }
                            resolveCurrentSlideToActive();
                            renderCurrentSlide();
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private void openHowToUseDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_how_to_use, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        Button btnClose = view.findViewById(R.id.btn_close_guide);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
    }

    private void openRecordSettingsDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_record_settings, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        int currentCountdown = prefs.getInt(KEY_COUNTDOWN_SECONDS, 3);
        boolean recordAudio = prefs.getBoolean(KEY_RECORD_AUDIO, true);

        RadioButton rb0 = view.findViewById(R.id.rb_countdown_0);
        RadioButton rb3 = view.findViewById(R.id.rb_countdown_3);
        RadioButton rb5 = view.findViewById(R.id.rb_countdown_5);
        RadioButton rb10 = view.findViewById(R.id.rb_countdown_10);

        if (currentCountdown == 0 && rb0 != null) rb0.setChecked(true);
        else if (currentCountdown == 5 && rb5 != null) rb5.setChecked(true);
        else if (currentCountdown == 10 && rb10 != null) rb10.setChecked(true);
        else if (rb3 != null) rb3.setChecked(true);

        RadioButton rbMic = view.findViewById(R.id.rb_audio_mic);
        RadioButton rbMute = view.findViewById(R.id.rb_audio_mute);

        if (recordAudio && rbMic != null) rbMic.setChecked(true);
        else if (!recordAudio && rbMute != null) rbMute.setChecked(true);

        // Auto-crop checkboxes
        CheckBox cbCrop916 = view.findViewById(R.id.cb_crop_9_16);
        CheckBox cbCrop45 = view.findViewById(R.id.cb_crop_4_5);
        CheckBox cbCrop11 = view.findViewById(R.id.cb_crop_1_1);

        if (cbCrop916 != null) cbCrop916.setChecked(prefs.getBoolean(KEY_AUTOCROP_9_16, true));
        if (cbCrop45 != null) cbCrop45.setChecked(prefs.getBoolean(KEY_AUTOCROP_4_5, false));
        if (cbCrop11 != null) cbCrop11.setChecked(prefs.getBoolean(KEY_AUTOCROP_1_1, false));

        setupPermissionsSection(view, dialog);

        Button btnCancel = view.findViewById(R.id.btn_cancel_settings);
        Button btnSave = view.findViewById(R.id.btn_save_settings);

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> dialog.dismiss());
        }

        if (btnSave != null) {
            btnSave.setOnClickListener(v -> {
                int newCountdown = 3;
                if (rb0 != null && rb0.isChecked()) newCountdown = 0;
                else if (rb5 != null && rb5.isChecked()) newCountdown = 5;
                else if (rb10 != null && rb10.isChecked()) newCountdown = 10;

                boolean newAudio = rbMic != null && rbMic.isChecked();

                prefs.edit()
                        .putInt(KEY_COUNTDOWN_SECONDS, newCountdown)
                        .putBoolean(KEY_RECORD_AUDIO, newAudio)
                        .putBoolean(KEY_AUTOCROP_9_16, cbCrop916 != null && cbCrop916.isChecked())
                        .putBoolean(KEY_AUTOCROP_4_5, cbCrop45 != null && cbCrop45.isChecked())
                        .putBoolean(KEY_AUTOCROP_1_1, cbCrop11 != null && cbCrop11.isChecked())
                        .apply();

                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
                dialog.dismiss();

                // A dialog never triggers onResume, so nothing would re-read the new
                // preferences until the app restarted. Refresh the affected UI here.
                applyRecordSettings();
            });
        }

        dialog.show();
    }

    /** A permission this app needs that the user hasn't granted yet. */
    private static final class MissingPermission {
        final String label;
        final String runtimePermission; // null for the "display over other apps" special access

        MissingPermission(String label, String runtimePermission) {
            this.label = label;
            this.runtimePermission = runtimePermission;
        }
    }

    /** Every permission the app asks for anywhere, filtered to the ones not granted. Add new ones here. */
    private List<MissingPermission> getMissingPermissionRows() {
        List<MissingPermission> rows = new ArrayList<>();
        for (String permission : getMissingPermissions()) {
            if (Manifest.permission.CAMERA.equals(permission)) {
                rows.add(new MissingPermission("Camera (your face cam)", permission));
            } else if (Manifest.permission.RECORD_AUDIO.equals(permission)) {
                rows.add(new MissingPermission("Microphone (your voice)", permission));
            } else if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) {
                rows.add(new MissingPermission("Notifications (stop recording from the shade)", permission));
            }
        }
        if (!Settings.canDrawOverlays(this)) {
            rows.add(new MissingPermission("Display over other apps (face cam in other apps)", null));
        }
        return rows;
    }

    /**
     * Fills the Settings dialog's permission section with one row per missing permission.
     * Rows rebuild whenever the user returns from a system screen, so a granted permission
     * disappears and the whole section hides once nothing is missing.
     */
    private void setupPermissionsSection(View dialogView, AlertDialog dialog) {
        View section = dialogView.findViewById(R.id.permissions_section);
        LinearLayout container = dialogView.findViewById(R.id.permissions_container);
        if (section == null || container == null) return;

        float density = getResources().getDisplayMetrics().density;
        refreshPermissionRows = () -> {
            container.removeAllViews();
            List<MissingPermission> missing = getMissingPermissionRows();
            section.setVisibility(missing.isEmpty() ? View.GONE : View.VISIBLE);
            for (MissingPermission item : missing) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                TextView label = new TextView(this);
                label.setText(item.label);
                label.setTextColor(0xFF1F1F1F);
                label.setTextSize(14);
                row.addView(label, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                Button allow = new Button(this);
                allow.setText("Allow");
                allow.setAllCaps(false);
                allow.setTextColor(0xFFFFFFFF);
                allow.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF6200EE));
                allow.setOnClickListener(v -> requestMissingPermission(item));
                row.addView(allow, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(40 * density)));

                container.addView(row);
            }
        };
        refreshPermissionRows.run();
        dialog.setOnDismissListener(d -> refreshPermissionRows = null);
    }

    private void requestMissingPermission(MissingPermission item) {
        if (item.runtimePermission == null) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", getPackageName(), null)));
        } else {
            pendingSettingsPermission = item.runtimePermission;
            settingsPermissionLauncher.launch(item.runtimePermission);
        }
    }

    /**
     * Re-applies every preference the main screen renders from.
     *
     * Countdown and mic/mute are read fresh at record time, so the crop guide lines
     * are the only thing that can go stale; this is the single place to extend if
     * another setting ever becomes visible here.
     */
    private void applyRecordSettings() {
        if (rootLayout == null) return;
        rootLayout.post(() -> {
            if (isRecording) {
                hideCropGuideLines();
            } else {
                showCropGuideLines();
            }
        });
    }

    private void openSavedRecordings() {
        Intent intent = new Intent(this, SavedRecordingsActivity.class);
        startActivity(intent);
    }

    private void openAboutDialog() {
        String appName = getString(R.string.app_name);
        new AlertDialog.Builder(this)
                .setTitle("About " + appName)
                .setMessage("Version 1.0.0\n\nA powerful, simple app for presenting slides with live face cam overlay and screen recording.\n\nPrivacy Notice: Camera and Screen Capture are used exclusively for live preview and recording when initiated by you.")
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void startTimedRecording(Intent serviceIntent, int countdownSeconds) {
        showCropGuideLines();

        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        if (countdownOverlayContainer != null) {
            countdownOverlayContainer.setVisibility(View.VISIBLE);
            countdownOverlayContainer.setAlpha(1.0f);
        }
        hideSwipeHint();

        if (tvCountdownNumber != null) {
            tvCountdownNumber.setText(String.valueOf(countdownSeconds));
        }

        countDownTimer = new CountDownTimer(countdownSeconds * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                int sec = Math.min(countdownSeconds, (int) Math.ceil(millisUntilFinished / 1000.0));
                if (sec > 0 && tvCountdownNumber != null) {
                    tvCountdownNumber.setText(String.valueOf(sec));
                    tvCountdownNumber.setScaleX(1.3f);
                    tvCountdownNumber.setScaleY(1.3f);
                    tvCountdownNumber.animate().scaleX(1.0f).scaleY(1.0f).setDuration(250).start();
                }
            }

            @Override
            public void onFinish() {
                if (countdownOverlayContainer != null) {
                    countdownOverlayContainer.setVisibility(View.GONE);
                }
                hideCropGuideLines();
                triggerStartFlashEffect(() -> {
                    ContextCompat.startForegroundService(MainActivity.this, serviceIntent);
                    isRecording = true;
                    isMenuBarVisible = false;
                    setPresentationMode(true);
                    updateUIState();
                });
            }
        }.start();
    }

    private void cancelCountdown() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        if (countdownOverlayContainer != null) {
            countdownOverlayContainer.setVisibility(View.GONE);
        }
        hideCropGuideLines();
        updateNavigationButtonsState();
        Toast.makeText(this, "Recording cancelled", Toast.LENGTH_SHORT).show();
    }

    private void triggerStartFlashEffect(Runnable onComplete) {
        if (flashOverlayView == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        flashOverlayView.setVisibility(View.VISIBLE);
        flashOverlayView.setAlpha(0.85f);

        if (onComplete != null) {
            onComplete.run();
        }

        flashOverlayView.animate()
                .alpha(0.0f)
                .setDuration(200)
                .withEndAction(() -> flashOverlayView.setVisibility(View.GONE))
                .start();
    }

    private final BroadcastReceiver recordingFinishedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ScreenRecordService.ACTION_RECORDING_FINISHED.equals(intent.getAction())) {
                if (isRecording) {
                    // Stopped externally (notification action / system "stop sharing")
                    onRecordingStoppedUi();
                }
                String path = intent.getStringExtra(ScreenRecordService.EXTRA_VIDEO_PATH);
                if (path != null) {
                    File file = new File(path);
                    if (file.exists() && file.length() > 0) {
                        Dialog dialog = VideoPlayerDialog.show(MainActivity.this, file, null);

                        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                        List<int[]> ratios = new ArrayList<>();
                        if (prefs.getBoolean(KEY_AUTOCROP_9_16, true)) ratios.add(new int[]{9, 16});
                        if (prefs.getBoolean(KEY_AUTOCROP_4_5, false)) ratios.add(new int[]{4, 5});
                        if (prefs.getBoolean(KEY_AUTOCROP_1_1, false)) ratios.add(new int[]{1, 1});

                        if (!ratios.isEmpty() && dialog != null) {
                            TextView tvCropStatus = dialog.findViewById(R.id.tv_crop_status);
                            if (tvCropStatus != null) {
                                tvCropStatus.setText("⏳ Auto-cropping as per your settings…");
                                tvCropStatus.setVisibility(View.VISIBLE);
                            }

                            VideoCropHelper.cropAsync(
                                getApplicationContext(), path, ratios,
                                new VideoCropHelper.CropCallback() {
                                    @Override
                                    public void onProgress(String ratioLabel, boolean success) {
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
    };

    private void registerRecordingFinishedReceiver() {
        IntentFilter filter = new IntentFilter(ScreenRecordService.ACTION_RECORDING_FINISHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingFinishedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recordingFinishedReceiver, filter);
        }
    }

    private void unregisterRecordingFinishedReceiver() {
        try {
            unregisterReceiver(recordingFinishedReceiver);
        } catch (Exception ignored) {}
    }

    private void applyTextSlideFontScaling(TextView textView, String text) {
        if (textView == null || text == null) return;
        int length = text.length();
        float maxFontSizeSp = 26.0f;
        float minFontSizeSp = 15.0f;

        float fontSizeSp;
        if (length <= 100) {
            fontSizeSp = maxFontSizeSp;
        } else if (length >= 200) {
            fontSizeSp = minFontSizeSp;
        } else {
            float fraction = (length - 100) / 100.0f;
            fontSizeSp = maxFontSizeSp - fraction * (maxFontSizeSp - minFontSizeSp);
        }
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp);
    }

    private boolean isSlideZoomed() {
        View activeView = getActiveSlideView();
        return activeView != null && activeView.getScaleX() > 1.05f;
    }

    private View getActiveSlideView() {
        if (textSlideContainer != null && textSlideContainer.getVisibility() == View.VISIBLE) {
            return textSlideContainer;
        } else if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
            return imageSlideView;
        } else if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
            return videoSlideContainer;
        } else if (webSlideContainer != null && webSlideContainer.getVisibility() == View.VISIBLE) {
            return webSlideContainer;
        }
        return null;
    }

    private void resetAllSlideZoom() {
        View[] views = new View[]{imageSlideView, textSlideContainer, videoSlideContainer, webSlideContainer};
        for (View v : views) {
            if (v != null) {
                v.setScaleX(1.0f);
                v.setScaleY(1.0f);
                v.setTranslationX(0.0f);
                v.setTranslationY(0.0f);
            }
        }
    }

    private void setupSlideZoomTouchListeners() {
        View[] slideViews = new View[]{imageSlideView, textSlideContainer, videoSlideContainer, webSlideContainer};

        for (View targetView : slideViews) {
            if (targetView == null) continue;

            targetView.setClickable(true);

            ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override
                        public boolean onScale(ScaleGestureDetector detector) {
                            float scaleFactor = detector.getScaleFactor();
                            float currentScale = targetView.getScaleX() * scaleFactor;
                            currentScale = Math.max(1.0f, Math.min(4.0f, currentScale));

                            if (targetView.getWidth() > 0 && targetView.getHeight() > 0) {
                                targetView.setPivotX(detector.getFocusX());
                                targetView.setPivotY(detector.getFocusY());
                            }

                            targetView.setScaleX(currentScale);
                            targetView.setScaleY(currentScale);

                            if (currentScale <= 1.0f) {
                                targetView.setTranslationX(0f);
                                targetView.setTranslationY(0f);
                            }
                            return true;
                        }
                    });

            GestureDetector doubleTapDetector = new GestureDetector(this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDoubleTap(MotionEvent e) {
                            if (targetView.getScaleX() > 1.05f) {
                                targetView.animate()
                                        .scaleX(1.0f)
                                        .scaleY(1.0f)
                                        .translationX(0.0f)
                                        .translationY(0.0f)
                                        .setDuration(200)
                                        .start();
                                return true;
                            }
                            return false;
                        }
                    });

            targetView.setOnTouchListener(new View.OnTouchListener() {
                private float lastX, lastY;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    scaleDetector.onTouchEvent(event);
                    doubleTapDetector.onTouchEvent(event);

                    float scale = v.getScaleX();

                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            lastX = event.getRawX();
                            lastY = event.getRawY();
                            if (scale > 1.05f) {
                                return true;
                            }
                            return false;

                        case MotionEvent.ACTION_MOVE:
                            if (scaleDetector.isInProgress()) {
                                return true;
                            }
                            if (scale > 1.05f && event.getPointerCount() == 1) {
                                float dx = event.getRawX() - lastX;
                                float dy = event.getRawY() - lastY;

                                float maxTranslationX = (v.getWidth() * (scale - 1.0f)) / 2.0f;
                                float maxTranslationY = (v.getHeight() * (scale - 1.0f)) / 2.0f;

                                float newTx = Math.max(-maxTranslationX, Math.min(maxTranslationX, v.getTranslationX() + dx));
                                float newTy = Math.max(-maxTranslationY, Math.min(maxTranslationY, v.getTranslationY() + dy));

                                v.setTranslationX(newTx);
                                v.setTranslationY(newTy);

                                lastX = event.getRawX();
                                lastY = event.getRawY();
                                return true;
                            }
                            break;
                    }

                    return scale > 1.05f || scaleDetector.isInProgress() || event.getPointerCount() > 1;
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        hideSwipeHint();
        hideHandleHandler.removeCallbacks(flipFreezeTimeoutRunnable);
        unregisterRecordingFinishedReceiver();
        stopVideoIfPlaying();
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        cancelGuideLineFlashes();
    }
}