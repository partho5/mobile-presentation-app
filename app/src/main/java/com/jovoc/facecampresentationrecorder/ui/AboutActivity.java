package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.R;

/** The "About App" screen: app name, real version, what the app does, privacy note, and links out. */
public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        TextView version = findViewById(R.id.tv_about_version);
        version.setText(getString(R.string.about_version, BuildConfig.VERSION_NAME));

        bindLink(findViewById(R.id.btn_about_privacy), WebLinks.privacyUrl());
        bindLink(findViewById(R.id.btn_about_terms), WebLinks.termsUrl());

        findViewById(R.id.btn_about_contact).setOnClickListener(v ->
                startActivity(new Intent(this, ContactActivity.class)));
    }

    /** A link button shows only once its page exists (the URL is empty until then). */
    private void bindLink(View button, String url) {
        if (TextUtils.isEmpty(url)) return;
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> WebLinks.open(this, url));
    }
}
