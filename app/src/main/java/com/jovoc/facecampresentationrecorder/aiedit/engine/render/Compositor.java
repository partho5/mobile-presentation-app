package com.jovoc.facecampresentationrecorder.aiedit.engine.render;

import android.opengl.GLES11Ext;
import android.opengl.GLES30;

import com.jovoc.facecampresentationrecorder.aiedit.engine.effects.VideoTransform;
import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.CropRect;

import java.nio.FloatBuffer;

/**
 * Draws the decoded frame (an external OES texture) cropped and scaled to fill the output. The crop is in the
 * upright source's pixels, top-anchored as the recorder crops (see {@code OutputTimeline.cropFor}); null shows
 * the whole frame. The video effects ({@link VideoTransform}) are drawn here: zoom, slide and shrink in the vertex
 * shader; flash, channel split and motion blur in the fragment shader; the facecam copy of the layout effects by a
 * second small program that draws a round, ringed piece of the same frame.
 */
final class Compositor {

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "layout(location = 0) in vec4 aPosition;\n" +
            "layout(location = 1) in vec2 aTexCoord;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "uniform float uZoom;\n" +
            "uniform vec2 uZoomCenter;\n" +
            "uniform vec2 uShift;\n" +
            "uniform float uFrameScale;\n" +
            "out vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    vec2 p = uZoomCenter + (aPosition.xy - uZoomCenter) * uZoom + uShift;\n" +
            "    gl_Position = vec4(p * uFrameScale, aPosition.zw);\n" +
            "    vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String OES_FRAG_SHADER =
            "#version 300 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision mediump float;\n" +
            "in vec2 vTexCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "uniform float uFlash;\n" +
            "uniform float uGlitch;\n" +
            "uniform float uBlur;\n" +
            "uniform float uDim;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec3 c;\n" +
            "    if (uBlur > 0.0) {\n" +
            "        c = vec3(0.0);\n" +
            "        for (int i = -4; i <= 4; i++) {\n" +
            "            c += texture(uTexture, vTexCoord + vec2(float(i) * 0.25 * uBlur, 0.0)).rgb;\n" +
            "        }\n" +
            "        c /= 9.0;\n" +
            "    } else if (uGlitch != 0.0) {\n" +
            "        c = vec3(texture(uTexture, vTexCoord + vec2(uGlitch, 0.0)).r,\n" +
            "                 texture(uTexture, vTexCoord).g,\n" +
            "                 texture(uTexture, vTexCoord - vec2(uGlitch, 0.0)).b);\n" +
            "    } else {\n" +
            "        c = texture(uTexture, vTexCoord).rgb;\n" +
            "    }\n" +
            "    fragColor = vec4(mix(c, vec3(1.0), uFlash) * uDim, 1.0);\n" +
            "}\n";

    private static final String FACE_VERTEX_SHADER =
            "#version 300 es\n" +
            "layout(location = 0) in vec4 aPosition;\n" +
            "layout(location = 1) in vec2 aTexCoord;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "uniform vec2 uCenter;\n" +
            "uniform vec2 uHalf;\n" +
            "out vec2 vTexCoord;\n" +
            "out vec2 vLocal;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(uCenter + aPosition.xy * uHalf, 0.0, 1.0);\n" +
            "    vLocal = aPosition.xy;\n" +
            "    vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FACE_FRAG_SHADER =
            "#version 300 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision mediump float;\n" +
            "in vec2 vTexCoord;\n" +
            "in vec2 vLocal;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    float d = length(vLocal);\n" +
            "    float edge = max(fwidth(d), 0.001);\n" +
            "    float alpha = 1.0 - smoothstep(1.0 - edge, 1.0, d);\n" +
            "    float ring = smoothstep(0.94 - edge, 0.94, d);\n" +
            "    vec3 c = mix(texture(uTexture, vTexCoord).rgb, vec3(1.0), ring);\n" +
            "    fragColor = vec4(c, alpha);\n" +
            "}\n";

    /** How dark the full frame behind a shrunk frame is. */
    private static final float BACKDROP_DIM = 0.35f;
    /** Share of the zoom's extra width a slide may use, so the slid picture still covers the frame. */
    private static final float SHIFT_SAFE_SHARE = 0.9f;

