package com.adobe.phonegap.push;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

public class OnNotificationReceiverActivity extends Activity {
    private static String LOG_TAG = "Push_OnNotificationReceiverActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(LOG_TAG, "OnNotificationReceiverActivity.onCreate()");
        handleNotification(this, getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(LOG_TAG, "OnNotificationReceiverActivity.onNewIntent()");
        handleNotification(this, intent);
        finish();
    }

    private static void handleNotification(Context context, Intent intent) {
        try {
            PackageManager pm = context.getPackageManager();

            Intent launchIntent = pm.getLaunchIntentForPackage(context.getPackageName());
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

            Bundle data = intent.getExtras();
            if(!data.containsKey("messageType")) data.putString("messageType", "notification");
            data.putString("tap", PushPlugin.isInBackground() ? "background" : "foreground");

            Log.d(LOG_TAG, "OnNotificationReceiverActivity.handleNotification(): "+data.toString());

            PushPlugin.sendExtras(data);

            launchIntent.putExtras(data);
            context.startActivity(launchIntent);
        } catch (Exception e) {
            Log.e(LOG_TAG, e.getLocalizedMessage(), e);
        }
    }
}