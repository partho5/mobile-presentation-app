package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.publish.PublishManager;

/**
 * Launcher entry: opens the onboarding on the first run of a fresh install, otherwise
 * MainActivity. Signing in is optional (it is only needed to publish), so there is no sign-in
 * gate and no network work at launch. It also publishes any auto-publish recording that was left
 * waiting for processing when the app was killed (local work; the upload runs in WorkManager).
 */
public class StartupActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean onboarding = OnboardingGate.shouldShow(this);
        PublishManager.get(this).sweepAuto();
        startActivity(new Intent(this, onboarding ? OnboardingActivity.class : MainActivity.class));
        finish();
    }
}