    private final int outW;
    private final int outH;
    private final CropRect crop;
    private final int srcW;
    private final int srcH;
    /** Width of the crop in texture coordinates, to turn output-width fractions into texture offsets. */
    private final float cropSpanU;
    private final int program;
    private final int stMatrixLoc;
    private final int textureLoc;
    private final int zoomLoc;
    private final int zoomCenterLoc;
    private final int shiftLoc;
    private final int frameScaleLoc;
    private final int flashLoc;
    private final int glitchLoc;
    private final int blurLoc;
    private final int dimLoc;
    private final int faceProgram;
    private final int faceStMatrixLoc;
    private final int faceTextureLoc;
    private final int faceCenterLoc;
    private final int faceHalfLoc;
    private final FloatBuffer positions;
    private final FloatBuffer texCoords;
    private final FloatBuffer faceTexCoords;
    private final float[] faceUv = new float[8];

    /**
     * @param crop the part of the upright source to show, or null for all of it
     * @param srcW upright source width
     * @param srcH upright source height
     */
    Compositor(int outW, int outH, CropRect crop, int srcW, int srcH) {
        this.outW = outW;
        this.outH = outH;
        this.crop = crop;
        this.srcW = srcW;
        this.srcH = srcH;
        float[] uv = cropTexCoords(crop, srcW, srcH);
        cropSpanU = uv[2] - uv[0];
        program = GlUtil.createProgram(VERTEX_SHADER, OES_FRAG_SHADER);
        stMatrixLoc = GLES30.glGetUniformLocation(program, "uSTMatrix");
        textureLoc = GLES30.glGetUniformLocation(program, "uTexture");
        zoomLoc = GLES30.glGetUniformLocation(program, "uZoom");
        zoomCenterLoc = GLES30.glGetUniformLocation(program, "uZoomCenter");
        shiftLoc = GLES30.glGetUniformLocation(program, "uShift");
        frameScaleLoc = GLES30.glGetUniformLocation(program, "uFrameScale");
        flashLoc = GLES30.glGetUniformLocation(program, "uFlash");
        glitchLoc = GLES30.glGetUniformLocation(program, "uGlitch");
        blurLoc = GLES30.glGetUniformLocation(program, "uBlur");
        dimLoc = GLES30.glGetUniformLocation(program, "uDim");
        faceProgram = GlUtil.createProgram(FACE_VERTEX_SHADER, FACE_FRAG_SHADER);
        faceStMatrixLoc = GLES30.glGetUniformLocation(faceProgram, "uSTMatrix");
        faceTextureLoc = GLES30.glGetUniformLocation(faceProgram, "uTexture");
        faceCenterLoc = GLES30.glGetUniformLocation(faceProgram, "uCenter");
        faceHalfLoc = GLES30.glGetUniformLocation(faceProgram, "uHalf");
        positions = GlUtil.floatBuffer(GlUtil.QUAD_POSITIONS);
        texCoords = GlUtil.floatBuffer(uv);
        faceTexCoords = GlUtil.floatBuffer(new float[8]);
    }

    /**
     * Texture coordinates of the crop in the frame's own space (origin bottom-left, as the SurfaceTexture
     * transform expects), in quad order: bottom-left, bottom-right, top-left, top-right.
     */
    static float[] cropTexCoords(CropRect crop, int srcW, int srcH) {
        if (crop == null || srcW <= 0 || srcH <= 0) return new float[]{0, 0, 1, 0, 0, 1, 1, 1};
        return rectTexCoords(crop.x, crop.y, crop.w, crop.h, srcW, srcH);
    }

    /** The same for any rectangle of the upright source, in source pixels counted from the top left. */
    static float[] rectTexCoords(double x, double y, double w, double h, int srcW, int srcH) {
        float u0 = (float) (x / srcW);
        float u1 = (float) ((x + w) / srcW);
        // Rows are counted from the top of the picture; texture v from the bottom
        float vTop = 1f - (float) (y / srcH);
        float vBottom = 1f - (float) ((y + h) / srcH);
        return new float[]{u0, vBottom, u1, vBottom, u0, vTop, u1, vTop};
    }

    /** Draws the latched frame of {@code oesTexture} over the whole output, no zoom. */
    void drawVideo(int oesTexture, float[] stMatrix) {
        drawVideo(oesTexture, stMatrix, 1f, 0.5f, 0.5f);
    }

