package com.niceplayer.app;

import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.io.OutputStream;
import java.io.FileNotFoundException;
import java.util.*;

/** Native VLC player: bundled decoders, no WebView playback dependency. */
public class PlayerActivity extends AppCompatActivity {
    private static final String TAG = "NicePlayer";
    private static final long TEN_SECONDS = 10_000L;
    private LibVLC vlc;
    private MediaPlayer player;
    private VLCVideoLayout video;
    private FrameLayout root;
    private LinearLayout top, bottom;
    private SeekBar seek;
    private TextView play, time, hint, lock;
    private boolean dragging, controls = true, locked;
    private boolean softwareRetryAttempted;
    private Uri sourceUri;
    private ParcelFileDescriptor sourceDescriptor;
    private float downX;
    private long downTime;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && !dragging) {
                long length = Math.max(0, player.getLength()), now = Math.max(0, player.getTime());
                seek.setMax((int)Math.min(Integer.MAX_VALUE, length));
                seek.setProgress((int)Math.min(Integer.MAX_VALUE, now));
                time.setText(clock(now) + "  /  " + clock(length));
                play.setText(player.isPlaying() ? "Ⅱ" : "▶");
            }
            handler.postDelayed(this, 300);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        sourceUri = getIntent().getData();
        if (sourceUri == null) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        ArrayList<String> options = new ArrayList<>();
        options.add("--audio-time-stretch");
        options.add("--avcodec-fast");
        options.add("--network-caching=1500");
        vlc = new LibVLC(this, options);
        player = new MediaPlayer(vlc);
        makeUi();
        player.attachViews(video, null, false, false);
        player.setEventListener(e -> runOnUiThread(() -> {
            if (e.type == MediaPlayer.Event.EncounteredError) handlePlaybackError();
        }));
        if (!startPlayback(true)) {
            Toast.makeText(this, "Could not open this video", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        handler.post(ticker);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
        });
    }

    /**
     * MediaStore content URIs are opened as a file descriptor. Passing the URI directly to
     * LibVLC works on some phones, but fails on others even though this app owns permission.
     */
    private boolean startPlayback(boolean hardwareDecoder) {
        closeSourceDescriptor();
        try {
            Media media;
            if ("content".equalsIgnoreCase(sourceUri.getScheme())) {
                sourceDescriptor = getContentResolver().openFileDescriptor(sourceUri, "r");
                if (sourceDescriptor == null) throw new FileNotFoundException("Null video descriptor");
                media = new Media(vlc, sourceDescriptor.getFileDescriptor());
            } else {
                media = new Media(vlc, sourceUri);
            }
            media.setHWDecoderEnabled(hardwareDecoder, hardwareDecoder);
            media.addOption(":clock-jitter=0");
            media.addOption(":clock-synchro=0");
            player.setMedia(media);
            media.release();
            player.play();
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Unable to open video " + sourceUri, error);
            closeSourceDescriptor();
            return false;
        }
    }

    private void handlePlaybackError() {
        if (!softwareRetryAttempted) {
            softwareRetryAttempted = true;
            Toast.makeText(this, "Hardware decoder failed. Trying compatible mode…", Toast.LENGTH_SHORT).show();
            player.stop();
            if (startPlayback(false)) return;
        }
        Toast.makeText(this, "This video is damaged or uses an unsupported codec", Toast.LENGTH_LONG).show();
    }

    private void closeSourceDescriptor() {
        if (sourceDescriptor == null) return;
        try { sourceDescriptor.close(); } catch (Exception ignored) {}
        sourceDescriptor = null;
    }

    private void makeUi() {
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        video = new VLCVideoLayout(this); root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        hint = label("", 17); hint.setBackgroundColor(0xCC111827); hint.setPadding(dp(18),dp(10),dp(18),dp(10)); hint.setVisibility(View.GONE);
        root.addView(hint, new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));

        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleControls(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { if (!locked) jump(e.getX() < root.getWidth()/2f ? -TEN_SECONDS : TEN_SECONDS); return true; }
        });
        View gestures = new View(this);
        gestures.setOnTouchListener((v,e) -> gesture(e, detector));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1,-1); gp.topMargin=dp(62); gp.bottomMargin=dp(112); root.addView(gestures,gp);
        makeTop(); makeBottom();
        lock = label("LOCK", 11); lock.setBackgroundColor(0xAA111827); lock.setOnClickListener(v -> toggleLock());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(62),dp(44),Gravity.START|Gravity.CENTER_VERTICAL); lp.leftMargin=dp(8); root.addView(lock,lp);
        setContentView(root);
    }

    private void makeTop() {
        top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(5),dp(5),dp(5),dp(5)); top.setBackgroundColor(0xAA000000);
        TextView back=label("‹",34); back.setOnClickListener(v->finish()); top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(50)));
        TextView title=label(getIntent().getStringExtra("title"),15); if(title.getText().toString().trim().isEmpty())title.setText("Video");
        title.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); top.addView(title,new LinearLayout.LayoutParams(0,dp(50),1));
        TextView speed=label("1×",15); speed.setOnClickListener(v->speedMenu(speed)); top.addView(speed,new LinearLayout.LayoutParams(dp(52),dp(50)));
        TextView ratio=label("FIT",11); ratio.setOnClickListener(v->ratio(ratio)); top.addView(ratio,new LinearLayout.LayoutParams(dp(55),dp(50)));
        TextView shot=label("▣",23); shot.setOnClickListener(v->screenshot()); top.addView(shot,new LinearLayout.LayoutParams(dp(48),dp(50)));
        TextView rotate=label("↻",25); rotate.setOnClickListener(v->rotate()); top.addView(rotate,new LinearLayout.LayoutParams(dp(48),dp(50)));
        root.addView(top,new FrameLayout.LayoutParams(-1,dp(60),Gravity.TOP));
    }

    private void makeBottom() {
        bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(dp(10),0,dp(10),dp(6)); bottom.setBackgroundColor(0xB0000000);
        seek=new SeekBar(this); seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){dragging=true;} public void onProgressChanged(SeekBar b,int n,boolean from){if(from)time.setText(clock(n)+"  /  "+clock(player.getLength()));}
            public void onStopTrackingTouch(SeekBar b){player.setTime(b.getProgress());dragging=false;}
        }); bottom.addView(seek,new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER);
        addControl(row,"|◀",21,v->player.setTime(0)); addControl(row,"↶ 10",18,v->jump(-TEN_SECONDS));
        play=addControl(row,"▶",31,v->{if(player.isPlaying())player.pause();else player.play();});
        addControl(row,"10 ↷",18,v->jump(TEN_SECONDS));
        TextView mute=addControl(row,"VOL",12,null); mute.setOnClickListener(v->{boolean m=player.getVolume()>0;player.setVolume(m?0:100);mute.setText(m?"MUTE":"VOL");});
        bottom.addView(row,new LinearLayout.LayoutParams(-1,dp(55)));
        time=label("00:00  /  00:00",12); time.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); bottom.addView(time,new LinearLayout.LayoutParams(-1,dp(24)));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,dp(109),Gravity.BOTTOM));
    }

    private TextView addControl(LinearLayout row,String text,int size,View.OnClickListener click){TextView v=label(text,size);if(click!=null)v.setOnClickListener(click);row.addView(v,new LinearLayout.LayoutParams(0,dp(55),1));return v;}

    private boolean gesture(MotionEvent e, GestureDetector detector) {
        detector.onTouchEvent(e); if(locked)return true;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){downX=e.getX();downTime=Math.max(0,player.getTime());dragging=false;}
        else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=e.getX()-downX;if(Math.abs(dx)>dp(20)){dragging=true;long len=player.getLength();if(len>0){long delta=(long)(dx/root.getWidth()*len),target=Math.max(0,Math.min(len,downTime+delta));player.setTime(target);hint.setText((delta>=0?"+ ":"− ")+clock(Math.abs(delta))+"   "+clock(target));hint.setVisibility(View.VISIBLE);}}}
        else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){hint.setVisibility(View.GONE);dragging=false;}
        return true;
    }

    private void jump(long amount){long len=Math.max(0,player.getLength());player.setTime(Math.max(0,Math.min(len,player.getTime()+amount)));hint.setText(amount>0?"+10 seconds":"−10 seconds");hint.setVisibility(View.VISIBLE);handler.postDelayed(()->hint.setVisibility(View.GONE),550);}
    private void speedMenu(TextView anchor){PopupMenu m=new PopupMenu(this,anchor);float[] s={.25f,.5f,.75f,1f,1.25f,1.5f,2f};for(int i=0;i<s.length;i++)m.getMenu().add(0,i,i,s[i]+"×");m.setOnMenuItemClickListener(x->{float n=s[x.getItemId()];player.setRate(n);anchor.setText(n==1f?"1×":n+"×");return true;});m.show();}
    private int ratioMode;
    private void ratio(TextView v){ratioMode=(ratioMode+1)%3;if(ratioMode==0){player.setAspectRatio(null);player.setScale(0);v.setText("FIT");}else if(ratioMode==1){player.setAspectRatio("16:9");player.setScale(0);v.setText("16:9");}else{player.setAspectRatio(null);player.setScale(1.35f);v.setText("ZOOM");}}
    private void toggleControls(){if(locked)return;controls=!controls;top.setVisibility(controls?View.VISIBLE:View.GONE);bottom.setVisibility(controls?View.VISIBLE:View.GONE);}
    private void toggleLock(){locked=!locked;lock.setText(locked?"UNLOCK":"LOCK");top.setVisibility(locked?View.GONE:View.VISIBLE);bottom.setVisibility(locked?View.GONE:View.VISIBLE);controls=!locked;}
    private TextView label(String s,int size){TextView v=new TextView(this);v.setText(s==null?"":s);v.setTextColor(Color.WHITE);v.setTextSize(size);v.setGravity(Gravity.CENTER);v.setPadding(dp(3),0,dp(3),0);return v;}
    private String clock(long ms){long t=Math.max(0,ms/1000),h=t/3600,m=(t%3600)/60,s=t%60;return h>0?String.format(Locale.US,"%d:%02d:%02d",h,m,s):String.format(Locale.US,"%02d:%02d",m,s);}
    private void rotate(){int o=getResources().getConfiguration().orientation;setRequestedOrientation(o==android.content.res.Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);}
    private void screenshot(){if(Build.VERSION.SDK_INT<26){Toast.makeText(this,"Android 8 or newer required",Toast.LENGTH_SHORT).show();return;}Bitmap b=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);PixelCopy.request(getWindow(),b,r->{if(r==PixelCopy.SUCCESS)save(b);else{b.recycle();Toast.makeText(this,"Screenshot failed",Toast.LENGTH_SHORT).show();}},handler);}
    private void save(Bitmap b){try{ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"NicePlayer_"+System.currentTimeMillis()+".jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29)v.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/NicePlayer");Uri u=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception();try(OutputStream s=getContentResolver().openOutputStream(u)){if(s==null||!b.compress(Bitmap.CompressFormat.JPEG,94,s))throw new Exception();}Toast.makeText(this,"Screenshot saved",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Could not save screenshot",Toast.LENGTH_LONG).show();}finally{b.recycle();}}
    private void immersive(){getWindow().getDecorView().setSystemUiVisibility(5894|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(player!=null){player.stop();player.detachViews();player.release();player=null;}closeSourceDescriptor();if(vlc!=null){vlc.release();vlc=null;}super.onDestroy();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
