package com.jovoc.facecampresentationrecorder.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;

/**
 * First-run onboarding: four swipeable screens on one shared skeleton. Shown once (see
 * {@link OnboardingGate}); finishing and skipping both mark it as seen and open MainActivity.
 * It asks for nothing: no sign-in, no permissions, no text input.
 */
public class OnboardingActivity extends AppCompatActivity {

    /** Title of each screen. The main button of a screen shows the title of the next one. */
    private static final int[] TITLES = {
            R.string.onb_title_what,
            R.string.onb_title_how,
            R.string.onb_title_todo,
            R.string.onb_brand_strategy,
    };
    private static final int[] PAGE_LAYOUTS = {
            R.layout.item_onboarding_what,
            R.layout.item_onboarding_how,
            R.layout.item_onboarding_todo,
            R.layout.item_onboarding_story,
    };
    private static final int LAST_PAGE = TITLES.length - 1;
    /** From this screen on, the main button is "Start recording video" (screens 3 and 4). */
    private static final int START_BUTTON_FROM_PAGE = 2;

    /** Where the glow sits behind the top of each screen: {x, y} as fractions of the screen. */
    private static final float[][] GLOW_CENTERS = {{0.5f, 0.22f}, {0.4f, 0.24f}, {0.5f, 0.2f}, {0.6f, 0.22f}};

    private static final int MAX_CONTENT_WIDTH_DP = 480;
    private static final String STATE_PAGE = "page";
    private static final String EXTRA_FROM_MENU = "from_menu";

    /** Screen 1's image spins past a full turn, then eases back to rest: a wheel coming to a stop. */
    private static final float SPIN_DEGREES = 375f;
    private static final float REST_DEGREES = 360f;
    private static final long SPIN_MS = 300;
    private static final long SETTLE_MS = 200;
    /** The brush underline under "personal brand" fades in right after the spin settles. */
    private static final long UNDERLINE_FADE_MS = 400;

    /** The drawer's "How to Use & Guide": the same screens, but ending just returns to where the user was. */
    public static Intent forGuide(Context context) {
        return new Intent(context, OnboardingActivity.class).putExtra(EXTRA_FROM_MENU, true);
    }

