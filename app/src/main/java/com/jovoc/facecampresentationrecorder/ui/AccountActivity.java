package com.jovoc.facecampresentationrecorder.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.AuthException;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

/** Minimal account screen: who is signed in on this device, and sign out. */
public class AccountActivity extends AppCompatActivity {

    private SessionManager session;
    private Button btnSignOut;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);
        session = SessionManager.get(this);
        if (!session.hasSession()) {
            SessionManager.openSignIn(this);
            return;
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.tv_account_email)).setText(session.email());
        ((TextView) findViewById(R.id.tv_account_device)).setText(session.deviceName());
        progress = findViewById(R.id.progress_account);
        btnSignOut = findViewById(R.id.btn_sign_out);
        btnSignOut.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setMessage(R.string.account_sign_out_confirm)
                .setPositiveButton(R.string.account_sign_out, (d, w) -> signOut())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private void signOut() {
        btnSignOut.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        session.signOut(new SessionManager.Callback<Boolean>() {
            @Override
            public void onResult(Boolean revokedOnServer) {
                if (!revokedOnServer) {
                    Toast.makeText(getApplicationContext(), R.string.account_sign_out_offline,
                            Toast.LENGTH_LONG).show();
                }
                SessionManager.openSignIn(AccountActivity.this);
            }

            @Override
            public void onError(AuthException error) {
                SessionManager.openSignIn(AccountActivity.this);
            }
        });
    }
}
