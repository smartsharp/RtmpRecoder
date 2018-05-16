package cn.campusapp.rtmprecorder;

import android.app.Service;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import com.wuwang.libyuv.Key;
import com.wuwang.libyuv.YuvUtils;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import static org.bytedeco.javacpp.avcodec.AV_CODEC_ID_H264;

public class RecordScreenService extends Service {
    private static final String TAG = "zhanghb/RSService";
    private HandlerThread myHandlerThread;
    private Handler myHandler;
    private boolean running;
    private int mWidth, mHeight, mDpi;
    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;
    private String outputUrl;
    private boolean recordToFile = false;
    // add for rtmp encoder
    private FFmpegFrameRecorder frameRecorder;
    private int frameRate = 30;

    private MediaCodec mEncoder;
    private long mVideoPtsOffset, mAudioPtsOffset;

    private long startTime;
    private Frame yuvImage = null;
    private Surface mSurface;
    private static final int MAX_IMAGE_NUMBER = 25;//30;//这个值代表ImageReader最大的存储图像
    private ImageReader mImageReader;

    private static final int FRAME_FROM_MY_SURFACE = 0;
    private static final int FRAME_FROM_IMAGE_READER = 1;
    private static final int FRAME_FROM_MEDIA_CODEC = 2;
    private static final boolean RECORD_WITH_AUTIO = true;

    private static final int frame_source = FRAME_FROM_IMAGE_READER;

    /* audio data getting thread */
    private AudioRecord audioRecord;
    private AudioRecordRunnable audioRecordRunnable;
    private Thread audioThread;
    boolean recording = false;
    volatile boolean runAudioThread = true;
    private int sampleAudioRateInHz = 44100;

    private RtmpThread mRtmpThread;
    private Object frameLock = new Object();
    private boolean frameAvail = false;

    // mysurface
    private int mTextureId = 1;
    private SurfaceTexture mSurfaceTexture;

    public RecordScreenService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        myHandlerThread = new HandlerThread("service_thread",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        myHandlerThread.start();
        myHandler = new Handler( myHandlerThread.getLooper() ){
            @Override
            public void handleMessage(Message msg) {
                //super.handleMessage(msg);
                //这个方法是运行在 handler-thread 线程中的 ，可以执行耗时操作
                Log.d( "handler " , "消息： " + msg.what + "  线程： " + Thread.currentThread().getName()  ) ;
            }
        };
        running = false;
    }

    @Override
    public void onDestroy() {
        myHandlerThread.quit();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.e(TAG, "onBind");
        return new RecordScreenBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.e(TAG, "onUnbind");
        stopRecord();
        return super.onUnbind(intent);
    }

    public void setDispParams(int width, int height, int densityDpi) {
        this.mWidth = width;
        this.mHeight = height;
        this.mDpi = densityDpi;
    }

    public void setOutputUrl(String outputUrl){
        this.outputUrl = outputUrl;
    }

