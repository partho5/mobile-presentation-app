package com.jovoc.facecampresentationrecorder.publish;

import java.util.HashMap;
import java.util.Map;

/**
 * Remembers recordings that were renamed while their auto-publish was waiting for processing.
 * The post-processing service still knows the recording by the path it was queued with, so the
 * waiting job (whose path follows the rename) is found again through this map: current path ->
 * the path processing started with. Memory only, which is enough: after a process death nothing
 * is processing any more and the startup sweep releases the job.
 *
 * Not thread safe; {@link PublishManager} uses it on its single database thread.
 */
final class RenameAliases {

    private final Map<String, String> rootByCurrent = new HashMap<>();

    /** The recording moved from {@code oldPath} to {@code newPath}; the root path is kept across renames. */
    void renamed(String oldPath, String newPath) {
        String root = rootByCurrent.remove(oldPath);
        rootByCurrent.put(newPath, root != null ? root : oldPath);
    }

    /** The path processing started with, or null when this path was never renamed. */
    String rootOf(String currentPath) {
        return rootByCurrent.get(currentPath);
    }

    /** The recording's current path for a processing path, or null when it was not renamed. */
    String currentFor(String rootPath) {
        for (Map.Entry<String, String> e : rootByCurrent.entrySet()) {
            if (e.getValue().equals(rootPath)) return e.getKey();
        }
        return null;
    }

    void forget(String currentPath) {
        rootByCurrent.remove(currentPath);
    }
}
