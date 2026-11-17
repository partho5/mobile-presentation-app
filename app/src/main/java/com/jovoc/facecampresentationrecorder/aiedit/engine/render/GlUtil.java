package com.jovoc.facecampresentationrecorder.aiedit.engine.render;

import android.opengl.GLES30;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Shader, buffer and texture helpers shared by the compositor and its layers. */
final class GlUtil {

    /** Full-screen quad in triangle-strip order: bottom-left, bottom-right, top-left, top-right. */
    static final float[] QUAD_POSITIONS = {-1, -1, 1, -1, -1, 1, 1, 1};

    private GlUtil() {
    }

    static FloatBuffer floatBuffer(float[] data) {
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data).position(0);
        return fb;
    }

    /** A new 2D or external texture with linear filtering and clamped edges. */
    static int newTexture(int target) {
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        GLES30.glBindTexture(target, tex[0]);
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glBindTexture(target, 0);
        return tex[0];
    }

    static void deleteTexture(int texture) {
        if (texture != 0) GLES30.glDeleteTextures(1, new int[]{texture}, 0);
    }

    static int createProgram(String vertexSource, String fragmentSource) {
        int vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource);
        int fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES30.glCreateProgram();
        GLES30.glAttachShader(program, vs);
        GLES30.glAttachShader(program, fs);
        GLES30.glLinkProgram(program);
        int[] status = new int[1];
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0);
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
        if (status[0] != GLES30.GL_TRUE) {
            String log = GLES30.glGetProgramInfoLog(program);
            GLES30.glDeleteProgram(program);
            throw new IllegalStateException("Link failed: " + log);
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES30.glGetShaderInfoLog(shader);
            GLES30.glDeleteShader(shader);
            throw new IllegalStateException("Compile failed: " + log);
        }
        return shader;
    }

    /** Draws the quad with attribute 0 = position, 1 = texture coordinate. */
    static void drawQuad(FloatBuffer positions, FloatBuffer texCoords) {
        positions.position(0);
        texCoords.position(0);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, positions);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texCoords);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        GLES30.glDisableVertexAttribArray(0);
        GLES30.glDisableVertexAttribArray(1);
    }
}
