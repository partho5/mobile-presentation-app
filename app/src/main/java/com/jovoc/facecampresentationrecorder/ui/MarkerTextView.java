package com.jovoc.facecampresentationrecorder.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatTextView;

import com.jovoc.facecampresentationrecorder.util.MarkerText;

/**
 * A TextView that draws [[marked]] phrases with a hand-made highlighter pen. Drop it in any
 * layout, or set the text from code; text with no markers behaves like a normal TextView.
 *
 * <pre>
 * &lt;com.jovoc.facecampresentationrecorder.ui.MarkerTextView
 *     android:text="It saves [[10-12 hours]] every week." /&gt;
 * </pre>
 *
 * Colours: {@code R.color.highlighter_bg} and {@code R.color.highlighter_text}.
 */
public class MarkerTextView extends AppCompatTextView {

    public MarkerTextView(Context context) {
        super(context);
    }

    public MarkerTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MarkerTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public void setText(CharSequence text, TextView.BufferType type) {
        if (text != null && text.toString().contains("[[")) {
            text = MarkerText.render(getContext(), text.toString());
        }
        super.setText(text, type);
    }
}
