package com.jovoc.facecampresentationrecorder.contentplan;

/**
 * Decides what to do with one plan video's topic set after a fetch: create it, silently replace an untouched older
 * version, adopt a set that predates the index, or leave it alone. Never deletes or overwrites anything the user may
 * have edited.
 */
public final class PlanSetPlanner {

    public enum Action { CREATE, REPLACE, KEEP, ADOPT }

    private PlanSetPlanner() {
    }

    public static Action decide(PlanSetIndex.Entry entryOrNull, boolean setHasSlides, String currentSignature,
                                int serverVersion) {
        if (entryOrNull == null) {
            return setHasSlides ? Action.ADOPT : Action.CREATE;
        }
        if (!setHasSlides) return Action.KEEP;
        if (serverVersion > entryOrNull.version && entryOrNull.signature != null
                && entryOrNull.signature.equals(currentSignature)) {
            return Action.REPLACE;
        }
        return Action.KEEP;
    }
}
