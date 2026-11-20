package com.jovoc.facecampresentationrecorder.publish;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.account.BrandPin;
import com.jovoc.facecampresentationrecorder.auth.SessionManager;
import com.jovoc.facecampresentationrecorder.auth.TokenStore;
import com.jovoc.facecampresentationrecorder.db.PublishJob;

import java.text.DateFormat;
import java.util.Date;

/** The words and colours for publish state, shared by the card and the publish screen. */
public final class PublishLabels {

    private PublishLabels() {}

    /** One line for a recording card, or null when the recording has no publish history. */
    public static String status(Context c, PublishJob job) {
        return status(c, job, System.currentTimeMillis());
    }

    /** Same with an explicit clock; an auto job inside its undo window reads "Publishing in N s". */
    public static String status(Context c, PublishJob job, long now) {
        if (job == null) return null;
        int undoLeft = AutoPublishUndo.secondsLeft(job, now);
        if (undoLeft > 0) return c.getString(R.string.status_auto_undo, undoLeft);
        switch (job.status == null ? "" : job.status) {
            case PublishJob.WAITING_PROCESSING:
                return c.getString(R.string.status_auto_waiting);
            case PublishJob.QUEUED:
                if (PublishNetwork.waitingForWifi(c, job, now)) {
                    return c.getString(R.string.status_waiting_wifi);
                }
                return c.getString(job.errorKind != null ? R.string.status_waiting : R.string.status_queued);
            case PublishJob.UPLOADING:
                return c.getString(R.string.status_uploading, job.progress);
            case PublishJob.PUBLISHING:
                return c.getString(R.string.status_publishing);
            case PublishJob.DONE:
                if (PublishJob.REMOTE_PUBLISHED.equals(job.remoteState)) {
                    return withAi(c, c.getString(R.string.status_live), job);
                }
                if (PublishJob.REMOTE_DELETED.equals(job.remoteState)) {
                    return c.getString(R.string.status_deleted_web);
                }
                if (PublishJob.REMOTE_ERROR.equals(job.remoteState)) {
                    return remoteFailure(c, job.remoteChannel, job.remoteError);
                }
                if (PublishJob.MODE_AUTO_SLOT.equals(job.mode)) {
                    return withAi(c, c.getString(R.string.status_scheduled_slot), job);
                }
                if (PublishJob.MODE_SCHEDULE.equals(job.mode)) {
                    String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(new Date(job.scheduleAt));
                    return withAi(c, c.getString(R.string.status_scheduled, when), job);
                }
                return withAi(c, c.getString(R.string.status_published), job);
            case PublishJob.FAILED:
                if (PurgedMedia.is(job)) {
                    return c.getString(PurgedMedia.fileExists(job)
                            ? R.string.status_media_purged : R.string.status_media_purged_gone);
                }
                return c.getString(R.string.status_failed, reasonFor(c, job));
            default:
                return null;
        }
    }

    /**
     * The quiet AI captions line for an accepted post, or null when there is nothing to say:
     * {@code ready} reads "AI captions" (joined to the status with " · "), the others explain
     * why the post went out with the user's own text. Never an error.
     */
    public static String aiLine(Context c, PublishJob job) {
        if (job == null || job.aiCaptionState == null) return null;
        switch (job.aiCaptionState) {
            case PublishJob.AI_READY:
                return c.getString(R.string.status_ai_ready);
            case PublishJob.AI_SKIPPED_CREDITS:
                return c.getString(R.string.status_ai_skipped_credits);
            case PublishJob.AI_NO_SPEECH:
                return c.getString(R.string.status_ai_no_speech);
            case PublishJob.AI_UNAVAILABLE:
            case PublishJob.AI_TIMEOUT:
                return c.getString(R.string.status_ai_unavailable);
            default:
                return null; // off, no_audio, or still working
        }
    }

    /** "Live on 5 · AI captions", or the status with the reason on a second line. */
    static String withAi(Context c, String status, PublishJob job) {
        String line = aiLine(c, job);
        if (line == null) return status;
        return PublishJob.AI_READY.equals(job.aiCaptionState)
                ? status + " \u00B7 " + line : status + "\n" + line;
    }

    /** Same colours; a job blocked only by Wi-Fi-only uses the amber of "Waiting for connection". */
    public static int color(Context c, PublishJob job) {
        if (PublishNetwork.waitingForWifi(c, job, System.currentTimeMillis())) return 0xFFFFB347;
        return color(job);
    }

    public static int color(PublishJob job) {
        if (job == null) return 0xFFBBBBBB;
        if (PublishJob.DONE.equals(job.status)) {
            if (PublishJob.REMOTE_ERROR.equals(job.remoteState)) return 0xFFFF6B6B;
            if (PublishJob.REMOTE_DELETED.equals(job.remoteState)) return 0xFFBBBBBB;
            return 0xFF4ADE80;
        }
        if (PurgedMedia.is(job)) return 0xFFFFB347;
        if (PublishJob.FAILED.equals(job.status)) return 0xFFFF6B6B;
        if (PublishJob.QUEUED.equals(job.status) && job.errorKind != null) return 0xFFFFB347;
        return 0xFF22D3EE;
    }

    /** "Failed on YouTube: reason", or "Failed: reason" when the channel is not known. */
    static String remoteFailure(Context c, String channel, String error) {
        String reason = error == null || error.trim().isEmpty()
                ? c.getString(R.string.pub_err_remote_unknown) : error.trim();
        if (channel == null || channel.isEmpty()) return c.getString(R.string.status_failed, reason);
        return c.getString(R.string.status_failed_on, channel, reason);
    }

