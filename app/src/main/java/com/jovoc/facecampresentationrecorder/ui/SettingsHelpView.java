package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

import com.jovoc.facecampresentationrecorder.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Collapsible "How it helps you" block for one Settings section. Content comes from
 * {@link SettingsHelp#forSection}; with none, the view is GONE and builds nothing. Put one at the
 * top of each section, right under its label: {@code <SettingsHelpView app:helpSection="countdown"/>}.
 *
 * <p>The host activity forwards {@link #onHostPause}/{@link #onHostResume} so a video never plays
 * in the background.
 */
public class SettingsHelpView extends LinearLayout {

    private static final int ACCENT = 0xFF4ADE80;
    private static final int BODY_TEXT = 0xFFD0D0D0;

    private final List<SettingsHelp.Block> blocks;
    private final List<YouTubeEmbedView> players = new ArrayList<>();
    private LinearLayout body;
    private ImageView chevron;
    private View header;
    private boolean expanded;

    public SettingsHelpView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);

        String section = null;
        try (TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.SettingsHelpView)) {
            section = a.getString(R.styleable.SettingsHelpView_helpSection);
        }
        blocks = SettingsHelp.forSection(section);
        if (blocks.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        buildHeader();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private void buildHeader() {
        header = new LinearLayout(getContext());
        LinearLayout row = (LinearLayout) header;
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Math.round(dp(48)));
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue tv = new TypedValue();
        if (getContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }

        TextView title = new TextView(getContext());
        title.setText(R.string.set_help_header);
        title.setTextColor(ACCENT);
        title.setTextSize(14);
        row.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        chevron = new ImageView(getContext());
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setColorFilter(ACCENT);
        chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(dp(24));
        row.addView(chevron, new LayoutParams(size, size));

        // Announced as a button with an expanded/collapsed state.
        ViewCompat.setAccessibilityDelegate(row, new AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(@NonNull View host,
                                                          @NonNull AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
                info.addAction(expanded ? AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE
                        : AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND);
            }

            @Override
            public boolean performAccessibilityAction(@NonNull View host, int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_EXPAND && !expanded
                        || action == AccessibilityNodeInfo.ACTION_COLLAPSE && expanded) {
                    toggle();
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        row.setStateDescription(getContext().getString(R.string.set_help_collapsed));
        row.setOnClickListener(v -> toggle());
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void toggle() {
        setExpanded(!expanded);
    }

    private void setExpanded(boolean value) {
        expanded = value;
        if (expanded) {
            if (body == null) buildBody(); // lazily: no WebView/image work until first opened
            body.setVisibility(VISIBLE);
            for (YouTubeEmbedView p : players) p.start();
        } else if (body != null) {
            body.setVisibility(GONE);
            for (YouTubeEmbedView p : players) p.stop();
        }
        chevron.animate().rotation(expanded ? 90f : 0f).setDuration(150).start();
        header.setStateDescription(getContext().getString(
                expanded ? R.string.set_help_expanded : R.string.set_help_collapsed));
    }

    private void buildBody() {
        body = new LinearLayout(getContext());
        body.setOrientation(VERTICAL);
        int gap = Math.round(dp(10));
        for (SettingsHelp.Block block : blocks) {
            View v = null;
            switch (block.type) {
                case TEXT:
                    v = textBlock(block);
                    break;
                case IMAGE:
                    v = imageBlock(block);
                    break;
                case YOUTUBE:
                    YouTubeEmbedView player = new YouTubeEmbedView(getContext(), block.videoId);
                    players.add(player);
                    v = player;
                    break;
            }
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = gap;
            body.addView(v, lp);
        }
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView textBlock(SettingsHelp.Block block) {
        TextView tv = new TextView(getContext());
        tv.setTextColor(BODY_TEXT);
        tv.setLinkTextColor(ACCENT);
        tv.setTextSize(14);
        tv.setLineSpacing(0, 1.15f);
        tv.setTextIsSelectable(false);

        // Linkify web URLs, but route taps through WebLinks (ACTION_VIEW, guarded) ourselves.
        SpannableString text = new SpannableString(getContext().getText(block.resId));
        Linkify.addLinks(text, Linkify.WEB_URLS);
        for (URLSpan span : text.getSpans(0, text.length(), URLSpan.class)) {
            int start = text.getSpanStart(span);
            int end = text.getSpanEnd(span);
            text.removeSpan(span);
            final String url = span.getURL();
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(@NonNull View widget) {
                    WebLinks.open(widget.getContext(), url);
                }
            }, start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tv.setText(text);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        return tv;
    }

    private ImageView imageBlock(SettingsHelp.Block block) {
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(block.resId);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setContentDescription(getContext().getString(block.descResId));
        return iv;
    }

    // ---- host lifecycle ----

    /** Stops any playing video; call from the activity's onPause. */
    public void onHostPause() {
        for (YouTubeEmbedView p : players) p.stop();
    }

    /** Restarts videos of a block that is still expanded; call from onResume. */
    public void onHostResume() {
        if (!expanded) return;
        for (YouTubeEmbedView p : players) p.start();
    }
}
