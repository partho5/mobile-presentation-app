package com.jovoc.facecampresentationrecorder.auth;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.ui.SignInActivity;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App-wide access to the session: owns the client, token store and repository, runs blocking
 * auth calls off the main thread, and sends the user to the sign-in screen when the session
 * ends without them asking (revoked on the server, refresh refused).
 */
public final class SessionManager {

    public interface Callback<T> {
        void onResult(T result);

        void onError(AuthException error);
    }

    private interface Job<T> {
        T run() throws AuthException;
    }

    private static SessionManager instance;

    private final Context appContext;
    private final AuthRepository repo;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized SessionManager get(Context context) {
        if (instance == null) instance = new SessionManager(context.getApplicationContext());
        return instance;
    }

    private SessionManager(Context appContext) {
        this.appContext = appContext;
        ApiClient api = new ApiClient(BuildConfig.API_BASE_URL, BuildConfig.DEBUG);
        TokenStore store = new TokenStore(new SecureKeyValueStore(appContext));
        this.repo = new AuthRepository(api, store);
        repo.setForcedSignOutListener(() -> main.post(() -> openSignIn(appContext)));
    }

    public AuthRepository repository() {
        return repo;
    }

    public boolean hasSession() {
        return repo.store().hasSession();
    }

    public String email() {
        return repo.store().email();
    }

    public String deviceName() {
        String stored = repo.store().deviceName();
        return stored != null ? stored : defaultDeviceName();
    }

    /** "Pixel 8", "Samsung SM-S918B": what the Devices list will show for this phone. */
    public static String defaultDeviceName() {
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String name = model.toLowerCase(Locale.ROOT).startsWith(maker.toLowerCase(Locale.ROOT))
                ? model : (maker + " " + model).trim();
        if (name.isEmpty()) return "Android";
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    public void requestCode(String email, Callback<Void> cb) {
        async(() -> {
            repo.requestCode(email);
            return null;
        }, cb);
    }

    /** Result is true when a new account was created. */
    public void exchangeCode(String email, String code, Callback<Boolean> cb) {
        async(() -> repo.exchangeCode(email, code, defaultDeviceName()), cb);
    }

    /** Result is true when the server confirmed the revoke; the local session is cleared either way. */
    public void signOut(Callback<Boolean> cb) {
        async(repo::signOut, cb);
    }

    /** Launches the sign-in screen as the only screen of a fresh task. */
    public static void openSignIn(Context context) {
        Intent i = new Intent(context, SignInActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(i);
    }

    private <T> void async(Job<T> job, Callback<T> cb) {
        io.execute(() -> {
            T result = null;
            AuthException error = null;
            try {
                result = job.run();
            } catch (AuthException e) {
                error = e;
            }
            final T r = result;
            final AuthException e = error;
            main.post(() -> {
                if (e != null) cb.onError(e);
                else cb.onResult(r);
            });
        });
    }
}
