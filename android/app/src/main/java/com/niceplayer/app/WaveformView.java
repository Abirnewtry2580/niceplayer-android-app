package com.niceplayer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Compact waveform centred on the current playback position.
 * The visible range is always five seconds behind and five seconds ahead.
 */
public class WaveformView extends View {
    private static final long WINDOW_SIDE_MS = 5_000L;
    private static final float SILENCE_THRESHOLD = 0.035f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] levels = new float[0];
    private long positionMs;
    private long durationMs;

    public WaveformView(Context context) { super(context); init(); }
    public WaveformView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setContentDescription("Audio waveform: previous five seconds and next five seconds");
    }

    public void setLevels(float[] values) {
        levels = values == null ? new float[0] : values;
        invalidate();
    }

    public void setTimeline(long position, long duration) {
        positionMs = Math.max(0, position);
        durationMs = Math.max(0, duration);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight(), centerY = height / 2f;
        if (width <= 0 || height <= 0) return;

        if (levels.length == 0 || durationMs <= 0) {
            paint.setColor(0xCCFFFFFF);
            paint.setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density * 2f));
            for (float x = 8; x < width; x += 12) canvas.drawCircle(x, centerY, 1.8f, paint);
            drawPlayhead(canvas, width, height);
            return;
        }

        int columns = Math.max(24, Math.min(100, (int)(width / 5f)));
        float step = width / columns;
        float stroke = Math.max(getResources().getDisplayMetrics().density * 3f, step * .68f);
        long windowStart = positionMs - WINDOW_SIDE_MS;
        long windowDuration = WINDOW_SIDE_MS * 2L;

        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int column = 0; column < columns; column++) {
            long sampleTime = windowStart + Math.round((column + .5f) * windowDuration / columns);
            float x = (column + .5f) * step;
            float level = levelAt(sampleTime);
            boolean past = sampleTime <= positionMs;

            if (level <= SILENCE_THRESHOLD || sampleTime < 0 || sampleTime > durationMs) {
                paint.setColor(0xCCFFFFFF);
                canvas.drawCircle(x, centerY, Math.max(1.5f, stroke * .38f), paint);
                continue;
            }

            float half = Math.max(stroke, level * height * .43f);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(stroke);
            canvas.drawLine(x, centerY - half, x, centerY + half, paint);
        }
        drawPlayhead(canvas, width, height);
    }

    private float levelAt(long timeMs) {
        if (timeMs < 0 || timeMs > durationMs || levels.length == 0) return 0f;
        float exact = timeMs * (levels.length - 1f) / Math.max(1L, durationMs);
        int left = Math.max(0, Math.min(levels.length - 1, (int)Math.floor(exact)));
        int right = Math.min(levels.length - 1, left + 1);
        float mix = exact - left;
        return Math.max(0f, Math.min(1f, levels[left] * (1f - mix) + levels[right] * mix));
    }

    private void drawPlayhead(Canvas canvas, float width, float height) {
        paint.setStrokeCap(Paint.Cap.SQUARE);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density * 1.5f));
        canvas.drawLine(width / 2f, 3f, width / 2f, height - 3f, paint);
    }
}
