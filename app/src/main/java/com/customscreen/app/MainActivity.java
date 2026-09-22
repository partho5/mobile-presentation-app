package com.customscreen.app;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
import com.customscreen.app.adapter.SlideAdapter;
import com.customscreen.app.db.Slide;
import com.customscreen.app.db.SlideRepository;
import com.customscreen.app.service.ScreenRecordService;
import com.customscreen.app.util.ImageStorageHelper;
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
    private TextView emptyStateView;
    private LinearLayout topMenuBar;
    private LinearLayout bottomNavContainer;
    private FrameLayout zonePrev;
    private FrameLayout zoneNext;
    private ImageButton btnPrev;
    private ImageButton btnNext;
    private Button btnRecord;

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
    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> screenCaptureLauncher;

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
        emptyStateView = findViewById(R.id.empty_state_view);
        topMenuBar = findViewById(R.id.top_menu_bar);
        bottomNavContainer = findViewById(R.id.bottom_nav_container);
        zonePrev = findViewById(R.id.zone_previous);
        zoneNext = findViewById(R.id.zone_next);
        btnPrev = findViewById(R.id.btn_prev);
        btnNext = findViewById(R.id.btn_next);

        Button btnSlideManager = findViewById(R.id.btn_slide_manager);
        btnSlideManager.setOnClickListener(v -> openSlideManagerDialog());

        zonePrev.setOnClickListener(v -> goToPreviousSlide());
        zoneNext.setOnClickListener(v -> goToNextSlide());
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
                    Toast.makeText(this, "Record Settings coming soon!", Toast.LENGTH_SHORT).show();
                } else if (itemId == R.id.nav_help) {
                    Toast.makeText(this, "Double-tap anywhere on screen to hide/show controls.", Toast.LENGTH_LONG).show();
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

        // Position camera in center initially once layout is measured
        rootLayout.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                rootLayout.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                centerCameraContainer();
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
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialResizeTouchX;
                    float dy = event.getRawY() - initialResizeTouchY;
                    int delta = (int) (Math.abs(dx) > Math.abs(dy) ? dx : dy);

                    int newSize = initialCardWidth + delta;
                    int minSize = (int) (90 * getResources().getDisplayMetrics().density);
                    int maxSize = (int) (320 * getResources().getDisplayMetrics().density);

                    newSize = Math.max(minSize, Math.min(maxSize, newSize));

                    ViewGroup.LayoutParams params = cameraCardContainer.getLayoutParams();
                    params.width = newSize;
                    params.height = newSize;
                    cameraCardContainer.setLayoutParams(params);
                    cameraCardContainer.setRadius(newSize / 2f);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    hideHandleHandler.postDelayed(hideHandleRunnable, 3000);
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
        if (btnRecord != null) {
            btnRecord.setOnClickListener(v -> toggleRecording());
        }
    }

    private void toggleRecording() {
        if (isRecording) {
            Intent serviceIntent = new Intent(this, ScreenRecordService.class);
            serviceIntent.setAction(ScreenRecordService.ACTION_STOP);
            startService(serviceIntent);
            isRecording = false;
            updateRecordButtonUI();
        } else {
            MediaProjectionManager projectionManager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (projectionManager != null) {
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent());
            }
        }
    }

    private void updateRecordButtonUI() {
        if (btnRecord == null) return;
        if (isRecording) {
            btnRecord.setText("Stop Record");
        } else {
            btnRecord.setText("Start Record");
        }
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
                        ContextCompat.startForegroundService(this, serviceIntent);
                        isRecording = true;
                        updateRecordButtonUI();
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
        topMenuBar.setVisibility(isMenuBarVisible ? View.VISIBLE : View.GONE);
        setPresentationMode(!isMenuBarVisible);
        updateNavigationButtonsState();
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
    }

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
            if (slides.isEmpty()) {
                seedInitialSlides();
            } else {
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

    private void renderCurrentSlide() {
        if (slides.isEmpty()) {
            emptyStateView.setVisibility(View.VISIBLE);
            textSlideContainer.setVisibility(View.GONE);
            imageSlideView.setVisibility(View.GONE);
            updateNavigationButtonsState();
            return;
        }

        emptyStateView.setVisibility(View.GONE);
        Slide slide = slides.get(currentSlideIndex);

        if (Slide.TYPE_TEXT.equals(slide.getType())) {
            imageSlideView.setVisibility(View.GONE);
            textSlideContainer.setVisibility(View.VISIBLE);

            textSlideView.setText(slide.getTextContent());
            animateTextSlideEntry();
        } else if (Slide.TYPE_IMAGE.equals(slide.getType())) {
            textSlideContainer.setVisibility(View.GONE);
            imageSlideView.setVisibility(View.VISIBLE);

            if (slide.getImagePath() != null) {
                Glide.with(this)
                        .load(new File(slide.getImagePath()))
                        .fitCenter()
                        .into(imageSlideView);
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

        if (isMenuBarVisible) {
            btnPrev.setVisibility(View.VISIBLE);
            btnNext.setVisibility(View.VISIBLE);
            btnPrev.setAlpha(hasPrev ? 1.0f : 0.3f);
            btnNext.setAlpha(hasNext ? 1.0f : 0.3f);
        } else {
            // Presentation mode: hide visual chrome while keeping tap zones functional
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
        Button btnClose = dialogView.findViewById(R.id.btn_close_manager);

        btnAddText.setOnClickListener(v -> showAddTextSlideDialog(null, -1));

        btnAddImage.setOnClickListener(v -> {
            photoPickerLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        });

        btnClose.setOnClickListener(v -> managerDialog.dismiss());

        managerDialog.setOnDismissListener(dialog -> {
            slideAdapter = null;
            recyclerSlides = null;
            managerDialog = null;
            loadSlidesFromDb();
        });
        managerDialog.show();
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
        } else {
            currentSlideIndex = position;
            renderCurrentSlide();
            if (managerDialog != null) managerDialog.dismiss();
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
}