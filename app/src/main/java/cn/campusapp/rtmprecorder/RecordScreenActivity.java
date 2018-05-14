package cn.campusapp.rtmprecorder;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;


public class RecordScreenActivity extends AppCompatActivity implements OnClickListener {

    private final static String CLASS_LABEL = "zhanghb/RSActivity";
    private final static String LOG_TAG = CLASS_LABEL;
    private final static String TAG = CLASS_LABEL;

    private Button btnControl;
    private static final String KEY_STREAM_URL = "stream_url";

    String ffmpeg_link;
    RecordScreenService mService;
    MediaProjectionManager projectionManager;

    public static Intent makeIntent(String streamUrl){
        Intent intent = new Intent(App.getContext(), RecordScreenActivity.class);
        intent.putExtra(KEY_STREAM_URL, streamUrl);
        return intent;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ffmpeg_link = getIntent().getStringExtra(KEY_STREAM_URL);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        setContentView(R.layout.activity_record);

        btnControl = (Button) findViewById(R.id.recorder_control);
        btnControl.setText("Start");
        btnControl.setOnClickListener(this);
        btnControl.setEnabled(false);

        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent = new Intent(this, RecordScreenService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        unbindService(mConnection);
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            try {
                RecordScreenService.RecordScreenBinder binder =
                        (RecordScreenService.RecordScreenBinder) service;
                mService = binder.getRecordService();
            }catch (Exception e){
                Log.e(TAG, "onServiceConnected exception "+e+","+Log.getStackTraceString(e));
            }
            if(mService != null){
                btnControl.setEnabled(true);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };


    @Override
    public void onClick(View v) {
        Log.i(TAG, "onClick: "+mService.isRunning());
        if (!mService.isRunning()) {
            Intent captureIntent= projectionManager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, 1);
        } else {
            mService.stopRecord();
            btnControl.setText(R.string.start_record);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        //super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK) {
            DisplayMetrics mDisplayMetrics = new DisplayMetrics();//屏幕分辨率容器
            getWindowManager().getDefaultDisplay().getMetrics(mDisplayMetrics);
            int width = mDisplayMetrics.widthPixels;
            int height = mDisplayMetrics.heightPixels;
            float density = mDisplayMetrics.density;
            int densityDpi = mDisplayMetrics.densityDpi;
            Log.d(TAG,"Screen Ratio: ["+width+"x"+height+"],density="+density+",densityDpi="+densityDpi);
            mService.setDispParams(width, height, densityDpi);
            mService.setMediaProjection(projectionManager.getMediaProjection(resultCode, data));
            mService.startRecord();
            if (mService.isRunning()) {
                btnControl.setText(R.string.stop_record);
            }
        }
    }

}