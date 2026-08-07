/* LICENSE>>
Copyright 2025 M5_Development (FM Infinite Authors)

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

<< LICENSE */

package com.m5dev.fminfinite;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class EmulatorGLSurfaceView extends GLSurfaceView {
    private static final String TAG = "EmulatorGLSurfaceView";

    private EmulatorRenderer renderer;
    private int[] pixels = new int[640 * 480];
    private int[] size = new int[2];
    private OnRendererFailedListener failedListener;

    public interface OnRendererFailedListener {
        void onRendererFailed(String reason);
    }

    public void setOnRendererFailedListener(OnRendererFailedListener listener) {
        this.failedListener = listener;
    }

    public EmulatorGLSurfaceView(Context context) {
        super(context);
        init();
    }

    public EmulatorGLSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setEGLContextClientVersion(2); // OpenGL ES 2.0
        renderer = new EmulatorRenderer();
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void drawFrame() {
        // Fetch latest framebuffer from core
        boolean hasFrame = EmulatorCore.nativeGetFrameBuffer(pixels, size);
        if (!hasFrame) return;

        int width = size[0];
        int height = size[1];
        if (width <= 0 || height <= 0) return;

        if (pixels.length < width * height) {
            pixels = new int[width * height];
        }

        updateFrame(pixels, width, height);
    }

    public void updateFrame(int[] newPixels, int width, int height) {
        renderer.updateTexture(newPixels, width, height);
        requestRender();
    }

    public void setScreenFilterBilinear(boolean bilinear) {
        if (renderer != null) {
            renderer.setScreenFilterBilinear(bilinear);
            requestRender();
        }
    }

    private class EmulatorRenderer implements GLSurfaceView.Renderer {
        private int textureId;
        private int program;
        private int[] localPixels;
        private int texWidth = 640;
        private int texHeight = 480;
        private boolean textureUpdated = false;

        private boolean screenFilterBilinear = false;
        private boolean filterChanged = true;

        private final FloatBuffer vertexBuffer;
        private final FloatBuffer texCoordBuffer;

        private final float[] vertices = {
            -1.0f, -1.0f, 0.0f,  // Bottom left
             1.0f, -1.0f, 0.0f,  // Bottom right
            -1.0f,  1.0f, 0.0f,  // Top left
             1.0f,  1.0f, 0.0f   // Top right
        };

        private final float[] texCoords = {
            0.0f, 1.0f,  // Bottom left
            1.0f, 1.0f,  // Bottom right
            0.0f, 0.0f,  // Top left
            1.0f, 0.0f   // Top right
        };

        private final String vertexShaderCode =
            "attribute vec4 vPosition;" +
            "attribute vec2 vTexCoord;" +
            "varying vec2 texCoord;" +
            "void main() {" +
            "  gl_Position = vPosition;" +
            "  texCoord = vTexCoord;" +
            "}";

        private final String fragmentShaderCode =
            "precision mediump float;" +
            "uniform sampler2D texture;" +
            "varying vec2 texCoord;" +
            "void main() {" +
            "  gl_FragColor = texture2D(texture, texCoord);" +
            "}";

        public EmulatorRenderer() {
            ByteBuffer vbb = ByteBuffer.allocateDirect(vertices.length * 4);
            vbb.order(ByteOrder.nativeOrder());
            vertexBuffer = vbb.asFloatBuffer();
            vertexBuffer.put(vertices);
            vertexBuffer.position(0);

            ByteBuffer tbb = ByteBuffer.allocateDirect(texCoords.length * 4);
            tbb.order(ByteOrder.nativeOrder());
            texCoordBuffer = tbb.asFloatBuffer();
            texCoordBuffer.put(texCoords);
            texCoordBuffer.position(0);
        }

        public synchronized void updateTexture(int[] newPixels, int width, int height) {
            if (localPixels == null || localPixels.length < width * height) {
                localPixels = new int[width * height];
            }
            System.arraycopy(newPixels, 0, localPixels, 0, width * height);
            texWidth = width;
            texHeight = height;
            textureUpdated = true;
        }

        public synchronized void setScreenFilterBilinear(boolean bilinear) {
            if (this.screenFilterBilinear != bilinear) {
                this.screenFilterBilinear = bilinear;
                this.filterChanged = true;
            }
        }

        private void reportError(final String msg) {
            Log.e(TAG, "Renderer Error: " + msg);
            post(() -> {
                if (failedListener != null) {
                    failedListener.onRendererFailed(msg);
                }
            });
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);

            // Compile shaders
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode);
            int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode);

            if (vertexShader == 0 || fragmentShader == 0) {
                reportError("Shader compilation failed.");
                return;
            }

            program = GLES20.glCreateProgram();
            if (program == 0) {
                reportError("Failed to create GLES program.");
                return;
            }

            GLES20.glAttachShader(program, vertexShader);
            GLES20.glAttachShader(program, fragmentShader);
            GLES20.glLinkProgram(program);

            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(program);
                reportError("Program linking failed: " + log);
                GLES20.glDeleteProgram(program);
                program = 0;
                return;
            }

            // Create texture
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];

            if (textureId == 0) {
                reportError("Failed to generate texture ID.");
                return;
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            int initialFilter = screenFilterBilinear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, initialFilter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, initialFilter);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            // Maintain 4:3 aspect ratio
            float targetAspectRatio = 4.0f / 3.0f;
            float aspect = (float) width / height;
            int viewportX, viewportY, viewportWidth, viewportHeight;
            if (aspect > targetAspectRatio) {
                viewportHeight = height;
                viewportWidth = Math.round(height * targetAspectRatio);
                viewportX = (width - viewportWidth) / 2;
                viewportY = 0;
            } else {
                viewportWidth = width;
                viewportHeight = Math.round(width / targetAspectRatio);
                viewportX = 0;
                viewportY = (height - viewportHeight) / 2;
            }
            GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            if (program == 0) {
                return;
            }

            synchronized (this) {
                if (localPixels == null) {
                    return;
                }

                if (textureUpdated || filterChanged) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

                    if (filterChanged) {
                        int filter = screenFilterBilinear ? GLES20.GL_LINEAR : GLES20.GL_NEAREST;
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
                        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
                        filterChanged = false;
                    }

                    if (textureUpdated) {
                        GLES20.glTexImage2D(
                            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                            texWidth, texHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                            IntBuffer.wrap(localPixels)
                        );
                        textureUpdated = false;
                    }
                }
            }

            GLES20.glUseProgram(program);

            int positionHandle = GLES20.glGetAttribLocation(program, "vPosition");
            int texCoordHandle = GLES20.glGetAttribLocation(program, "vTexCoord");
            int textureHandle = GLES20.glGetUniformLocation(program, "texture");

            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, vertexBuffer);

            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(textureHandle, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);
        }

        private int loadShader(int type, String shaderCode) {
            int shader = GLES20.glCreateShader(type);
            if (shader == 0) {
                return 0;
            }
            GLES20.glShaderSource(shader, shaderCode);
            GLES20.glCompileShader(shader);

            int[] compileStatus = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
            if (compileStatus[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                reportError("Shader type " + type + " compile failed: " + log);
                GLES20.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }
    }
}
