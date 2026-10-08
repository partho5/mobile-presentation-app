package com.jovoc.facecampresentationrecorder.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;

/**
 * Launcher gate: MainActivity when this device holds a session, the sign-in screen otherwise.
 * Looks at local storage only, so the offline features open without a connection.
 */
public class StartupActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Class<?> target = SessionManager.get(this).hasSession() ? MainActivity.class : SignInActivity.class;
        startActivity(new Intent(this, target));
        finish();
    }
}