    public class RecordScreenBinder extends Binder {
        public RecordScreenService getRecordService() {
            return RecordScreenService.this;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void setMediaProjection(MediaProjection m){
        mediaProjection = m;
    }

    public boolean startRecord() {
        if (mediaProjection == null || outputUrl == null || running) {
            return false;
        }
        Log.d(TAG, "startRecord "+outputUrl);
        recordToFile = outputUrl.startsWith("file://");
        if(recordToFile) {
            if(initFileRecorder()){
                mediaRecorder.start();
                running = true;
            }
        }else{
            if(initRtmpRecorder()) {
                try {
                    if(mRtmpThread != null){
                        mRtmpThread.cancel();
                    }
                    if(frame_source != FRAME_FROM_MEDIA_CODEC){
                        mRtmpThread = new RtmpThread();
                        mRtmpThread.start();
                    }
                    frameRecorder.start();
                    startTime = System.currentTimeMillis();
                    if(RECORD_WITH_AUTIO) {
                        audioThread.start();
                    }
                    if(frame_source == FRAME_FROM_MEDIA_CODEC){
                        mEncoder.start();
                    }
                    recording = true;
                    running = true;
                }catch (Exception e){
                    Log.e(TAG, "startRecord exception "+e+","+Log.getStackTraceString(e));
                    deinitRtmpRecorder();
                }
            }
        }
        Log.d(TAG, "startRecord result="+running);
        return running;
    }
    public boolean stopRecord() {
        if (!running) {
            return false;
        }
        Log.d(TAG, "stopRecord "+outputUrl);
        if(recordToFile) {
            deinitFileRecorder();
        }else{
            deinitRtmpRecorder();
        }
        running = false;

        return true;
    }
    private boolean initFileRecorder() {
        Log.d(TAG, "initFileRecorder ");
        try {
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setOutputFile(outputUrl.substring(7));
            mediaRecorder.setVideoSize(mWidth, mHeight);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.prepare();
            mSurface = mediaRecorder.getSurface();
            virtualDisplay = mediaProjection.createVirtualDisplay("MainScreen", mWidth, mHeight, mDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mSurface, null, null);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "initFileRecorder exception "+e+","+Log.getStackTraceString(e));
            deinitFileRecorder();
            return false;
        }
    }
    private void deinitFileRecorder() {
        Log.d(TAG, "deinitFileRecorder ");
        if(mediaRecorder != null) {
            mediaRecorder.stop();
            //mediaRecorder.reset();
            mediaRecorder.release();
            if(virtualDisplay != null)
                virtualDisplay.release();
            mediaProjection.stop();
            mediaRecorder = null;
        }
    }
    private boolean initRtmpRecorder() {
        Log.d(TAG, "initRtmpRecorder "+","+frame_source);
        try {
            if (mRtmpThread != null) {
                mRtmpThread.cancel();
                mRtmpThread = null;
            }
            // init rtmp recorder
            yuvImage = new Frame(mWidth, mHeight, Frame.DEPTH_UBYTE, 4);

            frameRecorder = new FFmpegFrameRecorder(outputUrl, mWidth, mHeight, 1);
            frameRecorder.setVideoCodec(AV_CODEC_ID_H264);//28);
            frameRecorder.setFormat("flv");
            frameRecorder.setSampleRate(sampleAudioRateInHz);
            // Set in the surface changed method
            frameRecorder.setFrameRate(frameRate); // 30fps
            //frameRecorder.setVideoQuality(0);

            if (RECORD_WITH_AUTIO) {
                audioRecordRunnable = new AudioRecordRunnable();
                audioThread = new Thread(audioRecordRunnable);
                runAudioThread = true;
            }

            if(frame_source == FRAME_FROM_MY_SURFACE){
                mSurfaceTexture = new SurfaceTexture(mTextureId);
                mSurface = new Surface(mSurfaceTexture);
                mSurfaceTexture.setOnFrameAvailableListener(frameListener, myHandler);
            }else if(frame_source == FRAME_FROM_IMAGE_READER){
                mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, MAX_IMAGE_NUMBER);
                mSurface = mImageReader.getSurface();
                mImageReader.setOnImageAvailableListener(imageListener, myHandler);
            }else {
                MediaFormat format = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight); // H.264 Advanced Video Coding
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000); // 500Kbps, 1Mbps
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30); // 30fps
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2); // 2 seconds between I-frames
                Log.d(TAG, "created video format: " + format);
                mEncoder = MediaCodec.createEncoderByType("video/avc");
                mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                //mEncoder.setOnFrameRenderedListener(mediaFrameListener, myHandler);
                mEncoder.setCallback(frameCallback, myHandler);
                mSurface = mEncoder.createInputSurface();
            }
            virtualDisplay = mediaProjection.createVirtualDisplay("MainScreen", mWidth, mHeight, mDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mSurface, null, null);

            return true;
        }catch (Exception e){
            Log.e(TAG,"initRtmpRecorder exception "+e+","+Log.getStackTraceString(e));
            return false;
        }
    }
    private void deinitRtmpRecorder() {
        Log.d(TAG, "deinitRtmpRecorder "+FRAME_FROM_IMAGE_READER);
        if(mRtmpThread != null){
            mRtmpThread.cancel();
            mRtmpThread = null;
        }
        if(RECORD_WITH_AUTIO) {
            runAudioThread = false;
            try {
                audioThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            audioRecordRunnable = null;
            audioThread = null;
        }

        if (virtualDisplay != null) {
            mediaProjection.stop();
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if(frame_source == FRAME_FROM_MY_SURFACE) {
            if(mSurfaceTexture != null) {
                mSurfaceTexture.release();
                mSurfaceTexture = null;
            }
        }else if(frame_source == FRAME_FROM_IMAGE_READER){
            if(mImageReader != null){
                mImageReader.close();
            }
        }else {
            if (mEncoder != null) {
                mEncoder.stop();
                mEncoder.release();
                mEncoder = null;
            }
        }
        if (frameRecorder != null && recording) {
            recording = false;
            try {
                frameRecorder.stop();
                frameRecorder.release();
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
                Log.e(TAG, "deinitRtmpRecorder exception "+e+", "+Log.getStackTraceString(e));
            }
            frameRecorder = null;
        }
    }


    private MediaCodec.Callback frameCallback = new MediaCodec.Callback() {
        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {

        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo buffer) {
            ByteBuffer encodedData = codec.getOutputBuffer(index);
            //writeSampleData(mVideoTrackIndex, buffer, encodedData);
            if ((buffer.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                // The codec config data was pulled out and fed to the muxer when we got
                // the INFO_OUTPUT_FORMAT_CHANGED status.
                // Ignore it.
                buffer.size = 0;
            }
            boolean eos = (buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (buffer.size == 0 && !eos) {
                encodedData = null;
            } else {
                if (buffer.presentationTimeUs != 0) { // maybe 0 if eos
                    if (mVideoPtsOffset == 0) {
                        mVideoPtsOffset = buffer.presentationTimeUs;
                        buffer.presentationTimeUs = 0;
                    } else {
                        buffer.presentationTimeUs -= mVideoPtsOffset;
                    }
                }
                /*if (VERBOSE)
                    Log.d(TAG, "[" + Thread.currentThread().getId() + "] Got buffer, track=" + track
                            + ", info: size=" + buffer.size
                            + ", presentationTimeUs=" + buffer.presentationTimeUs);
                if (!eos && mCallback != null) {
                    mCallback.onRecording(buffer.presentationTimeUs);
                }*/
            }
            if (encodedData != null) {
                encodedData.position(buffer.offset);
                encodedData.limit(buffer.offset + buffer.size);
                //byte[] bytes = new byte[encodedData.remaining()];
                //encodedData.get(bytes);
                /*((ByteBuffer) yuvImage.image[0].position(0)).put(encodedData);
                try {
                    long t = 1000 * (System.currentTimeMillis() - startTime);
                    Log.v(TAG, "Writing Frame t=" + t + ", ts=" + frameRecorder.getTimestamp());
                    if (t > frameRecorder.getTimestamp()) {
                        frameRecorder.setTimestamp(t);
                    }

                    frameRecorder.record(yuvImage, PixelFormat.);
                } catch (FFmpegFrameRecorder.Exception e) {
                    Log.e(TAG, "RtmpThread exception " + e + "," + Log.getStackTraceString(e));
                }*/
            }

            codec.releaseOutputBuffer(index, true);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {

        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

        }
    };
    private SurfaceTexture.OnFrameAvailableListener frameListener = new SurfaceTexture.OnFrameAvailableListener() {

        @Override
        public void onFrameAvailable(SurfaceTexture surfaceTexture) {
            Log.v(TAG, "OnFrameAvail");
            synchronized (frameLock){
                frameAvail = true;
                frameLock.notifyAll();
            }
        }
    };
    private ImageReader.OnImageAvailableListener imageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Log.v(TAG, "OnImageAvail");
            synchronized (frameLock){
                frameAvail = true;
                frameLock.notifyAll();
            }
        }
    };

    private MediaCodec.OnFrameRenderedListener mediaFrameListener = new MediaCodec.OnFrameRenderedListener() {
        @Override
        public void onFrameRendered(@NonNull MediaCodec codec, long presentationTimeUs, long nanoTime) {
            Log.v(TAG, "onFrameRendered");
            synchronized (frameLock){
                frameAvail = true;
                frameLock.notifyAll();
            }
        }
    };
    //---------------------------------------------
    // audio thread, gets and encodes audio data
    //---------------------------------------------
    class AudioRecordRunnable implements Runnable {

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize;
            ShortBuffer audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);


            audioData = ShortBuffer.allocate(bufferSize);

            Log.d(TAG, "audioRecord startRecording()");
            audioRecord.startRecording();

            /* ffmpeg_audio encoding loop */
            while (runAudioThread) {
                //Log.v(LOG_TAG,"recording? " + recording);
                bufferReadResult = audioRecord.read(audioData.array(), 0, audioData.capacity());
                audioData.limit(bufferReadResult);
                if (bufferReadResult > 0) {
                    //Log.v(TAG, "audioRecord bufferReadResult: " + bufferReadResult);
                    // If "recording" isn't true when start this thread, it never get's set according to this if statement...!!!
                    // Why?  Good question...
                    if (recording) {
                        try {
                            frameRecorder.recordSamples(audioData);
                            //Log.v(LOG_TAG,"recording " + 1024*i + " to " + 1024*i+1024);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            Log.e(TAG, "audioRecord: exception "+e+","+Log.getStackTraceString(e));
                        }
                    }
                }
            }
            Log.v(TAG, "audioRecord Finished");

            /* encoding finish, release recorder */
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
        }
    }

    private final class RtmpThread extends Thread {
        boolean running = false;
        boolean ended = true;
        public void cancel(){
            Log.d(TAG,"cancel scannerthread");
            long t1 = System.currentTimeMillis();
            running = false;
            int count = 0;
            while(!ended && (count++ <50)){
                try {
                    Thread.sleep(100);
                }catch (Exception e) {}
            }
            Log.d(TAG,"cancel MarkerThread consume "+(System.currentTimeMillis()-t1)+" ms");
        }

        @Override
        public void run() {
            running = true;
            ended = false;
            setName("RtmpThread");

            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            Log.d(TAG,"RtmpThread started.");
            boolean frameavail = false;
            while (running) {
                synchronized (frameLock){
                    try {
                        frameLock.wait();
                        frameavail = frameAvail;
                    }catch (Exception e){}
                    frameAvail = false;
                }
                if(running && frameavail){
                    //Log.d(TAG,"RtmpThread check image");
                    if(frame_source == FRAME_FROM_MY_SURFACE) {
                        mSurfaceTexture.updateTexImage();
                        Log.d(TAG,"RtmpThread  frame updated.");

                    }else if(frame_source == FRAME_FROM_IMAGE_READER) {
                        Image mImage = null;
                        while((mImage = mImageReader.acquireNextImage())!=null) {
                            int mWidth = mImage.getWidth();
                            int mHeight = mImage.getHeight();
                            //Log.d(TAG,"RtmpThread image found " + mWidth+","+mHeight);
                            if (recording) {
                                /*
                                // Four bytes per pixel: width * height * 4.
                                byte[] rgbaBytes = new byte[mWidth * mHeight * 4];
                                // put the data into the rgbaBytes array.
                                mImage.getPlanes()[0].getBuffer().get(rgbaBytes);
                                mImage.close(); // Access to the image is no longer needed, release it.

                                // Create a yuv byte array: width * height * 1.5 ().
                                byte[] yuv = new byte[mWidth * mHeight * 3 / 2];
                                int ret = YuvUtils.RgbaToI420(Key.ARGB_TO_I420, rgbaBytes, yuv, mWidth, mHeight);
                                if (ret == 0) {
                                    ((ByteBuffer) yuvImage.image[0].position(0)).put(yuv);
                                    try {
                                        long t = 1000 * (System.currentTimeMillis() - startTime);
                                        Log.v(TAG, "Writing Frame t=" + t + ", ts=" + frameRecorder.getTimestamp());
                                        if (t > frameRecorder.getTimestamp()) {
                                            frameRecorder.setTimestamp(t);
                                        }

                                        frameRecorder.record(yuvImage);
                                    } catch (FFmpegFrameRecorder.Exception e) {
                                        Log.e(TAG, "RtmpThread exception " + e + "," + Log.getStackTraceString(e));
                                    }
                                }*/
                                ((ByteBuffer) yuvImage.image[0].position(0)).put(mImage.getPlanes()[0].getBuffer());
                                mImage.close();

                                try {
                                    long t = 1000 * (System.currentTimeMillis() - startTime);
                                    Log.v(TAG, "Writing Frame t=" + t + ", ts=" + frameRecorder.getTimestamp());
                                    if (t > frameRecorder.getTimestamp()) {
                                        frameRecorder.setTimestamp(t);
                                    }

                                    frameRecorder.record(yuvImage);
                                } catch (FFmpegFrameRecorder.Exception e) {
                                    Log.e(TAG, "RtmpThread exception " + e + "," + Log.getStackTraceString(e));
                                }
                            }
                        }
                    }else{
                        // TBD
                    }
                }
            }
            ended = true;
            Log.d(TAG,"RtmpThread ended.");
        }
    }


}
