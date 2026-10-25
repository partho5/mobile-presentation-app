package com.jovoc.facecampresentationrecorder;

import android.Manifest;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import com.jovoc.facecampresentationrecorder.bgreplace.BubbleTrack;
import com.jovoc.facecampresentationrecorder.ui.DrawingBoardController;
import com.jovoc.facecampresentationrecorder.ui.DrawingBoardView;
import com.jovoc.facecampresentationrecorder.ui.Gate;
import com.jovoc.facecampresentationrecorder.ui.PremiumMark;
import com.jovoc.facecampresentationrecorder.ui.SlideInkController;
import com.jovoc.facecampresentationrecorder.ui.SlideTransitionController;
import com.jovoc.facecampresentationrecorder.util.SlideInkStorage;
import com.jovoc.facecampresentationrecorder.ui.VideoPlayerDialog;
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
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
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
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.content.SharedPreferences;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ProgressBar;
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

import androidx.activity.OnBackPressedCallback;
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
import com.bumptech.glide.RequestBuilder;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.adapter.SlideAdapter;
import com.jovoc.facecampresentationrecorder.db.Slide;
import com.jovoc.facecampresentationrecorder.db.SlideRepository;
import com.jovoc.facecampresentationrecorder.entitlements.Feature;
import com.jovoc.facecampresentationrecorder.entitlements.FeatureSnapshot;
import com.jovoc.facecampresentationrecorder.service.BgReplaceService;
import com.jovoc.facecampresentationrecorder.service.ScreenRecordService;
import com.jovoc.facecampresentationrecorder.util.AudioInputs;
import com.jovoc.facecampresentationrecorder.util.ImageStorageHelper;
import com.jovoc.facecampresentationrecorder.util.RecordSettings;
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

