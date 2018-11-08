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
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import com.ss.avframework.simpledecoder.Mp4Decoder;

public class Mp4DecodeTest extends AppCompatActivity {

    public final String TAG = "Mp4DecodeTest";

    private Mp4Decoder mMp4Decoder;

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        int permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 18888);
        }

        Button chooseFile = (Button)findViewById(R.id.button1);
        chooseFile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setType("*/*");
                intent.setAction(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, 1);
            }
        });


        Button startDecode = (Button)findViewById(R.id.button);
        startDecode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SurfaceView surfaceView = (SurfaceView)findViewById(R.id.surfaceView);
                EditText edit = (EditText)findViewById(R.id.editext);
                mMp4Decoder = new Mp4Decoder(edit.getText().toString(), true, surfaceView.getHolder().getSurface(), null, null);
                Log.i(TAG, "MP4 decoder created.");
            }
        });
    }

    @Override
    public void onDestroy() {
        if (mMp4Decoder != null) {
            mMp4Decoder.stop();
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
}
