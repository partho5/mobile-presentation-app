package com.jovoc.facecampresentationrecorder.ui;

import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.auth.SignInFlow;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;

/**
 * Sign-in without leaving the current screen: {@link #require} runs the action at once when signed in,
 * otherwise opens the email -> code form as a bottom sheet and runs the action once the code is verified.
 * "Later" just closes it. The half-done sign-in (email, "code sent", cooldowns) is kept for the app
 * process, so reopening after a rotation or "Later" continues at the code step instead of mailing a new
 * code; the queued action itself does not survive the host screen being destroyed.
 */
public final class SignInSheet {

    /** The sign-in in progress, shared by every sheet in this process; null when none. */
    private static SignInFlow draft;

    private SignInSheet() {
    }

    /**
     * @param title      the sheet heading, naming what the user is trying to do
     * @param reason     the line above the email box
     * @param onSignedIn what the user was doing; runs on the main thread once a session exists
     */
    public static void require(AppCompatActivity host, @StringRes int title, @StringRes int reason,
                               Runnable onSignedIn) {
        if (host.isFinishing() || host.isDestroyed()) return;
        if (SessionManager.get(host).hasSession()) {
            onSignedIn.run();
            return;
        }
        if (draft == null) draft = new SignInFlow(SystemClock::elapsedRealtime);
        draft.dropStaleCode();
        final SignInFlow flow = draft;
        ActivityTracker.log("signin", "sheet");

        final BottomSheetDialog dialog = new BottomSheetDialog(host);
        View root = host.getLayoutInflater().inflate(R.layout.sheet_sign_in, null, false);
        ((TextView) root.findViewById(R.id.tv_sign_in_sheet_title)).setText(title);
        ((Button) root.findViewById(R.id.btn_not_now)).setText(R.string.sign_in_later);
        dialog.setContentView(root);

        final SignInPanel[] panel = new SignInPanel[1];
        final DefaultLifecycleObserver hostGone = new DefaultLifecycleObserver() {
            @Override
            public void onDestroy(LifecycleOwner owner) {
                dialog.dismiss(); // a rotation recreates the host; never leak its window
            }
        };
        panel[0] = new SignInPanel(host, root.findViewById(R.id.sign_in_form), flow,
                host.getString(reason), new SignInPanel.Listener() {
            @Override
            public void onSignedIn(boolean accountCreated) {
                draft = null;
                dialog.dismiss();
                if (accountCreated) {
                    Toast.makeText(host.getApplicationContext(), R.string.sign_in_welcome_new,
                            Toast.LENGTH_SHORT).show();
                }
                if (!host.isFinishing() && !host.isDestroyed()) onSignedIn.run();
            }

            @Override
            public void onCancel() {
                dialog.dismiss();
            }
        });
        dialog.getOnBackPressedDispatcher().addCallback(dialog, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!panel[0].handleBack()) dialog.dismiss();
            }
        });
        dialog.setOnDismissListener(d -> {
            panel[0].destroy();
            host.getLifecycle().removeObserver(hostGone);
        });
        host.getLifecycle().addObserver(hostGone);

        // The host may ignore the keyboard (the main screen does); the sheet must not, or the box is hidden.
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        });
        panel[0].start();
        dialog.show();
    }
}
