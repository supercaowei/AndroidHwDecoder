package com.ss.avframework.androidhwdecoder;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.ss.avframework.simpledecoder.Mp4Decoder;

import java.nio.ByteBuffer;

public class Mp4DecodeTest extends AppCompatActivity implements SurfaceHolder.Callback, Mp4Decoder.IVideoFrameListener, Mp4Decoder.IAudioSampleListener {

    public final String TAG = "Mp4DecodeTest";

    private Mp4Decoder mMp4Decoder;
    private Surface mSurface;
    private String mMediaFilePath;
    private boolean started = false;

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        int permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 18888);
        }
        permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 18888);
        }

        final Button chooseFile = (Button)findViewById(R.id.btn_select_file);
        final Button startDecode = (Button)findViewById(R.id.btn_start_decode);
        final Button stopDecode = (Button)findViewById(R.id.btn_stop_decode);
        final Button pause = (Button)findViewById(R.id.btn_pause);
        final Button resume = (Button)findViewById(R.id.btn_resume);
        final SurfaceView surfaceView = (SurfaceView)findViewById(R.id.surfaceView);
        surfaceView.getHolder().addCallback(this);

        chooseFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setType("video/*;audio/*");
                intent.setAction(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, 1);
            }
        });

        startDecode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EditText edit = (EditText)findViewById(R.id.editext);
                mMediaFilePath = edit.getText().toString();
                if (mMediaFilePath.isEmpty() || mSurface == null) {
                    return;
                }
                mMp4Decoder = new Mp4Decoder(Mp4DecodeTest.this, Mp4DecodeTest.this);
                mMp4Decoder.start(mMediaFilePath, true, mSurface);
                started = true;
                Log.i(TAG, "MP4 decoding started.");
                startDecode.setEnabled(false);
                stopDecode.setEnabled(true);
                pause.setEnabled(true);
                resume.setEnabled(false);
            }
        });

        stopDecode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMp4Decoder != null) {
                    mMp4Decoder.stop();
                    mMp4Decoder = null;
                    started = false;
                    Log.i(TAG, "MP4 decoding stopped.");
                    startDecode.setEnabled(true);
                    stopDecode.setEnabled(false);
                    pause.setEnabled(false);
                    resume.setEnabled(false);
                }
            }
        });

        pause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMp4Decoder != null) {
                    mMp4Decoder.pause();
                    Log.i(TAG, "MP4 decoding paused.");
                    pause.setEnabled(false);
                    resume.setEnabled(true);
                }
            }
        });

        resume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mMp4Decoder != null) {
                    mMp4Decoder.resume();
                    Log.i(TAG, "MP4 decoding resumed.");
                    pause.setEnabled(true);
                    resume.setEnabled(false);
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        if (mMp4Decoder != null) {
            mMp4Decoder.stop();
            mMp4Decoder = null;
            started = false;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            Uri uri = data.getData();
            String path = UriToPathUtil.getRealFilePath(this, uri);
            EditText edit = (EditText)findViewById(R.id.editext);
            edit.setText(path);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder surfaceHolder) {
        mSurface = surfaceHolder.getSurface();
        if (mMp4Decoder != null && started) {
            mMp4Decoder.resetSurface(mSurface);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
        mSurface = surfaceHolder.getSurface();
        if (mMp4Decoder != null && started) {
            mMp4Decoder.resetSurface(mSurface);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
        if (mMp4Decoder != null && started) {
            mMp4Decoder.resetSurface(null);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
//        if (mMp4Decoder != null) {
//            mMp4Decoder.pause();
//        }
    }

    @Override
    public void onResume() {
//        if (mMp4Decoder != null) {
//            mMp4Decoder.resume();
//        }
        super.onResume();
    }

    @Override
    public void onVideoFrameDecoded(int textureId, int width, int height, int colorFormat, long timestampMs) {

    }

    @Override
    public void onAudioSampleDecoded(ByteBuffer data, int sampleRate, int channelCount, int bitsPerSample, int sampleCount, long timestampMs) {

    }
}
