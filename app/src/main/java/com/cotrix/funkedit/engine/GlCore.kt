package com.cotrix.funkedit.engine

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

object GlCore {

    const val VERTEX = """
        attribute vec2 aPos;
        attribute vec2 aTex;
        uniform mat4 uSt;
        uniform vec2 uScale;
        uniform vec2 uShift;
        varying vec2 vTex;
        void main() {
            vTex = (uSt * vec4(aTex, 0.0, 1.0)).xy;
            gl_Position = vec4(aPos * uScale + uShift, 0.0, 1.0);
        }
    """

    const val FRAG_OES = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex;
        uniform samplerExternalOES uTex;
        uniform vec2 uTexel;
        uniform float uBlur;
        uniform float uFlash;
        uniform float uChroma;
        void main() {
            vec2 uv = vTex;
            vec3 col;
            if (uChroma > 0.001) {
                col.r = texture2D(uTex, uv + vec2(uChroma * 0.012, 0.0)).r;
                col.g = texture2D(uTex, uv).g;
                col.b = texture2D(uTex, uv - vec2(uChroma * 0.012, 0.0)).b;
            } else {
                col = texture2D(uTex, uv).rgb;
            }
            if (uBlur > 0.001) {
                vec2 o = uTexel * (uBlur * 4.0);
                vec3 s = col;
                s += texture2D(uTex, uv + vec2(o.x, 0.0)).rgb;
                s += texture2D(uTex, uv - vec2(o.x, 0.0)).rgb;
                s += texture2D(uTex, uv + vec2(0.0, o.y)).rgb;
                s += texture2D(uTex, uv - vec2(0.0, o.y)).rgb;
                s += texture2D(uTex, uv + o).rgb;
                s += texture2D(uTex, uv - o).rgb;
                s += texture2D(uTex, uv + vec2(o.x, -o.y)).rgb;
                s += texture2D(uTex, uv + vec2(-o.x, o.y)).rgb;
                col = s / 9.0;
            }
            col += vec3(uFlash);
            gl_FragColor = vec4(col, 1.0);
        }
    """

    const val FRAG_SCENE = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform vec3 uBg;
        uniform float uAlpha;
        void main() {
            vec4 t = texture2D(uTex, vTex);
            float a = t.a * uAlpha;
            vec3 c = uBg * (1.0 - a) + t.rgb * uAlpha;
            gl_FragColor = vec4(c, 1.0);
        }
    """

    const val FRAG_OVERLAY = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        void main() {
            gl_FragColor = texture2D(uTex, vTex);
        }
    """

    val QUAD_POS = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    val TEX_FLAT = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
    val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    fun texCoordsFor(rotation: Int): FloatArray = when (rotation) {
        90 -> floatArrayOf(1f, 0f, 1f, 1f, 0f, 0f, 0f, 1f)
        180 -> floatArrayOf(1f, 1f, 0f, 1f, 1f, 0f, 0f, 0f)
        270 -> floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        else -> TEX_FLAT.clone()
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(sh) }
        return sh
    }

    fun program(fragSrc: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragSrc))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun createTexFromBitmap(b: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        return ids[0]
    }
}

class Quad {
    private val pos = ByteBuffer.allocateDirect(GlCore.QUAD_POS.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(GlCore.QUAD_POS); position(0) }
    private val tex = ByteBuffer.allocateDirect(8 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(GlCore.TEX_FLAT); position(0) }

    fun setTex(coords: FloatArray) {
        tex.clear(); tex.put(coords); tex.position(0)
    }

    fun draw(prog: Int) {
        val pa = GLES20.glGetAttribLocation(prog, "aPos")
        val ta = GLES20.glGetAttribLocation(prog, "aTex")
        GLES20.glEnableVertexAttribArray(pa)
        GLES20.glVertexAttribPointer(pa, 2, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(ta)
        GLES20.glVertexAttribPointer(ta, 2, GLES20.GL_FLOAT, false, 0, tex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}

class EglEnv(surface: Surface) {
    private val display: EGLDisplay
    private val eglSurface: EGLSurface

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)
        val cfgAttr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, cfgAttr, 0, configs, 0, 1, num, 0)
        check(num[0] > 0) { "No EGL config" }
        val ctx: EGLContext = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, ctx)
    }

    fun swap(timeNanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, timeNanos)
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglTerminate(display)
    }
}
