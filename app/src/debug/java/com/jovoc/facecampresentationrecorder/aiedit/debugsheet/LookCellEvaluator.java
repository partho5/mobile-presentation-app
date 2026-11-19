package com.jovoc.facecampresentationrecorder.aiedit.debugsheet;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import com.jovoc.facecampresentationrecorder.aiedit.engine.RenderNotes;
import com.jovoc.facecampresentationrecorder.aiedit.engine.captions.CaptionPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.EngineContract;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.OutputSpec;
import com.jovoc.facecampresentationrecorder.aiedit.engine.contract.TextLook;
import com.jovoc.facecampresentationrecorder.aiedit.engine.plan.Plan;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.PaletteResolver;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.TextLookRenderer;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.TextOverlayPainter;
import com.jovoc.facecampresentationrecorder.aiedit.engine.text.UnsupportedLookException;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws one cell of the look sheet the way the app does and reads the pixels with the probes of {@link LookProbes}. Two
 * draws per cell: the sprite from {@link TextLookRenderer} (the look's own picture, read for fill, border, shadow, stroke,
 * padding and its own edge), and the whole frame from {@link TextOverlayPainter} (the placed, tilted overlay, read at the
 * frame and safe rectangle edges). Debug only; one instance per thread (it reuses one frame bitmap per ratio).
 */
final class LookCellEvaluator {

    /** A cell's verdict and, when asked for, the sprite to show (the caller recycles it). */
    static final class Evaluated {
        final LookCellResult result;
        /** Null unless asked for, or when nothing was drawn. */
        final Bitmap sprite;
        final LookProbes.Sprite geometry;
        final int frameW;

        Evaluated(LookCellResult result, Bitmap sprite, LookProbes.Sprite geometry, int frameW) {
            this.result = result;
            this.sprite = sprite;
            this.geometry = geometry;
            this.frameW = frameW;
        }
    }

    private final EngineContract contract;
    private Bitmap frame;
    private String frameRatio;

    LookCellEvaluator(EngineContract contract) {
        this.contract = contract;
    }

    void release() {
        if (frame != null) frame.recycle();
        frame = null;
        frameRatio = null;
    }

    Evaluated evaluate(LookSheetGrid.Cell cell, TextLook look, boolean keepSprite) {
        OutputSpec spec = contract.output(cell.ratio);
        Plan plan = LookSheetGrid.plan(cell, look);
        PaletteResolver colors = new PaletteResolver(plan.palette, contract.getPalette());
        int slot = LookSheetGrid.colorSlot(cell.lookIdx);
        String language = LookSheetGrid.language(cell.string);
        float sizePx = spec.width * CaptionPainter.DEFAULT_TEXT_SIZE_SHARE * TextOverlayPainter.SIZE_FACTOR;
        int maxWidth = Math.round(TextOverlayPainter.MAX_WIDTH_SHARE * spec.width);

        TextLookRenderer.Sprite sprite;
        try {
            sprite = new TextLookRenderer(colors).render(cell.string.text, look, slot, sizePx, maxWidth, language);
        } catch (UnsupportedLookException e) {
            return new Evaluated(new LookCellResult(cell.lookId, cell.string.id, cell.ratio, LookCellResult.Status.UNSUPPORTED,
                    cell.string.overCap, new ArrayList<LookProbes.Result>(), new ArrayList<String>(), e.field), null, null,
                    spec.width);
        }
        if (sprite == null) {
            return new Evaluated(skipped(cell), null, null, spec.width);
        }

        // The sprite: the look's own picture. A shrunk text was laid out at a smaller size than asked; the sprite says which
        LookProbes.Sprite geometry = new LookProbes.Sprite(sprite.width, sprite.height, sprite.contentW, sprite.contentH,
                sprite.sizePx);
        final Bitmap bmp = sprite.bitmap;
        List<LookProbes.Result> results = LookProbes.evaluateAll(LookProbes.forSprite(look, colors, slot, geometry),
                new BitmapSource(bmp));

        // The whole frame: the placed overlay, at a moment past every entry
        RenderNotes notes = new RenderNotes();
        Bitmap f = frameFor(spec);
        f.eraseColor(0);
        TextOverlayPainter painter = new TextOverlayPainter(plan, contract, spec, spec.width, spec.height,
                spec.width * CaptionPainter.DEFAULT_TEXT_SIZE_SHARE, null, notes, language);
        boolean drew = painter.draw(new Canvas(f), LookSheetGrid.FRAME_MS, spec.width, spec.height);
        painter.draw(new Canvas(), MotionSheetGrid.ITEM_MS, 1, 1); // frees the painter's own sprite
        List<String> codes = new ArrayList<>();
        for (RenderNotes.Note n : notes.notes()) codes.add(n.code);
        if (!drew) {
            if (!keepSprite) sprite.recycle();
            return new Evaluated(skipped(cell, codes), keepSprite ? bmp : null, geometry, spec.width);
        }
        results.addAll(LookProbes.evaluateAll(LookProbes.forFrame(spec.width, spec.height, spec.safeX0, spec.safeX1,
                spec.safeY0, spec.safeY1), new BitmapSource(f)));
        LookCellResult r = new LookCellResult(cell.lookId, cell.string.id, cell.ratio, LookCellResult.Status.DRAWN,
                cell.string.overCap, results, codes, null);
        if (!keepSprite) sprite.recycle();
        return new Evaluated(r, keepSprite ? bmp : null, geometry, spec.width);
    }

    private static LookCellResult skipped(LookSheetGrid.Cell cell) {
        return skipped(cell, new ArrayList<String>());
    }

    private static LookCellResult skipped(LookSheetGrid.Cell cell, List<String> codes) {
        return new LookCellResult(cell.lookId, cell.string.id, cell.ratio, LookCellResult.Status.SKIPPED, cell.string.overCap,
                new ArrayList<LookProbes.Result>(), codes, null);
    }

    private Bitmap frameFor(OutputSpec spec) {
        if (frame == null || !spec.ratio.equals(frameRatio)) {
            if (frame != null) frame.recycle();
            frame = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888);
            frameRatio = spec.ratio;
        }
        return frame;
    }

    /** Pixels of a bitmap for the probes. */
    private static final class BitmapSource implements LookProbes.PixelSource {
        private final Bitmap bitmap;

        BitmapSource(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        @Override
        public int width() {
            return bitmap.getWidth();
        }

        @Override
        public int height() {
            return bitmap.getHeight();
        }

        @Override
        public int argb(int x, int y) {
            return bitmap.getPixel(x, y);
        }
    }
}
