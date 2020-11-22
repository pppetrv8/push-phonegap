package com.adobe.phonegap.push;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.trusted.care.local.R;

public class IncomingCallActivity extends Activity {

    public static final String VOIP_CONNECTED = "connected";
    public static final String VOIP_ACCEPT = "pickup";
    public static final String VOIP_DECLINE = "declined_callee";

    public static IncomingCallActivity instance = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        instance = this;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ((TextView) findViewById(R.id.tvCaller)).setText(getIntent().getExtras().getString("caller"));

        ((Button) findViewById(R.id.btnAccept)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                KeyguardManager.KeyguardLock keyguardLock = ((KeyguardManager) getApplicationContext().getSystemService(Context.KEYGUARD_SERVICE)).newKeyguardLock("IncomingCall");
                keyguardLock.disableKeyguard(); // to unlock the device

                Intent acceptIntent = new Intent(IncomingCallActivity.VOIP_ACCEPT);
                sendBroadcast(acceptIntent);

                PackageManager pm = getPackageManager();
                Intent initialActivityIntent = pm.getLaunchIntentForPackage(getApplicationContext().getPackageName());
                initialActivityIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP); // avoid having the activity started multiple times
                startActivity(initialActivityIntent);

                finish(); // close incoming call activity
            }
        });

        ((Button) findViewById(R.id.btnDecline)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent declineIntent = new Intent(IncomingCallActivity.VOIP_DECLINE);
                sendBroadcast(declineIntent);
                finish();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;
    }
}
