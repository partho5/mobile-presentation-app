package com.jovoc.facecampresentationrecorder.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;

/**
 * Full-screen, in-app YouTube player. Reuses {@link YouTubeEmbedView}, so it gets the same
 * Referer handling and "Watch on YouTube" fallback; the WebView lives only while the dialog shows.
 */
final class YouTubeDialog {

    private YouTubeDialog() {}

    static void show(Context context, String videoId) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        float density = context.getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        // 16:9, but never taller than the screen (landscape).
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        int width = Math.min(screen.widthPixels, screen.heightPixels * 16 / 9);
        YouTubeEmbedView player = new YouTubeEmbedView(context, videoId);
        root.addView(player, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        ImageButton close = new ImageButton(context);
        close.setImageResource(R.drawable.ic_close);
        close.setBackgroundResource(android.R.color.transparent);
        close.setContentDescription(context.getString(R.string.onb_close_video));
        close.setOnClickListener(v -> dialog.dismiss());
        int size = Math.round(48 * density);
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(size, size,
                Gravity.TOP | Gravity.END);
        closeParams.topMargin = Math.round(8 * density);
        closeParams.setMarginEnd(Math.round(8 * density));
        root.addView(close, closeParams);

        dialog.setContentView(root);
        dialog.setOnShowListener(d -> {
            ActivityLog.log("howto_video_play");
            player.start();
        });
        dialog.setOnDismissListener(d -> player.stop());
        dialog.show();
    }
}
