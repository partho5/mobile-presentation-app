package com.jovoc.facecampresentationrecorder.publish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;

/** The audio {@code complete} answer is {@code { id }}: no path, no poster. */
public class CompletedAudioTest {

    @Test
    public void parsesTheIdAlone() throws Exception {
        assertEquals("up_1", CompletedAudio.parse("{\"id\":\"up_1\"}").id);
    }

    @Test
    public void ignoresExtraFields() throws Exception {
        assertEquals("up_1", CompletedAudio.parse("{\"id\":\"up_1\",\"x\":2}").id);
    }

    @Test
    public void aMissingOrEmptyIdIsAnError() {
        for (String body : new String[]{"{}", "{\"id\":\"\"}", "{\"path\":\"/a.wav\"}"}) {
            try {
                CompletedAudio.parse(body);
                fail(body);
            } catch (JSONException expected) {
                // ok
            }
        }
    }

    @Test
    public void theVideoParserStillNeedsAPath() {
        try {
            CompletedUpload.parse("{\"id\":\"m1\"}");
            fail();
        } catch (JSONException expected) {
            // video keeps its own, stricter parser
        }
    }
}
