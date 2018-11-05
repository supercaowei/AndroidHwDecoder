package com.ss.avframework.simpledecoder;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

import static android.media.MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback;

public class Mp4AsyncDecoder extends MediaCodec.Callback {

    public final String TAG = "Mp4AsyncDecoder";

    private String mMp4FilePath;
    private Context mContext;
    private Surface mSurface;
    private MediaExtractor mExtractor;
    private MediaCodec mVideoDecoder = null;
    private MediaCodec mAudioDecoder = null;
    private AudioTrack mAudioTrack = null;

    private ArrayList<PacketBuffer> videoBufferList;
    private ArrayList<PacketBuffer> audioBufferList;
    private final int maxVideoBufferCount = 100;
    private final int maxAudioBufferCount = 100;
    private volatile Object videoBufferSync;
    private volatile Object audioBufferSync;

    public Mp4AsyncDecoder(String mp4FilePath, Context context, Surface surface) {
        mMp4FilePath = mp4FilePath;
        mContext = context;
        mSurface = surface;

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

    private void DemuxMp4() throws Exception {
        int videoTrackId = -1;
        int audioTrackId = -1;
        int audioSessionId = -1;
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
                Log.i(TAG, "Video MediaFormat: " + videoMediaFormat.toString());
                mExtractor.selectTrack(videoTrackId);
            }
            else if (mime.compareTo(MediaFormat.MIMETYPE_AUDIO_AAC) == 0) {
                audioTrackId = i;
                audioMediaFormat = format;
                Log.i(TAG, "Audio MediaFormat: " + audioMediaFormat.toString());
                mExtractor.selectTrack(audioTrackId);
            }
        }

        if (audioMediaFormat == null && videoMediaFormat == null) {
            throw new Exception("No supported track.");
        }

        if (audioMediaFormat != null) {
            AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            audioSessionId = audioManager.generateAudioSessionId();

            int sampleRate = audioMediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = audioMediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int channelConfig = (channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO);
            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSize < minBufferSize * 3)
                bufferSize = minBufferSize * 3;

