package com.jovoc.facecampresentationrecorder.bgreplace;

import android.opengl.GLES30;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OpenGL ES 3.0 Mask Refinement module.
 * Implements Guided / Joint Bilateral Filtering using full-res camera luma as guide image,
 * alongside motion-aware temporal stabilization.
 */
public class MaskRefiner {
    private static final String TAG = "MaskRefiner";

    // Vertex shader for fullscreen pass
    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "layout(location = 0) in vec4 aPosition;\n" +
            "layout(location = 1) in vec2 aTexCoord;\n" +
            "out vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    // Guided Filter / Joint Bilateral Fragment Shader
    private static final String GUIDED_FILTER_FRAG_SHADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec2 vTexCoord;\n" +
            "uniform sampler2D uCameraFullRes;\n" +
            "uniform sampler2D uCoarseMask;\n" +
            "uniform sampler2D uPrevMask;\n" +
            "uniform vec2 uTexelSize;\n" +
            "uniform float uTemporalBlend;\n" +
            "out vec4 fragColor;\n" +
            "\n" +
            "float getLuma(vec3 color) {\n" +
            "    return dot(color, vec3(0.299, 0.587, 0.114));\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec3 centerColor = texture(uCameraFullRes, vTexCoord).rgb;\n" +
            "    float centerLuma = getLuma(centerColor);\n" +
            "    float coarseMask = texture(uCoarseMask, vTexCoord).r;\n" +
            "    float prevMask = texture(uPrevMask, vTexCoord).r;\n" +
            "\n" +
            "    // Joint Bilateral / Guided Filter over 5x5 spatial window\n" +
            "    float sumMask = 0.0;\n" +
            "    float sumWeight = 0.0;\n" +
            "    float sigmaSpatial = 2.0;\n" +
            "    float sigmaColor = 0.15;\n" +
            "\n" +
            "    for (int x = -2; x <= 2; x++) {\n" +
            "        for (int y = -2; y <= 2; y++) {\n" +
            "            vec2 offset = vec2(float(x), float(y)) * uTexelSize;\n" +
            "            vec3 sampleColor = texture(uCameraFullRes, vTexCoord + offset).rgb;\n" +
            "            float sampleLuma = getLuma(sampleColor);\n" +
            "            float sampleCoarse = texture(uCoarseMask, vTexCoord + offset).r;\n" +
            "\n" +
            "            float spatialDistSq = float(x*x + y*y);\n" +
            "            float spatialWeight = exp(-spatialDistSq / (2.0 * sigmaSpatial * sigmaSpatial));\n" +
            "\n" +
            "            float colorDiff = sampleLuma - centerLuma;\n" +
            "            float colorWeight = exp(-(colorDiff * colorDiff) / (2.0 * sigmaColor * sigmaColor));\n" +
            "\n" +
            "            float weight = spatialWeight * colorWeight;\n" +
            "            sumMask += sampleCoarse * weight;\n" +
            "            sumWeight += weight;\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    float refinedMask = sumMask / max(sumWeight, 0.0001);\n" +
            "\n" +
            "    // Temporal stabilization: blend with previous refined mask\n" +
            "    float finalMask = mix(prevMask, refinedMask, uTemporalBlend);\n" +
            "\n" +
            "    // Soft edge mapping using smoothstep (0.35 to 0.75 confidence range)\n" +
            "    finalMask = smoothstep(0.35, 0.75, finalMask);\n" +
            "\n" +
            "    fragColor = vec4(finalMask, finalMask, finalMask, 1.0);\n" +
            "}\n";

    private static final float[] QUAD_POSITIONS = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
    };

    private static final float[] QUAD_TEX_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    private int program;
    private int uCameraFullResLoc;
    private int uCoarseMaskLoc;
    private int uPrevMaskLoc;
    private int uTexelSizeLoc;
    private int uTemporalBlendLoc;

    private FloatBuffer posBuffer;
    private FloatBuffer texBuffer;

    public MaskRefiner() {
        posBuffer = createFloatBuffer(QUAD_POSITIONS);
        texBuffer = createFloatBuffer(QUAD_TEX_COORDS);
    }

    public void init() {
        program = createProgram(VERTEX_SHADER, GUIDED_FILTER_FRAG_SHADER);
        uCameraFullResLoc = GLES30.glGetUniformLocation(program, "uCameraFullRes");
        uCoarseMaskLoc = GLES30.glGetUniformLocation(program, "uCoarseMask");
        uPrevMaskLoc = GLES30.glGetUniformLocation(program, "uPrevMask");
        uTexelSizeLoc = GLES30.glGetUniformLocation(program, "uTexelSize");
        uTemporalBlendLoc = GLES30.glGetUniformLocation(program, "uTemporalBlend");
    }

    public void refineMask(int cameraFullResTex, int coarseMaskTex, int prevMaskTex,
                           int width, int height, float temporalBlend) {
        GLES30.glUseProgram(program);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cameraFullResTex);
        GLES30.glUniform1i(uCameraFullResLoc, 0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coarseMaskTex);
        GLES30.glUniform1i(uCoarseMaskLoc, 1);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, prevMaskTex);
        GLES30.glUniform1i(uPrevMaskLoc, 2);

        GLES30.glUniform2f(uTexelSizeLoc, 1.0f / width, 1.0f / height);
        GLES30.glUniform1f(uTemporalBlendLoc, temporalBlend);

        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, posBuffer);

        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texBuffer);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glDisableVertexAttribArray(0);
        GLES30.glDisableVertexAttribArray(1);
        GLES30.glUseProgram(0);
    }

    public void release() {
        if (program != 0) {
            GLES30.glDeleteProgram(program);
            program = 0;
        }
    }

    private FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES30.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource);
        int prog = GLES30.glCreateProgram();
        GLES30.glAttachShader(prog, vertexShader);
        GLES30.glAttachShader(prog, fragmentShader);
        GLES30.glLinkProgram(prog);

        int[] linkStatus = new int[1];
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Could not link program: " + GLES30.glGetProgramInfoLog(prog));
            GLES30.glDeleteProgram(prog);
            prog = 0;
        }
        return prog;
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES30.glCreateShader(shaderType);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader " + shaderType + ": " + GLES30.glGetShaderInfoLog(shader));
            GLES30.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }
}
