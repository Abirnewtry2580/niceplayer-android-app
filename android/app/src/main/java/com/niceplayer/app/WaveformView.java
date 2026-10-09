package com.niceplayer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.Locale;

/**
 * Compact waveform centred on the current playback position.
 * The visible range is always five seconds behind and five seconds ahead.
 */
public class WaveformView extends View {
    private static final long WINDOW_SIDE_MS = 5_000L;
    private static final float SILENCE_THRESHOLD = 0.035f;
    private static final float DELETE_SLOT_DP = 42f;
    private static final float SIZE_SLOT_DP = 58f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF controlRect = new RectF();
    private final Path trashPath = new Path();
    private float[] levels = new float[0];
    private boolean[] speech = new boolean[0];
    private long positionMs;
    private long durationMs;
    private long cacheSizeBytes;
    private boolean loading;
    private int loadingPercent;

    public WaveformView(Context context) { super(context); init(); }
    public WaveformView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setContentDescription("Audio waveform with saved-cache delete button and size");
    }

    public void setAnalysis(AudioWaveformExtractor.Result result) {
        levels = result == null || result.levels == null ? new float[0] : result.levels;
        speech = result == null || result.speech == null ? new boolean[0] : result.speech;
        invalidate();
    }

    public void setCacheSizeBytes(long bytes) {
        cacheSizeBytes = Math.max(0L, bytes);
        setContentDescription("Audio waveform. Saved size " + cacheSizeLabel() + ". Tap trash icon to delete saved waveform.");
        invalidate();
    }

    public void clearAnalysis() { loading=false; loadingPercent=0; setAnalysis(null); }
    public void setLoading(boolean value) { loading=value; if(value){loadingPercent=0;} invalidate(); }
    public void setLoadingProgress(int percent,long ignoredEtaMs){loadingPercent=Math.max(0,Math.min(100,percent));invalidate();}

    public void setTimeline(long position, long duration) {
        positionMs = Math.max(0, position);
        durationMs = Math.max(0, duration);
        invalidate();
    }

    private float density() { return getResources().getDisplayMetrics().density; }
    private float contentLeft() { return DELETE_SLOT_DP * density(); }
    private float contentRight() { return Math.max(contentLeft()+dp(80),getWidth()-SIZE_SLOT_DP*density()); }
    private float dp(float value) { return value*density(); }
    private String cacheSizeLabel() {
        if(cacheSizeBytes<1024L)return cacheSizeBytes+"B";
        if(cacheSizeBytes<1024L*1024L)return String.format(Locale.US,"%.1fKB",cacheSizeBytes/1024f);
        return String.format(Locale.US,"%.1fMB",cacheSizeBytes/(1024f*1024f));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        float left=contentLeft(),right=contentRight(),contentWidth=right-left,centerY = height / 2f;
        if (contentWidth <= 0) return;

        if (levels.length == 0 || durationMs <= 0) {
            if(loading){
                paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(11*density());paint.setColor(Color.WHITE);
                canvas.drawText("Waveform loading  ·  "+loadingPercent+"%",(left+right)/2f,centerY+3,paint);
                float pad=10*density(),trackY=height-9*density();
                paint.setColor(0x665B6472);paint.setStrokeWidth(3*density());paint.setStrokeCap(Paint.Cap.ROUND);canvas.drawLine(left+pad,trackY,right-pad,trackY,paint);
                paint.setColor(0xFF19E6C1);canvas.drawLine(left+pad,trackY,left+pad+(contentWidth-2*pad)*loadingPercent/100f,trackY,paint);
                drawSideControls(canvas,width,height,centerY);
                postInvalidateDelayed(250);return;
            }
            paint.setColor(0x995B6472);
            paint.setStrokeWidth(Math.max(2f, density() * 2f));
            for (float x = left+8*density(); x < right; x += 12*density()) {
                canvas.drawCircle(x, centerY, 2.2f*density(), paint);
            }
            drawSideControls(canvas,width,height,centerY);
            return;
        }

        int columns = Math.max(18, Math.min(100, (int)(contentWidth / (5f*density()))));
        float step = contentWidth / columns;
        float stroke = Math.max(density() * 3f, step * .68f);
        long windowStart = positionMs - WINDOW_SIDE_MS;
        long windowDuration = WINDOW_SIDE_MS * 2L;

        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int column = 0; column < columns; column++) {
            long sampleTime = windowStart + Math.round((column + .5f) * windowDuration / columns);
            float x = left+(column + .5f) * step;
            float level = smoothedLevelAt(sampleTime);
            boolean played=sampleTime<=positionMs;
            if (level <= SILENCE_THRESHOLD || sampleTime < 0 || sampleTime > durationMs) {
                paint.setColor(played?0xB05B6472:0x705B6472);
                canvas.drawCircle(x, centerY, Math.max(1.4f, stroke * .34f), paint);
                continue;
            }

            float half = Math.max(stroke, level * height * .43f);
            boolean spoken=speechAt(sampleTime);
            int alpha=played?255:175;
            int base=spoken?0x0019E6C1:0x006B7280;
            paint.setColor((alpha<<24)|base);
            float centerDistance=Math.abs(x-(left+right)/2f)/Math.max(1f,step);
            paint.setStrokeWidth(centerDistance<.75f?stroke*1.22f:stroke);
            canvas.drawLine(x, centerY - half, x, centerY + half, paint);
        }
        drawSideControls(canvas,width,height,centerY);
    }

    private void drawSideControls(Canvas canvas,float width,float height,float centerY) {
        float d=density(),centerX=DELETE_SLOT_DP*d/2f,radius=14*d;
        paint.setStyle(Paint.Style.FILL);paint.setColor(0x553F1C1C);
        canvas.drawCircle(centerX,centerY,radius,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f*d);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(0xFFFF9A9A);
        trashPath.reset();
        trashPath.moveTo(centerX-5*d,centerY-4*d);trashPath.lineTo(centerX-4*d,centerY+6*d);
        trashPath.lineTo(centerX+4*d,centerY+6*d);trashPath.lineTo(centerX+5*d,centerY-4*d);
        canvas.drawPath(trashPath,paint);
        canvas.drawLine(centerX-7*d,centerY-5*d,centerX+7*d,centerY-5*d,paint);
        canvas.drawLine(centerX-2*d,centerY-8*d,centerX+2*d,centerY-8*d,paint);
        canvas.drawLine(centerX-2*d,centerY-1*d,centerX-2*d,centerY+4*d,paint);
        canvas.drawLine(centerX+2*d,centerY-1*d,centerX+2*d,centerY+4*d,paint);

        float sizeRight=width-7*d,sizeLeft=width-SIZE_SLOT_DP*d;
        controlRect.set(sizeLeft,centerY-13*d,sizeRight,centerY+13*d);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0x55374151);
        canvas.drawRoundRect(controlRect,12*d,12*d,paint);
        paint.setColor(Color.WHITE);paint.setTextAlign(Paint.Align.RIGHT);paint.setTextSize(10*d);
        canvas.drawText(cacheSizeLabel(),sizeRight-4*d,centerY+3.5f*d,paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private boolean speechAt(long timeMs) {
        if (timeMs < 0 || timeMs > durationMs || speech.length == 0) return false;
        int index = Math.max(0, Math.min(speech.length - 1,
                Math.round(timeMs * (speech.length - 1f) / Math.max(1L, durationMs))));
        return speech[index];
    }

    private float smoothedLevelAt(long timeMs) {
        long span=Math.max(1L,durationMs/Math.max(1,levels.length));
        return (levelAt(timeMs-span)+2f*levelAt(timeMs)+levelAt(timeMs+span))/4f;
    }

    private float levelAt(long timeMs) {
        if (timeMs < 0 || timeMs > durationMs || levels.length == 0) return 0f;
        float exact = timeMs * (levels.length - 1f) / Math.max(1L, durationMs);
        int left = Math.max(0, Math.min(levels.length - 1, (int)Math.floor(exact)));
        int right = Math.min(levels.length - 1, left + 1);
        float mix = exact - left;
        return Math.max(0f, Math.min(1f, levels[left] * (1f - mix) + levels[right] * mix));
    }
}
