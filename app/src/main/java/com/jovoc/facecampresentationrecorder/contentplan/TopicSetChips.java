package com.jovoc.facecampresentationrecorder.contentplan;

import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/** The row of set chips at the top of the Topic Manager: "My topics" and one chip per planned video. */
public final class TopicSetChips {

    private TopicSetChips() {
    }

    /** Fills {@code row}; hides {@code scroll} while there are no video sets. A tap switches the set and re-binds. */
    public static void bind(MainActivity host, HorizontalScrollView scroll, LinearLayout row) {
        TopicSetList.load(host, items -> {
            if (host.isFinishing() || host.isDestroyed()) return;
            if (!TopicSetList.hasVideos(items)) {
                scroll.setVisibility(View.GONE);
                return;
            }
            scroll.setVisibility(View.VISIBLE);
            row.removeAllViews();
            TextView selected = null;
            for (TopicSetList.Item item : items) {
                TextView chip = makeChip(host, item);
                row.addView(chip);
                if (item.onScreen) selected = chip;
            }
            if (selected != null) {
                final TextView target = selected;
                final int margin = dp(host, 16);
                scroll.post(() -> scroll.smoothScrollTo(Math.max(0, target.getLeft() - margin), 0));
            }
        });
    }

    private static TextView makeChip(MainActivity host, TopicSetList.Item item) {
        TextView chip = new TextView(host);
        chip.setText(label(host, item));
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setMaxWidth(dp(host, 220));
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        chip.setPadding(dp(host, 14), 0, dp(host, 14), 0);
        chip.setTextColor(item.onScreen ? 0xFF0B1620 : 0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(item.onScreen ? 0xFF64B5F6 : 0xFF2A2A2A);
        bg.setCornerRadius(dp(host, 20));
        chip.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(host, 40));
        lp.setMargins(dp(host, 4), 0, dp(host, 4), 0);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            host.switchSlideSet(item.setId);
            View parent = (View) v.getParent();
            if (parent instanceof LinearLayout && parent.getParent() instanceof HorizontalScrollView) {
                bind(host, (HorizontalScrollView) parent.getParent(), (LinearLayout) parent);
            }
        });
        return chip;
    }

    private static String label(MainActivity host, TopicSetList.Item item) {
        if (item.setId == null) return host.getString(R.string.cplan_my_topics);
        String text = item.date == null ? item.title
                : host.getString(R.string.cplan_status_join, formatDate(item.date), item.title);
        return item.recorded ? text + " ✓" : text;
    }

    static String formatDate(String iso) {
        try {
            return LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()));
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    private static int dp(MainActivity host, int v) {
        return Math.round(v * host.getResources().getDisplayMetrics().density);
    }
}