    /**
     * True for a job that failed because auto-publish is not set up: its card offers the Auto
     * upload screen. A setup link the server may have sent is ignored; that screen is the setup.
     */
    public static boolean needsSetup(PublishJob job) {
        return job != null && PublishJob.FAILED.equals(job.status)
                && "AUTO_NOT_CONFIGURED".equals(job.errorKind);
    }

    /**
     * True for a job the server refused because the plan has no auto upload: its card and its
     * notification open the Auto upload screen with the upgrade sheet.
     */
    public static boolean needsPlan(PublishJob job) {
        return job != null && PublishJob.FAILED.equals(job.status)
                && "NEEDS_PLAN".equals(job.errorKind);
    }

    /** Card-menu caption action for a job. */
    public enum CaptionAction { NONE, EDIT, SEE }

    /**
     * "Edit captions" for a scheduled post that is not live yet, "See captions" once it is posted,
     * NONE when the job has no caption (no id, or the literal "none").
     */
    public static CaptionAction captionAction(PublishJob job) {
        if (job == null || !PublishJob.DONE.equals(job.status)) return CaptionAction.NONE;
        String id = job.aiCaptionId;
        if (id == null || id.isEmpty() || PublishJob.AI_CAPTION_NONE.equals(id)) return CaptionAction.NONE;
        if (PublishJob.REMOTE_PUBLISHED.equals(job.remoteState)) return CaptionAction.SEE;
        if (job.remoteState == null && (PublishJob.MODE_SCHEDULE.equals(job.mode)
                || PublishJob.MODE_AUTO_SLOT.equals(job.mode))) return CaptionAction.EDIT;
        return CaptionAction.NONE;
    }

    /** The link of a post that is live, or null. */
    public static String liveUrl(PublishJob job) {
        if (job == null || !PublishJob.REMOTE_PUBLISHED.equals(job.remoteState)) return null;
        String u = job.remoteUrl;
        return u != null && (u.startsWith("https://") || u.startsWith("http://")) ? u : null;
    }

    /**
     * The reason a job failed. A job whose brand has lost access names that brand when it is the one
     * in use ("No access to Acme anymore"); a job for another brand gets the unnamed wording.
     */
    public static String reasonFor(Context c, PublishJob job) {
        if ("WORKSPACE_FORBIDDEN".equals(job.errorKind)) {
            TokenStore store = SessionManager.get(c).repository().store();
            String name = BrandPin.lostBrandName(job.workspaceId, store.workspaceId(), store.workspaceName());
            if (name != null) return c.getString(R.string.pub_err_workspace_named, name);
        }
        return reason(c, job.errorKind, job.errorDetail);
    }

    /** A short human reason for a failure kind ({@link PublishException.Kind} name). */
    public static String reason(Context c, String kind, String detail) {
        boolean hasDetail = detail != null && !detail.isEmpty();
        if (kind == null) return c.getString(R.string.pub_err_server);
        switch (kind) {
            case "NETWORK":
                return c.getString(R.string.pub_err_network);
            case "SIGNED_OUT":
                return c.getString(R.string.pub_err_signed_out);
            case "INSUFFICIENT_CREDITS":
                return hasDetail ? detail : c.getString(R.string.pub_err_credits);
            case "REJECTED":
                return hasDetail ? detail : c.getString(R.string.pub_err_rejected);
            case "WORKSPACE_FORBIDDEN":
                return c.getString(R.string.pub_err_workspace);
            case "NEEDS_PLAN":
                return c.getString(R.string.pub_err_needs_plan);
            case "TOO_LARGE":
                return c.getString(R.string.pub_err_too_large);
            case "RATE_LIMITED":
                return c.getString(R.string.pub_err_rate_limited);
            case "IN_PROGRESS":
                return c.getString(R.string.pub_err_busy);
            case "FILE":
                return c.getString(R.string.pub_err_file);
            case "AUTO_NOT_CONFIGURED":
                return c.getString(R.string.pub_err_auto_not_set_up);
            case "CREDITS_SHORT": {
                String[] n = hasDetail ? detail.split("\\|") : new String[0];
                if (n.length == 2) return c.getString(R.string.pub_err_credits_short, n[0], n[1]);
                return c.getString(R.string.pub_err_credits);
            }
            case "MEDIA_NOT_FOUND":
                return c.getString(R.string.pub_err_media_gone);
            case "MEDIA_PURGED":
                return c.getString(R.string.pub_err_media_purged);
            case "INTERRUPTED":
                return c.getString(R.string.pub_err_interrupted);
            case "UNSUPPORTED_TYPE":
                return c.getString(R.string.pub_err_unsupported_type);
            case "FILE_TOO_LARGE":
                return c.getString(R.string.pub_err_file_too_large);
            case "SIZE_MISMATCH":
                return c.getString(R.string.pub_err_size_mismatch);
            case "CONTENT_TYPE_MISMATCH":
                return c.getString(R.string.pub_err_content_type_mismatch);
            case "TOO_MANY_PENDING_UPLOADS":
                return c.getString(R.string.pub_err_too_many_uploads);
            case "STORAGE_ERROR":
                return c.getString(R.string.pub_err_storage);
            case "UPLOAD_NOT_FOUND":
            case "UPLOAD_NOT_PENDING":
            case "INVALID_PARTS":
            case "INVALID_POSTER":
                // The uploader handles these itself; if one still ends a job, a Retry starts over.
                return c.getString(R.string.pub_err_upload_restart);
            default:
                return c.getString(R.string.pub_err_server);
        }
    }

    public static String reason(Context c, PublishException e) {
        return reason(c, e.kind.name(), e.detail);
    }
}
