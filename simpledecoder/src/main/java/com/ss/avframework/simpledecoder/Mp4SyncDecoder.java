package com.ss.avframework.simpledecoder;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Mp4SyncDecoder {

    public final String TAG = "Mp4SyncDecoder";

    private String mMp4FilePath;
    private Context mContext;
    private Surface mSurface;
    private MediaExtractor mExtractor;
    private MediaCodec mVideoDecoder = null;
    private MediaCodec mAudioDecoder = null;
    private AudioTrack mAudioTrack = null;
    private boolean stopped = false;

    public Mp4SyncDecoder(String mp4FilePath, Context context, Surface surface) {
        mMp4FilePath = mp4FilePath;
        mContext = context;
        mSurface = surface;

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

    @SuppressLint("WrongConstant")
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

        if (audioMediaFormat != null) {
            int sampleRate = audioMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = audioMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int channelConfig = (channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO);
            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize < minBufferSize * 3)
                bufferSize = minBufferSize * 3;

            if (mContext != null) {
                AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                int audioSessionId = audioManager.generateAudioSessionId();

                AudioAttributes audioAttributes = (new AudioAttributes.Builder())
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        //.setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        //.setFlags(AudioAttributes.FLAG_HW_AV_SYNC)
                        .build();
                AudioFormat audioFormat = (new AudioFormat.Builder())
                        .setChannelMask(channelConfig)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .build();
                mAudioTrack = new AudioTrack(
                        audioAttributes,
                        audioFormat,
                        minBufferSize * 3,
                        AudioTrack.MODE_STREAM,
                        audioSessionId);
            }
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
        }
        if (audioMediaFormat != null) {
            String mime = audioMediaFormat.getString(MediaFormat.KEY_MIME);
            mAudioDecoder = MediaCodec.createDecoderByType(mime);
            Log.i(TAG, "Audio MediaFormat: " + audioMediaFormat.toString());
            mAudioDecoder.configure(audioMediaFormat, null, null, 0);
            mAudioDecoder.start();
        }

        ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
        int sampleSize = 0;
        while ((sampleSize = mExtractor.readSampleData(readBuffer, 0)) >= 0 && !stopped) {
            int trackIndex = mExtractor.getSampleTrackIndex();
            long ptsUs = mExtractor.getSampleTime();
            if (trackIndex == videoTrackId) {
                Log.i(TAG, "Video sample: size " + sampleSize + " bytes, pts " + (ptsUs / 1000) + "ms");
                int inputBufferIndex = mVideoDecoder.dequeueInputBuffer(-1); //infinitely wait if no available input buffer
                if (inputBufferIndex >= 0) {
                    byte[] b = new byte[readBuffer.remaining()];
                    readBuffer.get(b, 0, b.length);
                    ByteBuffer inputBuffer = mVideoDecoder.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    inputBuffer.position(0);
                    inputBuffer.put(b, 0, b.length);
                    mVideoDecoder.queueInputBuffer(inputBufferIndex, 0, b.length, ptsUs, 0);
                    Log.i(TAG, "Video input: read buffer size " + readBuffer.remaining() +
                            ", buffer index " + inputBufferIndex + ", buffer size " + b.length + ", pts " + (ptsUs / 1000) + "ms");
                }
                int outputBufferIndex = -1;
                do {
                    BufferInfo info = new BufferInfo();
                    outputBufferIndex = mVideoDecoder.dequeueOutputBuffer(info, 0);
                    if (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = mVideoDecoder.getOutputBuffer(outputBufferIndex);
                        Log.i(TAG, "Video output: size " + info.size + ", pts " + (info.presentationTimeUs / 1000) + "ms, buffer size " + outputBuffer.remaining());
                        Thread.sleep(33);
                        outputBuffer.clear();
                        outputBuffer.position(0);
                        mVideoDecoder.releaseOutputBuffer(outputBufferIndex, true);
                    }
                    else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newOutputFormat = mVideoDecoder.getOutputFormat();
                        Log.i(TAG, "Video output format changed to: " + newOutputFormat.toString());
                    }
                } while (outputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER && !stopped);
            } else if (trackIndex == audioTrackId) {
                Log.i(TAG, "Audio sample: size " + sampleSize + " bytes, pts " + (ptsUs / 1000) + "ms");
                int inputBufferIndex = mAudioDecoder.dequeueInputBuffer(-1); //infinitely wait if no available input buffer
                if (inputBufferIndex >= 0) {
                    byte[] b = new byte[readBuffer.remaining()];
                    readBuffer.get(b, 0, b.length);
                    ByteBuffer inputBuffer = mAudioDecoder.getInputBuffer(inputBufferIndex);
                    inputBuffer.clear();
                    inputBuffer.position(0);
                    inputBuffer.put(b, 0, b.length);
                    mAudioDecoder.queueInputBuffer(inputBufferIndex, 0, b.length, ptsUs, 0);
                    Log.i(TAG, "Audio input: read buffer size " + readBuffer.remaining() +
                            ", buffer index " + inputBufferIndex + ", buffer size " + b.length + ", pts " + (ptsUs / 1000) + "ms");
                }
                int outputBufferIndex = -1;
                do {
                    BufferInfo info = new BufferInfo();
                    outputBufferIndex = mAudioDecoder.dequeueOutputBuffer(info, 0);
                    if (outputBufferIndex >= 0) {
                        ByteBuffer outputBuffer = mAudioDecoder.getOutputBuffer(outputBufferIndex);
                        Log.i(TAG, "Audio output: size " + info.size + ", pts " + (info.presentationTimeUs / 1000) + "ms, buffer size " + outputBuffer.remaining());
//                        ByteBuffer avSyncHeader = ByteBuffer.allocate(16);
//                        avSyncHeader.order(ByteOrder.BIG_ENDIAN);
//                        avSyncHeader.putInt(0x55550001);
//                        avSyncHeader.putInt(info.size);
//                        avSyncHeader.putLong(info.presentationTimeUs * 1000);
//                        avSyncHeader.position(0);
//                        mAudioTrack.write(avSyncHeader, 16, AudioTrack.WRITE_BLOCKING);
//                        mAudioTrack.write(outputBuffer, info.size, AudioTrack.WRITE_BLOCKING);
                        outputBuffer.clear();
                        outputBuffer.position(0);
                        mAudioDecoder.releaseOutputBuffer(outputBufferIndex, true);
                    }
                    else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newOutputFormat = mAudioDecoder.getOutputFormat();
                        Log.i(TAG, "Audio output format changed to: " + newOutputFormat.toString());
                    }
                } while (outputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER && !stopped);
            } else {
                Log.i(TAG, "Unknown track id: " + trackIndex);
            }
            mExtractor.advance();
        }

        if (mAudioDecoder != null) {
            mAudioDecoder.stop();
            mAudioDecoder.release();
        }
        if (mVideoDecoder != null) {
            mVideoDecoder.stop();
            mVideoDecoder.release();
        }
        mExtractor.release();
        mExtractor = null;
    }
}
