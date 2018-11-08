package com.ss.avframework.simpledecoder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

public class Mp4Decoder {

    public final String TAG = "Mp4Decoder";

    private String mMp4FilePath;
    private Surface mSurface;
    private MediaExtractor mExtractor;
    private MediaCodec mVideoDecoder = null;
    private MediaCodec mAudioDecoder = null;
    private boolean mCircularly = false;
    private boolean stopped = false;
    private SyncClock primaryClock;
    private SyncClock videoClock;
    private SyncClock audioClock;
    private long mLastPtsMs = Long.MIN_VALUE;
    private long mBasePtsMs = 0;
    private ArrayList<PacketBuffer> videoBufferList;
    private ArrayList<PacketBuffer> audioBufferList;
    private final int maxVideoBufferCount = 100;
    private final int maxAudioBufferCount = 100;
    private volatile Object videoBufferSync;
    private volatile Object audioBufferSync;
    private IVideoFrameListener mVideoFrameListener;
    private IAudioSampleListener mAudioSampleListener;

    public Mp4Decoder(String mp4FilePath, boolean circularly, Surface surface,
                      IVideoFrameListener videoFrameListener,
                      IAudioSampleListener audioSampleListener) {
        mMp4FilePath = mp4FilePath;
        mCircularly = circularly;
        mSurface = surface;
        mVideoFrameListener = videoFrameListener;
        mAudioSampleListener = audioSampleListener;

        videoBufferList = new ArrayList<PacketBuffer>();
        audioBufferList = new ArrayList<PacketBuffer>();
        videoBufferSync = new Object();
        audioBufferSync = new Object();

        new Thread(new Runnable() {
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
        }).start();
    }

    public void stop() {
        stopped = true;
    }

    private void DemuxMp4() throws Exception {
        int videoTrackId = -1;
        int audioTrackId = -1;
        int bufferSize = 0;

        MediaFormat videoMediaFormat = null;
        MediaFormat audioMediaFormat = null;

        mExtractor = new MediaExtractor();
        mExtractor.setDataSource(mMp4FilePath);

        for (int i = 0; i < mExtractor.getTrackCount(); ++i) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            Log.i(TAG, "Find track type: " + mime);
            if (mime.compareTo(MediaFormat.MIMETYPE_VIDEO_AVC) == 0) {
                videoTrackId = i;
                videoMediaFormat = format;
                mExtractor.selectTrack(videoTrackId);
            }
            else if (mime.compareTo(MediaFormat.MIMETYPE_AUDIO_AAC) == 0) {
                audioTrackId = i;
                audioMediaFormat = format;
                mExtractor.selectTrack(audioTrackId);
            }
        }

        if (audioMediaFormat == null && videoMediaFormat == null) {
            throw new Exception("No supported track.");
        }

