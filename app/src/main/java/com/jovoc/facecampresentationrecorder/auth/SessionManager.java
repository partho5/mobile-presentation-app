package com.jovoc.facecampresentationrecorder.auth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.jovoc.facecampresentationrecorder.BuildConfig;
import com.jovoc.facecampresentationrecorder.MainActivity;
import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.account.AccountApi;
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
    private final ApiClient api;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized SessionManager get(Context context) {
        if (instance == null) instance = new SessionManager(context.getApplicationContext());
        return instance;
    }

    private SessionManager(Context appContext) {
        this.appContext = appContext;
        this.api = new ApiClient(initialBaseUrl(appContext), BuildConfig.DEBUG);
        TokenStore store = new TokenStore(new SecureKeyValueStore(appContext));
        this.repo = new AuthRepository(api, store);
        repo.setForcedSignOutListener(() -> main.post(() -> {
            // Signing in is optional, so a dead session is not a wall: say so and go home.
            Toast.makeText(appContext, R.string.session_ended_toast, Toast.LENGTH_LONG).show();
            openHome(appContext);
        }));
    }

    // ---- debug-only backend URL ----

    private static final String DEBUG_PREFS = "debug_settings";
    private static final String DEBUG_KEY_URL = "api_base_url";

    private static String initialBaseUrl(Context context) {
        if (BuildConfig.DEBUG) {
            String saved = context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                    .getString(DEBUG_KEY_URL, null);
            if (saved != null && !saved.isEmpty()) {
                try {
                    new ApiClient(saved, true); // validates only
                    return saved;
                } catch (IllegalArgumentException ignored) {
                    // fall through to the built-in URL
                }
            }
        }
        return BuildConfig.API_BASE_URL;
    }

    public String baseUrl() {
        return api.baseUrl();
    }

    /**
     * Debug builds only: points the app at another backend. The session belongs to the old
     * backend, so it is dropped locally and the user signs in again.
     * @return false when the URL is not acceptable (or this is not a debug build)
     */
    public boolean changeBaseUrl(String url) {
        if (!BuildConfig.DEBUG || url == null) return false;
        try {
            api.setBaseUrl(url.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        appContext.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE)
                .edit().putString(DEBUG_KEY_URL, api.baseUrl()).apply();
        AccountApi.resetBalanceProbe();
        repo.endLocalSession();
        return true;
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

    /**
     * Opens the sign-in screen on top of the current one. When {@code next} is given it is started
     * after a successful sign-in (e.g. the Publish screen the user was heading to); otherwise the
     * user just returns to where they were.
     */
    public static void openSignIn(Context context, Intent next) {
        Intent i = new Intent(context, SignInActivity.class);
        if (next != null) i.putExtra(SignInActivity.EXTRA_NEXT, next);
        if (!(context instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    /** The main screen as the only screen of a fresh task (after sign-out, delete, a dead session). */
    public static void openHome(Context context) {
        Intent i = new Intent(context, MainActivity.class);
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