            AudioAttributes audioAttributes = (new AudioAttributes.Builder())
                    .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
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
        if (videoMediaFormat != null) {
            String mime = videoMediaFormat.getString(MediaFormat.KEY_MIME);
            boolean supportTunneledPlayback = MediaCodec.createDecoderByType(mime).getCodecInfo()
                    .getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback);
            if (supportTunneledPlayback) {
                videoMediaFormat.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback, true);
            }
            if (audioSessionId >= 0) {
                videoMediaFormat.setInteger(MediaFormat.KEY_AUDIO_SESSION_ID, audioSessionId);
            }
            int wxh = videoMediaFormat.getInteger(MediaFormat.KEY_WIDTH) * videoMediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
            if (bufferSize < wxh * 2)
                bufferSize = wxh * 2;
            mVideoDecoder = MediaCodec.createDecoderByType(mime);
            mVideoDecoder.configure(videoMediaFormat, mSurface, null, 0);
            mVideoDecoder.setCallback(this);
            mVideoDecoder.start();
        }
        if (audioMediaFormat != null) {
            String mime = audioMediaFormat.getString(MediaFormat.KEY_MIME);
            mAudioDecoder = MediaCodec.createDecoderByType(mime);
            mAudioDecoder.configure(audioMediaFormat, null, null, 0);
            mAudioDecoder.setCallback(this);
            mAudioDecoder.start();
        }

        ByteBuffer inputBuffer = ByteBuffer.allocate(bufferSize);
        int sampleSize = 0;
        while ((sampleSize = mExtractor.readSampleData(inputBuffer, 0)) >= 0) {
            int trackIndex = mExtractor.getSampleTrackIndex();
            long ptsUs = mExtractor.getSampleTime();
            int sampleFlags = mExtractor.getSampleFlags();
            if (trackIndex == videoTrackId) {
                Log.i(TAG, "Video sample: size " + sampleSize + " bytes, pts " + (ptsUs / 1000) + "ms, sample flags " + sampleFlags);
                while (videoBufferList.size() >= maxVideoBufferCount) {
                    Thread.sleep(10);
                }
                synchronized (videoBufferSync) {
                    PacketBuffer packet = new PacketBuffer();
                    packet.byteBuffer = inputBuffer.duplicate();
                    packet.pts = ptsUs;
                    packet.flags = sampleFlags;
                    videoBufferList.add(packet);
                }
            } else if (trackIndex == audioTrackId) {
                Log.i(TAG, "Audio sample: size " + sampleSize + " bytes, pts " + (ptsUs / 1000) + "ms, sample flags " + sampleFlags);
                while (audioBufferList.size() >= maxAudioBufferCount) {
                    Thread.sleep(10);
                }
                synchronized (audioBufferSync) {
                    PacketBuffer packet = new PacketBuffer();
                    packet.byteBuffer = inputBuffer.duplicate();
                    packet.pts = ptsUs;
                    packet.flags = sampleFlags;
                    audioBufferList.add(packet);
                }
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

    @Override
    public void onInputBufferAvailable(MediaCodec codec, int index) {
        if (index < 0) {
            return;
        }
        ByteBuffer inputBuffer = codec.getInputBuffer(index);
        if (codec == mVideoDecoder) {
            try {
                while (videoBufferList.size() <= 0) {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e){
                e.printStackTrace();
            }
            synchronized (videoBufferSync) {
                if (videoBufferList.size() > 0) {
                    PacketBuffer packet = videoBufferList.remove(0);
                    byte[] b = new byte[packet.byteBuffer.remaining()];
                    packet.byteBuffer.get(b, 0, b.length);
                    inputBuffer.clear();
                    inputBuffer.position(0);
                    inputBuffer.put(b, 0, b.length);
                    int bufferFlags = 0;
                    if ((packet.flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        bufferFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    if ((packet.flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                    }
                    if ((packet.flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                        bufferFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                    }
                    codec.queueInputBuffer(index, 0, b.length, packet.pts, bufferFlags);
                    Log.i(TAG, "Video input: read buffer size " + packet.byteBuffer.remaining() +
                            ", buffer index " + index + ", buffer size " + b.length + ", pts " + (packet.pts / 1000) + "ms, flags " + bufferFlags);
                }
            }
        } else {
            try {
                while (audioBufferList.size() <= 0) {
                    Thread.sleep(10);
                }
            } catch (InterruptedException e){
                e.printStackTrace();
            }
            synchronized (audioBufferSync) {
                if (audioBufferList.size() > 0) {
                    PacketBuffer packet = audioBufferList.remove(0);
                    byte[] b = new byte[packet.byteBuffer.remaining()];
                    packet.byteBuffer.get(b, 0, b.length);
                    inputBuffer.clear();
                    inputBuffer.position(0);
                    inputBuffer.put(b, 0, b.length);
                    int bufferFlags = 0;
                    if ((packet.flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        bufferFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
                    }
                    if ((packet.flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                    }
                    if ((packet.flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                        bufferFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
                    }
                    codec.queueInputBuffer(index, 0, b.length, packet.pts, bufferFlags);
                    Log.i(TAG, "Video input: read buffer size " + packet.byteBuffer.remaining() +
                            ", buffer index " + index + ", buffer size " + b.length + ", pts " + (packet.pts / 1000) + "ms, flags " + bufferFlags);
                }
            }
        }
    }

    @Override
    public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
        if (index < 0) {
            Log.i(TAG, "index " + index);
            return;
        }

        ByteBuffer outputBuffer = codec.getOutputBuffer(index);
        MediaFormat bufferFormat = codec.getOutputFormat(index); // option A

        if (codec == mVideoDecoder) {
            Log.i(TAG, "Video output: decoder " + codec.getName() + ", size " + info.size + ", pts " + (info.presentationTimeUs / 1000) + "ms, flags " + info.flags);
        } else {
            Log.i(TAG, "Audio output: decoder " + codec.getName() + ", size " + info.size + ", pts " + (info.presentationTimeUs / 1000) + "ms, flags " + info.flags);
        }

        if (codec == mAudioDecoder) {
            ByteBuffer avSyncHeader = ByteBuffer.allocate(16);
            avSyncHeader.order(ByteOrder.BIG_ENDIAN);
            avSyncHeader.putInt(0x55550001);
            avSyncHeader.putInt(info.size);
            avSyncHeader.putLong(info.presentationTimeUs * 1000);
            avSyncHeader.position(0);
            mAudioTrack.write(avSyncHeader, 16, AudioTrack.WRITE_BLOCKING);
            mAudioTrack.write(outputBuffer, info.size, AudioTrack.WRITE_BLOCKING);
            outputBuffer.clear();
            outputBuffer.position(0);
        }
        codec.releaseOutputBuffer(index, false);
//        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
//            codec.stop();
//            codec.release();
//            if (codec == mVideoDecoder) {
//                mVideoDecoder = null;
//            } else {
//                mAudioDecoder = null;
//            }
//        }
    }

    @Override
    public void onError(MediaCodec codec, MediaCodec.CodecException e) {
        Log.e(TAG, e.toString());
    }

    @Override
    public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
        if (codec == mVideoDecoder) {
            Log.e(TAG, "Video output format changed to " + format.getString(MediaFormat.KEY_MIME));
        } else {
            Log.e(TAG, "Audio output format changed to " + format.getString(MediaFormat.KEY_MIME));
        }
    }

    public class PacketBuffer {
        public ByteBuffer byteBuffer;
        public long pts;
        public int flags;
    }
}
