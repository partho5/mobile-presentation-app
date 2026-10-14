package com.jovoc.facecampresentationrecorder.util;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.jovoc.facecampresentationrecorder.R;

/**
 * Highlighter-pen text for any TextView. Wrap the words to mark in double brackets, in a plain
 * string resource or literal: "It saves [[10-12 hours]] every week."
 *
 * <pre>MarkerText.set(textView, getString(R.string.some_copy));</pre>
 *
 * The colours are {@code R.color.highlighter_bg} and {@code R.color.highlighter_text}.
 */
public final class MarkerText {

    private MarkerText() {}

    /** Sets the text of a view, with every [[marked]] phrase drawn as a hand-made highlight. */
    public static void set(TextView view, String raw) {
        view.setText(render(view.getContext(), raw));
    }

    public static CharSequence render(Context context, String raw) {
        return render(raw, ContextCompat.getColor(context, R.color.highlighter_bg),
                ContextCompat.getColor(context, R.color.highlighter_text));
    }

    public static CharSequence render(String raw, int markColor, int textColor) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        int from = 0;
        while (true) {
            int open = raw.indexOf("[[", from);
            int close = open < 0 ? -1 : raw.indexOf("]]", open);
            if (close < 0) {
                out.append(raw, from, raw.length());
                return out;
            }
            out.append(raw, from, open);
            appendPhrase(out, raw.substring(open + 2, close), markColor, textColor);
            from = close + 2;
        }
    }

    /** One span per word, so the phrase still wraps; all words share one wobble seed. */
    private static void appendPhrase(SpannableStringBuilder out, String phrase, int markColor, int textColor) {
        int seed = phrase.hashCode();
        String[] words = phrase.split(" ");
        for (int i = 0; i < words.length; i++) {
            boolean last = i == words.length - 1;
            int start = out.length();
            out.append(words[i]);
            if (!last) out.append(' ');
            out.setSpan(new HighlighterSpan(markColor, textColor, seed, i == 0, last),
                    start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
