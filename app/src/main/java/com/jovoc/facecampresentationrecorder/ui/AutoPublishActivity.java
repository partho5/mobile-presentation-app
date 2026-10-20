package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

/**
 * "Auto upload to social media": the next step to take, every supported platform with its
 * connection state, and a footer row into the account screen. Opened from the drawer once signed in.
 */
public class AutoPublishActivity extends AppCompatActivity {

    private SessionManager session;
    private View nextStepCard;
    private RecyclerView platformList;
    private ProgressBar progress;
    private View errorBox;
    private TextView tvError;
    private TextView tvEmail;
    private boolean created;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auto_publish);
        session = SessionManager.get(this);
        if (!session.hasSession()) {
            SessionManager.openSignIn(this, new Intent(getIntent()));
            finish();
            return;
        }
        created = true;

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        ((ImageButton) findViewById(R.id.btn_back)).setOnClickListener(v -> finish());
        nextStepCard = findViewById(R.id.card_next_step);
        platformList = findViewById(R.id.rv_platforms);
        platformList.setLayoutManager(new LinearLayoutManager(this));
        progress = findViewById(R.id.progress_auto_publish);
        errorBox = findViewById(R.id.auto_publish_error_box);
        tvError = findViewById(R.id.tv_auto_publish_error);
        tvEmail = findViewById(R.id.tv_auto_publish_email);

        findViewById(R.id.btn_auto_publish_retry).setOnClickListener(v -> refresh());
        findViewById(R.id.row_account).setOnClickListener(
                v -> startActivity(new Intent(this, AccountActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!created) return;
        // Signed out on the account screen (or the session ended): nothing to show here.
        if (!session.hasSession()) {
            finish();
            return;
        }
        tvEmail.setText(session.email());
        // The user may come back from the browser after connecting a platform.
        refresh();
    }

    /** Loads the platforms and the auto-publish state; the list arrives in M2, the next-step card in M3. */
    private void refresh() {
        errorBox.setVisibility(View.GONE);
    }
}
