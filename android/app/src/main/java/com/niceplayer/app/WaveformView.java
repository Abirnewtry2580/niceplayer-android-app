package com.niceplayer.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class WaveformView extends View {
    public interface OnSeekListener { void onSeek(float fraction); }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] levels = new float[0];
    private float progress;
    private OnSeekListener listener;

    public WaveformView(Context context) { super(context); }
    public WaveformView(Context context, AttributeSet attrs) { super(context, attrs); }
    public void setLevels(float[] values) { levels = values == null ? new float[0] : values; invalidate(); }
    public void setProgressFraction(float value) { progress = Math.max(0, Math.min(1, value)); invalidate(); }
    public void setOnSeekListener(OnSeekListener value) { listener = value; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float w=getWidth(),h=getHeight(),center=h/2f;
        if(levels.length==0){paint.setColor(0x558AA4C8);paint.setStrokeWidth(2);canvas.drawLine(0,center,w,center,paint);return;}
        float step=w/levels.length,bar=Math.max(2,step*.55f);
        for(int i=0;i<levels.length;i++){float level=Math.max(.05f,Math.min(1,levels[i])),x=(i+.5f)*step,half=level*h*.46f;boolean played=x<=progress*w;
            paint.setColor(level>.82f?0xFFFF4D5E:level>.62f?0xFFFFA726:(played?0xFF22D3B6:0xFF718096));paint.setStrokeWidth(bar);canvas.drawLine(x,center-half,x,center+half,paint);
        }
        paint.setColor(Color.WHITE);paint.setStrokeWidth(3);canvas.drawLine(progress*w,0,progress*w,h,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE||e.getActionMasked()==MotionEvent.ACTION_UP){if(listener!=null)listener.onSeek(Math.max(0,Math.min(1,e.getX()/getWidth())));return true;}return super.onTouchEvent(e);}
}
