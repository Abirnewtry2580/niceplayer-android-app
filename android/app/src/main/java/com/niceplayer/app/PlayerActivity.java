package com.niceplayer.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.app.PictureInPictureParams;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Rational;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private TextView play, time, hint, lock, title;
    private boolean dragging, controls = true, locked;
    private boolean softwareRetryAttempted;
    private Uri sourceUri;
    private ParcelFileDescriptor sourceDescriptor;
    private ArrayList<String> playlistUris = new ArrayList<>(), playlistTitles = new ArrayList<>();
    private int playlistIndex;
    private float downX, downY, startBrightness;
    private int startVolume;
    private AudioManager audioManager;
    private SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private long lastPositionSave;
    private long downTime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideLockButton = () -> {
        if (locked && lock != null) lock.setVisibility(View.GONE);
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && !dragging) {
                long length = Math.max(0, player.getLength()), now = Math.max(0, player.getTime());
                seek.setMax((int)Math.min(Integer.MAX_VALUE, length));
                seek.setProgress((int)Math.min(Integer.MAX_VALUE, now));
                time.setText(clock(now) + "  /  " + clock(length));
                play.setText(player.isPlaying() ? "Ⅱ" : "▶");
                if (player.isPlaying() && now - lastPositionSave > 5000) {
                    savePosition();
                    lastPositionSave = now;
                }
            }
            handler.postDelayed(this, 300);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        playlistUris = getIntent().getStringArrayListExtra("uris");
        playlistTitles = getIntent().getStringArrayListExtra("titles");
        if (playlistUris == null) playlistUris = new ArrayList<>();
        if (playlistTitles == null) playlistTitles = new ArrayList<>();
        playlistIndex = Math.max(0, getIntent().getIntExtra("index", 0));
        sourceUri = getIntent().getData();
        if (sourceUri == null) { finish(); return; }
        if (playlistUris.isEmpty()) playlistUris.add(sourceUri.toString());
        if (playlistTitles.isEmpty()) playlistTitles.add(getIntent().getStringExtra("title"));
        playlistIndex = Math.min(playlistIndex, playlistUris.size() - 1);
        preferences = getSharedPreferences("playback", MODE_PRIVATE);
        audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
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
            else if (e.type == MediaPlayer.Event.Playing) restorePosition();
            else if (e.type == MediaPlayer.Event.EndReached) playAt(playlistIndex + 1);
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

    private String currentTitle() {
        return playlistIndex < playlistTitles.size() && playlistTitles.get(playlistIndex) != null
                ? playlistTitles.get(playlistIndex) : "Video";
    }

    private String positionKey() { return "position_" + sourceUri; }

    private void restorePosition() {
        long saved = preferences.getLong(positionKey(), 0);
        long length = player.getLength();
        if (saved > 5000 && (length <= 0 || saved < length - 10000)) player.setTime(saved);
        preferences.edit().remove(positionKey()).apply();
    }

    private void savePosition() {
        if (player == null || sourceUri == null) return;
        long at = player.getTime(), length = player.getLength();
        SharedPreferences.Editor edit = preferences.edit();
        if (at > 5000 && (length <= 0 || at < length - 10000)) edit.putLong(positionKey(), at);
        else edit.remove(positionKey());
        edit.apply();
    }

    private void playAt(int index) {
        if (index < 0 || index >= playlistUris.size()) {
            Toast.makeText(this, index < 0 ? "This is the first video" : "Playlist finished", Toast.LENGTH_SHORT).show();
            return;
        }
        savePosition();
        playlistIndex = index;
        sourceUri = Uri.parse(playlistUris.get(index));
        softwareRetryAttempted = false;
        title.setText(currentTitle());
        player.stop();
        startPlayback(true);
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
        title=label(currentTitle(),15); if(title.getText().toString().trim().isEmpty())title.setText("Video");
        title.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); top.addView(title,new LinearLayout.LayoutParams(0,dp(50),1));
        TextView speed=label("1×",15); speed.setOnClickListener(v->speedMenu(speed)); top.addView(speed,new LinearLayout.LayoutParams(dp(52),dp(50)));
        TextView ratio=label("FIT",11); ratio.setOnClickListener(v->ratio(ratio)); top.addView(ratio,new LinearLayout.LayoutParams(dp(55),dp(50)));
        TextView shot=label("▣",23); shot.setOnClickListener(v->screenshot()); top.addView(shot,new LinearLayout.LayoutParams(dp(48),dp(50)));
        TextView rotate=label("↻",25); rotate.setOnClickListener(v->rotate()); top.addView(rotate,new LinearLayout.LayoutParams(dp(48),dp(50)));
        TextView more=label("⋮",26); more.setOnClickListener(v->moreMenu(more)); top.addView(more,new LinearLayout.LayoutParams(dp(42),dp(50)));
        root.addView(top,new FrameLayout.LayoutParams(-1,dp(60),Gravity.TOP));
    }

    private void makeBottom() {
        bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(dp(10),0,dp(10),dp(6)); bottom.setBackgroundColor(0xB0000000);
        seek=new SeekBar(this); seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){dragging=true;} public void onProgressChanged(SeekBar b,int n,boolean from){if(from)time.setText(clock(n)+"  /  "+clock(player.getLength()));}
            public void onStopTrackingTouch(SeekBar b){player.setTime(b.getProgress());dragging=false;}
        }); bottom.addView(seek,new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER);
        addControl(row,"|◀",21,v->playAt(playlistIndex-1)); addControl(row,"↶ 10",18,v->jump(-TEN_SECONDS));
        play=addControl(row,"▶",31,v->{if(player.isPlaying())player.pause();else player.play();});
        addControl(row,"10 ↷",18,v->jump(TEN_SECONDS));
        addControl(row,"▶|",21,v->playAt(playlistIndex+1));
        bottom.addView(row,new LinearLayout.LayoutParams(-1,dp(55)));
        time=label("00:00  /  00:00",12); time.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); bottom.addView(time,new LinearLayout.LayoutParams(-1,dp(24)));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,dp(109),Gravity.BOTTOM));
    }

    private TextView addControl(LinearLayout row,String text,int size,View.OnClickListener click){TextView v=label(text,size);if(click!=null)v.setOnClickListener(click);row.addView(v,new LinearLayout.LayoutParams(0,dp(55),1));return v;}

    private boolean gesture(MotionEvent e, GestureDetector detector) {
        detector.onTouchEvent(e); if(locked)return true;
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
            downX=e.getX(); downY=e.getY(); downTime=Math.max(0,player.getTime()); dragging=false;
            WindowManager.LayoutParams p=getWindow().getAttributes(); startBrightness=p.screenBrightness<0?.5f:p.screenBrightness;
            startVolume=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }
        else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
            float dx=e.getX()-downX,dy=e.getY()-downY;
            if(Math.abs(dx)>Math.abs(dy)&&Math.abs(dx)>dp(20)){
                dragging=true;long len=player.getLength();if(len>0){long delta=(long)(dx/root.getWidth()*len),target=Math.max(0,Math.min(len,downTime+delta));player.setTime(target);hint.setText((delta>=0?"+ ":"− ")+clock(Math.abs(delta))+"   "+clock(target));hint.setVisibility(View.VISIBLE);}
            } else if(Math.abs(dy)>dp(20)) {
                dragging=true; float change=-dy/root.getHeight();
                if(downX<root.getWidth()/2f){
                    WindowManager.LayoutParams p=getWindow().getAttributes();p.screenBrightness=Math.max(.02f,Math.min(1f,startBrightness+change));getWindow().setAttributes(p);
                    hint.setText("Brightness  "+Math.round(p.screenBrightness*100)+"%");
                }else{
                    int max=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),n=Math.max(0,Math.min(max,startVolume+Math.round(change*max)));
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,n,0);hint.setText("Volume  "+Math.round(n*100f/max)+"%");
                }
                hint.setVisibility(View.VISIBLE);
            }
        }
        else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){hint.setVisibility(View.GONE);dragging=false;}
        return true;
    }

    private void jump(long amount){long len=Math.max(0,player.getLength());player.setTime(Math.max(0,Math.min(len,player.getTime()+amount)));hint.setText(amount>0?"+10 seconds":"−10 seconds");hint.setVisibility(View.VISIBLE);handler.postDelayed(()->hint.setVisibility(View.GONE),550);}
    private void speedMenu(TextView anchor){PopupMenu m=new PopupMenu(this,anchor);float[] s={.25f,.5f,.75f,1f,1.25f,1.5f,2f};for(int i=0;i<s.length;i++)m.getMenu().add(0,i,i,s[i]+"×");m.setOnMenuItemClickListener(x->{float n=s[x.getItemId()];player.setRate(n);anchor.setText(n==1f?"1×":n+"×");return true;});m.show();}
    private int ratioMode;
    private void ratio(TextView v){ratioMode=(ratioMode+1)%3;if(ratioMode==0){player.setAspectRatio(null);player.setScale(0);v.setText("FIT");}else if(ratioMode==1){player.setAspectRatio("16:9");player.setScale(0);v.setText("16:9");}else{player.setAspectRatio(null);player.setScale(1.35f);v.setText("ZOOM");}}
    private void toggleControls(){
        if(locked){
            lock.setVisibility(lock.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
            handler.removeCallbacks(hideLockButton);
            if(lock.getVisibility()==View.VISIBLE)handler.postDelayed(hideLockButton,2200);
            return;
        }
        controls=!controls;
        int visibility=controls?View.VISIBLE:View.GONE;
        top.setVisibility(visibility);bottom.setVisibility(visibility);lock.setVisibility(visibility);
    }
    private void toggleLock(){
        locked=!locked;handler.removeCallbacks(hideLockButton);lock.setText(locked?"UNLOCK":"LOCK");
        if(locked){top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);lock.setVisibility(View.VISIBLE);controls=false;handler.postDelayed(hideLockButton,2200);}
        else{top.setVisibility(View.VISIBLE);bottom.setVisibility(View.VISIBLE);lock.setVisibility(View.VISIBLE);controls=true;}
    }
    private void moreMenu(TextView anchor){
        PopupMenu m=new PopupMenu(this,anchor);
        m.getMenu().add(0,1,0,"Audio track");
        m.getMenu().add(0,2,1,"Subtitles");
        m.getMenu().add(0,3,2,"Sleep timer");
        m.getMenu().add(0,4,3,"Create preview sheet");
        m.getMenu().add(0,5,4,"Mirror video");
        if(Build.VERSION.SDK_INT>=26)m.getMenu().add(0,6,5,"Picture in picture");
        m.setOnMenuItemClickListener(x->{switch(x.getItemId()){case 1:audioTracks();break;case 2:subtitleTracks();break;case 3:sleepTimer();break;case 4:createPreviewSheet();break;case 5:video.setScaleX(video.getScaleX()<0?1f:-1f);break;case 6:enterPip();break;}return true;});m.show();
    }
    private void audioTracks(){
        MediaPlayer.TrackDescription[] tracks=player.getAudioTracks();
        if(tracks==null||tracks.length==0){Toast.makeText(this,"No audio tracks found",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[tracks.length];for(int i=0;i<tracks.length;i++)names[i]=tracks[i].name;
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Audio track").setItems(names,(d,n)->player.setAudioTrack(tracks[n].id)).show();
    }
    private void subtitleTracks(){
        MediaPlayer.TrackDescription[] tracks=player.getSpuTracks();
        int count=tracks==null?0:tracks.length;String[] names=new String[count+1];names[0]="Off";
        for(int i=0;i<count;i++)names[i+1]=tracks[i].name;
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Subtitles").setItems(names,(d,n)->{if(n==0)player.setSpuTrack(-1);else player.setSpuTrack(tracks[n-1].id);}).show();
    }
    private void sleepTimer(){
        String[] names={"15 minutes","30 minutes","60 minutes","90 minutes","Cancel timer"};int[] minutes={15,30,60,90,0};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Sleep timer").setItems(names,(d,n)->{handler.removeCallbacksAndMessages("sleep");if(minutes[n]>0){Runnable stop=()->{if(player!=null)player.pause();Toast.makeText(this,"Sleep timer finished",Toast.LENGTH_LONG).show();};handler.postAtTime(stop,"sleep",SystemClock.uptimeMillis()+minutes[n]*60000L);Toast.makeText(this,"Timer set for "+names[n],Toast.LENGTH_SHORT).show();}else Toast.makeText(this,"Sleep timer cancelled",Toast.LENGTH_SHORT).show();}).show();
    }
    private void enterPip(){if(Build.VERSION.SDK_INT>=26){PictureInPictureParams p=new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9)).build();enterPictureInPictureMode(p);}}
    private void createPreviewSheet(){
        Toast.makeText(this,"Creating preview sheet…",Toast.LENGTH_SHORT).show();Uri uri=sourceUri;String displayName=currentTitle();
        worker.execute(()->{Bitmap sheet=null;try(ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(uri,"r")){
            if(fd==null)throw new FileNotFoundException();MediaMetadataRetriever r=new MediaMetadataRetriever();r.setDataSource(fd.getFileDescriptor());
            long durationMs=Long.parseLong(Objects.requireNonNull(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))),durationUs=durationMs*1000L;
            String width=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),height=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            long fileSize=queryFileSize(uri);String safeName=displayName.length()>68?displayName.substring(0,65)+"…":displayName;
            sheet=Bitmap.createBitmap(960,640,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(sheet);c.drawColor(Color.BLACK);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);paint.setColor(Color.WHITE);
            paint.setTextSize(25);c.drawText(safeName,22,34,paint);paint.setTextSize(20);paint.setColor(0xFFB8C4D8);
            c.drawText("Resolution: "+(width==null?"Unknown":width+" × "+height)+"     Duration: "+clock(durationMs)+"     Size: "+formatBytes(fileSize),22,70,paint);
            paint.setColor(Color.WHITE);paint.setTextSize(22);
            for(int i=0;i<9;i++){long at=durationUs*(i+1)/10;Bitmap frame=r.getFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(frame!=null){int x=(i%3)*320,y=100+(i/3)*180;c.drawBitmap(frame,null,new android.graphics.Rect(x,y,x+320,y+180),paint);c.drawText(clock(at/1000),x+8,y+170,paint);frame.recycle();}}
            r.release();Bitmap result=sheet;runOnUiThread(()->save(result));
        }catch(Exception error){Log.e(TAG,"Preview failed",error);if(sheet!=null)sheet.recycle();runOnUiThread(()->Toast.makeText(this,"Could not create preview sheet",Toast.LENGTH_LONG).show());}});
    }
    private long queryFileSize(Uri uri){try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst())return c.getLong(0);}catch(Exception ignored){}return -1;}
    private String formatBytes(long bytes){if(bytes<0)return "Unknown";if(bytes<1024)return bytes+" B";double value=bytes;String[] units={"B","KB","MB","GB"};int unit=0;while(value>=1024&&unit<units.length-1){value/=1024;unit++;}return String.format(Locale.US,"%.1f %s",value,units[unit]);}
    private TextView label(String s,int size){TextView v=new TextView(this);v.setText(s==null?"":s);v.setTextColor(Color.WHITE);v.setTextSize(size);v.setGravity(Gravity.CENTER);v.setPadding(dp(3),0,dp(3),0);return v;}
    private String clock(long ms){long t=Math.max(0,ms/1000),h=t/3600,m=(t%3600)/60,s=t%60;return h>0?String.format(Locale.US,"%d:%02d:%02d",h,m,s):String.format(Locale.US,"%02d:%02d",m,s);}
    private void rotate(){int o=getResources().getConfiguration().orientation;setRequestedOrientation(o==android.content.res.Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);}
    private void screenshot(){if(Build.VERSION.SDK_INT<26){Toast.makeText(this,"Android 8 or newer required",Toast.LENGTH_SHORT).show();return;}Bitmap b=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);PixelCopy.request(getWindow(),b,r->{if(r==PixelCopy.SUCCESS)save(b);else{b.recycle();Toast.makeText(this,"Screenshot failed",Toast.LENGTH_SHORT).show();}},handler);}
    private void save(Bitmap b){try{ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"NicePlayer_"+System.currentTimeMillis()+".jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29)v.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/NicePlayer");Uri u=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception();try(OutputStream s=getContentResolver().openOutputStream(u)){if(s==null||!b.compress(Bitmap.CompressFormat.JPEG,94,s))throw new Exception();}Toast.makeText(this,"Screenshot saved",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Could not save screenshot",Toast.LENGTH_LONG).show();}finally{b.recycle();}}
    private void immersive(){getWindow().getDecorView().setSystemUiVisibility(5894|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);}
    @Override protected void onPause(){savePosition();super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);worker.shutdownNow();if(player!=null){player.stop();player.detachViews();player.release();player=null;}closeSourceDescriptor();if(vlc!=null){vlc.release();vlc=null;}super.onDestroy();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
