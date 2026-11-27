package com.jovoc.facecampresentationrecorder.contentplan;

import com.jovoc.facecampresentationrecorder.db.Slide;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * A fingerprint of a topic set's content (type, text, image path, disabled flag, speaker note when written, in order). Compared with the one
 * saved at creation time it tells whether the user changed the set. Ids and order indexes are not part of it.
 */
public final class PlanSetSignature {

    private PlanSetSignature() {
    }

    /** Lowercase hex SHA-256 of the ordered slides. */
    public static String of(List<Slide> orderedSlides) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (orderedSlides != null) {
            for (Slide s : orderedSlides) {
                if (!first) sb.append('\u0002');
                first = false;
                sb.append(nz(s.getType())).append('\u0001')
                        .append(nz(s.getTextContent())).append('\u0001')
                        .append(nz(s.getImagePath())).append('\u0001')
                        .append(s.isDisabled());
                // Only a written note joins the fingerprint, so sets saved before notes existed keep their signature.
                String note = s.getSpeakerNote();
                if (note != null && !note.isEmpty()) sb.append('\u0001').append(note);
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
