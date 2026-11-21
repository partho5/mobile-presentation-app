package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.tracker.ActivityTracker;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.auth.SignInFlow;

/**
 * Email one-time-code sign-in ("Connect publishing"). Step 1 asks for the email, step 2 for the
 * 6-digit code. New and existing addresses follow the same flow (the backend answers identically).
 * Signing in is optional: Back / "Not now" return to the previous screen. When started with
 * {@link #EXTRA_NEXT} the user continues into that screen after signing in.
 */
public class SignInActivity extends AppCompatActivity {

    /** Intent to start after a successful sign-in (e.g. the Publish screen the user was opening). */
    public static final String EXTRA_NEXT = "next";

    private static final String STATE_STEP_CODE = "step_code";
    private static final String STATE_EMAIL = "email";
    private static final String STATE_LEFT = "left_ms";

    private SessionManager session;
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final SignInFlow flow = new SignInFlow(SystemClock::elapsedRealtime);

    private View stepEmail;
    private View stepCode;
    private TextView subtitle;
    private TextView status;
    private ProgressBar progress;
    private EditText editEmail;
    private EditText editCode;
    private Button btnSend;
    private Button btnVerify;
    private Button btnResend;
    private Intent next;

    private boolean busy;
    private boolean sanitizing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_in);
        session = SessionManager.get(this);
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

        stepEmail = findViewById(R.id.step_email);
        stepCode = findViewById(R.id.step_code);
        subtitle = findViewById(R.id.tv_sign_in_subtitle);
        status = findViewById(R.id.tv_sign_in_status);
        progress = findViewById(R.id.progress_sign_in);
        editEmail = findViewById(R.id.edit_email);
        editCode = findViewById(R.id.edit_code);
        btnSend = findViewById(R.id.btn_send_code);
        btnVerify = findViewById(R.id.btn_verify);
        btnResend = findViewById(R.id.btn_resend);

        btnSend.setOnClickListener(v -> sendCode(false));
        btnResend.setOnClickListener(v -> sendCode(true));
        btnVerify.setOnClickListener(v -> verify());
        findViewById(R.id.btn_change_email).setOnClickListener(v -> showEmailStep());
        findViewById(R.id.btn_not_now).setOnClickListener(v -> finish());

        editEmail.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                sendCode(false);
                return true;
            }
            return false;
        });
        editCode.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                verify();
                return true;
            }
            return false;
        });
        editCode.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (sanitizing) return;
                String clean = AuthRepository.sanitizeCode(s.toString());
                if (!clean.contentEquals(s)) {
                    sanitizing = true;
                    editCode.setText(clean);
                    editCode.setSelection(clean.length());
                    sanitizing = false;
                }
                if (clean.length() == 6 && !busy && flow.canVerify()) {
                    verify();
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (flow.step() == SignInFlow.Step.CODE && !busy) {
                    showEmailStep();
                } else {
                    finish();
                }
            }
        });

        if (savedInstanceState != null) {
            flow.restore(savedInstanceState.getBoolean(STATE_STEP_CODE)
                            ? SignInFlow.Step.CODE : SignInFlow.Step.EMAIL,
                    savedInstanceState.getString(STATE_EMAIL, ""),
                    savedInstanceState.getLongArray(STATE_LEFT));
        }
        if (flow.step() == SignInFlow.Step.CODE) showCodeStep();
        else showEmailStep(false);
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
        ticker.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- steps ----

    private void showEmailStep() {
        showEmailStep(true);
    }

    private void showEmailStep(boolean clearCode) {
        flow.onChangeEmail();
        stepEmail.setVisibility(View.VISIBLE);
        stepCode.setVisibility(View.GONE);
        subtitle.setText(R.string.sign_in_email_hint_text);
        clearStatus();
        if (clearCode) editCode.setText("");
        refreshControls();
    }

    private void showCodeStep() {
        stepEmail.setVisibility(View.GONE);
        stepCode.setVisibility(View.VISIBLE);
        subtitle.setText(getString(R.string.sign_in_code_hint_text, flow.email()));
        editCode.requestFocus();
        refreshControls();
    }

    // ---- actions ----

    private void sendCode(boolean isResend) {
        if (busy || (isResend ? !flow.canResend() : !flow.canSend())) return;
        String target = flow.emailToSend(editEmail.getText().toString(), isResend);
        if (target == null) {
            showError(getString(R.string.err_bad_email));
            return;
        }
        clearStatus();
        setBusy(true);
        session.requestCode(target, new SessionManager.Callback<Void>() {
            @Override
            public void onResult(Void result) {
                if (isFinishing() || isDestroyed()) return;
                flow.onCodeSent(target);
                setBusy(false);
                if (isResend) {
                    editCode.setText("");
                    Toast.makeText(SignInActivity.this, R.string.sign_in_code_sent, Toast.LENGTH_SHORT).show();
                }
                showCodeStep();
            }

            @Override
            public void onError(AuthException e) {
                if (isFinishing() || isDestroyed()) return;
                SignInFlow.Problem p = flow.onSendFailed(e, isResend);
                setBusy(false);
                showError(messageFor(p, e.retryAfterSeconds));
            }
        });
    }

    private void verify() {
        if (busy || !flow.canVerify()) return;
        String code = AuthRepository.sanitizeCode(editCode.getText().toString());
        if (!AuthRepository.isValidCode(code)) {
            showError(getString(R.string.err_bad_code));
            return;
        }
        clearStatus();
        setBusy(true);
        session.exchangeCode(flow.email(), code, new SessionManager.Callback<Boolean>() {
            @Override
            public void onResult(Boolean created) {
                if (isFinishing() || isDestroyed()) return;
                finishSignedIn(created != null && created);
            }

            @Override
            public void onError(AuthException e) {
                if (isFinishing() || isDestroyed()) return;
                SignInFlow.Problem p = flow.onVerifyFailed(e);
                setBusy(false);
                if (p == SignInFlow.Problem.WRONG_CODE) {
                    editCode.setText("");
                    editCode.requestFocus();
                }
                showError(messageFor(p, e.retryAfterSeconds));
            }
        });
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

    // ---- state / messages ----

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        refreshControls();
    }

    /** Enables/disables the buttons and runs the resend countdown while a deadline is pending. */
    private void refreshControls() {
        ticker.removeCallbacksAndMessages(null);
        btnSend.setEnabled(!busy && flow.canSend());
        btnVerify.setEnabled(!busy && flow.canVerify());
        btnResend.setEnabled(!busy && flow.canResend());
        btnResend.setText(flow.canResend() ? getString(R.string.sign_in_resend)
                : getString(R.string.sign_in_resend_wait, flow.resendSecondsLeft()));
        editEmail.setEnabled(!busy);
        editCode.setEnabled(!busy);
        if (flow.hasPendingDeadline()) ticker.postDelayed(this::refreshControls, 1000);
    }

    private String messageFor(SignInFlow.Problem p, int retryAfterSeconds) {
        switch (p) {
            case OFFLINE:
                return getString(R.string.err_offline);
            case BAD_EMAIL:
                return getString(R.string.err_bad_email);
            case BAD_CODE:
                return getString(R.string.err_bad_code);
            case WRONG_CODE:
                return getString(R.string.err_invalid_code);
            case RATE_LIMITED:
                return retryAfterSeconds > 0
                        ? getString(R.string.err_rate_limited, formatWait(retryAfterSeconds))
                        : getString(R.string.err_rate_limited_unknown);
            case MAIL_FAILED:
                return getString(R.string.err_mail_failed);
            default:
                return getString(R.string.err_server);
        }
    }

    static String formatWait(int seconds) {
        if (seconds < 90) return seconds + (seconds == 1 ? " second" : " seconds");
        int minutes = (seconds + 59) / 60;
        return minutes + " minutes";
    }

    private void showError(String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private void clearStatus() {
        status.setVisibility(View.GONE);
    }
}