        if (videoMediaFormat != null) {
            int wxh = videoMediaFormat.getInteger(MediaFormat.KEY_WIDTH) * videoMediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
            if (bufferSize < wxh * 2)
                bufferSize = wxh * 2;
            String mime = videoMediaFormat.getString(MediaFormat.KEY_MIME);
            mVideoDecoder = MediaCodec.createDecoderByType(mime);
            Log.i(TAG, "Video MediaFormat: " + videoMediaFormat.toString());
            mVideoDecoder.configure(videoMediaFormat, mSurface, null, 0);
            mVideoDecoder.start();

            videoClock = new SyncClock();
            if (audioMediaFormat == null) {
                primaryClock = videoClock;
            }

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        VideoDecoderInput();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        VideoDecoderOutput();
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (mVideoDecoder == null) {
                            mVideoDecoder.stop();
                            mVideoDecoder.release();
                            mVideoDecoder = null;
                        }
                    }
                }
            }).start();
        }
        if (audioMediaFormat != null) {
            int sampleRate = audioMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = audioMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int channelConfig = (channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO);
            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize < minBufferSize * 3)
                bufferSize = minBufferSize * 3;

            String mime = audioMediaFormat.getString(MediaFormat.KEY_MIME);
            mAudioDecoder = MediaCodec.createDecoderByType(mime);
            Log.i(TAG, "Audio MediaFormat: " + audioMediaFormat.toString());
            mAudioDecoder.configure(audioMediaFormat, null, null, 0);
            mAudioDecoder.start();

            audioClock = new SyncClock();
            primaryClock = audioClock;

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        AudioDecoderInput();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        AudioDecoderOutput();
                    } catch (Exception e) {
                        e.printStackTrace();
                        if (mAudioDecoder == null) {
                            mAudioDecoder.stop();
                            mAudioDecoder.release();
                            mAudioDecoder = null;
                        }
                    }
                }
            }).start();
        }

        ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
        int sampleSize = 0;
        while (((sampleSize = mExtractor.readSampleData(readBuffer, 0)) >= 0 || mCircularly) && !stopped) {
            if (mCircularly && sampleSize < 0 && mLastPtsMs != Long.MIN_VALUE) {
                mExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                mBasePtsMs += mLastPtsMs + 20;
                mLastPtsMs = Long.MIN_VALUE;
                continue;
            }
            int trackIndex = mExtractor.getSampleTrackIndex();
            long ptsUs = mExtractor.getSampleTime();
            mLastPtsMs = ptsUs / 1000;
            if (trackIndex == videoTrackId) {
                Log.i(TAG, "Video sample: size " + sampleSize + " bytes, pts " + (ptsUs / 1000) + "ms");
                while (videoBufferList.size() >= maxVideoBufferCount) {
                    Thread.sleep(10);
                }
                synchronized (videoBufferSync) {
                    PacketBuffer packet = new PacketBuffer();
                    packet.data = new byte[sampleSize];
                    readBuffer.get(packet.data, 0, sampleSize);
                    packet.pts = ptsUs + mBasePtsMs * 1000;
                    videoBufferList.add(packet);
                }
            } else if (trackIndex == audioTrackId) {
                Log.i(TAG, "Audio sample: size " + sampleSize + " bytes, pts " + (ptsUs / 1000) + "ms");
                while (audioBufferList.size() >= maxAudioBufferCount) {
                    Thread.sleep(10);
                }
                synchronized (audioBufferSync) {
                    PacketBuffer packet = new PacketBuffer();
                    packet.data = new byte[sampleSize];
                    readBuffer.get(packet.data, 0, sampleSize);
                    packet.pts = ptsUs + mBasePtsMs * 1000;
                    audioBufferList.add(packet);
                }
            } else {
                Log.i(TAG, "Unknown track id: " + trackIndex);
            }
            mExtractor.advance();
        }

        //在videoBufferList和audioBufferList中分别添加一个结束包
        PacketBuffer packet = new PacketBuffer();
        packet.data = null;
        packet.pts = Long.MIN_VALUE;
        videoBufferList.add(packet);
        audioBufferList.add(packet);

        mExtractor.release();
        mExtractor = null;
    }

    private void VideoDecoderInput() throws Exception {
        while (mVideoDecoder != null && !stopped) {
            if (videoBufferList.size() <= 0) {
                Thread.sleep(10);
                continue;
            }
            int inputBufferIndex = mVideoDecoder.dequeueInputBuffer(-1); //infinitely wait if no available input buffer
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mVideoDecoder.getInputBuffer(inputBufferIndex);
                synchronized (videoBufferSync) {
                    PacketBuffer packet = videoBufferList.remove(0);
                    if (packet.data == null && packet.pts == Long.MIN_VALUE) { //end of stream
                        mVideoDecoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        break;
                    }

                    inputBuffer.put(packet.data, 0, packet.data.length);
                    mVideoDecoder.queueInputBuffer(inputBufferIndex, 0, packet.data.length, packet.pts, 0);
                    Log.i(TAG, "Video input: buffer index " + inputBufferIndex + ", buffer size " + packet.data.length + ", pts " + (packet.pts / 1000) + "ms");
                }
            }
        }
    }

    private void AudioDecoderInput() throws Exception {
        while (mAudioDecoder != null && !stopped) {
            if (audioBufferList.size() <= 0) {
                Thread.sleep(10);
                continue;
            }
            int inputBufferIndex = mAudioDecoder.dequeueInputBuffer(-1); //infinitely wait if no available input buffer
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mAudioDecoder.getInputBuffer(inputBufferIndex);
                synchronized (audioBufferSync) {
                    PacketBuffer packet = audioBufferList.remove(0);
                    if (packet.data == null && packet.pts == Long.MIN_VALUE) { //end of stream
                        mAudioDecoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        break;
                    }

                    inputBuffer.put(packet.data, 0, packet.data.length);
                    mAudioDecoder.queueInputBuffer(inputBufferIndex, 0, packet.data.length, packet.pts, 0);
                    Log.i(TAG, "Audio input: buffer index " + inputBufferIndex + ", buffer size " + packet.data.length + ", pts " + (packet.pts / 1000) + "ms");
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    private void VideoDecoderOutput() throws Exception {
        while (!stopped) {
            BufferInfo info = new BufferInfo();
            int outputBufferIndex = mVideoDecoder.dequeueOutputBuffer(info, 0);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
            if (outputBufferIndex >= 0) {
                boolean shouldDrop = CompareClockAndSleep(info.presentationTimeUs / 1000, videoClock, primaryClock);
                if (shouldDrop) {
                    mVideoDecoder.releaseOutputBuffer(outputBufferIndex, false);
                    continue;
                }
                ByteBuffer outputBuffer = mVideoDecoder.getOutputBuffer(outputBufferIndex);
                final MediaFormat videoOutputFmt = mVideoDecoder.getOutputFormat(outputBufferIndex);
                Log.i(TAG, "Video output: size " + info.size + ", pts " + (info.presentationTimeUs / 1000) + "ms, buffer size " + outputBuffer.position());
                if (mVideoFrameListener != null && mSurface == null) {
                    mVideoFrameListener.onVideoFrameAvailable(outputBuffer, null,
                            videoOutputFmt.getInteger(MediaFormat.KEY_WIDTH),
                            videoOutputFmt.getInteger(MediaFormat.KEY_HEIGHT),
                            videoClock.sysTimeMs);
                }
                outputBuffer.position(0);
                outputBuffer.clear();
                mVideoDecoder.releaseOutputBuffer(outputBufferIndex, true);
                if (mVideoFrameListener != null && mSurface != null) {
                    mVideoFrameListener.onVideoFrameAvailable(null, mSurface,
                            videoOutputFmt.getInteger(MediaFormat.KEY_WIDTH),
                            videoOutputFmt.getInteger(MediaFormat.KEY_HEIGHT),
                            videoClock.sysTimeMs);
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat newOutputFormat = mVideoDecoder.getOutputFormat();
                Log.i(TAG, "Video output format changed to: " + newOutputFormat.toString());
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Thread.sleep(5);
            }
        }

        mVideoDecoder.stop();
        mVideoDecoder.release();
        mVideoDecoder = null;
    }

    @SuppressLint("WrongConstant")
    private void AudioDecoderOutput() throws Exception {
        while (!stopped) {
            BufferInfo info = new BufferInfo();
            int outputBufferIndex = mAudioDecoder.dequeueOutputBuffer(info, 0);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
            if (outputBufferIndex >= 0) {
                boolean shouldDrop = CompareClockAndSleep(info.presentationTimeUs / 1000, audioClock, primaryClock);
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

        mAudioDecoder.stop();
        mAudioDecoder.release();
        mAudioDecoder = null;
    }

    private class PacketBuffer {
        public byte[] data;
        public long pts;
    }

    private class SyncClock {
        public long ptsMs = Long.MIN_VALUE;
        public long sysTimeMs = Long.MIN_VALUE;

        public boolean Uninitialized() {
            return (ptsMs == Long.MIN_VALUE && sysTimeMs == Long.MIN_VALUE);
        }
    }

    private boolean CompareClockAndSleep(long ptsMs, SyncClock currentClock, SyncClock primaryClock) throws Exception {
        long sysTimeMs = System.currentTimeMillis();
        if (primaryClock.Uninitialized()) {
            if (currentClock == primaryClock) {
                primaryClock.ptsMs = ptsMs;
                primaryClock.sysTimeMs = sysTimeMs;
                return false;
            } else {
                return true; //should drop this frame
            }
        }
        long sleepMs = (ptsMs - primaryClock.ptsMs) - (sysTimeMs - primaryClock.sysTimeMs);
        if (sleepMs > 0) { //It's not time for rendering this frame, sleep for a while
            Thread.sleep(sleepMs);
        } //else { // render this frame immediately }
        currentClock.sysTimeMs = primaryClock.sysTimeMs + ptsMs - primaryClock.ptsMs;
        currentClock.ptsMs = ptsMs;
        return false;
    }

    public interface IVideoFrameListener {
        void onVideoFrameAvailable(ByteBuffer data, Surface surface, int width, int height, long timestamp);
    }

    public interface IAudioSampleListener {
        void onAudioSampleAvailable(ByteBuffer data, int sampleRate, int channelCount, int bitsPerSample, int sampleCount, long timestamp);
    }
}
