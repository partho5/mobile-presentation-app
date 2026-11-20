package com.jovoc.facecampresentationrecorder.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.account.AccountApi;
import com.jovoc.facecampresentationrecorder.account.AccountException;
import com.jovoc.facecampresentationrecorder.account.BrandRow;
import com.jovoc.facecampresentationrecorder.account.Workspace;
import com.jovoc.facecampresentationrecorder.activitylog.ActivityLog;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;
import com.jovoc.facecampresentationrecorder.entitlements.FeatureSnapshot;

import java.util.concurrent.ExecutorService;

/**
 * Binds the shared brand row ({@code res/layout/view_brand_row.xml}) on one screen: avatar, name
 * and "Change". Tapping opens {@link WorkspaceActivity}; while a recording runs the row shows the
 * name only, and a tap shows a note instead. Call {@link #refresh} from the screen's onResume.
 */
public final class BrandRowBinder {

    private final AppCompatActivity activity;
    private final TokenStore store;
    private final AccountApi api;
    private final ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView tvAvatar;
    private final TextView tvCaption;
    private final TextView tvName;
    private final TextView tvChange;
    /** Bumped on each refresh, so a slow lookup never overwrites a newer result. */
    private int generation;

    /**
     * @param row     the inflated brand row
     * @param io      the screen's background executor, used for the server-default lookup
     * @param caption the small line above the name ("Brand", "Posting to"), or null to hide it
     */
    public BrandRowBinder(AppCompatActivity activity, View row, ExecutorService io, @Nullable String caption) {
        this.activity = activity;
        this.io = io;
        SessionManager session = SessionManager.get(activity);
        this.store = session.repository().store();
        this.api = new AccountApi(session.repository());
        tvAvatar = row.findViewById(R.id.tv_brand_avatar);
        tvCaption = row.findViewById(R.id.tv_brand_caption);
        tvName = row.findViewById(R.id.tv_brand_name);
        tvChange = row.findViewById(R.id.tv_brand_change);
        if (caption == null) {
            tvCaption.setVisibility(View.GONE);
        } else {
            tvCaption.setText(caption);
        }
        row.setOnClickListener(v -> onTap());
    }

    /**
     * Shows the stored pick and the recording state now. A brand that refused this phone reads "Choose a
     * brand". A pick is always stored once WS3 has pinned one, so the server default is only looked up
     * while none is stored yet.
     */
    public void refresh() {
        final int mine = ++generation;
        String pickedId = store.workspaceId();
        boolean recording = FeatureSnapshot.recordingNow();
        if (store.workspaceAccessLost()) {
            show(BrandRow.lost(activity.getString(R.string.brand_choose), recording));
            return;
        }
        show(BrandRow.of(pickedId, store.workspaceName(), null, placeholder(), recording));
        if (pickedId != null) return;
        io.execute(() -> {
            final String defaultName = resolveDefaultName();
            main.post(() -> {
                if (mine != generation || activity.isFinishing() || activity.isDestroyed()) return;
                if (defaultName != null) {
                    show(BrandRow.of(null, null, defaultName, placeholder(), FeatureSnapshot.recordingNow()));
                }
            });
        });
    }

    private void onTap() {
        boolean recording = FeatureSnapshot.recordingNow();
        ActivityLog.log("brand_row", "locked", recording);
        if (recording) {
            Toast.makeText(activity.getApplicationContext(), R.string.brand_locked_note, Toast.LENGTH_SHORT).show();
            return;
        }
        // The picker returns to this screen, rebuilt from the intent it was started with, after a change.
        activity.startActivity(WorkspaceActivity.open(activity, activity.getIntent()));
    }

    private void show(BrandRow row) {
        tvName.setText(row.name);
        tvAvatar.setText(row.initials);
        tvChange.setVisibility(row.changeable ? View.VISIBLE : View.GONE);
    }

    private String placeholder() {
        return activity.getString(R.string.acct_workspace_default);
    }

    /** Name of the brand the server uses when none is picked; null when unknown. Runs on the io thread. */
    @Nullable
    private String resolveDefaultName() {
        try {
            String id = api.currentWorkspaceId();
            for (Workspace w : api.listWorkspaces()) {
                if (w.id.equals(id)) return w.name;
            }
        } catch (AccountException ignored) {
            // the screen's own calls report connection problems
        }
        return null;
    }
}
