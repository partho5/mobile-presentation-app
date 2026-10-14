package com.jovoc.facecampresentationrecorder.ui;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;

/**
 * First-run onboarding: three swipeable screens on one shared skeleton. Shown once (see
 * {@link OnboardingGate}); finishing and skipping both mark it as seen and open MainActivity.
 * It asks for nothing: no sign-in, no permissions, no text input.
 */
public class OnboardingActivity extends AppCompatActivity {

    /** Title of each screen. The main button of a screen shows the title of the next one. */
    private static final int[] TITLES = {
            R.string.onb_title_what,
            R.string.onb_title_how,
            R.string.onb_title_todo,
    };
    private static final int LAST_PAGE = TITLES.length - 1;

    /** Where the glow sits behind each screen's visual: {x, y} as fractions of the screen. */
    private static final float[][] GLOW_CENTERS = {{0.5f, 0.28f}, {0.4f, 0.26f}, {0.5f, 0.22f}};

    private static final int MAX_CONTENT_WIDTH_DP = 480;
    private static final String STATE_PAGE = "page";

    private ViewPager2 pager;
    private OnboardingBackgroundView background;
    private ViewGroup progress;
    private Button skip;
    private Button next;

    /** Back returns to the previous screen; on the first screen it is off, so Back closes the app. */
    private final OnBackPressedCallback backToPreviousPage = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            goTo(pager.getCurrentItem() - 1);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        // Without this a landscape phone leaves a black strip beside the camera cutout.
        WindowManager.LayoutParams window = getWindow().getAttributes();
        window.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(window);
        setContentView(R.layout.activity_onboarding);

        pager = findViewById(R.id.onb_pager);
        background = findViewById(R.id.onb_background);
        progress = findViewById(R.id.onb_progress);
        skip = findViewById(R.id.btn_onb_skip);
        next = findViewById(R.id.btn_onb_next);

        int maxContentWidth = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                MAX_CONTENT_WIDTH_DP, getResources().getDisplayMetrics()));
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.onb_content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            int windowWidth = getWindowManager().getCurrentWindowMetrics().getBounds().width();
            int side = Math.max(0, (windowWidth - bars.left - bars.right - maxContentWidth) / 2);
            v.setPadding(bars.left + side, bars.top, bars.right + side, bars.bottom);
            return insets;
        });

        pager.setAdapter(new PageAdapter());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                showScroll(position + positionOffset);
            }

            @Override
            public void onPageSelected(int position) {
                showPage(position);
            }
        });

        skip.setOnClickListener(v -> finishOnboarding());
        next.setOnClickListener(v -> {
            if (pager.getCurrentItem() == LAST_PAGE) {
                finishOnboarding();
            } else {
                goTo(pager.getCurrentItem() + 1);
            }
        });
        getOnBackPressedDispatcher().addCallback(this, backToPreviousPage);

        int page = savedInstanceState != null ? savedInstanceState.getInt(STATE_PAGE) : 0;
        pager.setCurrentItem(page, false);
        showPage(page);
        showScroll(page);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PAGE, pager.getCurrentItem());
    }

    /** Slides to a screen, or jumps when the user turned animations off in system settings. */
    private void goTo(int page) {
        pager.setCurrentItem(page, ValueAnimator.areAnimatorsEnabled());
    }

    /** Everything that changes in one step when a screen becomes the current one. */
    private void showPage(int page) {
        for (int i = 0; i < progress.getChildCount(); i++) {
            progress.getChildAt(i).setSelected(i <= page);
        }
        progress.setContentDescription(getString(R.string.onb_progress_desc, page + 1, TITLES.length));
        next.setText(page == LAST_PAGE ? R.string.onb_start_recording : TITLES[page + 1]);
        backToPreviousPage.setEnabled(page > 0);
    }

    /**
     * Everything that follows the finger. {@code position} is the page index plus the swipe
     * progress towards the next page (1.5 = halfway between the second and third screen).
     */
    private void showScroll(float position) {
        int from = Math.max(0, Math.min((int) position, LAST_PAGE));
        int to = Math.min(from + 1, LAST_PAGE);
        float t = position - from;
        float x = lerp(GLOW_CENTERS[from][0], GLOW_CENTERS[to][0], t);
        float y = lerp(GLOW_CENTERS[from][1], GLOW_CENTERS[to][1], t);
        boolean rtl = pager.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        background.setGlowCenter(rtl ? 1f - x : x, y);

        // Skip fades out on the way to the last screen: "Start recording video" does its job there.
        float skipAlpha = Math.max(0f, Math.min(LAST_PAGE - position, 1f));
        skip.setAlpha(skipAlpha);
        skip.setVisibility(skipAlpha == 0f ? View.INVISIBLE : View.VISIBLE);
    }

    private void finishOnboarding() {
        OnboardingGate.markSeen(this);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static final class PageAdapter extends RecyclerView.Adapter<PageAdapter.PageHolder> {

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PageHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_onboarding_page, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            holder.title.setText(TITLES[position]);
        }

        @Override
        public int getItemCount() {
            return TITLES.length;
        }

        static final class PageHolder extends RecyclerView.ViewHolder {
            final TextView title;

            PageHolder(View page) {
                super(page);
                title = page.findViewById(R.id.onb_title);
            }
        }
    }
}