    /**
     * Draws the latched frame over the whole output, zoomed by {@code zoom} (1 or more, so the frame stays full)
     * about a point given as fractions of the output (x across, y down from the top).
     */
    void drawVideo(int oesTexture, float[] stMatrix, float zoom, float centerX, float centerY) {
        beginDraw();
        drawFrame(oesTexture, stMatrix, Math.max(1f, zoom), centerX, centerY, 0f, 1f, 0f, 0f, 0f, 1f);
        endDraw();
    }

    /** Draws the latched frame with every part of {@code t}: zoom, slide, flash, glitch, blur, shrunk frame, facecam. */
    void drawVideo(int oesTexture, float[] stMatrix, VideoTransform t) {
        beginDraw();
        float zoom = (float) Math.max(1.0, t.scale);
        float frameScale = (float) t.frameScale;
        if (frameScale < 1f) {
            // The full frame, dimmed, behind the shrunk one
            drawFrame(oesTexture, stMatrix, 1f, 0.5f, 0.5f, 0f, 1f, 0f, 0f, 0f, BACKDROP_DIM);
        }
        // The slide stays inside what the zoom adds on each side, in clip space (the output is 2 wide)
        float room = (zoom - 1f) * SHIFT_SAFE_SHARE;
        float shift = (float) Math.max(-room, Math.min(room, 2.0 * t.shiftX));
        drawFrame(oesTexture, stMatrix, zoom, (float) t.centerX, (float) t.centerY, shift, frameScale,
                (float) t.flash, (float) t.glitch * cropSpanU, (float) t.blur * cropSpanU, 1f);
        if (t.faceCopy) drawFaceCopy(oesTexture, stMatrix, t);
        endDraw();
    }

    private void beginDraw() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, outW, outH);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    private void endDraw() {
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    private void drawFrame(int oesTexture, float[] stMatrix, float zoom, float centerX, float centerY, float shift,
                           float frameScale, float flash, float glitchU, float blurU, float dim) {
        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(stMatrixLoc, 1, false, stMatrix, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES30.glUniform1i(textureLoc, 0);
        GLES30.glUniform1f(zoomLoc, zoom);
        GLES30.glUniform2f(zoomCenterLoc, 2f * centerX - 1f, 1f - 2f * centerY);
        GLES30.glUniform2f(shiftLoc, shift, 0f);
        GLES30.glUniform1f(frameScaleLoc, frameScale);
        GLES30.glUniform1f(flashLoc, flash);
        GLES30.glUniform1f(glitchLoc, glitchU);
        GLES30.glUniform1f(blurLoc, blurU);
        GLES30.glUniform1f(dimLoc, dim);
        GlUtil.drawQuad(positions, texCoords);
    }

    /** The round copy of the facecam: a square of the source around the circle, masked to a ringed circle. */
    private void drawFaceCopy(int oesTexture, float[] stMatrix, VideoTransform t) {
        double cropX = crop != null ? crop.x : 0;
        double cropY = crop != null ? crop.y : 0;
        double cropW = crop != null ? crop.w : srcW;
        double cropH = crop != null ? crop.h : srcH;
        double r = t.faceSrcR * cropW;
        System.arraycopy(rectTexCoords(cropX + t.faceSrcX * cropW - r, cropY + t.faceSrcY * cropH - r,
                2 * r, 2 * r, srcW, srcH), 0, faceUv, 0, 8);
        faceTexCoords.position(0);
        faceTexCoords.put(faceUv).position(0);

        GLES30.glEnable(GLES30.GL_BLEND);
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(faceProgram);
        GLES30.glUniformMatrix4fv(faceStMatrixLoc, 1, false, stMatrix, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES30.glUniform1i(faceTextureLoc, 0);
        GLES30.glUniform2f(faceCenterLoc, (float) (2 * t.faceX - 1), (float) (1 - 2 * t.faceY));
        GLES30.glUniform2f(faceHalfLoc, (float) (2 * t.faceRW), (float) (2 * t.faceRH));
        GlUtil.drawQuad(positions, faceTexCoords);
        GLES30.glDisable(GLES30.GL_BLEND);
    }

    void release() {
        GLES30.glDeleteProgram(program);
        GLES30.glDeleteProgram(faceProgram);
    }
}
