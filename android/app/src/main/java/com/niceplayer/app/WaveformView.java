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
    private boolean[] speech = new boolean[0];
    private long positionMs;
    private long durationMs;
    private boolean loading;
    private int loadingPercent;
    private long remainingMs = -1;

    public WaveformView(Context context) { super(context); init(); }
    public WaveformView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setContentDescription("Audio waveform: previous five seconds and next five seconds");
    }

    public void setAnalysis(AudioWaveformExtractor.Result result) {
        levels = result == null || result.levels == null ? new float[0] : result.levels;
        speech = result == null || result.speech == null ? new boolean[0] : result.speech;
        invalidate();
    }

    public void clearAnalysis() { loading=false; loadingPercent=0; remainingMs=-1; setAnalysis(null); }
    public void setLoading(boolean value) { loading=value; if(value){loadingPercent=0;remainingMs=-1;} invalidate(); }
    public void setLoadingProgress(int percent,long etaMs){loadingPercent=Math.max(0,Math.min(100,percent));remainingMs=etaMs;invalidate();}

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
            if(loading){
                paint.setStyle(Paint.Style.FILL);paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(11*getResources().getDisplayMetrics().density);paint.setColor(Color.WHITE);
                canvas.drawText("Waveform loading  ·  "+loadingPercent+"%",width/2f,centerY-3,paint);
                paint.setTextSize(10*getResources().getDisplayMetrics().density);
                String finish=remainingMs<0?"Calculating finish time…":"Calculated finish: "+formatFinishTime(System.currentTimeMillis()+remainingMs);
                canvas.drawText(finish,width/2f,centerY+13,paint);
                float pad=10*getResources().getDisplayMetrics().density,trackY=height-9*getResources().getDisplayMetrics().density;
                paint.setColor(0x665B6472);paint.setStrokeWidth(3*getResources().getDisplayMetrics().density);paint.setStrokeCap(Paint.Cap.ROUND);canvas.drawLine(pad,trackY,width-pad,trackY,paint);
                paint.setColor(0xFF19E6C1);canvas.drawLine(pad,trackY,pad+(width-2*pad)*loadingPercent/100f,trackY,paint);
                postInvalidateDelayed(250);return;
            }
            paint.setColor(0x995B6472);
            paint.setStrokeWidth(Math.max(2f, getResources().getDisplayMetrics().density * 2f));
            float phase=loading?(System.currentTimeMillis()%900L)/900f:0f;
            for (float x = 8; x < width; x += 12) {
                float pulse=loading?.55f+.45f*(float)Math.sin((x/width+phase)*Math.PI*2):1f;
                canvas.drawCircle(x, centerY, Math.max(1.4f,2.2f*pulse), paint);
            }
            if(loading)postInvalidateDelayed(32);
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
            float centerDistance=Math.abs(x-width/2f)/Math.max(1f,step);
            paint.setStrokeWidth(centerDistance<.75f?stroke*1.22f:stroke);
            canvas.drawLine(x, centerY - half, x, centerY + half, paint);
        }
    }

    private String formatFinishTime(long epochMs){return new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault()).format(new java.util.Date(epochMs));}

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
