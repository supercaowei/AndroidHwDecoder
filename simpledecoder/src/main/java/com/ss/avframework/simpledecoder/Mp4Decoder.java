package com.ss.avframework.simpledecoder;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

public class Mp4Decoder {

    public final String TAG = "Mp4Decoder";

    private String mMp4FilePath;
    private Surface mDisplaySurface;
    private MediaExtractor mExtractor;
    private MediaFormat mVideoMediaFormat = null;
    private MediaFormat mAudioMediaFormat = null;
    private MediaCodec mVideoDecoder = null;
    private MediaCodec mAudioDecoder = null;
    private boolean mCircularly = false;
    private boolean stopped = true;
    private boolean paused = false;
    private SyncClock primaryClock;
    private SyncClock videoClock;
    private SyncClock audioClock;
    private ArrayList<PacketBuffer> videoBufferList;
    private ArrayList<PacketBuffer> audioBufferList;
    private final int maxVideoBufferCount = 20;
    private final int maxAudioBufferCount = 30;
    private volatile Object videoBufferSync;
    private volatile Object audioBufferSync;
    private IVideoFrameListener mVideoFrameListener;
    private IAudioSampleListener mAudioSampleListener;
    private Thread mDemuxThread;
    private Thread mVideoInputThread;
    private Thread mAudioInputThread;
    private Thread mVideoOutputThread;
    private Thread mAudioOutputThread;

    CodecOutputSurface mOutputSurface;

    public Mp4Decoder(IVideoFrameListener videoFrameListener, IAudioSampleListener audioSampleListener) {
        mVideoFrameListener = videoFrameListener;
        mAudioSampleListener = audioSampleListener;
    }

