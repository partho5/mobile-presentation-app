package com.jovoc.facecampresentationrecorder.publish;

import android.content.Context;

import com.jovoc.facecampresentationrecorder.R;
import com.jovoc.facecampresentationrecorder.db.PublishJob;

import java.text.DateFormat;
import java.util.Date;

/** The words and colours for publish state, shared by the card and the publish screen. */
public final class PublishLabels {

    private PublishLabels() {}

    /** One line for a recording card, or null when the recording has no publish history. */
    public static String status(Context c, PublishJob job) {
        if (job == null) return null;
        switch (job.status == null ? "" : job.status) {
            case PublishJob.WAITING_PROCESSING:
                return c.getString(R.string.status_auto_waiting);
            case PublishJob.QUEUED:
                return c.getString(job.errorKind != null ? R.string.status_waiting : R.string.status_queued);
            case PublishJob.UPLOADING:
                return c.getString(R.string.status_uploading, job.progress);
            case PublishJob.PUBLISHING:
                return c.getString(R.string.status_publishing);
            case PublishJob.DONE:
                if (PublishJob.REMOTE_PUBLISHED.equals(job.remoteState)) {
                    return c.getString(R.string.status_live);
                }
                if (PublishJob.REMOTE_DELETED.equals(job.remoteState)) {
                    return c.getString(R.string.status_deleted_web);
                }
                if (PublishJob.REMOTE_ERROR.equals(job.remoteState)) {
                    return remoteFailure(c, job.remoteChannel, job.remoteError);
                }
                if (PublishJob.MODE_AUTO_SLOT.equals(job.mode)) {
                    return c.getString(R.string.status_scheduled_slot);
                }
                if (PublishJob.MODE_SCHEDULE.equals(job.mode)) {
                    String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(new Date(job.scheduleAt));
                    return c.getString(R.string.status_scheduled, when);
                }
                return c.getString(R.string.status_published);
            case PublishJob.FAILED:
                return c.getString(R.string.status_failed, reason(c, job.errorKind, job.errorDetail));
            default:
                return null;
        }
    }

    public static int color(PublishJob job) {
        if (job == null) return 0xFFBBBBBB;
        if (PublishJob.DONE.equals(job.status)) {
            if (PublishJob.REMOTE_ERROR.equals(job.remoteState)) return 0xFFFF6B6B;
            if (PublishJob.REMOTE_DELETED.equals(job.remoteState)) return 0xFFBBBBBB;
            return 0xFF4ADE80;
        }
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

    /** The link of a post that is live, or null. */
    public static String liveUrl(PublishJob job) {
        if (job == null || !PublishJob.REMOTE_PUBLISHED.equals(job.remoteState)) return null;
        String u = job.remoteUrl;
        return u != null && (u.startsWith("https://") || u.startsWith("http://")) ? u : null;
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
            case "INTERRUPTED":
                return c.getString(R.string.pub_err_interrupted);
            default:
                return c.getString(R.string.pub_err_server);
        }
    }

    public static String reason(Context c, PublishException e) {
        return reason(c, e.kind.name(), e.detail);
    }
}
