package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.jovoc.facecampresentationrecorder.MainActivity;

/**
 * Launcher entry: opens the onboarding on the first run of a fresh install, otherwise
 * MainActivity. Signing in is optional (it is only needed to publish), so there is no sign-in
 * gate and no network work at launch.
 */
public class StartupActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this,
                OnboardingGate.shouldShow(this) ? OnboardingActivity.class : MainActivity.class));
        finish();
    }
}
