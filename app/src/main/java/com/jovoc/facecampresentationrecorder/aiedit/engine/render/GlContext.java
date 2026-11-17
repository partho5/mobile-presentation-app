package com.jovoc.facecampresentationrecorder.aiedit.engine.render;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.view.Surface;

/** An EGL display, a GL ES 3.0 context and one window surface on an encoder's input, current on this thread. */
final class GlContext {

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;

    /** Creates the context and makes {@code encoderInput} current. */
    GlContext(Surface encoderInput) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("No EGL display");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            display = EGL14.EGL_NO_DISPLAY;
            throw new IllegalStateException("EGL init failed");
        }
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            release();
            throw new IllegalStateException("No GL ES 3 config");
        }
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            context = EGL14.EGL_NO_CONTEXT;
            release();
            throw new IllegalStateException("No GL ES 3 context");
        }
        surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderInput,
                new int[]{EGL14.EGL_NONE}, 0);
        if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
            surface = EGL14.EGL_NO_SURFACE;
            release();
            throw new IllegalStateException("No window surface");
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            release();
            throw new IllegalStateException("eglMakeCurrent failed");
        }
    }

    /** Stamps the frame being drawn; the encoder uses it as the sample time. */
    void setPresentationTime(long nanos) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos);
    }

    /** Hands the drawn frame to the encoder. */
    void swapBuffers() {
        EGL14.eglSwapBuffers(display, surface);
    }

    void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        surface = EGL14.EGL_NO_SURFACE;
    }
}
