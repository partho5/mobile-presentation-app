package com.jovoc.facecampresentationrecorder.aiedit.flow;

import android.content.Context;
import android.util.Log;

import com.jovoc.facecampresentationrecorder.aiedit.engine.overlay.AssetFiles;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.PlanAsset;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The phone's copy of the plan's icons, icon clips and sounds: {@code getFilesDir()/aiedit/assets/<id>}, each id
 * downloaded once and kept for every later edit. A download that fails leaves no file, and the renderer then leaves
 * that one icon or sound out; the edit itself is never failed by an asset. The urls are the server's own cached
 * copies (no key, nothing of the recording is sent). Blocking: call from the job's worker thread.
 */
final class AssetCache {

    private static final String TAG = "AiEditAssets";
    /** No asset is larger than this; a bigger answer is not an asset. */
    private static final long MAX_ASSET_BYTES = 12L * 1024 * 1024;
    /** Old assets beyond this total are removed, oldest first, never one the current plan names. */
    private static final long MAX_CACHE_BYTES = 120L * 1024 * 1024;

    private final File dir;
    private final OkHttpClient http;
    private final AtomicBoolean cancel;

    AssetCache(Context context, AtomicBoolean cancel) {
        this(new File(context.getApplicationContext().getFilesDir(), "aiedit/assets"), newClient(), cancel);
    }

    AssetCache(File dir, OkHttpClient http, AtomicBoolean cancel) {
        this.dir = dir;
        this.http = http;
        this.cancel = cancel;
    }

    private static OkHttpClient newClient() {
        return new OkHttpClient.Builder()
                .callTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Makes sure every asset of {@code plan} that is not on the phone yet is downloaded, and returns the folder.
     * An asset that cannot be fetched is skipped. Throws {@link CancellationException} when the job is cancelled.
     */
    File ensure(Plan plan) {
        if (plan.assets.isEmpty()) return dir;
        if (!dir.isDirectory() && !dir.mkdirs()) return dir;
        Set<String> wanted = new HashSet<>();
        for (PlanAsset asset : plan.assets) {
            throwIfCancelled();
            File target = AssetFiles.fileFor(dir, asset.id);
            wanted.add(target.getName());
            if (target.isFile() && target.length() > 0) {
                // A use keeps it among the newest
                target.setLastModified(System.currentTimeMillis());
            } else {
                download(asset, target);
            }
            // The clip's frames are made once, after its download (also for one downloaded by an earlier edit)
            if ("video".equals(asset.kind) && target.isFile() && target.length() > 0) {
                keyFrames(asset, target);
            }
        }
        prune(wanted);
        return dir;
    }

    private void download(PlanAsset asset, File target) {
        HttpUrl url = HttpUrl.parse(asset.url);
        if (url == null) return;
        File part = new File(dir, target.getName() + ".part");
        boolean ok = false;
        try (Response response = http.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return;
            long declared = response.body().contentLength();
            if (declared > MAX_ASSET_BYTES) return;
            try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[16 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    throwIfCancelled();
                    total += n;
                    if (total > MAX_ASSET_BYTES) return;
                    out.write(buf, 0, n);
                }
            }
            if (part.length() > 0 && part.renameTo(target)) ok = true;
        } catch (CancellationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Never the url or the id here: the log stays free of anything about the edit
            Log.w(TAG, "An asset could not be downloaded: " + e.getClass().getSimpleName());
        } finally {
            if (!ok) part.delete();
        }
    }

    /** Keys the white box of a video asset to transparent frames, unless they are already there; a failure skips it. */
    private void keyFrames(PlanAsset asset, File target) {
        if (AssetFiles.existingFrames(dir, asset.id) != null) return;
        throwIfCancelled();
        boolean keyed = AssetKeyer.key(target, AssetFiles.framesDir(dir, asset.id), cancel);
        // A cancelled job keeps the download; only a real keying failure drops it
        throwIfCancelled();
        if (!keyed) {
            // No frames means no clip: the renderer leaves this one out
            target.delete();
        }
    }

    /**
     * Removes the oldest assets until the folder is under the limit; the plan's own files stay. A video asset is its
     * mp4 and its frames folder, counted and removed together.
     */
    private void prune(Set<String> keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += AssetFiles.sizeOf(f);
        if (total <= MAX_CACHE_BYTES) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_CACHE_BYTES) break;
            String name = f.getName();
            File frames = new File(dir, name + ".frames");
            if (AssetFiles.isFramesName(name)) {
                // Frames go with their mp4; only frames left without one are judged here
                String owner = name.substring(0, name.length() - ".frames".length());
                if (new File(dir, owner).exists()) continue;
                if (keep.contains(owner)) continue;
            } else if (keep.contains(name)) {
                continue;
            }
            long size = AssetFiles.sizeOf(f);
            if (frames.exists()) {
                size += AssetFiles.sizeOf(frames);
                AssetFiles.deleteTree(frames);
            }
            if (AssetFiles.deleteTree(f)) total -= size;
        }
    }

    private void throwIfCancelled() {
        if (cancel != null && cancel.get()) throw new CancellationException("Asset download cancelled");
    }
}
