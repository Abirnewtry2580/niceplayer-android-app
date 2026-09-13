package com.niceplayer.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class AudioWaveformExtractor {
    interface Callback { void complete(float[] levels); void failed(); }
    static void extract(Context context, Uri uri, int buckets, Callback callback) {
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try{
            extractor.setDataSource(context,uri,null);int audioTrack=-1;MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){audioTrack=i;format=f;break;}}
            if(audioTrack<0||format==null)throw new IllegalStateException("No audio track");extractor.selectTrack(audioTrack);
            long duration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):1;String mime=format.getString(MediaFormat.KEY_MIME);codec=MediaCodec.createDecoderByType(mime);codec.configure(format,null,null,0);codec.start();
            float[] peaks=new float[buckets];boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
            while(!outputDone){if(!inputDone){int in=codec.dequeueInputBuffer(10000);if(in>=0){ByteBuffer buffer=codec.getInputBuffer(in);int size=extractor.readSampleData(buffer,0);if(size<0){codec.queueInputBuffer(in,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{codec.queueInputBuffer(in,0,size,extractor.getSampleTime(),0);extractor.advance();}}}
                int out=codec.dequeueOutputBuffer(info,10000);if(out>=0){ByteBuffer pcm=codec.getOutputBuffer(out);if(pcm!=null&&info.size>1){pcm.position(info.offset);pcm.limit(info.offset+info.size);pcm.order(ByteOrder.LITTLE_ENDIAN);float peak=0;while(pcm.remaining()>=2)peak=Math.max(peak,Math.abs(pcm.getShort())/32768f);int bucket=(int)Math.min(buckets-1,Math.max(0,(info.presentationTimeUs*(long)buckets)/Math.max(1,duration)));peaks[bucket]=Math.max(peaks[bucket],peak);}outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(out,false);}}
            float max=.001f;for(float p:peaks)max=Math.max(max,p);for(int i=0;i<peaks.length;i++)peaks[i]=(float)Math.sqrt(peaks[i]/max);callback.complete(peaks);
        }catch(Exception error){callback.failed();}finally{try{extractor.release();}catch(Exception ignored){}if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}}}
    }
}
