package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.AuthRepository;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.auth.SignInFlow;

/**
 * Drives the shared email -> 6-digit code form ({@code view_sign_in_form}) for both the full sign-in screen
 * and the in-place sign-in sheet: sending, resending, verifying, the countdowns and the error lines. The
 * host owns the {@link SignInFlow} (so it can keep it across a rotation) and decides what happens after.
 */
final class SignInPanel {

    interface Listener {
        /** The session now exists. */
        void onSignedIn(boolean accountCreated);

        /** "Not now" / "Later". */
        void onCancel();
    }

    private final Context context;
    private final SignInFlow flow;
    private final String emailSubtitle;
    private final Listener listener;
    private final SessionManager session;
    private final Handler ticker = new Handler(Looper.getMainLooper());

    private final View stepEmail;
    private final View stepCode;
    private final TextView subtitle;
    private final TextView status;
    private final ProgressBar progress;
    private final EditText editEmail;
    private final EditText editCode;
    private final Button btnSend;
    private final Button btnVerify;
    private final Button btnResend;

    private boolean busy;
    private boolean sanitizing;
    private boolean destroyed;

    /** {@code emailSubtitle} is the line above the email box; null keeps the form's default. */
    SignInPanel(Context context, View form, SignInFlow flow, String emailSubtitle, Listener listener) {
        this.context = context;
        this.flow = flow;
        this.emailSubtitle = emailSubtitle != null ? emailSubtitle
                : context.getString(R.string.sign_in_email_hint_text);
        this.listener = listener;
        this.session = SessionManager.get(context);

        stepEmail = form.findViewById(R.id.step_email);
        stepCode = form.findViewById(R.id.step_code);
        subtitle = form.findViewById(R.id.tv_sign_in_subtitle);
        status = form.findViewById(R.id.tv_sign_in_status);
        progress = form.findViewById(R.id.progress_sign_in);
        editEmail = form.findViewById(R.id.edit_email);
        editCode = form.findViewById(R.id.edit_code);
        btnSend = form.findViewById(R.id.btn_send_code);
        btnVerify = form.findViewById(R.id.btn_verify);
        btnResend = form.findViewById(R.id.btn_resend);

        btnSend.setOnClickListener(v -> sendCode(false));
        btnResend.setOnClickListener(v -> sendCode(true));
        btnVerify.setOnClickListener(v -> verify());
        form.findViewById(R.id.btn_change_email).setOnClickListener(v -> showEmailStep(true));
        form.findViewById(R.id.btn_not_now).setOnClickListener(v -> listener.onCancel());

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
    }

    /** Draws the step the flow is on; call once after construction. */
    void start() {
        if (!flow.email().isEmpty() && editEmail.getText().length() == 0) editEmail.setText(flow.email());
        if (flow.step() == SignInFlow.Step.CODE) showCodeStep();
        else showEmailStep(false);
    }

    /** Back on the code step returns to the email step; returns false when the host should close. */
    boolean handleBack() {
        if (flow.step() == SignInFlow.Step.CODE && !busy) {
            showEmailStep(true);
            return true;
        }
        return false;
    }

    void destroy() {
        destroyed = true;
        ticker.removeCallbacksAndMessages(null);
    }

    // ---- steps ----

    private void showEmailStep(boolean clearCode) {
        flow.onChangeEmail();
        stepEmail.setVisibility(View.VISIBLE);
        stepCode.setVisibility(View.GONE);
        subtitle.setText(emailSubtitle);
        clearStatus();
        if (clearCode) editCode.setText("");
        refreshControls();
    }

    private void showCodeStep() {
        stepEmail.setVisibility(View.GONE);
        stepCode.setVisibility(View.VISIBLE);
        subtitle.setText(context.getString(R.string.sign_in_code_hint_text, flow.email()));
        editCode.requestFocus();
        refreshControls();
    }

    // ---- actions ----

    private void sendCode(boolean isResend) {
        if (busy || (isResend ? !flow.canResend() : !flow.canSend())) return;
        String target = flow.emailToSend(editEmail.getText().toString(), isResend);
        if (target == null) {
            showError(context.getString(R.string.err_bad_email));
            return;
        }
        clearStatus();
        setBusy(true);
        session.requestCode(target, new SessionManager.Callback<Void>() {
            @Override
            public void onResult(Void result) {
                if (destroyed) return;
                flow.onCodeSent(target);
                setBusy(false);
                if (isResend) {
                    editCode.setText("");
                    Toast.makeText(context, R.string.sign_in_code_sent, Toast.LENGTH_SHORT).show();
                }
                showCodeStep();
            }

            @Override
            public void onError(AuthException e) {
                if (destroyed) return;
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
            showError(context.getString(R.string.err_bad_code));
            return;
        }
        clearStatus();
        setBusy(true);
        session.exchangeCode(flow.email(), code, new SessionManager.Callback<Boolean>() {
            @Override
            public void onResult(Boolean created) {
                if (destroyed) return;
                listener.onSignedIn(created != null && created);
            }

            @Override
            public void onError(AuthException e) {
                if (destroyed) return;
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

    // ---- state / messages ----

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        refreshControls();
    }

    /** Enables/disables the buttons and runs the resend countdown while a deadline is pending. */
    private void refreshControls() {
        ticker.removeCallbacksAndMessages(null);
        if (destroyed) return;
        btnSend.setEnabled(!busy && flow.canSend());
        btnVerify.setEnabled(!busy && flow.canVerify());
        btnResend.setEnabled(!busy && flow.canResend());
        btnResend.setText(flow.canResend() ? context.getString(R.string.sign_in_resend)
                : context.getString(R.string.sign_in_resend_wait, flow.resendSecondsLeft()));
        editEmail.setEnabled(!busy);
        editCode.setEnabled(!busy);
        if (flow.hasPendingDeadline()) ticker.postDelayed(this::refreshControls, 1000);
    }

    private String messageFor(SignInFlow.Problem p, int retryAfterSeconds) {
        switch (p) {
            case OFFLINE:
                return context.getString(R.string.err_offline);
            case BAD_EMAIL:
                return context.getString(R.string.err_bad_email);
            case BAD_CODE:
                return context.getString(R.string.err_bad_code);
            case WRONG_CODE:
                return context.getString(R.string.err_invalid_code);
            case RATE_LIMITED:
                return retryAfterSeconds > 0
                        ? context.getString(R.string.err_rate_limited, SignInActivity.formatWait(retryAfterSeconds))
                        : context.getString(R.string.err_rate_limited_unknown);
            case MAIL_FAILED:
                return context.getString(R.string.err_mail_failed);
            default:
                return context.getString(R.string.err_server);
        }
    }

    private void showError(String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private void clearStatus() {
        status.setVisibility(View.GONE);
    }
}