public class MainActivity extends AppCompatActivity
        implements SlideAdapter.SlideActionListener,
                   com.jovoc.facecampresentationrecorder.ui.EditorLauncher.Host {

    /** Editor started from here; a successful Save shows the "Delete original?" offer. */
    private final ActivityResultLauncher<Intent> editorLauncher =
            com.jovoc.facecampresentationrecorder.ui.EditorLauncher.register(this, null);

    private static final String TAG = "MainActivity";

    private SlideRepository repository;
    private List<Slide> slides = new ArrayList<>();
    private int currentSlideIndex = 0;

    // Slide transition: id of the slide whose content is on screen (changes only while the scrim
    // covers the stage), the next/previous direction of the pending render, and the owner of the fade.
    private int displayedSlideId = SlideInkController.NO_SLIDE;
    private int pendingNavDirection = 0;
    private SlideTransitionController slideTransition;
    // Released by the web slide's first paint (or error); null when nothing is waiting.
    private Runnable webSlideReadyCallback;

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
    private WebView webSlideView; // shared view, used only for YouTube embeds
    private FrameLayout webViewHost;
    /** The WebView on screen right now (the shared one or a pooled browser one); null when no web slide shows. */
    private WebView activeWebView;
    /** One live WebView per browser slide (slide id -> view), least recently shown first. */
    private final java.util.LinkedHashMap<Integer, WebView> browserWebViews = new java.util.LinkedHashMap<>();
    private static final int MAX_LIVE_BROWSER_VIEWS = 3;
    private SwipeRefreshLayout swipeRefreshWebSlide;
    private ImageButton btnReloadWebSlide;
    /** Browser-style bar above a website slide: back / forward / reload. Hidden for YouTube slides. */
    private View webNavBar;
    private View webNavBarDivider;
    private ImageButton btnWebBack;
    private ImageButton btnWebForward;
    /** Set when the shared YouTube view loads a new embed, so its history starts at that video (not at the blank page before it). */
    private boolean clearYoutubeHistory = false;
    /** A website slide is 1.5x as tall as wide, but never taller than this share of the screen. */
    private static final float WEB_SLIDE_HEIGHT_FACTOR = 1.5f;
    private static final float WEB_SLIDE_MAX_SCREEN_SHARE = 0.70f;
    /** While recording, a first Back with nothing to go back to only arms the exit; a second one inside this window exits. */
    private static final long BACK_EXIT_WINDOW_MS = 2000;
    private long backExitArmedUntil = 0;
    private MediaController mediaController;
    private TextView emptyStateView;
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
    /** The button plus its premium crown; this is what shows and hides, with the recording. */
    private View wrapDrawingBoard;
    private ImageView crownDrawingBoard;
    private View drawingBoardContainer;
    private DrawingBoardController drawingBoardController;
    private boolean boardTouchActive = false;
    private boolean isDrawingBoardVisible = false;
    private boolean isDrawingBoardAnimating = false;
    private static final long DRAWING_BOARD_ANIM_MS = 250L;

    // Slide pen: marks drawn on the current slide (shown always, drawn only in pen mode while recording)
    private ImageButton btnSlidePen;
    private View wrapSlidePen;
    private ImageView crownSlidePen;
    private DrawingBoardView slideInkView;
    private SlideInkController slideInkController;
    // Cap on the ink layer's height so a very tall image can't force a huge bitmap.
    private static final int SLIDE_INK_MAX_HEIGHT_SCREENS = 3;

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
    /** True once the preview is bound; lets onResume start it after permission is granted elsewhere. */
    private boolean cameraPreviewStarted = false;
    private Observer<PreviewView.StreamState> flipStreamObserver;
    private final Runnable flipFreezeTimeoutRunnable = this::hideFreezeFrame;

    private ScaleGestureDetector scaleGestureDetector;
    private float dX, dY;
    private float initialResizeTouchX, initialResizeTouchY;
    private int initialCardWidth;

    private final Handler hideHandleHandler = new Handler(Looper.getMainLooper());
    // While the activity is visible during a recording, logs where the face cam circle is
    // so its background can be replaced after recording (see BubbleTrack)
    private final Handler bubbleTrackHandler = new Handler(Looper.getMainLooper());
    private final Runnable bubbleTrackRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRecording && cameraCardContainer != null) {
                BubbleTrack.report(cameraCardContainer, cameraCardContainer.getStrokeWidth());
            }
            bubbleTrackHandler.postDelayed(this, 33);
        }
    };
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
    private AudioDeviceCallback audioDeviceCallback;
    private boolean overlayRequested = false;
    private boolean leftAppWithoutOverlayPermission = false;

    // Launchers
    private ActivityResultLauncher<PickVisualMediaRequest> photoPickerLauncher;
    private ActivityResultLauncher<PickVisualMediaRequest> updateImagePickerLauncher;
    private ActivityResultLauncher<PickVisualMediaRequest> videoPickerLauncher;
    private ActivityResultLauncher<PickVisualMediaRequest> updateVideoPickerLauncher;
    private ActivityResultLauncher<String[]> permissionLauncher;
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
        webViewHost = findViewById(R.id.web_view_host);
        swipeRefreshWebSlide = findViewById(R.id.swipe_refresh_web_slide);
        btnReloadWebSlide = findViewById(R.id.btn_reload_web_slide);

        View slideStage = findViewById(R.id.slide_stage);
        View slideScrim = findViewById(R.id.slide_transition_scrim);
        if (slideStage != null && slideScrim != null) {
            slideTransition = new SlideTransitionController(slideStage, slideScrim);
        }

        webNavBar = findViewById(R.id.web_nav_bar);
        webNavBarDivider = findViewById(R.id.web_nav_bar_divider);
        btnWebBack = findViewById(R.id.btn_web_back);
        btnWebForward = findViewById(R.id.btn_web_forward);
        if (btnWebBack != null) {
            btnWebBack.setOnClickListener(v -> {
                if (activeWebView != null && activeWebView.canGoBack()) activeWebView.goBack();
            });
        }
        if (btnWebForward != null) {
            btnWebForward.setOnClickListener(v -> {
                if (activeWebView != null && activeWebView.canGoForward()) activeWebView.goForward();
            });
        }
        if (btnReloadWebSlide != null) {
            btnReloadWebSlide.setOnClickListener(v -> {
                if (activeWebView != null) activeWebView.reload();
            });
        }
        updateWebNavState();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // A browser slide's own history comes first, like Back in a browser.
                if (activeWebView != null
                        && webSlideContainer != null && webSlideContainer.getVisibility() == View.VISIBLE
                        && activeWebView.canGoBack()) {
                    activeWebView.goBack();
                    return;
                }
                // Mid-recording a stray Back must not end the session: it takes two quick presses.
                if (isRecording) {
                    long now = SystemClock.elapsedRealtime();
                    if (now > backExitArmedUntil) {
                        backExitArmedUntil = now + BACK_EXIT_WINDOW_MS;
                        Toast.makeText(MainActivity.this, "Press back again to exit", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });

        if (swipeRefreshWebSlide != null) {
            // The scroll target is the host FrameLayout, so ask the visible WebView whether it can scroll up.
            swipeRefreshWebSlide.setOnChildScrollUpCallback((parent, child) ->
                    activeWebView != null && activeWebView.canScrollVertically(-1));
            swipeRefreshWebSlide.setOnRefreshListener(() -> {
                if (activeWebView != null) {
                    activeWebView.reload();
                    Toast.makeText(this, "Reloading page...", Toast.LENGTH_SHORT).show();
                } else if (swipeRefreshWebSlide != null) {
                    swipeRefreshWebSlide.setRefreshing(false);
                }
            });
        }

        if (webSlideView != null) configureWebView(webSlideView);
        emptyStateView = findViewById(R.id.empty_state_view);
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

    private static final String PREF_NAME = RecordSettings.PREF_NAME;
    private static final String KEY_CAM_SIZE = "key_cam_size";
    private static final String KEY_CAM_POS_X = "key_cam_pos_x";
    private static final String KEY_CAM_POS_Y = "key_cam_pos_y";
    private static final String KEY_CAM_LENS_FACING = "key_cam_lens_facing";
    private static final String KEY_COUNTDOWN_SECONDS = RecordSettings.KEY_COUNTDOWN_SECONDS;

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
        float center = cardSize / 2f;
        float resizeX;
        float resizeY;
        float flipX;
        float flipY;
        if (isRecording) {
            // The icons are part of the screen recording, so while recording they sit fully
            // outside the circle; inside it they would be mistaken for background and replaced.
            float flipSize = btnFlipCamera != null && btnFlipCamera.getWidth() > 0
                    ? btnFlipCamera.getWidth() : handleSize;
            float diag = 0.70711f;
            float resizeDist = cardSize / 2f + handleSize * 0.6f;
            float flipDist = cardSize / 2f + flipSize * 0.6f;
            resizeX = center + resizeDist * diag - handleSize / 2f;
            resizeY = center + resizeDist * diag - handleSize / 2f;
            flipX = center - flipDist * diag - flipSize / 2f;
            flipY = center + flipDist * diag - flipSize / 2f;
        } else {
            // 45-degree angle on circle border is at (cardSize * 0.85355f, cardSize * 0.85355f).
            float borderPos = cardSize * 0.85355f;
            float flipSize = btnFlipCamera != null && btnFlipCamera.getWidth() > 0
                    ? btnFlipCamera.getWidth() : handleSize;
            resizeX = borderPos - handleSize / 2f;
            resizeY = borderPos - handleSize / 2f;
            // Mirror of the resize handle: bottom-left 45 degrees on the circle border.
            flipX = cardSize * 0.14645f - flipSize / 2f;
            flipY = borderPos - flipSize / 2f;
        }
        btnResizeHandle.setTranslationX(resizeX);
        btnResizeHandle.setTranslationY(resizeY);
        if (btnFlipCamera != null) {
            btnFlipCamera.setTranslationX(flipX);
            btnFlipCamera.setTranslationY(flipY);
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
        // A ratio the plan does not cover gets no guide line, as it gets no crop
        showOneGuideLine(guideLineView916, guideLabel916,
                RecordSettings.autoCrop916(this), 9, 16);
        showOneGuideLine(guideLineView45, guideLabel45,
                RecordSettings.autoCrop45(this), 4, 5);
        showOneGuideLine(guideLineView11, guideLabel11,
                RecordSettings.autoCrop11(this), 1, 1);
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

        // Recording: these follow the plan the recording started with
        checkGuideLineOverlap(GUIDE_9_16, guideLineView916, guideLabel916,
                RecordSettings.autoCrop916(this), 9, 16);
        checkGuideLineOverlap(GUIDE_4_5, guideLineView45, guideLabel45,
                RecordSettings.autoCrop45(this), 4, 5);
        checkGuideLineOverlap(GUIDE_1_1, guideLineView11, guideLabel11,
                RecordSettings.autoCrop11(this), 1, 1);
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

    /** White icons for every drawer entry except the full-colour "Auto upload" one, so the view-wide tint is off. */
    private void tintDrawerIcons() {
        navigationView.setItemIconTintList(null);
        android.view.Menu menu = navigationView.getMenu();
        for (int i = 0; i < menu.size(); i++) {
            android.view.MenuItem item = menu.getItem(i);
            android.graphics.drawable.Drawable icon = item.getIcon();
            if (icon == null || item.getItemId() == R.id.nav_account) continue;
            icon = androidx.core.graphics.drawable.DrawableCompat.wrap(icon.mutate());
            androidx.core.graphics.drawable.DrawableCompat.setTint(icon, 0xFFFFFFFF);
            item.setIcon(icon);
        }
    }

    private void setupDrawer() {
        ImageButton btnOpenDrawer = findViewById(R.id.btn_open_drawer);

        if (btnOpenDrawer != null) {
            btnOpenDrawer.setOnClickListener(v -> {
                ActivityLog.log("drawer_open");
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            });
        }

        if (navigationView != null) {
            tintDrawerIcons();
            navigationView.setNavigationItemSelectedListener(item -> {
                int itemId = item.getItemId();
                ActivityLog.log("drawer_item", "item", drawerItemName(itemId));
                if (itemId == R.id.nav_edit_slides) {
                    openSlideManagerDialog();
                } else if (itemId == R.id.nav_record_settings) {
                    startActivity(new Intent(this, com.jovoc.facecampresentationrecorder.ui.SettingsActivity.class));
                } else if (itemId == R.id.nav_saved_recordings) {
                    openSavedRecordings();
                } else if (itemId == R.id.nav_account) {
                    // Signed in: the Auto upload screen; signed out: sign-in first, then that screen.
                    Intent autoPublish = new Intent(this, com.jovoc.facecampresentationrecorder.ui.AutoPublishActivity.class);
                    if (com.jovoc.facecampresentationrecorder.auth.SessionManager.get(this).hasSession()) {
                        startActivity(autoPublish);
                    } else {
                        com.jovoc.facecampresentationrecorder.auth.SessionManager.openSignIn(this, autoPublish);
                    }
                } else if (itemId == R.id.nav_help) {
                    startActivity(com.jovoc.facecampresentationrecorder.ui.OnboardingActivity.forGuide(this));
                } else if (itemId == R.id.nav_contact) {
                    startActivity(new Intent(this, com.jovoc.facecampresentationrecorder.ui.ContactActivity.class));
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
        ActivityLog.log("camera_flip", "to", next == CameraSelector.LENS_FACING_FRONT ? "front" : "back");

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
        wrapDrawingBoard = findViewById(R.id.wrap_drawing_board);
        crownDrawingBoard = findViewById(R.id.iv_premium_drawing_board);
        wrapSlidePen = findViewById(R.id.wrap_slide_pen);
        crownSlidePen = findViewById(R.id.iv_premium_slide_pen);
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
        setupSlideInk();
    }

    private void setupSlideInk() {
        if (!SlideInkStorage.isPersistentDrawingSlide) {
            SlideInkStorage.clearSavedFiles(this);
        }
        btnSlidePen = findViewById(R.id.btn_slide_pen);
        slideInkView = findViewById(R.id.slide_ink_canvas);
        if (btnSlidePen == null || slideInkView == null || rootLayout == null) return;
        slideInkController = new SlideInkController(rootLayout);
        // One drawing mode at a time: pen mode closes the board (and opening the board ends pen mode).
        slideInkController.setListener(penMode -> {
            if (penMode && isDrawingBoardVisible) hideDrawingBoard(true);
        });
        // The slide's size changes after the fact (image/video loaded, text container sized): refit the ink.
        View.OnLayoutChangeListener refit = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (l != ol || t != ot || r != or || b != ob) slideInkView.post(this::syncSlideInk);
        };
        View[] slideViews = {imageSlideView, textSlideContainer, videoSlideContainer, webSlideContainer};
        for (View v : slideViews) {
            if (v != null) v.addOnLayoutChangeListener(refit);
        }
    }

    /**
     * The view that hosts the slide that is on screen, by slide type. Resolved by displayedSlideId (not
     * currentSlideIndex): during a transition the index already points at the next slide.
     */
    private View getCurrentSlideView() {
        if (slides == null || displayedSlideId == SlideInkController.NO_SLIDE) return null;
        Slide displayed = null;
        for (Slide s : slides) {
            if (s.getId() == displayedSlideId) {
                displayed = s;
                break;
            }
        }
        if (displayed == null) return null;
        String type = displayed.getType();
        if (Slide.TYPE_TEXT.equals(type)) return textSlideContainer;
        if (Slide.TYPE_IMAGE.equals(type)) return imageSlideView;
        if (Slide.TYPE_VIDEO.equals(type)) return videoSlideContainer;
        return webSlideContainer; // website + YouTube
    }

    /** Settings and clients shared by the YouTube WebView and every browser-slide WebView. */
    private void configureWebView(WebView webView) {
        WebSettings settings = webView.getSettings();
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
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                if (view == activeWebView) updateWebNavState();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (view == webSlideView && clearYoutubeHistory && !"about:blank".equals(url)) {
                    clearYoutubeHistory = false;
                    view.clearHistory();
                }
                if (view == activeWebView) updateWebNavState();
                if (view == activeWebView && swipeRefreshWebSlide != null) {
                    swipeRefreshWebSlide.setRefreshing(false);
                }
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                if (view == activeWebView) releaseWebSlideReady();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (view == activeWebView) {
                    if (swipeRefreshWebSlide != null) swipeRefreshWebSlide.setRefreshing(false);
                    releaseWebSlideReady();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
    }

    /**
     * Makes {@code target} the only visible WebView. The one that was on screen keeps its page: browser
     * views just stop their media and rendering, and the YouTube view is blanked so the video stops.
     */
    private void showWebView(WebView target) {
        if (activeWebView != null && activeWebView != target) deactivateWebView(activeWebView);
        activeWebView = target;
        updateWebNavState();
        if (target == null) return;
        target.setVisibility(View.VISIBLE);
        target.onResume();
    }

    /** Dims back / forward when the page on screen has nowhere to go in that direction. */
    private void updateWebNavState() {
        boolean shown = activeWebView != null;
        setWebNavEnabled(btnWebBack, shown && activeWebView.canGoBack());
        setWebNavEnabled(btnWebForward, shown && activeWebView.canGoForward());
    }

    private static void setWebNavEnabled(ImageButton button, boolean enabled) {
        if (button == null) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.35f);
    }

    private void setWebNavBarVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        if (webNavBar != null) webNavBar.setVisibility(v);
        if (webNavBarDivider != null) webNavBarDivider.setVisibility(v);
    }

    /** Website slide height: 1.5x the width, capped to a share of the screen so it never swallows it. */
    private int websiteSlideHeight(int displayWidth) {
        int viewHeight = rootLayout != null && rootLayout.getHeight() > 0
                ? rootLayout.getHeight() : getResources().getDisplayMetrics().heightPixels;
        return Math.min(Math.round(displayWidth * WEB_SLIDE_HEIGHT_FACTOR),
                Math.round(viewHeight * WEB_SLIDE_MAX_SCREEN_SHARE));
    }

    private void deactivateWebView(WebView view) {
        view.setVisibility(View.GONE);
        if (view == webSlideView) {
            view.loadUrl("about:blank");
        } else {
            view.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();})", null);
            view.onPause();
        }
    }

    /** Hides the web area (the slide on screen is not a web slide) without discarding any browser page. */
    private void hideWebSlide() {
        if (webSlideContainer != null) webSlideContainer.setVisibility(View.GONE);
        showWebView(null);
    }

    /**
     * The live WebView for a browser slide, created (and loaded) on first use. Returns null when the
     * slide's URL changed since it was loaded, after dropping the stale view so the caller reloads.
     * Past MAX_LIVE_BROWSER_VIEWS the least recently shown view is destroyed and its slide starts over.
     */
    private WebView browserWebViewFor(Slide slide, String url, boolean[] created) {
        pruneBrowserWebViews();
        WebView view = browserWebViews.remove(slide.getId()); // re-inserted below: marks it most recent
        if (view != null && !url.equals(view.getTag())) {
            destroyBrowserWebView(view);
            view = null;
        }
        if (view == null) {
            view = new WebView(this);
            view.setTag(url);
            view.setVisibility(View.GONE);
            configureWebView(view);
            webViewHost.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            created[0] = true;
        }
        browserWebViews.put(slide.getId(), view);
        while (browserWebViews.size() > MAX_LIVE_BROWSER_VIEWS) {
            java.util.Iterator<java.util.Map.Entry<Integer, WebView>> it = browserWebViews.entrySet().iterator();
            WebView eldest = it.next().getValue();
            if (eldest == view || eldest == activeWebView) break;
            it.remove();
            destroyBrowserWebView(eldest);
        }
        return view;
    }

    /** Drops WebViews of browser slides that no longer exist. */
    private void pruneBrowserWebViews() {
        java.util.Iterator<java.util.Map.Entry<Integer, WebView>> it = browserWebViews.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Integer, WebView> entry = it.next();
            boolean exists = false;
            for (Slide s : slides) {
                if (s.getId() == entry.getKey() && Slide.TYPE_WEBSITE.equals(s.getType())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                it.remove();
                destroyBrowserWebView(entry.getValue());
            }
        }
    }

    private void destroyBrowserWebView(WebView view) {
        if (view == activeWebView) activeWebView = null;
        webViewHost.removeView(view);
        view.stopLoading();
        view.destroy();
    }

    private void releaseWebSlideReady() {
        Runnable ready = webSlideReadyCallback;
        webSlideReadyCallback = null;
        if (ready != null) ready.run();
    }

    /**
     * Fits the ink layer exactly over the current slide (same bounds, same zoom), so a stroke's
     * normalised points are relative to the slide and nothing outside the slide can be drawn on.
     */
    private void syncSlideInk() {
        if (slideInkView == null || rootLayout == null) return;
        View slideView = getCurrentSlideView();
        if (slideView == null || slideView.getVisibility() != View.VISIBLE
                || slideView.getWidth() <= 0 || slideView.getHeight() <= 0) {
            slideInkView.setVisibility(View.GONE);
            return;
        }
        int w = slideView.getWidth();
        int h = Math.min(slideView.getHeight(), SLIDE_INK_MAX_HEIGHT_SCREENS * Math.max(1, rootLayout.getHeight()));
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) slideInkView.getLayoutParams();
        if (lp.width != w || lp.height != h
                || lp.leftMargin != slideView.getLeft() || lp.topMargin != slideView.getTop()) {
            lp.width = w;
            lp.height = h;
            lp.leftMargin = slideView.getLeft();
            lp.topMargin = slideView.getTop();
            slideInkView.setLayoutParams(lp);
        }
        // Follow pinch-zoom / pan. Below zoom there is nothing to follow.
        boolean zoomed = slideView.getScaleX() > 1.05f;
        slideInkView.setPivotX(slideView.getPivotX());
        slideInkView.setPivotY(slideView.getPivotY());
        slideInkView.setScaleX(zoomed ? slideView.getScaleX() : 1f);
        slideInkView.setScaleY(zoomed ? slideView.getScaleY() : 1f);
        slideInkView.setTranslationX(zoomed ? slideView.getTranslationX() : 0f);
        slideInkView.setTranslationY(zoomed ? slideView.getTranslationY() : 0f);
        slideInkView.setVisibility(View.VISIBLE);
    }

    private boolean isTouchInsideSlideInk(MotionEvent ev) {
        if (slideInkController == null || !slideInkController.isPenMode()
                || slideInkView == null || slideInkView.getVisibility() != View.VISIBLE) {
            return false;
        }
        int[] location = new int[2];
        slideInkView.getLocationOnScreen(location);
        float w = slideInkView.getWidth() * slideInkView.getScaleX();
        float h = slideInkView.getHeight() * slideInkView.getScaleY();
        return ev.getRawX() >= location[0] && ev.getRawX() <= location[0] + w
                && ev.getRawY() >= location[1] && ev.getRawY() <= location[1] + h;
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

    private void setToolGap(View wrap, int startDp) {
        if (wrap == null || !(wrap.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) wrap.getLayoutParams();
        int px = Math.round(startDp * getResources().getDisplayMetrics().density);
        if (lp.getMarginStart() == px) return;
        lp.setMarginStart(px);
        wrap.setLayoutParams(lp);
    }

    /** Pen off and board closed at each start and stop of a recording; the drawn marks stay. */
    private void resetDrawingTools() {
        if (slideInkController != null) slideInkController.setPenMode(false);
        hideDrawingBoard(false);
    }

    private void toggleDrawingBoard() {
        if (drawingBoardContainer == null || isDrawingBoardAnimating) return;
        if (!isDrawingBoardVisible && !FeatureSnapshot.allows(Feature.DRAWING_BOARD)) {
            if (isRecording) {
                // No sheet while recording (it would end up in the video).
                Gate.lockedWhileRecording(this, Feature.DRAWING_BOARD);
            } else {
                Gate.run(this, Feature.DRAWING_BOARD, this::showDrawingBoard);
            }
            return;
        }
        ActivityLog.log("drawing_board_toggle", "on", !isDrawingBoardVisible);
        if (isDrawingBoardVisible) {
            hideDrawingBoard(true);
        } else {
            showDrawingBoard();
        }
    }

    /** The premium crown on the two recording tools: gold while the plan lacks one, none for a free tool. */
    private void applyToolMarks() {
        if (crownDrawingBoard != null) PremiumMark.apply(crownDrawingBoard, Feature.DRAWING_BOARD);
        if (crownSlidePen != null) PremiumMark.apply(crownSlidePen, Feature.SLIDE_INK);
    }

    private void showDrawingBoard() {
        // The plan this recording started with decides, whoever asks.
        if (!FeatureSnapshot.allows(Feature.DRAWING_BOARD)) return;
        if (slideInkController != null) slideInkController.setPenMode(false);
        sizeDrawingBoard();
        int side = drawingBoardContainer.getLayoutParams().height;
        drawingBoardContainer.setTranslationY(-side);
        drawingBoardContainer.setVisibility(View.VISIBLE);
        isDrawingBoardVisible = true;
        if (drawingBoardController != null) drawingBoardController.onBoardShown();
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
        return RecordSettings.missingPermissions(this);
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
        ActivityLog.log("record_tap");

        // Check for website/youtube slides requiring internet before proceeding
        for (int i = 0; i < slides.size(); i++) {
            Slide s = slides.get(i);
            if (s.isDisabled()) continue;
            if (Slide.TYPE_WEBSITE.equals(s.getType())) {
                if (!isNetworkConnected()) {
                    ActivityLog.log("record_blocked", "reason", "no_internet_for_topic", "type", "website");
                    Toast.makeText(this, "Topic " + (i + 1) + " is website, so please turn ON the internet", Toast.LENGTH_LONG).show();
                    return;
                }
            } else if (Slide.TYPE_YOUTUBE.equals(s.getType())) {
                if (!isNetworkConnected()) {
                    ActivityLog.log("record_blocked", "reason", "no_internet_for_topic", "type", "youtube");
                    Toast.makeText(this, "Topic " + (i + 1) + " is YouTube video, so please turn ON the internet", Toast.LENGTH_LONG).show();
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
                ActivityLog.log("permission_prompt", "perm", "screen_capture");
                screenCaptureLauncher.launch(captureIntent);
            }
        }
    }

    private void showPermissionRationaleDialog(List<String> missingPermissions) {
        ActivityLog.log("permission_rationale", "action", "shown", "perms", permissionNames(missingPermissions));
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
                ActivityLog.log("permission_rationale", "action", "open_settings");
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
                ActivityLog.log("permission_rationale", "action", "ok");
                ActivityLog.log("permission_prompt", "perm", permissionNames(missingPermissions));
                permissionLauncher.launch(missingPermissions.toArray(new String[0]));
            });
        }

        btnCancel.setOnClickListener(v -> {
            ActivityLog.log("permission_rationale", "action", "cancel");
            dialog.dismiss();
        });

        dialog.show();
    }

    /** Short name of an Android permission, for the activity log. */
    private static String permissionName(String permission) {
        if (Manifest.permission.CAMERA.equals(permission)) return "camera";
        if (Manifest.permission.RECORD_AUDIO.equals(permission)) return "mic";
        if (Manifest.permission.POST_NOTIFICATIONS.equals(permission)) return "notifications";
        return "other";
    }

    private static String permissionNames(java.util.Collection<String> permissions) {
        StringBuilder names = new StringBuilder();
        for (String permission : permissions) {
            if (names.length() > 0) names.append(',');
            names.append(permissionName(permission));
        }
        return names.toString();
    }

    private int activeTopicCount() {
        int n = 0;
        if (slides != null) {
            for (Slide s : slides) {
                if (!s.isDisabled()) n++;
            }
        }
        return n;
    }

    /** The recording is about to begin (after the countdown, when there is one). */
    private void logRecordStart(int countdownSeconds) {
        ActivityLog.log("record_start", "countdown_s", countdownSeconds, "topics", activeTopicCount(),
                "camera", ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED);
    }

    private static String drawerItemName(int itemId) {
        if (itemId == R.id.nav_edit_slides) return "topics";
        if (itemId == R.id.nav_record_settings) return "settings";
        if (itemId == R.id.nav_saved_recordings) return "my_videos";
        if (itemId == R.id.nav_account) return "auto_upload";
        if (itemId == R.id.nav_help) return "guide";
        if (itemId == R.id.nav_contact) return "contact";
        if (itemId == R.id.nav_about) return "about";
        return "other";
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
            serviceIntent.putExtra(ScreenRecordService.EXTRA_STOP_SOURCE, "button");
            startService(serviceIntent);
            onRecordingStoppedUi();
        }
    }

    /** Resets the UI to Edit Mode after a recording ends (stop button, notification, or system chip). */
    private void onRecordingStoppedUi() {
        isRecording = false;
        resetDrawingTools();
        showCropGuideLines();

        incrementSuccessfulRecordingsCount();

        // Return to Edit Mode UI
        isMenuBarVisible = true;
        setPresentationMode(false);
        updateUIState();
    }

    private void updateUIState() {
        if (cameraCardContainer != null) updateResizeHandlePosition(cameraCardContainer.getWidth());

        // Pen and drawing board are available before recording too (to prepare the slide).
        if (wrapDrawingBoard != null) wrapDrawingBoard.setVisibility(View.VISIBLE);
        if (wrapSlidePen != null) wrapSlidePen.setVisibility(View.VISIBLE);
        // Beside the stop button while recording; without it, the pair is centred in the row.
        setToolGap(wrapDrawingBoard, isRecording ? 50 : 0);
        setToolGap(wrapSlidePen, isRecording ? 50 : 18);
        applyToolMarks();

        if (isRecording) {
            // In Recording Mode: hide top bar, hide red start button, show ONLY floating gray stop button
            topMenuBar.setVisibility(View.GONE);
            hideSwipeHint();
            if (btnRecord != null) btnRecord.setVisibility(View.GONE);
            if (btnStopRecordFloating != null) btnStopRecordFloating.setVisibility(View.VISIBLE);

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
                    for (java.util.Map.Entry<String, Boolean> entry : result.entrySet()) {
                        ActivityLog.log("permission_result", "perm", permissionName(entry.getKey()),
                                "granted", Boolean.TRUE.equals(entry.getValue()));
                    }
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

        screenCaptureLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    ActivityLog.log("permission_result", "perm", "screen_capture",
                            "granted", result.getResultCode() == RESULT_OK && result.getData() != null);
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
                                logRecordStart(0);
                                ContextCompat.startForegroundService(this, serviceIntent);
                                isRecording = true;
                                resetDrawingTools();
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
                cameraPreviewStarted = true;
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

        // A tall image must not run past 70% of the screen from the top (15px top margin included).
        imageSlideView.setMaxHeight((int) (screenHeight * 0.70) - 15);
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
            boardTouchActive = (isDrawingBoardVisible && isTouchInsideView(ev, drawingBoardContainer))
                    || isTouchInsideSlideInk(ev);
        }
        // Touches on the board (or on the slide in pen mode) only draw: no double-tap menu toggle, no slide swipe.
        if (!boardTouchActive) gestureDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private void toggleMenuBarAndSystemBars() {
        isMenuBarVisible = !isMenuBarVisible;
        ActivityLog.log("menu_toggle", "visible", isMenuBarVisible);
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
                            ActivityLog.log("topic_add", "type", "image");
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
                            ActivityLog.log("topic_edit", "type", "image");
                            slideToUpdateImage.setImagePath(localPath);
                            Slide slideToSave = slideToUpdateImage;
                            int posToUpdate = targetSlidePosition;
                            // Marks belong to the old picture; they would sit on the wrong content.
                            if (slideInkController != null) slideInkController.discard(slideToSave.getId());
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
                            ActivityLog.log("topic_add", "type", "video");
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
                            ActivityLog.log("topic_edit", "type", "video");
                            slideToUpdateVideo.setImagePath(localPath);
                            Slide slideToSave = slideToUpdateVideo;
                            int posToUpdate = targetSlidePosition;
                            if (slideInkController != null) slideInkController.discard(slideToSave.getId());
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
        String[] options = {"Update / Replace Image", "View Image"};
        new AlertDialog.Builder(this)
                .setTitle("Image Options")
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
    private static final String KEY_INITIAL_SLIDES_SEEDED = "initialSlidesSeeded";
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
                if (openedTimes <= 1 && !prefs.getBoolean(KEY_INITIAL_SLIDES_SEEDED, false)) {
                    seedInitialSlides();
                    return;
                }
                if (isInitialAppLaunchCheck) {
                    isInitialAppLaunchCheck = false;
                    Toast.makeText(this, "Double tap to add topics", Toast.LENGTH_SHORT).show();
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
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_INITIAL_SLIDES_SEEDED, true).apply();
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
                videoSlideView.setOnInfoListener(null);
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
        // Permission may have been granted on the Settings screen or in system settings.
        if (!cameraPreviewStarted && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview();
        }
        if (slides != null && !slides.isEmpty()) {
            renderCurrentSlide();
        }
        // Picks up crop ratios changed on the Settings screen.
        applyRecordSettings();
    }

    @Override
    protected void onStart() {
        super.onStart();
        hideFloatingCamOverlay();
        bubbleTrackHandler.post(bubbleTrackRunnable);
        BgReplaceService.setListener(processingListener);
        if (BgReplaceService.isBusy() && !BgReplaceService.getCurrentStage().isEmpty()) {
            showProcessingDialog(BgReplaceService.getCurrentStage(), BgReplaceService.getCurrentPercent());
        }
        maybeShowOverlayPermissionPrompt();
        audioDeviceCallback = AudioInputs.register(this, this::announceExternalMic);
    }

    /**
     * Tells the user, once per app process, that an external mic is connected and will be used.
     * Never while recording: a mic change mid-recording must not disturb it.
     */
    private void announceExternalMic() {
        if (isRecording || isFinishing() || isDestroyed()) return;
        if (!getSharedPreferences(RecordSettings.PREF_NAME, MODE_PRIVATE)
                .getBoolean(RecordSettings.KEY_RECORD_AUDIO, true)) return;
        AudioDeviceInfo device = AudioInputs.resolveDevice(this);
        if (device == null || device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC
                || !AudioInputs.claimAnnouncement()) return;
        Toast.makeText(this, getString(R.string.audio_src_connected,
                AudioInputs.label(this, device)), Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (audioDeviceCallback != null) {
            AudioInputs.unregister(this, audioDeviceCallback);
            audioDeviceCallback = null;
        }
        // While stopped, BgReplaceService reports through its notification instead
        BgReplaceService.setListener(null);
        dismissProcessingDialog();
        bubbleTrackHandler.removeCallbacks(bubbleTrackRunnable);
        BubbleTrack.reportHidden();
        if (drawingBoardController != null) drawingBoardController.flush();
        if (slideInkController != null) slideInkController.flush();
        stopVideoIfPlaying();
        // The plan this recording started with decides; without it nothing floats over other apps,
        // and the missing permission is not worth asking for.
        if (isRecording && !isChangingConfigurations()
                && FeatureSnapshot.allows(Feature.FLOATING_CAM)) {
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

    /**
     * Shows the current slide. A different slide goes through the fade-through transition (direction
     * from goToNext/PreviousSlide, none otherwise); the same slide is rebound at once.
     */
    private void renderCurrentSlide() {
        boolean hasActiveSlide = false;
        for (Slide s : slides) {
            if (!s.isDisabled()) {
                hasActiveSlide = true;
                break;
            }
        }
        int targetId = (slides.isEmpty() || !hasActiveSlide)
                ? SlideInkController.NO_SLIDE
                : slides.get(currentSlideIndex).getId();
        boolean changed = targetId != displayedSlideId;
        int direction = pendingNavDirection;
        pendingNavDirection = 0;

        // Buttons react at once; the content follows under the scrim.
        updateNavigationButtonsState();
        if (slideTransition == null) {
            bindSlideContent(() -> { });
            return;
        }
        slideTransition.show(direction, changed, this::bindSlideContent);
    }

    /**
     * Swaps the stage content to the current slide (re-read at call time: it may have changed while the
     * scrim was fading in). onReady must be reached on every path, or the scrim never lifts.
     */
    private void bindSlideContent(Runnable onReady) {
        webSlideReadyCallback = null;

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
            displayedSlideId = SlideInkController.NO_SLIDE;
            emptyStateView.setVisibility(View.GONE);
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);
            if (imageSlideView != null) imageSlideView.setVisibility(View.GONE);
            if (videoSlideContainer != null) {
                stopVideoIfPlaying();
                videoSlideContainer.setVisibility(View.GONE);
            }
            if (webSlideContainer != null) {
                hideWebSlide();
            }
            if (swipeRefreshWebSlide != null) {
                swipeRefreshWebSlide.setRefreshing(false);
            }
            if (slideInkController != null) slideInkController.setSlide(SlideInkController.NO_SLIDE);
            if (slideInkView != null) slideInkView.setVisibility(View.GONE);
            onReady.run();
            return;
        }

        emptyStateView.setVisibility(View.GONE);
        Slide slide = slides.get(currentSlideIndex);
        displayedSlideId = slide.getId();
        resetAllSlideZoom();
        if (slideInkController != null) slideInkController.setSlide(slide.getId());
        SlideLogger.log("RENDER", String.format(Locale.US,
                "renderCurrentSlide: index=%d/%d, type=%s, id=%d, imagePath=%s, textContent=%s",
                currentSlideIndex, slides.size(), slide.getType(), slide.getId(),
                slide.getImagePath(), slide.getTextContent()));

        if (Slide.TYPE_TEXT.equals(slide.getType())) {
            if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
                stopVideoIfPlaying();
                videoSlideContainer.setVisibility(View.GONE);
            }
            if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
                imageSlideView.setVisibility(View.GONE);
            }
            hideWebSlide();
            textSlideContainer.setVisibility(View.VISIBLE);

            textSlideView.setText(slide.getTextContent());
            applyTextSlideFontScaling(textSlideView, slide.getTextContent());
            textSlideView.setTranslationY(0f);
            textSlideView.setAlpha(1f);
            textSlideView.post(onReady);
            SlideLogger.log("RENDER_TEXT", "Text slide rendered: " + slide.getTextContent());
        } else if (Slide.TYPE_IMAGE.equals(slide.getType())) {
            if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
                stopVideoIfPlaying();
                videoSlideContainer.setVisibility(View.GONE);
            }
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);
            hideWebSlide();

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

                    // Without this, re-showing the image the view last held (back and forth between two
                    // slides) makes Glide restart the old, equivalent request with the old listener: our
                    // onReady never runs and the scrim sits out the whole ready timeout (~0.7s).
                    Glide.with(this).clear(imageSlideView);
                    final long loadStartedAt = SystemClock.uptimeMillis();
                    slideImageRequest(imgFile)
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
                                    onReady.run();
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
                                            "Image loaded: intrinsicW=%d, intrinsicH=%d, displayW=%d, calculatedH=%d, source=%s, %dms",
                                            intrinsicWidth, intrinsicHeight, displayWidth, calculatedHeight,
                                            dataSource, SystemClock.uptimeMillis() - loadStartedAt));

                                    imageSlideView.setAlpha(1f);
                                    // Glide sets the drawable right after this returns; report ready once it is in.
                                    imageSlideView.post(onReady);
                                    return false;
                                }
                            })
                            .into(imageSlideView);
                } else {
                    SlideLogger.log("IMAGE_ERROR", "Image file does not exist on disk: " + slide.getImagePath());
                    imageSlideView.setImageDrawable(null);
                    applyMediaTopMargin(imageSlideView, 0);
                    imageSlideView.setVisibility(View.GONE);
                    onReady.run();
                }
            } else {
                SlideLogger.log("IMAGE_ERROR", "Slide imagePath is NULL for slide id=" + slide.getId());
                imageSlideView.setImageDrawable(null);
                applyMediaTopMargin(imageSlideView, 0);
                imageSlideView.setVisibility(View.GONE);
                onReady.run();
            }
        } else if (Slide.TYPE_VIDEO.equals(slide.getType())) {
            if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
                imageSlideView.setVisibility(View.GONE);
            }
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);
            hideWebSlide();

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

                        mp.start();
                    });

                    // The scrim lifts when the first frame is on screen, not when the (black) box is laid out.
                    videoSlideView.setOnInfoListener((mp, what, extra) -> {
                        if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onReady.run();
                        return false;
                    });

                    videoSlideView.setOnErrorListener((mp, what, extra) -> {
                        SlideLogger.log("VIDEO_ERROR", String.format(Locale.US,
                                "Video playback error: what=%d, extra=%d, path=%s",
                                what, extra, slide.getImagePath()));
                        stopVideoIfPlaying();
                        applyMediaTopMargin(videoSlideContainer, 0);
                        if (videoSlideContainer != null) videoSlideContainer.setVisibility(View.GONE);
                        onReady.run();
                        return true;
                    });
                } else {
                    SlideLogger.log("VIDEO_ERROR", "Video file does not exist on disk: " + slide.getImagePath());
                    stopVideoIfPlaying();
                    applyMediaTopMargin(videoSlideContainer, 0);
                    if (videoSlideContainer != null) videoSlideContainer.setVisibility(View.GONE);
                    onReady.run();
                }
            } else {
                SlideLogger.log("VIDEO_ERROR", "Slide imagePath is NULL or videoSlideView is null for slide id=" + slide.getId());
                stopVideoIfPlaying();
                if (videoSlideContainer != null) {
                    applyMediaTopMargin(videoSlideContainer, 0);
                    videoSlideContainer.setVisibility(View.GONE);
                }
                onReady.run();
            }
        } else if (Slide.TYPE_WEBSITE.equals(slide.getType())) {
            if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
                stopVideoIfPlaying();
                videoSlideContainer.setVisibility(View.GONE);
            }
            if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
                imageSlideView.setVisibility(View.GONE);
            }
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);

            int displayWidth = rootLayout.getWidth() > 0 ? rootLayout.getWidth() : getResources().getDisplayMetrics().widthPixels;
            if (webSlideContainer != null) {
                ViewGroup.LayoutParams params = webSlideContainer.getLayoutParams();
                params.height = websiteSlideHeight(displayWidth);
                webSlideContainer.setLayoutParams(params);
                webSlideContainer.setVisibility(View.VISIBLE);
            }
            setWebNavBarVisible(true);

            if (swipeRefreshWebSlide != null) {
                swipeRefreshWebSlide.setRefreshing(false);
                swipeRefreshWebSlide.setEnabled(true);
            }

            boolean webLoading = false;
            String url = slide.getTextContent();
            if (url != null && !url.trim().isEmpty() && webViewHost != null) {
                url = url.trim();
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }
                // Each browser slide keeps its own WebView, so coming back shows the page as it was left.
                boolean[] created = {false};
                WebView browserView = browserWebViewFor(slide, url, created);
                showWebView(browserView);
                if (created[0]) {
                    webSlideReadyCallback = onReady;
                    webLoading = true;
                    browserView.loadUrl(url);
                }
            } else {
                showWebView(null);
            }
            if (!webLoading) onReady.run();
        } else if (Slide.TYPE_YOUTUBE.equals(slide.getType())) {
            if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
                stopVideoIfPlaying();
                videoSlideContainer.setVisibility(View.GONE);
            }
            if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
                imageSlideView.setVisibility(View.GONE);
            }
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);

            int displayWidth = rootLayout.getWidth() > 0 ? rootLayout.getWidth() : getResources().getDisplayMetrics().widthPixels;
            if (webSlideContainer != null) {
                ViewGroup.LayoutParams params = webSlideContainer.getLayoutParams();
                params.height = displayWidth;
                webSlideContainer.setLayoutParams(params);
                webSlideContainer.setVisibility(View.VISIBLE);
            }
            setWebNavBarVisible(false);

            if (swipeRefreshWebSlide != null) {
                swipeRefreshWebSlide.setRefreshing(false);
                swipeRefreshWebSlide.setEnabled(false);
            }

            String videoId = extractYouTubeVideoId(slide.getTextContent());
            boolean embedLoading = videoId != null && !videoId.isEmpty() && webSlideView != null;
            showWebView(embedLoading ? webSlideView : null);
            if (embedLoading) {
                webSlideReadyCallback = onReady;
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
                clearYoutubeHistory = true;
                webSlideView.loadDataWithBaseURL("https://localhost", html, "text/html", "utf-8", null);
            }
            if (!embedLoading) onReady.run();
        } else {
            onReady.run(); // unknown slide type: never leave the scrim down
        }

        // Fit the ink layer once this render's visibility/size changes have been laid out.
        if (slideInkView != null) slideInkView.post(this::syncSlideInk);
        preloadNeighbourImages();
    }

    /**
     * The one Glide request for a slide image, used both to show and to preload it. The size is fixed
     * (stage width, longest screen side) instead of read from the wrap_content ImageView: that view
     * still has the previous image's height, so the decode size used to depend on which image was shown
     * before (a wide photo after a short one was decoded small and then upscaled), and a preloaded
     * image never matched the memory-cache key of the real request.
     */
    private RequestBuilder<Drawable> slideImageRequest(File file) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        View stage = (View) imageSlideView.getParent();
        int width = stage != null && stage.getWidth() > 0 ? stage.getWidth() : metrics.widthPixels;
        int height = Math.max(metrics.widthPixels, metrics.heightPixels);
        return Glide.with(this).load(file).fitCenter().override(width, height);
    }

    /**
     * Decodes the image slides on both sides of the current one into Glide's memory cache, so moving to
     * them does not wait for a disk decode under the scrim. The first call also starts Glide itself,
     * which otherwise happened on the first image slide (usually the 2nd slide) and slowed it down.
     */
    private void preloadNeighbourImages() {
        if (slides.isEmpty() || isFinishing() || isDestroyed()) return;
        for (int i = currentSlideIndex + 1; i < slides.size(); i++) {
            if (!slides.get(i).isDisabled()) {
                preloadSlideImage(slides.get(i));
                break;
            }
        }
        for (int i = currentSlideIndex - 1; i >= 0; i--) {
            if (!slides.get(i).isDisabled()) {
                preloadSlideImage(slides.get(i));
                break;
            }
        }
    }

    private void preloadSlideImage(Slide slide) {
        if (!Slide.TYPE_IMAGE.equals(slide.getType()) || slide.getImagePath() == null) return;
        File file = new File(slide.getImagePath());
        if (file.exists()) slideImageRequest(file).preload();
    }

    private void goToPreviousSlide() {
        for (int i = currentSlideIndex - 1; i >= 0; i--) {
            if (!slides.get(i).isDisabled()) {
                currentSlideIndex = i;
                ActivityLog.log("topic_prev", "recording", isRecording);
                SlideLogger.log("NAV", "Navigated to previous slide index: " + currentSlideIndex);
                pendingNavDirection = -1;
                renderCurrentSlide();
                return;
            }
        }
    }

    private void goToNextSlide() {
        for (int i = currentSlideIndex + 1; i < slides.size(); i++) {
            if (!slides.get(i).isDisabled()) {
                currentSlideIndex = i;
                ActivityLog.log("topic_next", "recording", isRecording);
                SlideLogger.log("NAV", "Navigated to next slide index: " + currentSlideIndex);
                pendingNavDirection = 1;
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
                Toast.makeText(this, "Double-tap anywhere for menu & topic editing", Toast.LENGTH_LONG).show();
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
        tvSwipeHintLabel.setText(wantNext ? "Swipe left for next topic" : "Swipe right to go back");
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
        ActivityLog.log("topics_open", "count", slides == null ? 0 : slides.size());
        managerDialog = new BottomSheetDialog(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_slide_manager, null);
        managerDialog.setContentView(dialogView);

        recyclerSlides = dialogView.findViewById(R.id.recycler_slides);
        recyclerSlides.setLayoutManager(new LinearLayoutManager(this));

        slideAdapter = new SlideAdapter(this);
        recyclerSlides.setAdapter(slideAdapter);
        slideAdapter.setSlides(new ArrayList<>(slides));

        ImageButton btnDeleteAll = dialogView.findViewById(R.id.btn_delete_all_slides);
        final SlideAdapter observedAdapter = slideAdapter;
        Runnable syncDeleteAllState = () -> {
            boolean hasSlides = observedAdapter.getItemCount() > 0;
            btnDeleteAll.setEnabled(hasSlides);
            btnDeleteAll.setAlpha(hasSlides ? 1f : 0.3f);
        };
        observedAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                syncDeleteAllState.run();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                syncDeleteAllState.run();
            }

            @Override
            public void onItemRangeRemoved(int positionStart, int itemCount) {
                syncDeleteAllState.run();
            }
        });
        syncDeleteAllState.run();
        btnDeleteAll.setOnClickListener(v -> confirmDeleteAllSlides());

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
        String[] options = {"Update / Replace Video", "View Video"};
        new AlertDialog.Builder(this)
                .setTitle("Video Options")
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
            title.setText("Edit Topic");
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
                ActivityLog.log("topic_edit", "type", "text");
                slideToEdit.setTextContent(text);
                repository.update(slideToEdit, () -> {
                    dialog.dismiss();
                    if (slideAdapter != null) slideAdapter.notifyItemChanged(position);
                    renderCurrentSlide();
                });
            } else {
                ActivityLog.log("topic_add", "type", "text");
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
        View cardGoogle = popupView.findViewById(R.id.card_type_google);
        View cardYouTube = popupView.findViewById(R.id.card_type_youtube);
        Button btnCancel = popupView.findViewById(R.id.btn_cancel_more);

        if (cardWebsite != null) {
            cardWebsite.setOnClickListener(v -> {
                dialog.dismiss();
                showAddWebsiteSlideDialog(null, -1);
            });
        }

        if (cardGoogle != null) {
            cardGoogle.setOnClickListener(v -> {
                dialog.dismiss();
                showAddWebsiteSlideDialog(null, -1, "google.com"); // same browser slide, just prefilled
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
        showAddWebsiteSlideDialog(slideToEdit, position, null);
    }

    private void showAddWebsiteSlideDialog(Slide slideToEdit, int position, String prefillUrl) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_website, null);
        TextView title = dialogView.findViewById(R.id.dialog_website_title);
        EditText editUrl = dialogView.findViewById(R.id.edit_website_url);
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel_website);
        Button btnSave = dialogView.findViewById(R.id.btn_save_website);

        if (slideToEdit != null) {
            if (title != null) title.setText("Edit Browser");
            if (editUrl != null) editUrl.setText(slideToEdit.getTextContent());
        } else if (prefillUrl != null && editUrl != null) {
            editUrl.setText(prefillUrl);
            editUrl.setSelection(prefillUrl.length());
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
                    ActivityLog.log("topic_edit", "type", "website");
                    slideToEdit.setTextContent(inputUrl);
                    repository.update(slideToEdit, () -> {
                        dialog.dismiss();
                        if (slideAdapter != null && position != -1) slideAdapter.notifyItemChanged(position);
                        renderCurrentSlide();
                    });
                } else {
                    ActivityLog.log("topic_add", "type", "website");
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
            if (title != null) title.setText("Edit YouTube Video");
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
                    ActivityLog.log("topic_edit", "type", "youtube");
                    slideToEdit.setTextContent(input);
                    repository.update(slideToEdit, () -> {
                        dialog.dismiss();
                        if (slideAdapter != null && position != -1) slideAdapter.notifyItemChanged(position);
                        renderCurrentSlide();
                    });
                } else {
                    ActivityLog.log("topic_add", "type", "youtube");
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
                    .setTitle("Item Disabled")
                    .setMessage("This item is currently disabled and hidden during presentation. Would you like to enable it?")
                    .setPositiveButton("Enable", (dialog, which) -> {
                        ActivityLog.log("topic_toggle", "enabled", true);
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
            ActivityLog.log("topic_move", "dir", "up");
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
            ActivityLog.log("topic_move", "dir", "down");
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
                    .setTitle("Disable Item?")
                    .setMessage("This item will be hidden during presentation mode. You can re-enable it later.")
                    .setPositiveButton("Disable", (dialog, which) -> {
                        ActivityLog.log("topic_toggle", "enabled", false);
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
                    .setMessage("This item will be permanently deleted. This action cannot be undone.")
                    .setPositiveButton("Delete", (dialog, which) -> {
                        ActivityLog.log("topic_delete");
                        repository.delete(slide, () -> {
                            if (slideInkController != null) slideInkController.discard(slide.getId());
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

    private void confirmDeleteAllSlides() {
        if (slides == null || slides.isEmpty()) return;
        int n = slides.size();
        String what = n == 1 ? "1 topic" : n + " topics";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete all topics?")
                .setMessage("All " + what + " will be permanently deleted, including their images, videos and drawings. This cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete all", (d, which) -> deleteAllSlides())
                .create();
        dialog.show();
        Button yes = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (yes != null) yes.setTextColor(0xFFE53935);
    }

    /** Removes every topic and its local files. Single place for a future server-sync hook. */
    private void deleteAllSlides() {
        ActivityLog.log("topics_delete_all", "count", slides.size());
        final List<Slide> doomed = new ArrayList<>(slides);
        repository.deleteAll(() -> {
            stopVideoIfPlaying();
            if (slideInkController != null) {
                for (Slide s : doomed) slideInkController.discard(s.getId());
            }
            slides.clear();
            currentSlideIndex = 0;
            if (slideAdapter != null) slideAdapter.setSlides(new ArrayList<>());
            renderCurrentSlide();
            getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putBoolean(KEY_INITIAL_SLIDES_SEEDED, true).apply();

            final File filesDir = getFilesDir();
            new Thread(() -> {
                try {
                    String root = filesDir.getCanonicalPath();
                    for (Slide s : doomed) {
                        String path = s.getImagePath();
                        if (path == null) continue;
                        File f = new File(path);
                        if (f.getCanonicalPath().startsWith(root + File.separator)) {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error deleting topic media files", e);
                }
            }).start();
            Toast.makeText(this, "All topics deleted", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Re-applies every preference the main screen renders from.
     *
     * Countdown and mic/mute are read fresh at record time, so the crop guide lines
     * are the only thing that can go stale; this is the single place to extend if
     * another setting ever becomes visible here. Called from onResume, which is
     * how changes made on the Settings screen reach this one.
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
                .setMessage("Version 1.0.0\n\nA powerful, simple app for presenting topics with live face cam overlay and screen recording.\n\nPrivacy Notice: Camera and Screen Capture are used exclusively for live preview and recording when initiated by you.")
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
                    logRecordStart(countdownSeconds);
                    ContextCompat.startForegroundService(MainActivity.this, serviceIntent);
                    isRecording = true;
                    resetDrawingTools();
                    isMenuBarVisible = false;
                    setPresentationMode(true);
                    updateUIState();
                });
            }
        }.start();
    }

    private void cancelCountdown() {
        ActivityLog.log("record_countdown_cancel");
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
                if (path == null) return;
                // With background replace, BgReplaceService reports progress and the result
                // through processingListener; the file is only shown once that is done.
                // Auto-crops run silently in the background
                if (!intent.getBooleanExtra(ScreenRecordService.EXTRA_PROCESSING, false)) {
                    onRecordingReady(path);
                }
            }
        }
    };

    private android.app.Dialog processingDialog;
    private static final long PATIENCE_DELAY_MS = 20_000L;
    private Runnable patienceRunnable;
    private final BgReplaceService.Listener processingListener = new BgReplaceService.Listener() {
        @Override
        public void onProgress(String stage, int percent) {
            showProcessingDialog(stage, percent);
        }

        @Override
        public void onReady(String path) {
            dismissProcessingDialog();
            onRecordingReady(path);
        }
    };

    private void showProcessingDialog(String stage, int percent) {
        if (isFinishing() || isDestroyed()) return;
        // Once the user confirmed a cancel, a late progress report must not bring the bar back
        boolean cancelling = BgReplaceService.isCancelRequested()
                || BgReplaceService.STAGE_CANCELLING.equals(stage);
        if (cancelling) {
            stage = BgReplaceService.STAGE_CANCELLING;
            percent = -1;
        }
        if (processingDialog == null) {
            processingDialog = new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar_Fullscreen);
            processingDialog.setContentView(R.layout.dialog_processing);
            processingDialog.setCancelable(false);
            processingDialog.show();
            java.util.List<android.animation.Animator> running = new java.util.ArrayList<>();
            View halo = processingDialog.findViewById(R.id.processing_halo);
            if (halo != null) {
                android.animation.ObjectAnimator pulse = android.animation.ObjectAnimator.ofPropertyValuesHolder(halo,
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1f),
                        android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1f),
                        android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.35f));
                pulse.setDuration(1200);
                pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
                pulse.start();
                running.add(pulse);
            }
            // Same stepped hourglass as the crop status: it moves even when the percentage doesn't
            TextView tickIcon = processingDialog.findViewById(R.id.processing_tick);
            if (tickIcon != null) {
                final int steps = 12;
                android.animation.ObjectAnimator tick = android.animation.ObjectAnimator.ofFloat(
                        tickIcon, View.ROTATION, 0f, 360f);
                tick.setDuration(steps * 300L);
                tick.setInterpolator(t -> (float) Math.floor(t * steps) / steps);
                tick.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                tick.start();
                running.add(tick);
            }
            processingDialog.setOnDismissListener(d -> {
                for (android.animation.Animator a : running) a.cancel();
            });
            View cancelButton = processingDialog.findViewById(R.id.processing_cancel);
            if (cancelButton != null) {
                cancelButton.setOnClickListener(v -> confirmCancelBackgroundReplace());
            }
            // Counted from the start of the replace (owned by the service), not from this dialog
            TextView patience = processingDialog.findViewById(R.id.processing_patience);
            if (patience != null) {
                long remaining = PATIENCE_DELAY_MS - BgReplaceService.getReplaceElapsedMs();
                if (remaining <= 0) {
                    patience.setVisibility(View.VISIBLE);
                    patience.setAlpha(1f);
                } else {
                    patienceRunnable = () -> {
                        patience.setVisibility(View.VISIBLE);
                        patience.setAlpha(0f);
                        patience.setTranslationY(8 * getResources().getDisplayMetrics().density);
                        patience.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(600)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                    };
                    patience.postDelayed(patienceRunnable, remaining);
                }
            }
        }
        TextView title = processingDialog.findViewById(R.id.processing_title);
        ProgressBar bar = processingDialog.findViewById(R.id.processing_bar);
        TextView pct = processingDialog.findViewById(R.id.processing_percent);
        if (cancelling) {
            Button cancelButton = processingDialog.findViewById(R.id.processing_cancel);
            TextView hint = processingDialog.findViewById(R.id.processing_hint);
            if (cancelButton != null) {
                cancelButton.setEnabled(false);
                cancelButton.setAlpha(0.5f);
                cancelButton.setText(BgReplaceService.STAGE_CANCELLING);
            }
            if (hint != null) hint.setText("Stopping and cleaning up…");
        }
        if (title != null) title.setText(stage);
        if (bar != null) {
            bar.setIndeterminate(percent < 0);
            if (percent >= 0) {
                // Glide to the new value so a jump out of warm-up does not look like a glitch
                android.animation.ObjectAnimator.ofInt(bar, "progress", bar.getProgress(), percent)
                        .setDuration(400).start();
            }
        }
        if (pct != null) pct.setText(percent >= 0 ? percent + "%" : "");
    }

    private android.app.Dialog cancelConfirmDialog;

    /** Asks before cancelling; only a "yes" stops the replace (the crops still run afterwards). */
    private void confirmCancelBackgroundReplace() {
        if (isFinishing() || isDestroyed() || cancelConfirmDialog != null) return;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Cancel background replacement?")
                .setMessage("Your recording will keep its original background.")
                .setPositiveButton("Yes, cancel", (d, which) -> {
                    if (BgReplaceService.cancelReplace()) {
                        ActivityLog.log("bgreplace_cancel");
                        showProcessingDialog(BgReplaceService.STAGE_CANCELLING, -1);
                    }
                })
                .setNegativeButton("Keep going", null)
                .create();
        dialog.setOnDismissListener(d -> cancelConfirmDialog = null);
        cancelConfirmDialog = dialog;
        dialog.show();
        Button yes = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (yes != null) yes.setTextColor(0xFFE53935);
    }

    private void dismissProcessingDialog() {
        if (cancelConfirmDialog != null) {
            // The replace finished while the user was still deciding: nothing left to cancel
            try {
                cancelConfirmDialog.dismiss();
            } catch (Exception ignored) {}
            cancelConfirmDialog = null;
        }
        if (patienceRunnable != null) {
            if (processingDialog != null) {
                View patience = processingDialog.findViewById(R.id.processing_patience);
                if (patience != null) {
                    patience.removeCallbacks(patienceRunnable);
                    patience.animate().cancel();
                }
            }
            patienceRunnable = null;
        }
        if (processingDialog != null) {
            try {
                processingDialog.dismiss();
            } catch (Exception ignored) {}
            processingDialog = null;
        }
    }

    /** Opens the editor for a result, so a successful Save can offer to delete the original. */
    @Override
    public void launchEditor(String masterPath) {
        editorLauncher.launch(com.jovoc.facecampresentationrecorder.ui.VideoEditorActivity
                .newIntent(this, masterPath));
    }

    /** Shows the recording; auto-crops, if any, finish in the background (BgReplaceService). */
    private void onRecordingReady(String path) {
        if (isFinishing() || isDestroyed()) return;
        File file = new File(path);
        if (file.exists() && file.length() > 0) {
            // "Show video editor after recording": the final master (bg-replace is done by now)
            // opens in the editor instead of the preview; crops are already running as usual.
            if (com.jovoc.facecampresentationrecorder.edit.EditorGate.shouldShowAfterRecording(this)) {
                launchEditor(path);
                return;
            }
            Dialog dialog = VideoPlayerDialog.show(MainActivity.this, file, null);
            bindCropStatus(dialog, path);
        }
    }

    /** "Editing video to 9:16, 4:5 and 1:1 format …", listing the ratios this recording is cropped to. */
    private String cropProgressMessage(String path) {
        java.util.List<int[]> ratios = BgReplaceService.cropRatiosFor(path);
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < ratios.size(); i++) {
            if (i > 0) list.append(i == ratios.size() - 1 ? " and " : ", ");
            list.append(ratios.get(i)[0]).append(':').append(ratios.get(i)[1]);
        }
        return "Editing video to " + list + " format (You can change from settings)";
    }

    /** Inline auto-crop status in the video player: pending message, then the result. */
    private void bindCropStatus(@androidx.annotation.Nullable Dialog dialog, String path) {
        if (dialog == null || !BgReplaceService.isCropPending(path)) return;
        View row = dialog.findViewById(R.id.crop_status_row);
        TextView tvIcon = dialog.findViewById(R.id.tv_crop_icon);
        TextView tvCropStatus = dialog.findViewById(R.id.tv_crop_status);
        if (row == null || tvIcon == null || tvCropStatus == null) return;
        tvIcon.setText("⏳");
        tvCropStatus.setText(cropProgressMessage(path));
        row.setVisibility(View.VISIBLE);

        // The hourglass ticks round in 30° steps every 300 ms instead of spinning smoothly
        final int steps = 12;
        android.animation.ObjectAnimator tick = android.animation.ObjectAnimator.ofFloat(tvIcon, View.ROTATION, 0f, 360f);
        tick.setDuration(steps * 300L);
        tick.setInterpolator(t -> (float) Math.floor(t * steps) / steps);
        tick.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        tick.start();
        dialog.getWindow().getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) { tick.cancel(); }
        });

        BgReplaceService.setCropListener((successCount, failCount) -> {
            tick.cancel();
            tvIcon.setRotation(0f);
            tvIcon.setText(failCount == 0 ? "✓" : "⚠");
            tvCropStatus.setText(failCount == 0 ? "Edited versions saved · swipe to preview"
                    : failCount + " edit(s) failed, " + successCount + " saved");
            BgReplaceService.setCropListener(null);
        });
    }

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
        syncSlideInk();
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
                            syncSlideInk();
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
                                        .setUpdateListener(a -> syncSlideInk())
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
                                syncSlideInk();

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
        if (slideTransition != null) slideTransition.cancel();
        stopVideoIfPlaying();
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        cancelGuideLineFlashes();
        for (WebView view : browserWebViews.values()) destroyBrowserWebView(view);
        browserWebViews.clear();
        if (webSlideView != null) webSlideView.destroy();
    }
}