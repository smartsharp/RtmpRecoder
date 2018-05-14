package cn.campusapp.rtmprecorder;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

//import butterknife.ButterKnife;
//import butterknife.OnClick;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private static final String STREAM_URL = "rtmp://192.168.30.178/live/livestream";



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        //ButterKnife.bind(this);
        Button button = (Button)findViewById(R.id.record_btn);
        button.setOnClickListener(this);
        button = (Button)findViewById(R.id.record_btn2);
        button.setOnClickListener(this);
    }


    protected String getUrl(){
        return STREAM_URL;
    }

    @Override
    public void onClick(View v) {
        if(PermissionUtil.isGrantPermissionsRequired(this)) {
            if(v.getId()==R.id.record_btn) {
                startActivity(RecordActivity.makeIntent(STREAM_URL));
            }else {
                startActivity(RecordScreenActivity.makeIntent(STREAM_URL));
            }
        }else {
            Toast.makeText(this, "Permission requested.", Toast.LENGTH_SHORT).show();
        }
    }
}
