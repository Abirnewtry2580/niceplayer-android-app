package com.niceplayer.app;

import android.app.*;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class PlaybackService extends Service {
    public static final String PLAY_PAUSE="com.niceplayer.PLAY_PAUSE",PREVIOUS="com.niceplayer.PREVIOUS",NEXT="com.niceplayer.NEXT";
    private static final String CHANNEL="niceplayer_playback";
    @Override public void onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"Video playback",NotificationManager.IMPORTANCE_LOW);c.setSound(null,null);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    @Override public int onStartCommand(Intent intent,int flags,int startId){String title=intent==null?"NicePlayer":intent.getStringExtra("title");Intent open=new Intent(this,MainActivity.class);PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification_player).setContentTitle(title==null?"NicePlayer":title).setContentText("Playing video").setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(0,"Previous",action(PREVIOUS,1)).addAction(0,"Play/Pause",action(PLAY_PAUSE,2)).addAction(0,"Next",action(NEXT,3)).build();startForeground(41,n);return START_NOT_STICKY;}
    private PendingIntent action(String action,int request){Intent i=new Intent(action).setPackage(getPackageName());return PendingIntent.getBroadcast(this,request,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    @Nullable @Override public IBinder onBind(Intent intent){return null;}
}
