package com.jovoc.facecampresentationrecorder;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import androidx.annotation.Nullable;
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
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.jovoc.facecampresentationrecorder.adapter.SlideAdapter;
import com.jovoc.facecampresentationrecorder.db.Slide;
import com.jovoc.facecampresentationrecorder.db.SlideRepository;
import com.jovoc.facecampresentationrecorder.service.ScreenRecordService;
import com.jovoc.facecampresentationrecorder.util.ImageStorageHelper;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.navigation.NavigationView;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    // Countdown & Start Flash Components
    private View countdownOverlayContainer;
    private TextView tvCountdownNumber;
    private Button btnCancelCountdown;
    private View flashOverlayView;
    private CountDownTimer countDownTimer;

    // Draggable & Resizable Camera Components
    private FrameLayout cameraRootWrapper;
    private MaterialCardView cameraCardContainer;
    private PreviewView cameraPreviewView;
    private ImageView btnResizeHandle;

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
    };

    private GestureDetector gestureDetector;
    private boolean isMenuBarVisible = false;
    private boolean isRecording = false;

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
        setContentView(R.layout.activity_main);

        repository = new SlideRepository(this);

        initViews();
        setupDrawer();
        setupGestureDetector();
        setupPhotoPicker();
        setupTop30PercentLayout();
        setupBottom40PercentLayout();

        setupDraggableCameraContainer();
        setupRecordButton();
        setupPermissionsAndCamera();

        // Track app opened count
        incrementAppOpenedTimes();

        // Start directly in Presentation Mode (system bars hidden)
        setPresentationMode(true);

        // Load slides from DB
        loadSlidesFromDb();
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

        if (btnCancelCountdown != null) {
            btnCancelCountdown.setOnClickListener(v -> cancelCountdown());
        }

        Button btnSlideManager = findViewById(R.id.btn_slide_manager);
        btnSlideManager.setOnClickListener(v -> openSlideManagerDialog());

        zonePrev.setOnClickListener(v -> goToPreviousSlide());
        zoneNext.setOnClickListener(v -> goToNextSlide());
    }

    private static final String PREF_NAME = "app_prefs";
    private static final String KEY_CAM_SIZE = "key_cam_size";
    private static final String KEY_CAM_POS_X = "key_cam_pos_x";
    private static final String KEY_CAM_POS_Y = "key_cam_pos_y";
    private static final String KEY_COUNTDOWN_SECONDS = "key_countdown_seconds";
    private static final String KEY_RECORD_AUDIO = "key_record_audio";

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
    }

    private void restoreCameraState() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
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

                    newX = Math.max(0, Math.min(parentWidth - cameraRootWrapper.getWidth(), newX));
                    newY = Math.max(0, Math.min(parentHeight - cameraRootWrapper.getHeight(), newY));

                    cameraRootWrapper.setX(newX);
                    cameraRootWrapper.setY(newY);
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
    }

    private void showResizeHandleFor3Seconds() {
        if (btnResizeHandle == null) return;
        btnResizeHandle.animate().cancel();
        btnResizeHandle.setAlpha(1f);
        btnResizeHandle.setVisibility(View.VISIBLE);
        hideHandleHandler.removeCallbacks(hideHandleRunnable);
        hideHandleHandler.postDelayed(hideHandleRunnable, 3000);
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
    }

    private void startRecordingFlow() {
        if (!isRecording) {
            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager != null) {
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent());
            }
        }
    }

    private void stopRecordingFlow() {
        if (isRecording) {
            Intent serviceIntent = new Intent(this, ScreenRecordService.class);
            serviceIntent.setAction(ScreenRecordService.ACTION_STOP);
            startService(serviceIntent);
            isRecording = false;

            // Return to Edit Mode UI
            isMenuBarVisible = true;
            setPresentationMode(false);
            updateUIState();
        }
    }

    private void updateUIState() {
        if (toolbarTitle != null) {
            toolbarTitle.setText(isRecording ? "Recording Mode" : "Edit Mode");
        }

        if (isRecording) {
            // In Recording Mode: hide top bar, hide red start button, show ONLY floating gray stop button
            topMenuBar.setVisibility(View.GONE);
            if (btnRecord != null) btnRecord.setVisibility(View.GONE);
            if (btnStopRecordFloating != null) btnStopRecordFloating.setVisibility(View.VISIBLE);

            SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            int openedTimes = prefs.getInt(KEY_APP_OPENED_TIMES, 0);
            if (openedTimes <= 2 && ivStopArrowHint != null) {
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
                    Boolean cameraGranted = result.getOrDefault(Manifest.permission.CAMERA, false);
                    if (Boolean.TRUE.equals(cameraGranted)) {
                        startCameraPreview();
                    } else {
                        Toast.makeText(this, "Camera permission required for face cam preview", Toast.LENGTH_SHORT).show();
                    }
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
                            triggerStartFlashEffect(() -> {
                                ContextCompat.startForegroundService(this, serviceIntent);
                                isRecording = true;
                                isMenuBarVisible = false;
                                setPresentationMode(true);
                                updateUIState();
                            });
                        }
                    } else {
                        Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
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

        if (!neededPermissions.isEmpty()) {
            permissionLauncher.launch(neededPermissions.toArray(new String[0]));
        } else {
            startCameraPreview();
        }
    }

    private void startCameraPreview() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build();

                preview.setSurfaceProvider(cameraPreviewView.getSurfaceProvider());
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview);
            } catch (Exception e) {
                Log.e(TAG, "Error starting front camera preview", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setupTop30PercentLayout() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenHeight = displayMetrics.heightPixels;

        // Set Top 30% container height dynamically
        int top30Height = (int) (screenHeight * 0.30);
        ViewGroup.LayoutParams params = textSlideContainer.getLayoutParams();
        params.height = top30Height;
        textSlideContainer.setLayoutParams(params);
    }

    private void setupBottom40PercentLayout() {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenHeight = displayMetrics.heightPixels;

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
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        gestureDetector.onTouchEvent(ev);
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

    private static final String KEY_APP_OPENED_TIMES = "appOpenedTimes";
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
                renderCurrentSlide();
            }
        });
    }

    private void seedInitialSlides() {
        Slide textSlide1 = new Slide(Slide.TYPE_TEXT, 0, "Double-tap anywhere to toggle Menu & Edit Slides", null);
        Slide textSlide2 = new Slide(Slide.TYPE_TEXT, 1, "Welcome to Presentation Viewer", null);

        repository.insert(textSlide1, id1 -> {
            repository.insert(textSlide2, id2 -> {
                loadSlidesFromDb();
            });
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
        if (slides != null && !slides.isEmpty()) {
            renderCurrentSlide();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopVideoIfPlaying();
    }

    private void applyMediaTopMargin(View view, int mediaHeight) {
        if (view == null) return;
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screenHeight = displayMetrics.heightPixels;

        int topMargin = 0;
        if (mediaHeight > 0 && mediaHeight <= (screenHeight * 0.50)) {
            topMargin = (int) (screenHeight * 0.15);
        }

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

    private void renderCurrentSlide() {
        if (slides.isEmpty()) {
            emptyStateView.setVisibility(View.GONE);
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);
            if (imageSlideView != null) animateMediaSlideExit(imageSlideView);
            if (videoSlideContainer != null) {
                stopVideoIfPlaying();
                animateMediaSlideExit(videoSlideContainer);
            }
            updateNavigationButtonsState();
            return;
        }

        emptyStateView.setVisibility(View.GONE);
        Slide slide = slides.get(currentSlideIndex);

        if (Slide.TYPE_TEXT.equals(slide.getType())) {
            if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
                stopVideoIfPlaying();
                animateMediaSlideExit(videoSlideContainer);
            }
            if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
                animateMediaSlideExit(imageSlideView);
            }
            textSlideContainer.setVisibility(View.VISIBLE);

            textSlideView.setText(slide.getTextContent());
            animateTextSlideEntry();
        } else if (Slide.TYPE_IMAGE.equals(slide.getType())) {
            if (videoSlideContainer != null && videoSlideContainer.getVisibility() == View.VISIBLE) {
                stopVideoIfPlaying();
                animateMediaSlideExit(videoSlideContainer);
            }
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);

            if (slide.getImagePath() != null) {
                File imgFile = new File(slide.getImagePath());
                if (imgFile.exists()) {
                    Glide.with(this)
                            .load(imgFile)
                            .listener(new RequestListener<Drawable>() {
                                @Override
                                public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
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
                    imageSlideView.setImageDrawable(null);
                    applyMediaTopMargin(imageSlideView, 0);
                    imageSlideView.setVisibility(View.GONE);
                }
            } else {
                imageSlideView.setImageDrawable(null);
                applyMediaTopMargin(imageSlideView, 0);
                imageSlideView.setVisibility(View.GONE);
            }
        } else if (Slide.TYPE_VIDEO.equals(slide.getType())) {
            if (imageSlideView != null && imageSlideView.getVisibility() == View.VISIBLE) {
                animateMediaSlideExit(imageSlideView);
            }
            if (textSlideContainer != null) textSlideContainer.setVisibility(View.GONE);

            if (slide.getImagePath() != null && videoSlideView != null) {
                File vidFile = new File(slide.getImagePath());
                if (vidFile.exists()) {
                    videoSlideView.setVideoPath(slide.getImagePath());
                    if (mediaController == null) {
                        mediaController = new MediaController(this);
                    }
                    mediaController.setAnchorView(videoSlideView);
                    videoSlideView.setMediaController(mediaController);
                    videoSlideView.setOnPreparedListener(mp -> {
                        int videoWidth = mp.getVideoWidth();
                        int videoHeight = mp.getVideoHeight();
                        int displayWidth = rootLayout.getWidth() > 0 ? rootLayout.getWidth() : getResources().getDisplayMetrics().widthPixels;
                        int calculatedHeight = 0;
                        if (videoWidth > 0 && videoHeight > 0) {
                            calculatedHeight = (int) (((float) displayWidth / videoWidth) * videoHeight);
                        }
                        applyMediaTopMargin(videoSlideContainer, calculatedHeight);
                        mp.setLooping(true);

                        if (videoSlideContainer.getVisibility() == View.VISIBLE && videoSlideContainer.getTranslationX() == 0f) {
                            mp.start();
                        } else {
                            animateMediaSlideEntry(videoSlideContainer, () -> mp.start());
                        }
                    });
                    videoSlideView.setOnErrorListener((mp, what, extra) -> {
                        stopVideoIfPlaying();
                        applyMediaTopMargin(videoSlideContainer, 0);
                        if (videoSlideContainer != null) videoSlideContainer.setVisibility(View.GONE);
                        return true; // handled error, blank screen
                    });
                } else {
                    stopVideoIfPlaying();
                    applyMediaTopMargin(videoSlideContainer, 0);
                    if (videoSlideContainer != null) videoSlideContainer.setVisibility(View.GONE);
                }
            } else {
                stopVideoIfPlaying();
                if (videoSlideContainer != null) {
                    applyMediaTopMargin(videoSlideContainer, 0);
                    videoSlideContainer.setVisibility(View.GONE);
                }
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
        if (currentSlideIndex > 0) {
            currentSlideIndex--;
            renderCurrentSlide();
        }
    }

    private void goToNextSlide() {
        if (currentSlideIndex < slides.size() - 1) {
            currentSlideIndex++;
            renderCurrentSlide();
        }
    }

    private void updateNavigationButtonsState() {
        boolean hasPrev = currentSlideIndex > 0;
        boolean hasNext = currentSlideIndex < slides.size() - 1 && !slides.isEmpty();

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

        Button btnAddText = dialogView.findViewById(R.id.btn_add_text_slide);
        Button btnAddImage = dialogView.findViewById(R.id.btn_add_image_slide);
        Button btnAddVideo = dialogView.findViewById(R.id.btn_add_video_slide);
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
        Button btnCancel = dialogView.findViewById(R.id.btn_cancel_text);
        Button btnSave = dialogView.findViewById(R.id.btn_save_text);

        if (slideToEdit != null) {
            title.setText("Edit Text Slide");
            editText.setText(slideToEdit.getTextContent());
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

    // --- SlideAdapter.SlideActionListener implementations ---

    @Override
    public void onSlideClick(Slide slide, int position) {
        if (Slide.TYPE_TEXT.equals(slide.getType())) {
            showAddTextSlideDialog(slide, position);
        } else if (Slide.TYPE_IMAGE.equals(slide.getType())) {
            showImageSlideOptionsDialog(slide, position);
        } else if (Slide.TYPE_VIDEO.equals(slide.getType())) {
            showVideoSlideOptionsDialog(slide, position);
        }
    }

    @Override
    public void onEditSlide(Slide slide, int position) {
        if (Slide.TYPE_TEXT.equals(slide.getType())) {
            showAddTextSlideDialog(slide, position);
        } else if (Slide.TYPE_IMAGE.equals(slide.getType())) {
            launchImageUpdater(slide, position);
        } else if (Slide.TYPE_VIDEO.equals(slide.getType())) {
            launchVideoUpdater(slide, position);
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
        repository.delete(slide, () -> {
            slides.remove(position);
            slideAdapter.setSlides(slides);
            if (currentSlideIndex >= slides.size()) {
                currentSlideIndex = Math.max(0, slides.size() - 1);
            }
            renderCurrentSlide();
        });
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
                        .apply();

                Toast.makeText(this, "Record Settings saved", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
        }

        dialog.show();
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
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        if (countdownOverlayContainer != null) {
            countdownOverlayContainer.setVisibility(View.VISIBLE);
            countdownOverlayContainer.setAlpha(1.0f);
        }

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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopVideoIfPlaying();
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }
}