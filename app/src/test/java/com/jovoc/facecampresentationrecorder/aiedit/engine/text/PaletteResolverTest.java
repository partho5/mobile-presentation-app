package com.jovoc.facecampresentationrecorder.aiedit.engine.text;

import static org.junit.Assert.assertEquals;

import com.jovoc.facecampresentationrecorder.aiedit.TestContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Palette;

import org.junit.Test;

import java.util.Arrays;

/** Colour roles from the plan palette and the contract's ink and paper. */
public class PaletteResolverTest {

    private static PaletteResolver resolver(String... colors) throws Exception {
        return new PaletteResolver(new Palette("multi", Arrays.asList(colors)), TestContract.load().getPalette());
    }

    @Test
    public void accentFollowsTheColorSlotAndRotates() throws Exception {
        PaletteResolver r = resolver("#FF3B30", "#FFD60A");
        assertEquals(0xFFFF3B30, r.resolve("accent", 0));
        assertEquals(0xFFFFD60A, r.resolve("accent", 1));
        assertEquals(0xFFFF3B30, r.resolve("accent", 2));
    }

    @Test
    public void inkAndPaperComeFromTheContract() throws Exception {
        PaletteResolver r = resolver("#FF3B30");
        assertEquals(0xFF111111, r.resolve("ink", 0));
        assertEquals(0xFFFFFFFF, r.resolve("paper", 0));
    }

    @Test
    public void accentDarkIsFiftyFivePercentBrightness() throws Exception {
        PaletteResolver r = resolver("#FF8040");
        int dark = r.resolve("accent_dark", 0);
        assertEquals(Math.round(255 * 0.55), (dark >> 16) & 0xFF);
        assertEquals(Math.round(128 * 0.55), (dark >> 8) & 0xFF);
        assertEquals(Math.round(64 * 0.55), dark & 0xFF);
        assertEquals(0xFF, dark >>> 24);
    }

    @Test
    public void onAccentPicksTheReadableNeutral() throws Exception {
        assertEquals(0xFF111111, resolver("#FFD60A").resolve("on_accent", 0));
        assertEquals(0xFFFFFFFF, resolver("#1A1AFF").resolve("on_accent", 0));
    }

    @Test
    public void parsesShortAndLongHexAndScalesAlpha() {
        assertEquals(0xFF112233, PaletteResolver.parse("#112233"));
        assertEquals(0x80112233, PaletteResolver.parse("#80112233"));
        assertEquals(0x80112233, PaletteResolver.withAlpha(0xFF112233, 0.5) & 0xFFFFFFFF);
    }
}
