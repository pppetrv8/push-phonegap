package com.adobe.phonegap.push;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.graphics.drawable.Animatable2Compat;
import android.support.graphics.drawable.AnimatedVectorDrawableCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

public class IncomingCallActivity extends Activity {

    public static final String VOIP_CONNECTED = "connected";
    public static final String VOIP_ACCEPT = "pickup";
    public static final String VOIP_DECLINE = "declined_callee";

    private static final int NOTIFICATION_MESSAGE_ID = 1337;

    public static IncomingCallActivity instance = null;
    String caller = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(getResources().getIdentifier("activity_incoming_call", "layout", getPackageName()));

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        instance = this;

        caller = getIntent().getExtras().getString("caller");
        ((TextView) findViewById(getResources().getIdentifier("tvCaller", "id", getPackageName()))).setText(caller);

        Button btnAccept = findViewById(getResources().getIdentifier("btnAccept", "id", getPackageName()));
        Button btnDecline = findViewById(getResources().getIdentifier("btnDecline", "id", getPackageName()));

        btnAccept.setOnClickListener(v -> requestPhoneUnlock());
        btnDecline.setOnClickListener(v -> declineIncomingVoIP());

        final ImageView animatedCircle = findViewById(getResources().getIdentifier("ivAnimatedCircle", "id", getPackageName()));
        final AnimatedVectorDrawableCompat drawableCompat = AnimatedVectorDrawableCompat.create(this, getResources().getIdentifier("circle_animation_avd", "drawable", getPackageName()));
        animatedCircle.setImageDrawable(drawableCompat);
        drawableCompat.registerAnimationCallback(new Animatable2Compat.AnimationCallback() {
            @NonNull
            private final Handler fHandler = new Handler(Looper.getMainLooper());

            @Override
            public void onAnimationEnd(Drawable drawable) {
                super.onAnimationEnd(drawable);
                if (instance != null) {
                    fHandler.post(drawableCompat::start);
                }
            }
        });

        drawableCompat.start();
    }

    @Override
    public void onBackPressed() {
        // Do nothing on back button
    }

    void requestPhoneUnlock() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km.isKeyguardLocked()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override
                    public void onDismissSucceeded() {
                        super.onDismissSucceeded();
                        acceptIncomingVoIP();
                    }

                    @Override
                    public void onDismissCancelled() {
                        super.onDismissCancelled();
                    }

                    @Override
                    public void onDismissError() {
                        super.onDismissError();
                    }
                });
            } else {
                acceptIncomingVoIP();
                if (km.isKeyguardSecure()) {
                    // Register receiver for dismissing "Unlock Screen" notification
                    IncomingCallActivity.phoneUnlockBR = new PhoneUnlockBroadcastReceiver();
                    IntentFilter filter = new IntentFilter();
                    filter.addAction(Intent.ACTION_USER_PRESENT);
                    this.getApplicationContext().registerReceiver(IncomingCallActivity.phoneUnlockBR, filter);

                    showUnlockScreenNotification();
                } else {
                    KeyguardManager.KeyguardLock myLock = km.newKeyguardLock("AnswerCall");
                    myLock.disableKeyguard();
                }
            }
        } else {
            acceptIncomingVoIP();
        }
    }

    void acceptIncomingVoIP() {
        Intent acceptIntent = new Intent(IncomingCallActivity.VOIP_ACCEPT);
        sendBroadcast(acceptIntent);
    }

    void declineIncomingVoIP() {
        Intent declineIntent = new Intent(IncomingCallActivity.VOIP_DECLINE);
        sendBroadcast(declineIntent);
    }

    private void showUnlockScreenNotification() {
        NotificationCompat.Builder notificationBuilder =
                new NotificationCompat.Builder(this, PushConstants.DEFAULT_CHANNEL_ID)
                        .setSmallIcon(getResources().getIdentifier("pushicon", "drawable", getPackageName()))
                        .setContentTitle("Ongoing call with " + caller)
                        .setContentText("Please unlock your device to continue")
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                        .setAutoCancel(false)
                        .setOngoing(true)
                        .setStyle(new NotificationCompat.BigTextStyle())
                        .setSound(null);

        Notification ongoingCallNotification = notificationBuilder.build();

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(this.getApplicationContext());
        // Display notification
        notificationManager.notify(NOTIFICATION_MESSAGE_ID, ongoingCallNotification);
    }

    static PhoneUnlockBroadcastReceiver phoneUnlockBR;

    public static void dismissUnlockScreenNotification(Context applicationContext) {
        NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_MESSAGE_ID);
        if (IncomingCallActivity.phoneUnlockBR != null) {
            applicationContext.unregisterReceiver(IncomingCallActivity.phoneUnlockBR);
            IncomingCallActivity.phoneUnlockBR = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    public static class PhoneUnlockBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {
                IncomingCallActivity.dismissUnlockScreenNotification(context.getApplicationContext());
            }
        }
    }
}
