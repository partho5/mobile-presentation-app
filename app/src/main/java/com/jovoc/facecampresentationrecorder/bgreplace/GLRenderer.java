package com.jovoc.facecampresentationrecorder.bgreplace;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OpenGL ES 3.0 Renderer for Real-time Background Replacement.
 * Correctly transforms OES camera texture with uSTMatrix, blits FBOs,
 * downscales to 256x256 FBO_LowRes, and composites with virtual background.
 */
public class GLRenderer {
    private static final String TAG = "GLRenderer";

    private static final String OES_VERTEX_SHADER =
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

    private static final String COMPOSITE_VERTEX_SHADER =
            "#version 300 es\n" +
            "layout(location = 0) in vec4 aPosition;\n" +
            "layout(location = 1) in vec2 aTexCoord;\n" +
            "out vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String COMPOSITE_FRAG_SHADER =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "in vec2 vTexCoord;\n" +
            "uniform sampler2D uCameraFrame;\n" +
            "uniform sampler2D uUserBg;\n" +
            "uniform sampler2D uRefinedMask;\n" +
            "uniform int uEnableBgReplace;\n" +
            "out vec4 fragColor;\n" +
            "void main() {\n" +
            "    vec4 cameraColor = texture(uCameraFrame, vTexCoord);\n" +
            "    if (uEnableBgReplace == 0) {\n" +
            "        fragColor = cameraColor;\n" +
            "        return;\n" +
            "    }\n" +
            "    vec2 bgUV = vec2(vTexCoord.x, 1.0 - vTexCoord.y);\n" +
            "    vec4 bgColor = texture(uUserBg, bgUV);\n" +
            "    float alpha = texture(uRefinedMask, vTexCoord).r;\n" +
            "    fragColor = mix(bgColor, cameraColor, alpha);\n" +
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

    private int oesTextureId = 0;
    private SurfaceTexture cameraSurfaceTexture;
    private final float[] stMatrix = new float[16];

    private int oesProgram;
    private int oesSTMatrixLoc;

    private int compositeProgram;
    private int uCameraFrameLoc;
    private int uUserBgLoc;
    private int uRefinedMaskLoc;
    private int uEnableBgReplaceLoc;

    private int userBgTextureId = 0;

    private int fboFullRes = 0;
    private int texFullRes = 0;

    private int fboLowRes = 0;
    private int texLowRes = 0;

    private int fboRefinedMask = 0;
    private int texRefinedMask = 0;

    private MaskRefiner maskRefiner;
    private FloatBuffer posBuffer;
    private FloatBuffer texBuffer;

    private boolean enableBgReplace = true;

    public GLRenderer() {
        posBuffer = createFloatBuffer(QUAD_POSITIONS);
        texBuffer = createFloatBuffer(QUAD_TEX_COORDS);
        maskRefiner = new MaskRefiner();
    }

    public void initGL() {
        int[] textures = new int[1];
        GLES30.glGenTextures(1, textures, 0);
        oesTextureId = textures[0];
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);

        cameraSurfaceTexture = new SurfaceTexture(oesTextureId);

        oesProgram = createProgram(OES_VERTEX_SHADER, OES_FRAG_SHADER);
        oesSTMatrixLoc = GLES30.glGetUniformLocation(oesProgram, "uSTMatrix");

        compositeProgram = createProgram(COMPOSITE_VERTEX_SHADER, COMPOSITE_FRAG_SHADER);
        uCameraFrameLoc = GLES30.glGetUniformLocation(compositeProgram, "uCameraFrame");
        uUserBgLoc = GLES30.glGetUniformLocation(compositeProgram, "uUserBg");
        uRefinedMaskLoc = GLES30.glGetUniformLocation(compositeProgram, "uRefinedMask");
        uEnableBgReplaceLoc = GLES30.glGetUniformLocation(compositeProgram, "uEnableBgReplace");

