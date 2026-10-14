package com.jovoc.facecampresentationrecorder.publish;

import com.jovoc.facecampresentationrecorder.db.PublishJob;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Learns how accepted posts ended up (live, failed, deleted). Pure logic plus one network loop,
 * no Android: which jobs to ask about, one call per 50 posts and workspace, and how the answers
 * of a job's posts combine into one result for its card.
 */
public final class PostStatusSync {

    private PostStatusSync() {}

    /** Accepted jobs are checked for this long after they were accepted (or are due to go out). */
    public static final long WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    /** How a job's posts ended, ready to be stored on the job. */
    public static final class Result {
        /** {@link PublishJob#REMOTE_PUBLISHED}, {@link PublishJob#REMOTE_ERROR} or {@link PublishJob#REMOTE_DELETED}. */
        public final String state;
        /** Name(s) of the failed channel(s); null when unknown or not a failure. */
        public final String channel;
        public final String error;
        public final String url;

        Result(String state, String channel, String error, String url) {
            this.state = state;
            this.channel = channel;
            this.error = error;
            this.url = url;
        }
    }

    /** Receives each job that reached a final answer. */
    public interface Sink {
        void onResult(PublishJob job, Result result);
    }

    /** The post ids a job was given, in order; empty when it was never accepted. */
    static List<String> postIds(PublishJob job) {
        return splitCsv(job.postIds);
    }

    static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String part : csv.split(",", -1)) out.add(part.trim());
        return out;
    }

    /**
     * Jobs worth asking about: accepted (DONE), with posts, no final state yet, and still inside
     * the window. The window starts when the backend accepted the job; a scheduled job's clock
     * starts at its schedule time if that is later, so a post due next week is still checked then.
     */
    public static List<PublishJob> due(List<PublishJob> jobs, long nowMs) {
        List<PublishJob> out = new ArrayList<>();
        for (PublishJob job : jobs) {
            if (job == null || !PublishJob.DONE.equals(job.status)) continue;
            if (job.remoteState != null && !job.remoteState.isEmpty()) continue;
            if (nonEmptyIds(job).isEmpty()) continue;
            long since = job.updatedAt;
            if (PublishJob.MODE_SCHEDULE.equals(job.mode)) since = Math.max(since, job.scheduleAt);
            if (nowMs - since > WINDOW_MS) continue;
            out.add(job);
        }
        return out;
    }

    private static List<String> nonEmptyIds(PublishJob job) {
        List<String> ids = new ArrayList<>();
        for (String id : postIds(job)) {
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    /** Splits a list into consecutive pieces of at most {@code size}. */
    public static <T> List<List<T>> chunk(List<T> items, int size) {
        if (size < 1) throw new IllegalArgumentException("size");
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(new ArrayList<>(items.subList(i, Math.min(items.size(), i + size))));
        }
        return out;
    }

    /** The {@code ids=} value: comma separated, each id URL-encoded. */
    static String idsQuery(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            try {
                sb.append(URLEncoder.encode(id, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return sb.toString();
    }

    /**
     * Combines the answers for one job's posts. Null while any of its posts is missing from the
     * answers or not final yet (queued, draft): the card keeps its "Published" line until then.
     * One failed post makes the job a failure; otherwise a live post makes it live; deleted only
     * when every post was deleted.
     */
    public static Result resolve(PublishJob job, Map<String, PostStatus> byId) {
        List<String> ids = nonEmptyIds(job);
        if (ids.isEmpty()) return null;
        List<String> integrations = splitCsv(job.postIntegrations);
        List<Channel> channels = Channel.fromJson(job.channelsJson);

        Set<String> failedNames = new LinkedHashSet<>();
        String firstError = null;
        String liveUrl = null;
        boolean anyError = false;
        boolean anyLive = false;
        for (String id : ids) {
            PostStatus s = byId.get(id);
            if (s == null || !s.state.isFinal()) return null;
            if (s.state == PostStatus.State.ERROR) {
                anyError = true;
                if (firstError == null) firstError = s.error;
                String name = channelName(channels, integrations, postIds(job).indexOf(id));
                if (name != null) failedNames.add(name);
            } else if (s.state == PostStatus.State.PUBLISHED) {
                anyLive = true;
                if (liveUrl == null) liveUrl = s.releaseUrl;
            }
        }
        if (anyError) {
            return new Result(PublishJob.REMOTE_ERROR,
                    failedNames.isEmpty() ? null : String.join(", ", failedNames), firstError, null);
        }
        if (anyLive) return new Result(PublishJob.REMOTE_PUBLISHED, null, null, liveUrl);
        return new Result(PublishJob.REMOTE_DELETED, null, null, null);
    }

    private static String channelName(List<Channel> channels, List<String> integrations, int index) {
        if (index < 0 || index >= integrations.size()) return null;
        String integrationId = integrations.get(index);
        if (integrationId.isEmpty()) return null;
        for (Channel c : channels) {
            if (integrationId.equals(c.id)) return c.name;
        }
        return null;
    }

    /**
     * Asks the server about every due job: one call per 50 posts and workspace (a job keeps the
     * workspace it was made in). A call that fails leaves its jobs as they are, to be asked again
     * the next time. Blocking.
     * @return how many jobs reached a final answer
     */
    public static int refresh(PublishApi api, List<PublishJob> jobs, long nowMs, Sink sink) {
        // Per workspace (null = the device's default), the due jobs in order.
        Map<String, List<PublishJob>> byWorkspace = new LinkedHashMap<>();
        for (PublishJob job : due(jobs, nowMs)) {
            String key = job.workspaceId == null ? "" : job.workspaceId;
            List<PublishJob> list = byWorkspace.get(key);
            if (list == null) {
                list = new ArrayList<>();
                byWorkspace.put(key, list);
            }
            list.add(job);
        }

        int resolved = 0;
        for (Map.Entry<String, List<PublishJob>> group : byWorkspace.entrySet()) {
            Set<String> ids = new LinkedHashSet<>();
            for (PublishJob job : group.getValue()) ids.addAll(nonEmptyIds(job));

            Map<String, PostStatus> answers = new LinkedHashMap<>();
            for (List<String> piece : chunk(new ArrayList<>(ids), PublishApi.MAX_STATUS_IDS)) {
                try {
                    for (PostStatus s : api.postStatus(piece, group.getKey())) answers.put(s.id, s);
                } catch (PublishException e) {
                    if (e.kind == PublishException.Kind.SIGNED_OUT) return resolved;
                    // Offline, server trouble, no access to this workspace: ask again next time.
                }
            }
            for (PublishJob job : group.getValue()) {
                Result r = resolve(job, answers);
                if (r == null) continue;
                sink.onResult(job, r);
                resolved++;
            }
        }
        return resolved;
    }
}
