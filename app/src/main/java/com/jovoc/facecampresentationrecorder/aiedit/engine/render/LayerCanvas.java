package com.jovoc.facecampresentationrecorder.aiedit.engine.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.opengl.GLES30;
import android.opengl.GLUtils;

import java.nio.FloatBuffer;

/**
 * One output-sized bitmap and its texture for everything drawn with Canvas (captions, text, icons), blended
 * over the video. Allocated once per render; each frame clears and reuses it, so nothing is allocated per frame.
 */
public final class LayerCanvas {

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "layout(location = 0) in vec4 aPosition;\n" +
            "layout(location = 1) in vec2 aTexCoord;\n" +
            "out vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String FRAG_SHADER =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "in vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    fragColor = texture(uTexture, vTexCoord);\n" +
            "}\n";

    /** Bitmap row 0 is the top of the picture: flip v against the quad. */
    private static final float[] TEX_COORDS = {0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f};

    private final int width;
    private final int height;
    private final Bitmap bitmap;
    private final Canvas canvas;
    private final int texture;
    private final int program;
    private final int textureLoc;
    private final FloatBuffer positions;
    private final FloatBuffer texCoords;

    /** Needs the GL context current on this thread. */
    LayerCanvas(int width, int height) {
        this.width = width;
        this.height = height;
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        canvas = new Canvas(bitmap);
        texture = GlUtil.newTexture(GLES30.GL_TEXTURE_2D);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        program = GlUtil.createProgram(VERTEX_SHADER, FRAG_SHADER);
        textureLoc = GLES30.glGetUniformLocation(program, "uTexture");
        positions = GlUtil.floatBuffer(GlUtil.QUAD_POSITIONS);
        texCoords = GlUtil.floatBuffer(TEX_COORDS);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Clears the layer and hands out its canvas for this frame. */
    public Canvas begin() {
        bitmap.eraseColor(Color.TRANSPARENT);
        return canvas;
    }

    /** Uploads what was drawn since {@link #begin} and blends it over the current frame. */
    void drawOver() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture);
        GLUtils.texSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap);
        GLES30.glEnable(GLES30.GL_BLEND);
        // Android bitmaps are premultiplied
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        GLES30.glUseProgram(program);
        GLES30.glUniform1i(textureLoc, 0);
        GlUtil.drawQuad(positions, texCoords);
        GLES30.glDisable(GLES30.GL_BLEND);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
    }

    void release() {
        GLES30.glDeleteProgram(program);
        GlUtil.deleteTexture(texture);
        bitmap.recycle();
    }
}
