package com.jovoc.facecampresentationrecorder.aiedit.engine.render;

import android.opengl.GLES11Ext;
import android.opengl.GLES30;

import com.jovoc.facecampresentationrecorder.aiedit.engine.timeline.CropRect;

import java.nio.FloatBuffer;

/**
 * Draws the decoded frame (an external OES texture) cropped and scaled to fill the output. The crop is in the
 * upright source's pixels, top-anchored as the recorder crops (see {@code OutputTimeline.cropFor}); null shows
 * the whole frame. Video effects (later cards) become extra shader programs here.
 */
final class Compositor {

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "layout(location = 0) in vec4 aPosition;\n" +
            "layout(location = 1) in vec2 aTexCoord;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "out vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String OES_FRAG_SHADER =
            "#version 300 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision mediump float;\n" +
            "in vec2 vTexCoord;\n" +
            "uniform samplerExternalOES uTexture;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = texture(uTexture, vTexCoord);\n" +
            "}\n";

    private final int outW;
    private final int outH;
    private final int program;
    private final int stMatrixLoc;
    private final int textureLoc;
    private final FloatBuffer positions;
    private final FloatBuffer texCoords;

    /**
     * @param crop the part of the upright source to show, or null for all of it
     * @param srcW upright source width
     * @param srcH upright source height
     */
    Compositor(int outW, int outH, CropRect crop, int srcW, int srcH) {
        this.outW = outW;
        this.outH = outH;
        program = GlUtil.createProgram(VERTEX_SHADER, OES_FRAG_SHADER);
        stMatrixLoc = GLES30.glGetUniformLocation(program, "uSTMatrix");
        textureLoc = GLES30.glGetUniformLocation(program, "uTexture");
        positions = GlUtil.floatBuffer(GlUtil.QUAD_POSITIONS);
        texCoords = GlUtil.floatBuffer(cropTexCoords(crop, srcW, srcH));
    }

    /**
     * Texture coordinates of the crop in the frame's own space (origin bottom-left, as the SurfaceTexture
     * transform expects), in quad order: bottom-left, bottom-right, top-left, top-right.
     */
    static float[] cropTexCoords(CropRect crop, int srcW, int srcH) {
        if (crop == null || srcW <= 0 || srcH <= 0) return new float[]{0, 0, 1, 0, 0, 1, 1, 1};
        float u0 = (float) crop.x / srcW;
        float u1 = (float) (crop.x + crop.w) / srcW;
        // Rows are counted from the top of the picture; texture v from the bottom
        float vTop = 1f - (float) crop.y / srcH;
        float vBottom = 1f - (float) (crop.y + crop.h) / srcH;
        return new float[]{u0, vBottom, u1, vBottom, u0, vTop, u1, vTop};
    }

    /** Draws the latched frame of {@code oesTexture} over the whole output. */
    void drawVideo(int oesTexture, float[] stMatrix) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, outW, outH);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix4fv(stMatrixLoc, 1, false, stMatrix, 0);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES30.glUniform1i(textureLoc, 0);
        GlUtil.drawQuad(positions, texCoords);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    void release() {
        GLES30.glDeleteProgram(program);
    }
}