    //return: 0 file doesn't exist or not a media file, 1 aac audio only, 2 H.264 video only, 3 aac audio and H.264 video
    public static int CheckMediaFile(String mp4FilePath) {
        int ret = 0;
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(mp4FilePath);
            for (int i = 0; i < extractor.getTrackCount(); ++i) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.compareTo(MediaFormat.MIMETYPE_VIDEO_AVC) == 0) {
                    ret |= 2;
                } else if (mime.compareTo(MediaFormat.MIMETYPE_AUDIO_AAC) == 0) {
                    ret |= 1;
                }
            }
            extractor.release();
        } catch (Exception e) {
            ret = 0;
        }
        return ret;
    }

    //size[0] width, size[1] height
    public static boolean GetVideoSizeFromFile(String videoFilePath, int[] size) {
        if (size.length < 2) {
            return false;
        }
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(videoFilePath);
            for (int i = 0; i < extractor.getTrackCount(); ++i) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.compareTo(MediaFormat.MIMETYPE_VIDEO_AVC) == 0) {
                    size[0] = format.getInteger(MediaFormat.KEY_WIDTH);
                    if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
                        size[0] = format.getInteger("crop-right") + 1 - format.getInteger("crop-left");
                    }
                    size[1] = format.getInteger(MediaFormat.KEY_HEIGHT);
                    if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
                        size[1] = format.getInteger("crop-bottom") + 1 - format.getInteger("crop-top");
                    }
                    return true;
                }
            }
            extractor.release();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    //info[0]: sample rate, info[1]: channel count
    public static boolean GetAudioInfoFromFile(String videoFilePath, int[] info) {
        if (info.length < 2) {
            return false;
        }
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(videoFilePath);
            for (int i = 0; i < extractor.getTrackCount(); ++i) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.compareTo(MediaFormat.MIMETYPE_AUDIO_AAC) == 0) {
                    info[0] = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    info[1] = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    return true;
                }
            }
            extractor.release();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public void start(String mp4FilePath, boolean circularly, Surface surface) {
        if (!stopped) {
            Log.w(TAG, "Decoding already started.");
            return;
        }
        mMp4FilePath = mp4FilePath;
        mCircularly = circularly;
        mDisplaySurface = surface;

        SharedEGLContext.GetCurrentEGLContext();

        stopped = false;
        paused = false;
        mDemuxThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DemuxMp4();
                } catch (Exception e) {
                    e.printStackTrace();
                    if (mExtractor != null) {
                        mExtractor.release();
                        mExtractor = null;
                    }
                }
            }
        }, "Demux");
        mDemuxThread.start();
    }

    public void stop() {
        if (stopped) {
            Log.w(TAG, "Decoding already stopped.");
            return;
        }
        paused = false;
        stopped = true;
        try {
            if (mDemuxThread != null) {
                mDemuxThread.join();
                mDemuxThread = null;
            }
        } catch (java.lang.InterruptedException e) {
            e.printStackTrace();
        }
        videoBufferList = null;
        audioBufferList = null;
        videoBufferSync = null;
        audioBufferSync = null;

        mMp4FilePath = null;
        mCircularly = false;
        mDisplaySurface = null;
    }

    public boolean stopped() {
        return stopped;
    }

    public void pause() {
        if (!stopped) {
            paused = true;
        }
    }

    public void resume() {
        if (!stopped) {
            paused = false;
        }
    }

    public boolean paused() {
        return paused;
    }

    public void resetSurface(Surface surface) {
        mDisplaySurface = surface;
//        if (mVideoDecoder != null && !stopped) {
//            mVideoDecoder.setOutputSurface(mDisplaySurface);
//        }
    }

    private void DemuxMp4() throws Exception {
        int videoTrackId = -1;
        int audioTrackId = -1;
        int bufferSize = 0;

        mExtractor = new MediaExtractor();
        mExtractor.setDataSource(mMp4FilePath);

        for (int i = 0; i < mExtractor.getTrackCount(); ++i) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            Log.i(TAG, "Find track type: " + mime);
            if (mime.compareTo(MediaFormat.MIMETYPE_VIDEO_AVC) == 0) {
                videoTrackId = i;
                mVideoMediaFormat = format;
                mExtractor.selectTrack(videoTrackId);
            }
            else if (mime.compareTo(MediaFormat.MIMETYPE_AUDIO_AAC) == 0) {
                audioTrackId = i;
                mAudioMediaFormat = format;
                mExtractor.selectTrack(audioTrackId);
            }
        }

        if (mAudioMediaFormat == null && mVideoMediaFormat == null) {
            throw new Exception("No supported track.");
        }

        if (mVideoMediaFormat != null) {
            videoBufferList = new ArrayList<PacketBuffer>();
            videoBufferSync = new Object();

            int wxh = mVideoMediaFormat.getInteger(MediaFormat.KEY_WIDTH) * mVideoMediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
            if (bufferSize < wxh * 2)
                bufferSize = wxh * 2;

            CreateVideoIOThreads();
        }
        if (mAudioMediaFormat != null) {
            audioBufferList = new ArrayList<PacketBuffer>();
            audioBufferSync = new Object();

            int sampleRate = mAudioMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = mAudioMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int channelConfig = (channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO);
            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize < minBufferSize * 3)
                bufferSize = minBufferSize * 3;

            CreateAudioIOThreads();
        }

        ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
        int sampleSize = 0;
        boolean noSample = true;
        while (!stopped) {
            sampleSize = mExtractor.readSampleData(readBuffer, 0);
            noSample = noSample ? (sampleSize < 0) : noSample;
            if (sampleSize < 0) { //reach the file end
                if (noSample) {
                    break;
                }
                if (ShouldRecycle())
                    continue;
                else
                    break;
            }
            int trackIndex = mExtractor.getSampleTrackIndex();
            long ptsUs = mExtractor.getSampleTime();
            if (trackIndex == videoTrackId) {
                Log.i(TAG, "Video sample: size " + sampleSize + " bytes, pts " + (ptsUs / 1000) + "ms");
                while (videoBufferList.size() >= maxVideoBufferCount && !stopped) {
                    Thread.sleep(10);
                }
                if (stopped) {
                    break;
                }
                synchronized (videoBufferSync) {
                    PacketBuffer packet = new PacketBuffer();
                    packet.data = new byte[sampleSize];
                    readBuffer.get(packet.data, 0, sampleSize);
                    packet.ptsUs = ptsUs;
                    videoBufferList.add(packet);
                }
            } else if (trackIndex == audioTrackId) {
                Log.i(TAG, "Audio sample: size " + sampleSize + " bytes, pts " + (ptsUs / 1000) + "ms");
                while (audioBufferList.size() >= maxAudioBufferCount && !stopped) {
                    Thread.sleep(10);
                }
                if (stopped) {
                    break;
                }
                synchronized (audioBufferSync) {
                    PacketBuffer packet = new PacketBuffer();
                    packet.data = new byte[sampleSize];
                    readBuffer.get(packet.data, 0, sampleSize);
                    packet.ptsUs = ptsUs;
                    audioBufferList.add(packet);
                }
            } else {
                Log.i(TAG, "Unknown track id: " + trackIndex);
            }
            mExtractor.advance();
        }

        mExtractor.release();
        mExtractor = null;

        JoinVideoIOThreads();
        JoinAudioIOThreads();

        Log.i(TAG, "Demux thread exit");
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private boolean ShouldRecycle() {
        //在videoBufferList和audioBufferList中分别添加一个结束包
        PacketBuffer packet = new PacketBuffer();
        packet.data = null;
        packet.ptsUs = Long.MIN_VALUE;
        if (videoBufferList != null && videoBufferSync != null) {
            synchronized (videoBufferSync) {
                videoBufferList.add(packet);
            }
        }
        if (audioBufferList != null && audioBufferSync != null) {
            synchronized (audioBufferSync) {
                audioBufferList.add(packet);
            }
        }

        //wait for the threads exit
        JoinVideoIOThreads();
        JoinAudioIOThreads();

        if (!mCircularly) {
            return false; //exit immediately
        } else {
            //reset the decoders and restart the threads
            if (mVideoMediaFormat != null) {
                CreateVideoIOThreads();
            }
            if (mAudioMediaFormat != null) {
                CreateAudioIOThreads();
            }
            //seek to the head of the file and continue reading
            mExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            return true;
        }
    }

    private void CreateVideoIOThreads() {
        mVideoOutputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    VideoDecoderOutput();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "Video Output");
        mVideoOutputThread.start();
    }

    private void CreateAudioIOThreads() {
        mAudioOutputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AudioDecoderOutput();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "Audio Output");
        mAudioOutputThread.start();
    }

    private void JoinVideoIOThreads() {
        try {
            if (mVideoOutputThread != null) {
                mVideoOutputThread.join();
                mVideoOutputThread = null;
            }
        } catch (java.lang.InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void JoinAudioIOThreads(){
        try {
            if (mAudioOutputThread != null) {
                mAudioOutputThread.join();
                mAudioOutputThread = null;
            }
        } catch (java.lang.InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void VideoDecoderInput() throws Exception {
        while (!stopped) {
            if (videoBufferList.size() <= 0) {
                Thread.sleep(10);
                continue;
            }
            int inputBufferIndex = mVideoDecoder.dequeueInputBuffer(10000); //infinitely wait if no available input buffer
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mVideoDecoder.getInputBuffer(inputBufferIndex);
                synchronized (videoBufferSync) {
                    PacketBuffer packet = videoBufferList.remove(0);
                    if (packet.data == null && packet.ptsUs == Long.MIN_VALUE) { //end of stream
                        mVideoDecoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        break;
                    }

                    inputBuffer.put(packet.data, 0, packet.data.length);
                    mVideoDecoder.queueInputBuffer(inputBufferIndex, 0, packet.data.length, packet.ptsUs, 0);
                    Log.i(TAG, "Video input: buffer index " + inputBufferIndex + ", buffer size " + packet.data.length + ", pts " + (packet.ptsUs / 1000) + "ms");
                }
            }
        }
        Log.i(TAG, "VideoDecoderInput thread exit");
    }

    private void AudioDecoderInput() throws Exception {
        while (!stopped) {
            if (mAudioDecoder == null || audioBufferList.size() <= 0) {
                Thread.sleep(10);
                continue;
            }
            int inputBufferIndex = mAudioDecoder.dequeueInputBuffer(10000); //infinitely wait if no available input buffer
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mAudioDecoder.getInputBuffer(inputBufferIndex);
                synchronized (audioBufferSync) {
                    PacketBuffer packet = audioBufferList.remove(0);
                    if (packet.data == null && packet.ptsUs == Long.MIN_VALUE) { //end of stream
                        mAudioDecoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        break;
                    }

                    inputBuffer.put(packet.data, 0, packet.data.length);
                    mAudioDecoder.queueInputBuffer(inputBufferIndex, 0, packet.data.length, packet.ptsUs, 0);
                    Log.i(TAG, "Audio input: buffer index " + inputBufferIndex + ", buffer size " + packet.data.length + ", pts " + (packet.ptsUs / 1000) + "ms");
                }
            }
        }
        Log.i(TAG, "AudioDecoderInput thread exit");
    }

    private void VideoDecoderOutput() throws Exception {
        mOutputSurface = new CodecOutputSurface(mVideoMediaFormat.getInteger(MediaFormat.KEY_WIDTH), mVideoMediaFormat.getInteger(MediaFormat.KEY_HEIGHT));

        String mime = mVideoMediaFormat.getString(MediaFormat.KEY_MIME);
        mVideoDecoder = MediaCodec.createDecoderByType(mime);
        Log.i(TAG, "Video MediaFormat: " + mVideoMediaFormat.toString());
        mVideoDecoder.configure(mVideoMediaFormat, mOutputSurface.getSurface(), null, 0);
        mVideoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
        mVideoDecoder.start();

        videoClock = new SyncClock();
        if (mAudioMediaFormat == null) {
            primaryClock = videoClock;
        }

        mVideoInputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    VideoDecoderInput();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "Video Input");
        mVideoInputThread.start();

//        int interval = 100;
//        int saveLimit = 10;

        while (!stopped) {
            if (paused) {
                Thread.sleep(10);
                videoClock.sysTimeMs = System.currentTimeMillis();
                continue;
            }
            BufferInfo info = new BufferInfo();
            int outputBufferIndex = mVideoDecoder.dequeueOutputBuffer(info, 0);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
            if (outputBufferIndex >= 0) {
                boolean shouldDrop = CompareClockAndSleep(info.presentationTimeUs, videoClock, primaryClock);
                if (shouldDrop) {
                    mVideoDecoder.releaseOutputBuffer(outputBufferIndex, false);
                    continue;
                }
                ByteBuffer outputBuffer = mVideoDecoder.getOutputBuffer(outputBufferIndex);
                final MediaFormat videoOutputFmt = mVideoDecoder.getOutputFormat(outputBufferIndex);
                Log.i(TAG, "Video output: size " + info.size + ", pts " + (info.presentationTimeUs / 1000) + "ms, buffer size " + outputBuffer.remaining());
                outputBuffer.position(0);
                outputBuffer.clear();
                mVideoDecoder.releaseOutputBuffer(outputBufferIndex, true);
                boolean doRender = info.size > 0;
                if (doRender) {
                    mOutputSurface.awaitNewImage();
                    mOutputSurface.drawImage(false);

//                    if (saveLimit > 0) {
//                        if (interval <= 0) {
//                            DateFormat df = new SimpleDateFormat("HH-mm-ss");
//                            String time = df.format(new Date());
//                            File outputFile = new File("/sdcard/frame-" + time + ".png");
//                            outputFile.createNewFile();
//                            mOutputSurface.saveFrame(outputFile.toString());
//                            interval = 100;
//                            saveLimit--;
//                        }
//                        interval--;
//                    }

                    if (mVideoFrameListener != null) {
                        int width = videoOutputFmt.getInteger(MediaFormat.KEY_WIDTH);
                        if (videoOutputFmt.containsKey("crop-left") && videoOutputFmt.containsKey("crop-right")) {
                            width = videoOutputFmt.getInteger("crop-right") + 1 - videoOutputFmt.getInteger("crop-left");
                        }
                        int height = videoOutputFmt.getInteger(MediaFormat.KEY_HEIGHT);
                        if (videoOutputFmt.containsKey("crop-top") && videoOutputFmt.containsKey("crop-bottom")) {
                            height = videoOutputFmt.getInteger("crop-bottom") + 1 - videoOutputFmt.getInteger("crop-top");
                        }
                        int colorFormat = videoOutputFmt.getInteger(MediaFormat.KEY_COLOR_FORMAT); //android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatXXXXXX
                        mVideoFrameListener.onVideoFrameDecoded(mOutputSurface.getTextureId(), width, height, colorFormat, System.currentTimeMillis());
                    }
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newOutputFormat = mVideoDecoder.getOutputFormat();
                Log.i(TAG, "Video output format changed to: " + newOutputFormat.toString());
                mVideoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Thread.sleep(5);
            }
        }

        mVideoInputThread.join();
        mVideoInputThread = null;

        videoClock.Reset();
        if (videoClock == primaryClock) {
            primaryClock = null;
        }
        videoClock = null;

        mVideoDecoder.stop();
        mVideoDecoder.release();
        mVideoDecoder = null;

        mOutputSurface.release();

        Log.i(TAG, "VideoDecoderOutput thread exit");
    }

    private void AudioDecoderOutput() throws Exception {
        String mime = mAudioMediaFormat.getString(MediaFormat.KEY_MIME);
        mAudioDecoder = MediaCodec.createDecoderByType(mime);
        Log.i(TAG, "Audio MediaFormat: " + mAudioMediaFormat.toString());
        mAudioDecoder.configure(mAudioMediaFormat, null, null, 0);
        mAudioDecoder.start();

        audioClock = new SyncClock();
        primaryClock = audioClock;

        mAudioInputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AudioDecoderInput();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "Audio Input");
        mAudioInputThread.start();

        while (!stopped) {
            if (paused) {
                Thread.sleep(10);
                audioClock.sysTimeMs = System.currentTimeMillis();
                continue;
            }
            BufferInfo info = new BufferInfo();
            int outputBufferIndex = mAudioDecoder.dequeueOutputBuffer(info, 0);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
            if (outputBufferIndex >= 0) {
                boolean shouldDrop = CompareClockAndSleep(info.presentationTimeUs, audioClock, primaryClock);
                if (shouldDrop) {
                    mAudioDecoder.releaseOutputBuffer(outputBufferIndex, false);
                    continue;
                }
                ByteBuffer outputBuffer = mAudioDecoder.getOutputBuffer(outputBufferIndex);
                final MediaFormat audioOutputFmt = mAudioDecoder.getOutputFormat(outputBufferIndex);
                Log.i(TAG, "Audio output: size " + info.size + ", pts " + (info.presentationTimeUs / 1000) + "ms, buffer size " + outputBuffer.remaining());
                if (mAudioSampleListener != null) {
                    int channelCount = audioOutputFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int sampleCount = info.size / (16 / 8);
                    mAudioSampleListener.onAudioSampleDecoded(outputBuffer,
                            audioOutputFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            channelCount,
                            16, //AudioFormat.ENCODING_PCM_XXX
                            sampleCount,
                            audioClock.sysTimeMs
                            );
                }
                outputBuffer.clear();
                mAudioDecoder.releaseOutputBuffer(outputBufferIndex, true);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newOutputFormat = mAudioDecoder.getOutputFormat();
                Log.i(TAG, "Audio output format changed to: " + newOutputFormat.toString());
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Thread.sleep(5);
            }
        }

        mAudioInputThread.join();
        mAudioInputThread = null;

        audioClock.Reset();
        audioClock = null;
        primaryClock = null;

        mAudioDecoder.stop();
        mAudioDecoder.release();
        mAudioDecoder = null;

        Log.i(TAG, "AudioDecoderOutput thread exit");
    }

    private class PacketBuffer {
        public byte[] data;
        public long ptsUs;
    }

    private class SyncClock {
        public long ptsUs = Long.MIN_VALUE;
        public long sysTimeMs = Long.MIN_VALUE;

        public boolean Uninitialized() {
            return (ptsUs == Long.MIN_VALUE && sysTimeMs == Long.MIN_VALUE);
        }

        public void Reset() {
            ptsUs = Long.MIN_VALUE;
            sysTimeMs = Long.MIN_VALUE;
        }
    }

    private boolean CompareClockAndSleep(long ptsUs, SyncClock currentClock, SyncClock primaryClock) throws Exception {
        long sysTimeMs = System.currentTimeMillis();
        if (primaryClock.Uninitialized()) {
            if (currentClock == primaryClock) {
                primaryClock.ptsUs = ptsUs;
                primaryClock.sysTimeMs = sysTimeMs;
                return false;
            } else {
                return true; //should drop this frame
            }
        }
        long sleepMs = (ptsUs - primaryClock.ptsUs) / 1000 - (sysTimeMs - primaryClock.sysTimeMs);
        if (sleepMs > 0) { //It's not time for rendering this frame, sleep for a while
            Log.e(TAG, "sleepMs " + sleepMs);
            Thread.sleep(sleepMs);
        } //else { // render this frame immediately }
        currentClock.sysTimeMs = primaryClock.sysTimeMs + (ptsUs - primaryClock.ptsUs) / 1000;
        currentClock.ptsUs = ptsUs;
        return false;
    }

    public interface IVideoFrameListener {
        void onVideoFrameDecoded(int textureId, int width, int height, int colorFormat, long timestampMs);
    }

    public interface IAudioSampleListener {
        void onAudioSampleDecoded(ByteBuffer data, int sampleRate, int channelCount, int bitsPerSample, int sampleCount, long timestampMs);
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    public static class SharedEGLContext {
        private static EGLContext mSharedEGLContext;
        public static void GetCurrentEGLContext() {
            EGL10 egl = (EGL10) EGLContext.getEGL();
            mSharedEGLContext = egl.eglGetCurrentContext();
        }
        public static EGLContext GetEGLContext() {
            return mSharedEGLContext;
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * Holds state associated with a Surface used for MediaCodec decoder output.
     * <p>
     * The constructor for this class will prepare GL, create a SurfaceTexture,
     * and then create a Surface for that SurfaceTexture.  The Surface can be passed to
     * MediaCodec.configure() to receive decoder output.  When a frame arrives, we latch the
     * texture with updateTexImage(), then render the texture with GL to a pbuffer.
     * <p>
     * By default, the Surface will be using a BufferQueue in asynchronous mode, so we
     * can potentially drop frames.
     */
    private static class CodecOutputSurface implements SurfaceTexture.OnFrameAvailableListener {
        private static final String TAG = "CodecOutputSurface";

        private STextureRender mTextureRender;
        private SurfaceTexture mSurfaceTexture;
        private Surface mSurface;
        private EGL10 mEgl;
        private EGLContext mEglContext;

        private EGLDisplay mEGLDisplay = EGL10.EGL_NO_DISPLAY;
        private EGLContext mEGLContext = EGL10.EGL_NO_CONTEXT;
        private EGLSurface mEGLSurface = EGL10.EGL_NO_SURFACE;
        int mWidth;
        int mHeight;

        private Object mFrameSyncObject = new Object();     // guards mFrameAvailable
        private boolean mFrameAvailable;

        private ByteBuffer mPixelBuf;                       // used by saveFrame()

        /**
         * Creates a CodecOutputSurface backed by a pbuffer with the specified dimensions.  The
         * new EGL context and surface will be made current.  Creates a Surface that can be passed
         * to MediaCodec.configure().
         */
        public CodecOutputSurface(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException();
            }
            mEgl = (EGL10) EGLContext.getEGL();
            mWidth = width;
            mHeight = height;

            eglSetup();
            makeCurrent();
            setup();
        }

        /**
         * Creates interconnected instances of TextureRender, SurfaceTexture, and Surface.
         */
        private void setup() {
            mTextureRender = new STextureRender();
            mTextureRender.surfaceCreated();

            Log.d(TAG, "textureID=" + mTextureRender.getTextureId());
            mSurfaceTexture = new SurfaceTexture(mTextureRender.getTextureId());

            // This doesn't work if this object is created on the thread that CTS started for
            // these test cases.
            //
            // The CTS-created thread has a Looper, and the SurfaceTexture constructor will
            // create a Handler that uses it.  The "frame available" message is delivered
            // there, but since we're not a Looper-based thread we'll never see it.  For
            // this to do anything useful, CodecOutputSurface must be created on a thread without
            // a Looper, so that SurfaceTexture uses the main application Looper instead.
            //
            // Java language note: passing "this" out of a constructor is generally unwise,
            // but we should be able to get away with it here.
            mSurfaceTexture.setOnFrameAvailableListener(this);

            mSurface = new Surface(mSurfaceTexture);

            mPixelBuf = ByteBuffer.allocateDirect(mWidth * mHeight * 4);
            mPixelBuf.order(ByteOrder.LITTLE_ENDIAN);
        }

        /**
         * Prepares EGL.  We want a GLES 2.0 context and a surface that supports pbuffer.
         */
        private void eglSetup() {
            final int EGL_OPENGL_ES2_BIT = 0x0004;
            final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;

            mEGLDisplay = mEgl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (mEGLDisplay == EGL10.EGL_NO_DISPLAY) {
                throw new RuntimeException("unable to get EGL14 display");
            }
            int[] version = new int[2];
            if (!mEgl.eglInitialize(mEGLDisplay, version)) {
                mEGLDisplay = null;
                throw new RuntimeException("unable to initialize EGL14");
            }

            // Configure EGL for pbuffer and OpenGL ES 2.0, 24-bit RGB.
            int[] attribList = {
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL10.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!mEgl.eglChooseConfig(mEGLDisplay, attribList, configs, configs.length,
                    numConfigs)) {
                throw new RuntimeException("unable to find RGB888+recordable ES2 EGL config");
            }

            // Configure context for OpenGL ES 2.0.
            int[] attrib_list = {
                    EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL10.EGL_NONE
            };

            EGLContext sharedContext = SharedEGLContext.GetEGLContext();
            mEGLContext = mEgl.eglCreateContext(mEGLDisplay, configs[0], sharedContext, attrib_list);
            checkEglError("eglCreateContext");
            if (mEGLContext == null) {
                throw new RuntimeException("null context");
            }

            // Create a pbuffer surface.
            int[] surfaceAttribs = {
                    EGL10.EGL_WIDTH, mWidth,
                    EGL10.EGL_HEIGHT, mHeight,
                    EGL10.EGL_NONE
            };
            mEGLSurface = mEgl.eglCreatePbufferSurface(mEGLDisplay, configs[0], surfaceAttribs);
            checkEglError("eglCreatePbufferSurface");
            if (mEGLSurface == null) {
                throw new RuntimeException("surface was null");
            }
        }

        /**
         * Discard all resources held by this class, notably the EGL context.
         */
        public void release() {
            if (mEGLDisplay != EGL10.EGL_NO_DISPLAY) {
                mEgl.eglDestroySurface(mEGLDisplay, mEGLSurface);
                mEgl.eglDestroyContext(mEGLDisplay, mEGLContext);
                //mEgl.eglReleaseThread();
                mEgl.eglMakeCurrent(mEGLDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE,
                        EGL10.EGL_NO_CONTEXT);
                mEgl.eglTerminate(mEGLDisplay);
            }
            mEGLDisplay = EGL10.EGL_NO_DISPLAY;
            mEGLContext = EGL10.EGL_NO_CONTEXT;
            mEGLSurface = EGL10.EGL_NO_SURFACE;

            mSurface.release();

            // this causes a bunch of warnings that appear harmless but might confuse someone:
            //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
            //mSurfaceTexture.release();

            mTextureRender = null;
            mSurface = null;
            mSurfaceTexture = null;
        }

        /**
         * Makes our EGL context and surface current.
         */
        public void makeCurrent() {
            if (!mEgl.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
                throw new RuntimeException("eglMakeCurrent failed");
            }
        }

        /**
         * Returns the Surface.
         */
        public Surface getSurface() {
            return mSurface;
        }

        public int getTextureId() {
            if (mTextureRender != null) {
                return mTextureRender.getTextureId();
            }
            return -1;
        }

        /**
         * Latches the next buffer into the texture.  Must be called from the thread that created
         * the CodecOutputSurface object.  (More specifically, it must be called on the thread
         * with the EGLContext that contains the GL texture object used by SurfaceTexture.)
         */
        public void awaitNewImage() {
            final int TIMEOUT_MS = 100;

            synchronized (mFrameSyncObject) {
                while (!mFrameAvailable) {
                    try {
                        // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                        // stalling the test if it doesn't arrive.
                        mFrameSyncObject.wait(TIMEOUT_MS);
                        if (!mFrameAvailable) {
                            // TODO: if "spurious wakeup", continue while loop
                            //throw new RuntimeException("frame wait timed out");
                            return;
                        }
                    } catch (InterruptedException ie) {
                        // shouldn't happen
                        throw new RuntimeException(ie);
                    }
                }
                mFrameAvailable = false;
            }

            // Latch the data.
            mTextureRender.checkGlError("before updateTexImage");
            mSurfaceTexture.updateTexImage();
        }

        /**
         * Draws the data from SurfaceTexture onto the current EGL surface.
         *
         * @param invert if set, render the image with Y inverted (0,0 in top left)
         */
        public void drawImage(boolean invert) {
            mTextureRender.drawFrame(mSurfaceTexture, invert);
        }

        // SurfaceTexture callback
        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            Log.i(TAG, "new frame available");
            synchronized (mFrameSyncObject) {
                if (mFrameAvailable) {
                    throw new RuntimeException("mFrameAvailable already set, frame could be dropped");
                }
                mFrameAvailable = true;
                mFrameSyncObject.notifyAll();
            }
        }

        /**
         * Saves the current frame to disk as a PNG image.
         */
        public void saveFrame(String filename) throws IOException {
            // glReadPixels gives us a ByteBuffer filled with what is essentially big-endian RGBA
            // data (i.e. a byte of red, followed by a byte of green...).  To use the Bitmap
            // constructor that takes an int[] array with pixel data, we need an int[] filled
            // with little-endian ARGB data.
            //
            // If we implement this as a series of buf.get() calls, we can spend 2.5 seconds just
            // copying data around for a 720p frame.  It's better to do a bulk get() and then
            // rearrange the data in memory.  (For comparison, the PNG compress takes about 500ms
            // for a trivial frame.)
            //
            // So... we set the ByteBuffer to little-endian, which should turn the bulk IntBuffer
            // get() into a straight memcpy on most Android devices.  Our ints will hold ABGR data.
            // Swapping B and R gives us ARGB.  We need about 30ms for the bulk get(), and another
            // 270ms for the color swap.
            //
            // We can avoid the costly B/R swap here if we do it in the fragment shader (see
            // http://stackoverflow.com/questions/21634450/ ).
            //
            // Having said all that... it turns out that the Bitmap#copyPixelsFromBuffer()
            // method wants RGBA pixels, not ARGB, so if we create an empty bitmap and then
            // copy pixel data in we can avoid the swap issue entirely, and just copy straight
            // into the Bitmap from the ByteBuffer.
            //
            // Making this even more interesting is the upside-down nature of GL, which means
            // our output will look upside-down relative to what appears on screen if the
            // typical GL conventions are used.  (For ExtractMpegFrameTest, we avoid the issue
            // by inverting the frame when we render it.)
            //
            // Allocating large buffers is expensive, so we really want mPixelBuf to be
            // allocated ahead of time if possible.  We still get some allocations from the
            // Bitmap / PNG creation.

            mPixelBuf.rewind();
            GLES20.glReadPixels(0, 0, mWidth, mHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    mPixelBuf);

            BufferedOutputStream bos = null;
            try {
                bos = new BufferedOutputStream(new FileOutputStream(filename));
                Bitmap bmp = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
                mPixelBuf.rewind();
                bmp.copyPixelsFromBuffer(mPixelBuf);
                bmp.compress(Bitmap.CompressFormat.PNG, 90, bos);
                bmp.recycle();
            } finally {
                if (bos != null) bos.close();
            }
            Log.d(TAG, "Saved " + mWidth + "x" + mHeight + " frame as '" + filename + "'");
        }

        /**
         * Checks for EGL errors.
         */
        private void checkEglError(String msg) {
            int error;
            if ((error = mEgl.eglGetError()) != EGL10.EGL_SUCCESS) {
                throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
            }
        }
    }


    ////////////////////////////////////////////////////////////////////////////////////////////////
    /**
     * Code for rendering a texture onto a surface using OpenGL ES 2.0.
     */
    private static class STextureRender {
        private static final String TAG = "STextureRender";
        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
        private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
        private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
        private final float[] mTriangleVerticesData = {
                // X, Y, Z, U, V
                -1.0f, -1.0f, 0, 0.f, 0.f,
                1.0f, -1.0f, 0, 1.f, 0.f,
                -1.0f,  1.0f, 0, 0.f, 1.f,
                1.0f,  1.0f, 0, 1.f, 1.f,
        };

        private FloatBuffer mTriangleVertices;

        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n" +
                        "uniform mat4 uSTMatrix;\n" +
                        "attribute vec4 aPosition;\n" +
                        "attribute vec4 aTextureCoord;\n" +
                        "varying vec2 vTextureCoord;\n" +
                        "void main() {\n" +
                        "    gl_Position = uMVPMatrix * aPosition;\n" +
                        "    vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                        "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;\n" +      // highp here doesn't seem to matter
                        "varying vec2 vTextureCoord;\n" +
                        "uniform samplerExternalOES sTexture;\n" +
                        "void main() {\n" +
                        "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                        "}\n";

        private float[] mMVPMatrix = new float[16];
        private float[] mSTMatrix = new float[16];

        private int mProgram;
        private int mTextureID = -12345;
        private int muMVPMatrixHandle;
        private int muSTMatrixHandle;
        private int maPositionHandle;
        private int maTextureHandle;

        public STextureRender() {
            mTriangleVertices = ByteBuffer.allocateDirect(
                    mTriangleVerticesData.length * FLOAT_SIZE_BYTES)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            mTriangleVertices.put(mTriangleVerticesData).position(0);

            Matrix.setIdentityM(mSTMatrix, 0);
        }

        public int getTextureId() {
            return mTextureID;
        }

        /**
         * Draws the external texture in SurfaceTexture onto the current EGL surface.
         */
        public void drawFrame(SurfaceTexture st, boolean invert) {
            checkGlError("onDrawFrame start");
            st.getTransformMatrix(mSTMatrix);
            if (invert) {
                mSTMatrix[5] = -mSTMatrix[5];
                mSTMatrix[13] = 1.0f - mSTMatrix[13];
            }

            // (optional) clear to green so we can see if we're failing to set pixels
            GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(mProgram);
            checkGlError("glUseProgram");

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maPosition");
            GLES20.glEnableVertexAttribArray(maPositionHandle);
            checkGlError("glEnableVertexAttribArray maPositionHandle");

            mTriangleVertices.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mTriangleVertices);
            checkGlError("glVertexAttribPointer maTextureHandle");
            GLES20.glEnableVertexAttribArray(maTextureHandle);
            checkGlError("glEnableVertexAttribArray maTextureHandle");

            Matrix.setIdentityM(mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0);
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            checkGlError("glDrawArrays");

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }

        /**
         * Initializes GL state.  Call this after the EGL surface has been created and made current.
         */
        public void surfaceCreated() {
            mProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }

            maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
            checkLocation(maPositionHandle, "aPosition");
            maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
            checkLocation(maTextureHandle, "aTextureCoord");

            muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
            checkLocation(muMVPMatrixHandle, "uMVPMatrix");
            muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix");
            checkLocation(muSTMatrixHandle, "uSTMatrix");

            int[] textures = new int[1];
            textures[0] = 86;
            GLES20.glGenTextures(1, textures, 0);

            mTextureID = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID);
            checkGlError("glBindTexture mTextureID");

            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            checkGlError("glTexParameter");
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }

        /**
         * Replaces the fragment shader.  Pass in null to reset to default.
         */
        public void changeFragmentShader(String fragmentShader) {
            if (fragmentShader == null) {
                fragmentShader = FRAGMENT_SHADER;
            }
            GLES20.glDeleteProgram(mProgram);
            mProgram = createProgram(VERTEX_SHADER, fragmentShader);
            if (mProgram == 0) {
                throw new RuntimeException("failed creating program");
            }
        }

        private int loadShader(int shaderType, String source) {
            int shader = GLES20.glCreateShader(shaderType);
            checkGlError("glCreateShader type=" + shaderType);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader " + shaderType + ":");
                Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
                GLES20.glDeleteShader(shader);
                shader = 0;
            }
            return shader;
        }

        private int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            if (vertexShader == 0) {
                return 0;
            }
            int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            if (pixelShader == 0) {
                return 0;
            }

            int program = GLES20.glCreateProgram();
            if (program == 0) {
                Log.e(TAG, "Could not create program");
            }
            GLES20.glAttachShader(program, vertexShader);
            checkGlError("glAttachShader");
            GLES20.glAttachShader(program, pixelShader);
            checkGlError("glAttachShader");
            GLES20.glLinkProgram(program);
            int[] linkStatus = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
            if (linkStatus[0] != GLES20.GL_TRUE) {
                Log.e(TAG, "Could not link program: ");
                Log.e(TAG, GLES20.glGetProgramInfoLog(program));
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            return program;
        }

        public void checkGlError(String op) {
            int error;
            while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
                Log.e(TAG, op + ": glError " + error);
                throw new RuntimeException(op + ": glError " + error);
            }
        }

        public static void checkLocation(int location, String label) {
            if (location < 0) {
                throw new RuntimeException("Unable to locate '" + label + "' in program");
            }
        }
    }
}