        maskRefiner.init();
    }

    public void setupFBOs(int width, int height) {
        if (fboFullRes != 0) {
            GLES30.glDeleteFramebuffers(3, new int[]{fboFullRes, fboLowRes, fboRefinedMask}, 0);
            GLES30.glDeleteTextures(3, new int[]{texFullRes, texLowRes, texRefinedMask}, 0);
        }

        int[] fbos = new int[3];
        int[] texs = new int[3];
        GLES30.glGenFramebuffers(3, fbos, 0);
        GLES30.glGenTextures(3, texs, 0);

        fboFullRes = fbos[0];
        texFullRes = texs[0];
        fboLowRes = fbos[1];
        texLowRes = texs[1];
        fboRefinedMask = fbos[2];
        texRefinedMask = texs[2];

        // 1. FullRes FBO
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFullRes);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFullRes);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texFullRes, 0);

        // 2. LowRes 256x256 FBO for ML input
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLowRes);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 256, 256, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboLowRes);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texLowRes, 0);

        // 3. Refined Mask FBO
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texRefinedMask);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboRefinedMask);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texRefinedMask, 0);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    public SurfaceTexture getCameraSurfaceTexture() {
        return cameraSurfaceTexture;
    }

    public void setEnableBgReplace(boolean enable) {
        this.enableBgReplace = enable;
    }

    public void setUserBgBitmap(Bitmap bitmap) {
        if (bitmap == null) return;
        if (userBgTextureId != 0) {
            GLES30.glDeleteTextures(1, new int[]{userBgTextureId}, 0);
        }
        int[] textures = new int[1];
        GLES30.glGenTextures(1, textures, 0);
        userBgTextureId = textures[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, userBgTextureId);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
    }

    public void loadDefaultBgFromAssets(Context context, String assetPath) {
        try (InputStream is = context.getAssets().open(assetPath)) {
            Bitmap bgBitmap = BitmapFactory.decodeStream(is);
            setUserBgBitmap(bgBitmap);
        } catch (Exception e) {
            Log.e(TAG, "Error loading background asset: " + assetPath, e);
        }
    }

    public void drawFrame(int width, int height, int coarseMaskTex, int prevMaskTex, boolean maskReady) {
        // Without a real mask yet (model loading or failed), show the plain camera
        // rather than compositing against an all-zero mask, which shows only the background
        boolean replace = enableBgReplace && maskReady;
        if (cameraSurfaceTexture == null) return;
        cameraSurfaceTexture.updateTexImage();
        cameraSurfaceTexture.getTransformMatrix(stMatrix);

        // 1. OES texture -> FBO_FullRes
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboFullRes);
        GLES30.glViewport(0, 0, width, height);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        GLES30.glUseProgram(oesProgram);
        GLES30.glUniformMatrix4fv(oesSTMatrixLoc, 1, false, stMatrix, 0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);

        drawQuad();

        // 2. Downscale FullRes -> LowRes 256x256 FBO for ML input
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fboFullRes);
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, fboLowRes);
        GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, 256, 256, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_LINEAR);

        // 3. Refine mask via Guided Filter -> FBO_RefinedMask
        if (replace) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboRefinedMask);
            GLES30.glViewport(0, 0, width, height);
            maskRefiner.refineMask(texFullRes, coarseMaskTex, prevMaskTex, width, height, 0.25f);
        }

        // 4. Final Composite -> Target Screen Surface
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(0, 0, width, height);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);

        GLES30.glUseProgram(compositeProgram);
        GLES30.glUniform1i(uEnableBgReplaceLoc, replace ? 1 : 0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texFullRes);
        GLES30.glUniform1i(uCameraFrameLoc, 0);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, userBgTextureId);
        GLES30.glUniform1i(uUserBgLoc, 1);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texRefinedMask);
        GLES30.glUniform1i(uRefinedMaskLoc, 2);

        drawQuad();
    }

    public void readLowResPixels(ByteBuffer outBuffer) {
        if (fboLowRes == 0 || outBuffer == null) return;
        outBuffer.rewind();
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboLowRes);
        GLES30.glReadPixels(0, 0, 256, 256, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, outBuffer);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    private void drawQuad() {
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, posBuffer);

        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, texBuffer);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        GLES30.glDisableVertexAttribArray(0);
        GLES30.glDisableVertexAttribArray(1);
    }

    public void release() {
        if (maskRefiner != null) {
            maskRefiner.release();
        }
        if (oesTextureId != 0) {
            GLES30.glDeleteTextures(1, new int[]{oesTextureId}, 0);
        }
        if (userBgTextureId != 0) {
            GLES30.glDeleteTextures(1, new int[]{userBgTextureId}, 0);
        }
        if (fboFullRes != 0) {
            GLES30.glDeleteFramebuffers(3, new int[]{fboFullRes, fboLowRes, fboRefinedMask}, 0);
            GLES30.glDeleteTextures(3, new int[]{texFullRes, texLowRes, texRefinedMask}, 0);
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
        return prog;
    }

    private int loadShader(int shaderType, String source) {
        int shader = GLES30.glCreateShader(shaderType);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        return shader;
    }
}
