package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.auth.SignInFlow;

/**
 * Email one-time-code sign-in ("Connect publishing"). Step 1 asks for the email, step 2 for the
 * 6-digit code. New and existing addresses follow the same flow (the backend answers identically).
 * Signing in is optional: Back / "Not now" return to the previous screen. When started with
 * {@link #EXTRA_NEXT} the user continues into that screen after signing in. The form itself is
 * {@link SignInPanel}, shared with the in-place {@link SignInSheet}.
 */
public class SignInActivity extends AppCompatActivity {

    /** Intent to start after a successful sign-in (e.g. the Publish screen the user was opening). */
    public static final String EXTRA_NEXT = "next";

    private static final String STATE_STEP_CODE = "step_code";
    private static final String STATE_EMAIL = "email";
    private static final String STATE_LEFT = "left_ms";

    private final SignInFlow flow = new SignInFlow(SystemClock::elapsedRealtime);
    private SignInPanel panel;
    private Intent next;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_in);
        SessionManager session = SessionManager.get(this);
        next = IntentCompat.getParcelableExtra(getIntent(), EXTRA_NEXT, Intent.class);
        if (session.hasSession()) { // opened while already signed in: nothing to do here
            finishSignedIn(false);
            return;
        }
        if (savedInstanceState == null) ActivityTracker.log("signin", "start");

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.sign_in_root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        if (savedInstanceState != null) {
            flow.restore(savedInstanceState.getBoolean(STATE_STEP_CODE)
                            ? SignInFlow.Step.CODE : SignInFlow.Step.EMAIL,
                    savedInstanceState.getString(STATE_EMAIL, ""),
                    savedInstanceState.getLongArray(STATE_LEFT));
        }
        panel = new SignInPanel(this, findViewById(R.id.sign_in_form), flow, null, new SignInPanel.Listener() {
            @Override
            public void onSignedIn(boolean accountCreated) {
                finishSignedIn(accountCreated);
            }

            @Override
            public void onCancel() {
                finish();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!panel.handleBack()) finish();
            }
        });
        panel.start();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_STEP_CODE, flow.step() == SignInFlow.Step.CODE);
        out.putString(STATE_EMAIL, flow.email());
        out.putLongArray(STATE_LEFT, flow.remainingMillis());
    }

    @Override
    protected void onDestroy() {
        if (panel != null) panel.destroy();
        super.onDestroy();
    }

    /** Signed in: continue into the queued screen, or just return to the one that opened this. */
    private void finishSignedIn(boolean accountCreated) {
        if (accountCreated) {
            Toast.makeText(getApplicationContext(), R.string.sign_in_welcome_new, Toast.LENGTH_SHORT).show();
        }
        if (SignInFlow.afterSignIn(next != null) == SignInFlow.Destination.NEXT) {
            startActivity(next);
        }
        finish();
    }

    static String formatWait(int seconds) {
        if (seconds < 90) return seconds + (seconds == 1 ? " second" : " seconds");
        int minutes = (seconds + 59) / 60;
        return minutes + " minutes";
    }
}
