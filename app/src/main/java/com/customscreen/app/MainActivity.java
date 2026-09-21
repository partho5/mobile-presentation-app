package com.customscreen.app;

import android.animation.ObjectAnimator;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.customscreen.app.adapter.SlideAdapter;
import com.customscreen.app.db.Slide;
import com.customscreen.app.db.SlideRepository;
import com.customscreen.app.util.ImageStorageHelper;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SlideAdapter.SlideActionListener {

    private SlideRepository repository;
    private List<Slide> slides = new ArrayList<>();
    private int currentSlideIndex = 0;

    // UI Components
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

    private GestureDetector gestureDetector;
    private boolean isMenuBarVisible = false;

    // Photo Picker launcher
    private ActivityResultLauncher<PickVisualMediaRequest> photoPickerLauncher;
    private SlideAdapter slideAdapter;
    private BottomSheetDialog managerDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = new SlideRepository(this);

        initViews();
        setupGestureDetector();
        setupPhotoPicker();
        setupTop30PercentLayout();
        setupBottom40PercentLayout();

        // Start directly in Presentation Mode (system bars hidden)
        setPresentationMode(true);

        // Load slides from DB
        loadSlidesFromDb();
    }

    private void initViews() {
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
        if (controller == null) return;

        if (enableImmersive) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            controller.show(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
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
                            repository.insert(newSlide, id -> loadSlidesFromDb());
                        } else {
                            Toast.makeText(this, "Failed to save image", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
        );
    }

    private void loadSlidesFromDb() {
        repository.getAllOrdered(result -> {
            slides = result;
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
        Slide textSlide1 = new Slide(Slide.TYPE_TEXT, 0, "Welcome to Presentation Viewer", null);
        Slide textSlide2 = new Slide(Slide.TYPE_TEXT, 1, "Double-tap anywhere to toggle Menu & Edit Slides", null);

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

        RecyclerView recyclerSlides = dialogView.findViewById(R.id.recycler_slides);
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

        managerDialog.setOnDismissListener(dialog -> loadSlidesFromDb());
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
                    loadSlidesFromDb();
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