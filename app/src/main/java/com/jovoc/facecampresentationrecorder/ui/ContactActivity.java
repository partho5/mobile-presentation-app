package com.jovoc.facecampresentationrecorder.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.jovoc.facecampresentationrecorder.R;

/** The developer's contact screen: his face, a short note, and the email as the one thing to tap. */
public class ContactActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.contact_email_card).setOnClickListener(v -> composeEmail());
        findViewById(R.id.btn_copy_email).setOnClickListener(v -> copyEmail());
    }

    private void composeEmail() {
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"));
        intent.putExtra(Intent.EXTRA_EMAIL, new String[]{getString(R.string.contact_email)});
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            copyEmail();
        }
    }

    private void copyEmail() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.contact_name),
                    getString(R.string.contact_email)));
        }
        Toast.makeText(this, R.string.contact_copied, Toast.LENGTH_SHORT).show();
    }
}
