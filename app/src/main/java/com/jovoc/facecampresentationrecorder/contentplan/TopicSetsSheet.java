package com.jovoc.facecampresentationrecorder.contentplan;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.db.Slide;

import java.util.List;

/** The "Topic sets" sheet: every set to switch to, delete with confirmation, and a link to the website planner. */
public final class TopicSetsSheet {

    private static final int ACCENT = 0xFF64B5F6;

    private TopicSetsSheet() {
    }

    public static void show(MainActivity host) {
        final BottomSheetDialog dialog = new BottomSheetDialog(host);
        final ScrollView scroll = new ScrollView(host);
        scroll.setBackgroundColor(0xFF121212);
        dialog.setContentView(scroll);
        dialog.show();
        fill(host, dialog, scroll);
    }

    private static void fill(MainActivity host, BottomSheetDialog dialog, ScrollView scroll) {
        TopicSetList.load(host, items -> {
            if (!dialog.isShowing()) return;
            scroll.removeAllViews();
            scroll.addView(build(host, dialog, scroll, items));
        });
    }

    private static View build(MainActivity host, BottomSheetDialog dialog, ScrollView scroll,
                              List<TopicSetList.Item> items) {
        LinearLayout root = new LinearLayout(host);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        int pad = dp(host, 16);
        root.setPadding(pad, pad, pad, pad);

        TextView heading = new TextView(host);
        heading.setText(R.string.cplan_sets_title);
        heading.setTextColor(0xFFFFFFFF);
        heading.setTextSize(20);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setPadding(0, 0, 0, dp(host, 8));
        root.addView(heading);

        for (TopicSetList.Item item : items) {
            root.addView(row(host, dialog, scroll, item));
        }

        if (!TopicSetList.hasVideos(items)) {
            TextView empty = new TextView(host);
            empty.setText(R.string.cplan_sets_empty);
            empty.setTextColor(0xB3FFFFFF);
            empty.setTextSize(14);
            empty.setPadding(0, dp(host, 16), 0, dp(host, 8));
            root.addView(empty);
        }

        Button web = new Button(host);
        web.setText(R.string.cplan_open_web);
        web.setAllCaps(false);
        web.setTextColor(0xFF0B1620);
        web.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        web.setMinHeight(dp(host, 48));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = dp(host, 12);
        web.setLayoutParams(wlp);
        web.setOnClickListener(v -> ContentPlanWeb.open(host));
        root.addView(web);
        return root;
    }

    private static View row(MainActivity host, BottomSheetDialog dialog, ScrollView scroll,
                            TopicSetList.Item item) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(host, 48));
        row.setPadding(dp(host, 12), dp(host, 6), dp(host, 4), dp(host, 6));
        row.setBackgroundColor(0xFF222222);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(host, 6);
        row.setLayoutParams(lp);

        LinearLayout text = new LinearLayout(host);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        String state = item.setId == null ? null : stateWord(host, item);
        if (item.setId != null && item.date != null) {
            TextView head = new TextView(host);
            String d = TopicSetChips.formatDate(item.date);
            String headText = state == null ? d : host.getString(R.string.cplan_status_join, d, state);
            head.setText(headText);
            head.setTextColor(0xB3FFFFFF);
            head.setTextSize(12);
            head.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            text.addView(head);
        }

        TextView name = new TextView(host);
        name.setText(item.setId == null ? host.getString(R.string.cplan_my_topics) : item.title);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        text.addView(name);

        if (item.onScreen) {
            TextView on = new TextView(host);
            on.setText(R.string.cplan_on_screen);
            on.setTextColor(ACCENT);
            on.setTextSize(12);
            text.addView(on);
        }
        row.addView(text);

        row.setOnClickListener(v -> {
            host.switchSlideSet(item.setId);
            dialog.dismiss();
        });

        if (item.setId != null) {
            Button del = new Button(host, null, 0);
            del.setText(R.string.cplan_delete);
            del.setAllCaps(false);
            del.setTextColor(ACCENT);
            del.setTextSize(14);
            del.setBackgroundColor(0x00000000);
            del.setMinHeight(dp(host, 48));
            del.setMinimumHeight(dp(host, 48));
            del.setOnClickListener(v -> confirmDelete(host, dialog, scroll, item));
            row.addView(del);
        }
        return row;
    }

    private static String stateWord(MainActivity host, TopicSetList.Item item) {
        if (item.recorded) return host.getString(R.string.cplan_recorded);
        if (item.missed) return host.getString(R.string.cplan_day_missed);
        if (item.isToday) return host.getString(R.string.cplan_day_today);
        return null;
    }

    private static void confirmDelete(MainActivity host, BottomSheetDialog dialog, ScrollView scroll,
                                      TopicSetList.Item item) {
        new AlertDialog.Builder(host)
                .setTitle(R.string.cplan_delete_title)
                .setMessage(R.string.cplan_delete_message)
                .setNegativeButton(R.string.cplan_cancel, null)
                .setPositiveButton(R.string.cplan_delete, (d, w) ->
                        // The PlanSetIndex entry stays, so a later fetch never brings this set back (planner rule 3).
                        host.slideRepository().deleteSetOrOwn(item.setId, removed -> {
                            host.discardSlideData(removed);
                            if (item.onScreen || (item.setId != null && item.setId.equals(host.activeSetId()))) {
                                host.switchSlideSet(null);
                            }
                            if (dialog.isShowing()) fill(host, dialog, scroll);
                        }))
                .show();
    }

    private static int dp(MainActivity host, int v) {
        return Math.round(v * host.getResources().getDisplayMetrics().density);
    }
}
