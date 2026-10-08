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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

/**
 * Email one-time-code sign-in. Step 1 asks for the email, step 2 for the 6-digit code.
 * New and existing addresses follow the same flow (the backend answers identically).
 */
public class SignInActivity extends AppCompatActivity {

    private static final String STATE_STEP_CODE = "step_code";
    private static final String STATE_EMAIL = "email";
    private static final String STATE_RESEND_LEFT = "resend_left_ms";
    private static final long RESEND_COOLDOWN_MS = 30_000L;
    private static final int DEFAULT_LOCK_SECONDS = 60;

    private SessionManager session;
    private final Handler ticker = new Handler(Looper.getMainLooper());

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

    private boolean onCodeStep;
    private String email = "";
    private boolean busy;
    private boolean sanitizing;
    // SystemClock.elapsedRealtime() deadlines for the three lockable actions
    private long sendUntil;
    private long verifyUntil;
    private long resendUntil;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sign_in);
        session = SessionManager.get(this);

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

        editEmail.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
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
                if (clean.length() == 6 && !busy && SystemClock.elapsedRealtime() >= verifyUntil) {
                    verify();
                }
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (onCodeStep && !busy) {
                    showEmailStep();
                } else {
                    finish();
                }
            }
        });

        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_STEP_CODE)) {
            email = savedInstanceState.getString(STATE_EMAIL, "");
            resendUntil = SystemClock.elapsedRealtime() + savedInstanceState.getLong(STATE_RESEND_LEFT);
            showCodeStep();
        } else {
            showEmailStep();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_STEP_CODE, onCodeStep);
        out.putString(STATE_EMAIL, email);
        out.putLong(STATE_RESEND_LEFT, Math.max(0, resendUntil - SystemClock.elapsedRealtime()));
    }

    @Override
    protected void onDestroy() {
        ticker.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---- steps ----

    private void showEmailStep() {
        onCodeStep = false;
        stepEmail.setVisibility(View.VISIBLE);
        stepCode.setVisibility(View.GONE);
        subtitle.setText(R.string.sign_in_email_hint_text);
        clearStatus();
        editCode.setText("");
        refreshControls();
    }

    private void showCodeStep() {
        onCodeStep = true;
        stepEmail.setVisibility(View.GONE);
        stepCode.setVisibility(View.VISIBLE);
        subtitle.setText(getString(R.string.sign_in_code_hint_text, email));
        editCode.requestFocus();
        refreshControls();
    }

    // ---- actions ----

    private void sendCode(boolean isResend) {
        if (busy) return;
        String target = isResend ? email : AuthRepository.normalizeEmail(editEmail.getText().toString());
        if (!AuthRepository.isValidEmail(target)) {
            showError(getString(R.string.err_bad_email));
            return;
        }
        clearStatus();
        setBusy(true);
        session.requestCode(target, new SessionManager.Callback<Void>() {
            @Override
            public void onResult(Void result) {
                if (isFinishing() || isDestroyed()) return;
                email = target;
                resendUntil = SystemClock.elapsedRealtime() + RESEND_COOLDOWN_MS;
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
                long until = lockUntil(e);
                if (until > 0) {
                    if (isResend) resendUntil = until;
                    else sendUntil = until;
                }
                setBusy(false);
                showError(messageFor(e));
            }
        });
    }

    private void verify() {
        if (busy) return;
        String code = AuthRepository.sanitizeCode(editCode.getText().toString());
        if (!AuthRepository.isValidCode(code)) {
            showError(getString(R.string.err_bad_code));
            return;
        }
        clearStatus();
        setBusy(true);
        session.exchangeCode(email, code, new SessionManager.Callback<Boolean>() {
            @Override
            public void onResult(Boolean created) {
                if (isFinishing() || isDestroyed()) return;
                if (created != null && created) {
                    Toast.makeText(getApplicationContext(), R.string.sign_in_welcome_new,
                            Toast.LENGTH_SHORT).show();
                }
                Intent i = new Intent(SignInActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
                finish();
            }

            @Override
            public void onError(AuthException e) {
                if (isFinishing() || isDestroyed()) return;
                long until = lockUntil(e);
                if (until > 0) verifyUntil = until;
                setBusy(false);
                if (e.kind == AuthException.Kind.INVALID_CODE) {
                    editCode.setText("");
                    editCode.requestFocus();
                }
                showError(messageFor(e));
            }
        });
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
        long now = SystemClock.elapsedRealtime();
        btnSend.setEnabled(!busy && now >= sendUntil);
        btnVerify.setEnabled(!busy && now >= verifyUntil);
        boolean resendReady = now >= resendUntil;
        btnResend.setEnabled(!busy && resendReady);
        btnResend.setText(resendReady ? getString(R.string.sign_in_resend)
                : getString(R.string.sign_in_resend_wait, (int) ((resendUntil - now + 999) / 1000)));
        if (!(now >= sendUntil && now >= verifyUntil && resendReady)) {
            ticker.postDelayed(this::refreshControls, 1000);
        }
    }

    private static long lockUntil(AuthException e) {
        if (e.kind != AuthException.Kind.RATE_LIMITED) return 0;
        int secs = e.retryAfterSeconds > 0 ? e.retryAfterSeconds : DEFAULT_LOCK_SECONDS;
        return SystemClock.elapsedRealtime() + secs * 1000L;
    }

    private String messageFor(AuthException e) {
        switch (e.kind) {
            case NETWORK:
                return getString(R.string.err_offline);
            case BAD_INPUT:
                return getString(onCodeStep ? R.string.err_bad_code : R.string.err_bad_email);
            case INVALID_CODE:
                return getString(R.string.err_invalid_code);
            case RATE_LIMITED:
                return e.retryAfterSeconds > 0
                        ? getString(R.string.err_rate_limited, formatWait(e.retryAfterSeconds))
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
