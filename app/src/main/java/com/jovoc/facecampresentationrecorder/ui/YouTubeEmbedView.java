package com.jovoc.facecampresentationrecorder.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.jovoc.facecampresentationrecorder.R;

import java.util.Collections;

/**
 * A 16:9 YouTube player. The WebView only exists between {@link #start()} and {@link #stop()}, so
 * nothing loads (or plays) while the help block is collapsed or the screen is paused. If the page
 * cannot load, a "Watch on YouTube" link opens the video outside the app.
 */
final class YouTubeEmbedView extends FrameLayout {

    private static final String TAG = "YouTubeEmbedView";

    private final String videoId;
    private final TextView fallback;
    private final ProgressBar spinner;
    private WebView webView;

    YouTubeEmbedView(Context context, String videoId) {
        super(context);
        this.videoId = videoId;
        setBackgroundColor(Color.BLACK);

        fallback = new TextView(context);
        fallback.setText(R.string.set_help_watch_on_youtube);
        fallback.setTextColor(0xFF22D3EE);
        fallback.setTextSize(14);
        fallback.setPaintFlags(fallback.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        fallback.setGravity(Gravity.CENTER);
        fallback.setMinHeight(Math.round(48 * getResources().getDisplayMetrics().density));
        fallback.setClickable(true);
        fallback.setFocusable(true);
        fallback.setVisibility(GONE);
        fallback.setOnClickListener(v -> WebLinks.open(getContext(), SettingsHelp.watchUrl(this.videoId)));
        addView(fallback, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        // Shown from start() until the page has loaded, so the player is never a blank black box.
        spinner = new ProgressBar(context);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(0xFF22D3EE));
        spinner.setVisibility(GONE);
        addView(spinner, new LayoutParams(Math.round(40 * getResources().getDisplayMetrics().density),
                Math.round(40 * getResources().getDisplayMetrics().density), Gravity.CENTER));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
    }

    /** Creates the WebView and starts loading; no-op if already running. */
    @SuppressLint("SetJavaScriptEnabled") // the YouTube player is a JS app
    void start() {
        if (webView != null) return;
        fallback.setVisibility(GONE);
        spinner.setVisibility(VISIBLE);
        WebView view;
        try {
            view = new WebView(getContext());
        } catch (RuntimeException e) { // WebView missing or being updated
            Log.w(TAG, "WebView unavailable", e);
            spinner.setVisibility(GONE);
            fallback.setVisibility(VISIBLE);
            return;
        }
        webView = view;
        view.setBackgroundColor(Color.BLACK);

        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        // The installed WebView's own Chrome string, current by construction, minus the "; wv"
        // marker that identifies an embedded WebView.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("https://www.youtube-nocookie.com/embed/")) return false;
                // Anything else (logo, title, "Watch on YouTube" in the player) leaves the app.
                if (request.isForMainFrame()) {
                    WebLinks.open(getContext(), url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                spinner.setVisibility(GONE);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showFallback();
            }

            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest request,
                                            WebResourceResponse response) {
                if (request.isForMainFrame()) showFallback();
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                showFallback();
                return true; // handled: the app must not die with the renderer
            }
        });

        addView(view, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // YouTube rejects embeds without a Referer identifying the app (player error 153).
        view.loadUrl(SettingsHelp.embedUrl(videoId), Collections.singletonMap(
                "Referer", "https://" + getContext().getPackageName()));
    }

    /** Stops playback and destroys the WebView; no-op if not running. */
    void stop() {
        WebView view = webView;
        if (view == null) return;
        webView = null;
        view.stopLoading();
        view.setWebViewClient(new WebViewClient());
        removeView(view);
        view.loadUrl("about:blank");
        view.destroy();
    }

    private void showFallback() {
        WebView view = webView;
        if (view != null) view.setVisibility(View.INVISIBLE);
        spinner.setVisibility(GONE);
        fallback.setVisibility(VISIBLE);
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
