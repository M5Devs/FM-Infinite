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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class EmulatorSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private SurfaceHolder holder;
    private int[] pixels = new int[640 * 480];
    private int[] size = new int[2];
    private Bitmap reusableBitmap = null;
    private boolean screenFilterBilinear = false;

    public void setScreenFilterBilinear(boolean bilinear) {
        this.screenFilterBilinear = bilinear;
    }

    public EmulatorSurfaceView(Context context) {
        super(context);
        init();
    }

    public EmulatorSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EmulatorSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        holder = getHolder();
        holder.addCallback(this);
    }

    public void drawFrame() {
        SurfaceHolder h = holder; // Local snapshot — thread-safe read
        if (h == null) return;

        // Fetch latest framebuffer from core
        boolean hasFrame = EmulatorCore.nativeGetFrameBuffer(pixels, size);
        if (!hasFrame) return;

        int width = size[0];
        int height = size[1];
        if (width <= 0 || height <= 0) return;

        // Recreate reusable bitmap if size changed
        if (reusableBitmap == null || reusableBitmap.getWidth() != width || reusableBitmap.getHeight() != height) {
            reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            if (pixels.length < width * height) {
                pixels = new int[width * height];
            }
        }

        reusableBitmap.setPixels(pixels, 0, width, 0, 0, width, height);

        Canvas canvas = h.lockCanvas(); // Use local h, not this.holder
        if (canvas != null) {
            try {
                // Clear background
                canvas.drawColor(0xFF000000);

                // Draw scaled to fit the view, maintaining aspect ratio
                int viewWidth = getWidth();
                int viewHeight = getHeight();

                float scaleX = (float) viewWidth / width;
                float scaleY = (float) viewHeight / height;
                float scale = Math.min(scaleX, scaleY);

                int scaledWidth = Math.round(width * scale);
                int scaledHeight = Math.round(height * scale);

                int left = (viewWidth - scaledWidth) / 2;
                int top = (viewHeight - scaledHeight) / 2;

                Rect destRect = new Rect(left, top, left + scaledWidth, top + scaledHeight);
                Paint paint = new Paint();
                paint.setFilterBitmap(screenFilterBilinear);
                canvas.drawBitmap(reusableBitmap, null, destRect, paint);
            } finally {
                h.unlockCanvasAndPost(canvas);
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        this.holder = holder;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        this.holder = holder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        this.holder = null;
    }
}
