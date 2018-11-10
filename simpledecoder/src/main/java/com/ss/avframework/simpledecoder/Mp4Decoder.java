package com.ss.avframework.simpledecoder;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;

public class Mp4Decoder {

    public final String TAG = "Mp4Decoder";

    private String mMp4FilePath;
    private Surface mSurface;
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

    public void start(String mp4FilePath, boolean circularly, Surface surface) {
        mMp4FilePath = mp4FilePath;
        mCircularly = circularly;
        mSurface = surface;

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
        });
        mDemuxThread.start();
    }

    public void stop() {
        paused = false;
        stopped = true;
        try {
            mDemuxThread.join();
        } catch (java.lang.InterruptedException e) {
            e.printStackTrace();
        }
        videoBufferList = null;
        audioBufferList = null;
        videoBufferSync = null;
        audioBufferSync = null;

        mMp4FilePath = null;
        mCircularly = false;
        mSurface = null;
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
        mSurface = surface;
        if (mVideoDecoder != null && !stopped) {
            mVideoDecoder.setOutputSurface(mSurface);
        }
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
            String mime = mVideoMediaFormat.getString(MediaFormat.KEY_MIME);
            mVideoDecoder = MediaCodec.createDecoderByType(mime);
            Log.i(TAG, "Video MediaFormat: " + mVideoMediaFormat.toString());
            mVideoDecoder.configure(mVideoMediaFormat, mSurface, null, 0);
            mVideoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            mVideoDecoder.start();

            videoClock = new SyncClock();
            if (mAudioMediaFormat == null) {
                primaryClock = videoClock;
            }

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

            String mime = mAudioMediaFormat.getString(MediaFormat.KEY_MIME);
            mAudioDecoder = MediaCodec.createDecoderByType(mime);
            Log.i(TAG, "Audio MediaFormat: " + mAudioMediaFormat.toString());
            mAudioDecoder.configure(mAudioMediaFormat, null, null, 0);
            mAudioDecoder.start();

            audioClock = new SyncClock();
            primaryClock = audioClock;

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
                    break; //exit immediately
                } else {
                    //reset the decoders and restart the threads
                    if (mVideoMediaFormat != null && mVideoDecoder != null) {
                        mVideoDecoder.reset();
                        mVideoDecoder.configure(mVideoMediaFormat, mSurface, null, 0);
                        mVideoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                        mVideoDecoder.start();
                        CreateVideoIOThreads();
                    }
                    if (mAudioMediaFormat != null && mAudioDecoder != null) {
                        mAudioDecoder.reset();
                        mAudioDecoder.configure(mAudioMediaFormat, null, null, 0);
                        mAudioDecoder.start();
                        CreateAudioIOThreads();
                    }
                    //seek to the head of the file and continue reading
                    mExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    continue;
                }
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

        if (mVideoDecoder != null) {
            mVideoDecoder.stop();
        }
        if (mAudioDecoder != null) {
            mAudioDecoder.stop();
        }

        JoinVideoIOThreads();
        JoinAudioIOThreads();

        if (mVideoDecoder != null) {
            mVideoDecoder.release();
            mVideoDecoder = null;
        }
        if (mAudioDecoder != null) {
            mAudioDecoder.release();
            mAudioDecoder = null;
        }

        Log.i(TAG, "Demux thread exit");
    }

    private void CreateVideoIOThreads() {
        mVideoInputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    VideoDecoderInput();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        mVideoInputThread.start();

        mVideoOutputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    VideoDecoderOutput();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        mVideoOutputThread.start();
    }

    private void CreateAudioIOThreads() {
        mAudioInputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AudioDecoderInput();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        mAudioInputThread.start();

        mAudioOutputThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    AudioDecoderOutput();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        mAudioOutputThread.start();
    }

    private void JoinVideoIOThreads() {
        try {
            if (mVideoInputThread != null) {
                mVideoInputThread.join();
                mVideoInputThread = null;
            }
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
            if (mAudioInputThread != null) {
                mAudioInputThread.join();
                mAudioInputThread = null;
            }
            if (mAudioOutputThread != null) {
                mAudioOutputThread.join();
                mAudioOutputThread = null;
            }
        } catch (java.lang.InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void VideoDecoderInput() throws Exception {
        while (mVideoDecoder != null && !stopped) {
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
        while (mAudioDecoder != null && !stopped) {
            if (audioBufferList.size() <= 0) {
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

    @SuppressLint("WrongConstant")
    private void VideoDecoderOutput() throws Exception {
        while (!stopped) {
            if (paused) {
                Thread.sleep(20);
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
                int width = videoOutputFmt.getInteger(MediaFormat.KEY_WIDTH);
                if (videoOutputFmt.containsKey("crop-left") && videoOutputFmt.containsKey("crop-right")) {
                    width = videoOutputFmt.getInteger("crop-right") + 1 - videoOutputFmt.getInteger("crop-left");
                }
                int height = videoOutputFmt.getInteger(MediaFormat.KEY_HEIGHT);
                if (videoOutputFmt.containsKey("crop-top") && videoOutputFmt.containsKey("crop-bottom")) {
                    height = videoOutputFmt.getInteger("crop-bottom") + 1 - videoOutputFmt.getInteger("crop-top");
                }
                if (mVideoFrameListener != null && mSurface == null) {
                    mVideoFrameListener.onVideoFrameAvailable(outputBuffer, null, width, height, System.currentTimeMillis());
                }
                outputBuffer.position(0);
                outputBuffer.clear();
                mVideoDecoder.releaseOutputBuffer(outputBufferIndex, true);
                if (mVideoFrameListener != null && mSurface != null) {
                    mVideoFrameListener.onVideoFrameAvailable(null, mSurface, width, height, System.currentTimeMillis());
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newOutputFormat = mVideoDecoder.getOutputFormat();
                Log.i(TAG, "Video output format changed to: " + newOutputFormat.toString());
                mVideoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Thread.sleep(5);
            }
        }
        if (videoClock != null) {
            videoClock.Reset();
        }
        Log.i(TAG, "VideoDecoderOutput thread exit");
    }

    @SuppressLint("WrongConstant")
    private void AudioDecoderOutput() throws Exception {
        while (!stopped) {
            if (paused) {
                Thread.sleep(20);
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
                    int sampleCount = info.size / (16 / 8) / channelCount;
                    mAudioSampleListener.onAudioSampleAvailable(outputBuffer,
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
        if (audioClock != null) {
            audioClock.Reset();
        }
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
        void onVideoFrameAvailable(ByteBuffer data, Surface surface, int width, int height, long timestampMs);
    }

    public interface IAudioSampleListener {
        void onAudioSampleAvailable(ByteBuffer data, int sampleRate, int channelCount, int bitsPerSample, int sampleCount, long timestampMs);
    }
}