    private ViewPager2 pager;
    /** The spin plays once per launch, not again when the user swipes back to screen 1. */
    private boolean imageSpun;
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
                ActivityLog.log("onboarding_page", "n", position + 1);
                showPage(position);
            }
        });

        skip.setOnClickListener(v -> {
            ActivityLog.log("onboarding_skip");
            finishOnboarding();
        });
        next.setOnClickListener(v -> {
            if (pager.getCurrentItem() >= START_BUTTON_FROM_PAGE) {
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
        next.setText(page >= START_BUTTON_FROM_PAGE ? R.string.onb_start_recording : TITLES[page + 1]);
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

    }

    private void finishOnboarding() {
        ActivityLog.log("onboarding_done", "from_menu", getIntent().getBooleanExtra(EXTRA_FROM_MENU, false));
        if (getIntent().getBooleanExtra(EXTRA_FROM_MENU, false)) {
            finish();
            return;
        }
        OnboardingGate.markSeen(this);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void spinOnce(ImageView image, BrushUnderlineSpan underline, TextView body) {
        if (imageSpun || !ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        imageSpun = true;
        underline.setAlpha(0);
        ValueAnimator fade = ValueAnimator.ofInt(0, 255);
        fade.setDuration(UNDERLINE_FADE_MS);
        fade.addUpdateListener(a -> {
            underline.setAlpha((int) a.getAnimatedValue());
            body.invalidate();
        });
        ObjectAnimator spin = ObjectAnimator.ofFloat(image, View.ROTATION, 0f, SPIN_DEGREES);
        spin.setDuration(SPIN_MS);
        spin.setInterpolator(new AccelerateDecelerateInterpolator());
        ObjectAnimator settle = ObjectAnimator.ofFloat(image, View.ROTATION, SPIN_DEGREES, REST_DEGREES);
        settle.setDuration(SETTLE_MS);
        settle.setInterpolator(new DecelerateInterpolator());
        AnimatorSet set = new AnimatorSet();
        set.playSequentially(spin, settle, fade);
        set.start();
    }

    /** Fills a screen's text: the app name in white, the one number that matters in amber. */
    private void bindPage(View page, int position) {
        String appName = getString(R.string.app_name);
        int white = ContextCompat.getColor(this, R.color.onb_title);
        int amber = ContextCompat.getColor(this, R.color.onb_highlight);
        if (position == 0) {
            TextView body = page.findViewById(R.id.onb_what_body);
            SpannableString text = colored(getString(R.string.onb_what_body, appName), white, appName);
            BrushUnderlineSpan underline = new BrushUnderlineSpan(amber, getResources().getDisplayMetrics().density);
            int at = text.toString().indexOf(getString(R.string.onb_what_brand_underline));
            if (at >= 0) {
                text.setSpan(underline, at, at + getString(R.string.onb_what_brand_underline).length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            body.setText(text);
            spinOnce(page.findViewById(R.id.onb_what_image), underline, body);
        } else if (position == 1) {
            TextView publish = page.findViewById(R.id.onb_how_publish);
            SpannableString text = colored(getString(R.string.onb_how_publish, appName), white, appName);
            publish.setText(colored(text, amber, getString(R.string.onb_how_publish_highlight)));
            TextView offer = page.findViewById(R.id.onb_how_offer);
            SpannableString offerText = new SpannableString(getString(R.string.onb_how_offer));
            for (String part : getResources().getStringArray(R.array.onb_how_offer_highlights)) {
                colored(offerText, amber, part);
            }
            offer.setText(offerText);
        } else if (position == 2) {
            TextView talk = page.findViewById(R.id.onb_todo_talk);
            talk.setText(colored(new SpannableString(getString(R.string.onb_todo_talk)), amber,
                    getString(R.string.onb_todo_talk_highlight)));
            TextView grow = page.findViewById(R.id.onb_todo_grow);
            grow.setText(colored(getString(R.string.onb_todo_grow, appName), white, appName));
            page.findViewById(R.id.btn_onb_strategy).setOnClickListener(v -> goTo(LAST_PAGE));
            page.findViewById(R.id.btn_onb_watch).setOnClickListener(v ->
                    YouTubeDialog.show(this, getString(R.string.onb_how_to_video_id)));
        } else {
            fillStory(page.findViewById(R.id.onb_story_container));
        }
    }

    /**
     * One paragraph per array item; words in [[double brackets]] get the highlighter pen.
     * Items that start with "1. ", "2. " ... become numbered cards.
     */
    private void fillStory(LinearLayout container) {
        if (container.getChildCount() > 0) return;
        float density = getResources().getDisplayMetrics().density;
        String[] paragraphs = getResources().getStringArray(R.array.onb_story);
        boolean previousWasCard = false;
        for (int i = 0; i < paragraphs.length; i++) {
            boolean card = paragraphs[i].matches("^\\d\\. .*");
            View view = card ? storyCard(container, paragraphs[i]) : storyParagraph(container, paragraphs[i]);
            if (i > 0) {
                int gap = previousWasCard && card ? 10 : (card ? 12 : 14);
                ((LinearLayout.LayoutParams) view.getLayoutParams()).topMargin = Math.round(gap * density);
            }
            container.addView(view);
            previousWasCard = card;
        }
        // The story ends with the how-to video: it appears only once the reader scrolls here.
        View watch = getLayoutInflater().inflate(R.layout.item_onboarding_story_watch, container, false);
        ((LinearLayout.LayoutParams) watch.getLayoutParams()).topMargin = Math.round(28 * density);
        watch.setOnClickListener(v -> YouTubeDialog.show(this, getString(R.string.onb_how_to_video_id)));
        container.addView(watch);
    }

    private MarkerTextView storyParagraph(LinearLayout container, String text) {
        MarkerTextView paragraph = (MarkerTextView) getLayoutInflater().inflate(
                R.layout.item_onboarding_story_paragraph, container, false);
        paragraph.setText(text);
        return paragraph;
    }

    /** A "1. text" item drawn as a card: round number badge on the left, text on the right. */
    private View storyCard(LinearLayout container, String item) {
        LinearLayout card = (LinearLayout) getLayoutInflater().inflate(
                R.layout.item_onboarding_story_card, container, false);
        ((TextView) card.findViewById(R.id.onb_card_number)).setText(item.substring(0, 1));
        ((MarkerTextView) card.findViewById(R.id.onb_card_text)).setText(item.substring(3));
        return card;
    }

    private static SpannableString colored(String text, int color, String part) {
        return colored(new SpannableString(text), color, part);
    }

    private static SpannableString colored(SpannableString text, int color, String part) {
        int at = text.toString().indexOf(part);
        if (at >= 0) {
            text.setSpan(new ForegroundColorSpan(color), at, at + part.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private final class PageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return PAGE_LAYOUTS[position];
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int layout) {
            View page = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            return new RecyclerView.ViewHolder(page) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            bindPage(holder.itemView, position);
        }

        @Override
        public int getItemCount() {
            return PAGE_LAYOUTS.length;
        }
    }
}
