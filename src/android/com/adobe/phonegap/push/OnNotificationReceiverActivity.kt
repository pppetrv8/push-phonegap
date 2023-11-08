package com.adobe.phonegap.push

import android.app.Activity

class OnNotificationReceiverActivity : Activity() {
  @Override
  protected fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    Log.d(LOG_TAG, "OnNotificationReceiverActivity.onCreate()")
    handleNotification(this, getIntent())
    finish()
  }

  @Override
  protected fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Log.d(LOG_TAG, "OnNotificationReceiverActivity.onNewIntent()")
    handleNotification(this, intent)
    finish()
  }

  companion object {
    private const val LOG_TAG = "Push_OnNotificationReceiverActivity"
    private fun handleNotification(context: Context, intent: Intent) {
      try {
        val pm: PackageManager = context.getPackageManager()
        val launchIntent: Intent = pm.getLaunchIntentForPackage(context.getPackageName())
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val data: Bundle = intent.getExtras()
        if (!data.containsKey("messageType")) data.putString("messageType", "notification")
        data.putString("tap", if (PushPlugin.isInBackground()) "background" else "foreground")
        Log.d(LOG_TAG, "OnNotificationReceiverActivity.handleNotification(): " + data.toString())
        PushPlugin.sendExtras(data)
        launchIntent.putExtras(data)
        context.startActivity(launchIntent)
      } catch (e: Exception) {
        Log.e(LOG_TAG, e.getLocalizedMessage(), e)
      }
    }
  }
}
