package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.jovoc.facecampresentationrecorder.MainActivity;

/**
 * Launcher entry: always opens MainActivity. Signing in is optional (it is only needed to
 * publish), so there is no gate and no network or storage work at launch.
 */
public class StartupActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
