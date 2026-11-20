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
import com.jovoc.facecampresentationrecorder.account.AccountException;
import com.jovoc.facecampresentationrecorder.account.BrandPin;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.entitlements.Entitlements;
import com.jovoc.facecampresentationrecorder.entitlements.EntitlementsRefresher;
import com.jovoc.facecampresentationrecorder.publish.PublishManager;
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
        this.api = new ApiClient(BuildConfig.API_BASE_URL, BuildConfig.DEBUG);
        TokenStore store = new TokenStore(new SecureKeyValueStore(appContext));
        // A token signed for the old session means nothing after sign-out, a dead session or a new sign-in.
        store.setSessionListener(Entitlements::clear);
        // The plan follows the brand: a change of pick, or a lost brand, re-decides which plan counts.
        store.setBrandListener(Entitlements::brandChanged);
        this.repo = new AuthRepository(api, store);
        // A call with no brand picked yet pins the server's default first; it never goes out brandless.
        repo.setWorkspaceResolver(() -> resolveBrand(false));
        repo.setForcedSignOutListener(() -> main.post(() -> {
            ActivityLog.log("session_ended");
            // Signing in is optional, so a dead session is not a wall: say so and go home.
            Toast.makeText(appContext, R.string.session_ended_toast, Toast.LENGTH_LONG).show();
            openHome(appContext);
        }));
    }

    public AuthRepository repository() {
        return repo;
    }

    /**
     * The brand a new job is made in: the pick, or the server's default pinned now. Null when none can
     * be had (offline, first use): the job must then not be saved. With no pick stored this waits on the
     * network, so it is never called on the main thread or where a screen waits for it.
     */
    public String brandForNewJob() {
        try {
            return repo.brandForCall();
        } catch (AuthException e) {
            return null;
        }
    }

    /**
     * Pins this phone's brand in the background: at sign-in ({@code atSignIn}, replacing any earlier pick),
     * or at startup, where it only fills an empty pick. Then the plan is asked for the brand and jobs
     * without a brand get this one. Never blocks the caller; a failure is retried by the next call.
     */
    public void pinBrandInBackground(boolean atSignIn) {
        io.execute(() -> {
            try {
                if (!repo.store().hasSession()) return;
                boolean hadPick = repo.store().workspaceId() != null;
                String brand = resolveBrand(atSignIn);
                PublishManager.get(appContext).pinDefaultWorkspace(brand);
                // The plan is asked for a brand only when this pinned a new one (sign-in, or a first pick).
                if (atSignIn || !hadPick) EntitlementsRefresher.refreshNow();
            } catch (AuthException | RuntimeException ignored) {
                // Offline or refused: the next authed call or startup asks again.
            }
        });
    }

    /** Blocking: see {@link BrandPin#resolve}. Maps the account failure onto the auth failure kinds. */
    private String resolveBrand(boolean atSignIn) throws AuthException {
        try {
            return BrandPin.resolve(new AccountApi(repo), repo.store(), atSignIn);
        } catch (AccountException e) {
            switch (e.kind) {
                case NETWORK:
                    throw new AuthException(AuthException.Kind.NETWORK);
                case RATE_LIMITED:
                    throw new AuthException(AuthException.Kind.RATE_LIMITED, e.retryAfterSeconds, null);
                case SIGNED_OUT:
                    throw new AuthException(AuthException.Kind.NOT_SIGNED_IN);
                default:
                    throw new AuthException(AuthException.Kind.SERVER);
            }
        }
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
            try {
                repo.requestCode(email);
            } catch (AuthException e) {
                logSignInFailure("email", e);
                throw e;
            }
            ActivityLog.log("signin_code_requested");
            return null;
        }, cb);
    }

    /** Result is true when a new account was created. */
    public void exchangeCode(String email, String code, Callback<Boolean> cb) {
        async(() -> {
            try {
                boolean created = repo.exchangeCode(email, code, defaultDeviceName());
                ActivityLog.log("signin_success", "new_account", created);
                // The wipe on the new session left Free; fetch this account's plan.
                EntitlementsRefresher.refreshNow();
                // This account's default brand becomes the pick, in the background (sign-in is not slowed).
                pinBrandInBackground(true);
                return created;
            } catch (AuthException e) {
                logSignInFailure("code", e);
                throw e;
            }
        }, cb);
    }

    /** Result is true when the server confirmed the revoke; the local session is cleared either way. */
    public void signOut(Callback<Boolean> cb) {
        async(() -> {
            boolean confirmed = repo.signOut();
            ActivityLog.log("signout", "server_confirmed", confirmed);
            return confirmed;
        }, cb);
    }

    private static void logSignInFailure(String step, AuthException e) {
        ActivityLog.log("signin_fail", "step", step, "kind", e.kind.name().toLowerCase(Locale.ROOT));
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
