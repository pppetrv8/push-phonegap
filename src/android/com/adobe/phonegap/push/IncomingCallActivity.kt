package com.adobe.phonegap.push

import android.app.Activity

class IncomingCallActivity : Activity() {
  var caller = ""
  @Override
  protected fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(getResources().getIdentifier("activity_incoming_call", "layout", getPackageName()))
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    instance = this
    caller = getIntent().getExtras().getString("caller")
    (findViewById(getResources().getIdentifier("tvCaller", "id", getPackageName())) as TextView).setText(caller)
    val btnAccept: Button = findViewById(getResources().getIdentifier("btnAccept", "id", getPackageName()))
    val btnDecline: Button = findViewById(getResources().getIdentifier("btnDecline", "id", getPackageName()))
    btnAccept.setOnClickListener { v -> requestPhoneUnlock() }
    btnDecline.setOnClickListener { v -> declineIncomingVoIP() }
    val animatedCircle: ImageView = findViewById(getResources().getIdentifier("ivAnimatedCircle", "id", getPackageName()))
    val drawableCompat: AnimatedVectorDrawableCompat = AnimatedVectorDrawableCompat.create(this, getResources().getIdentifier("circle_animation_avd", "drawable", getPackageName()))
    animatedCircle.setImageDrawable(drawableCompat)
    drawableCompat.registerAnimationCallback(object : AnimationCallback() {
      @NonNull
      private val fHandler: Handler = Handler(Looper.getMainLooper())
      @Override
      fun onAnimationEnd(drawable: Drawable?) {
        super.onAnimationEnd(drawable)
        if (instance != null) {
          fHandler.post(drawableCompat::start)
        }
      }
    })
    drawableCompat.start()
  }

  @Override
  fun onBackPressed() {
    // Do nothing on back button
  }

  fun requestPhoneUnlock() {
    val km: KeyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    if (km.isKeyguardLocked()) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        km.requestDismissKeyguard(this, object : KeyguardDismissCallback() {
          @Override
          fun onDismissSucceeded() {
            super.onDismissSucceeded()
            acceptIncomingVoIP()
          }

          @Override
          fun onDismissCancelled() {
            super.onDismissCancelled()
          }

          @Override
          fun onDismissError() {
            super.onDismissError()
          }
        })
      } else {
        acceptIncomingVoIP()
        if (km.isKeyguardSecure()) {
          // Register receiver for dismissing "Unlock Screen" notification
          phoneUnlockBR = PhoneUnlockBroadcastReceiver()
          val filter = IntentFilter()
          filter.addAction(Intent.ACTION_USER_PRESENT)
          this.getApplicationContext().registerReceiver(phoneUnlockBR, filter)
          showUnlockScreenNotification()
        } else {
          val myLock: KeyguardManager.KeyguardLock = km.newKeyguardLock("AnswerCall")
          myLock.disableKeyguard()
        }
      }
    } else {
      acceptIncomingVoIP()
    }
  }

  fun acceptIncomingVoIP() {
    val acceptIntent = Intent(VOIP_ACCEPT)
    sendBroadcast(acceptIntent)
  }

  fun declineIncomingVoIP() {
    val declineIntent = Intent(VOIP_DECLINE)
    sendBroadcast(declineIntent)
  }

  private fun showUnlockScreenNotification() {
    val notificationBuilder: NotificationCompat.Builder = Builder(this, PushConstants.DEFAULT_CHANNEL_ID)
        .setSmallIcon(getResources().getIdentifier("pushicon", "drawable", getPackageName()))
        .setContentTitle("Ongoing call with $caller")
        .setContentText("Please unlock your device to continue")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setAutoCancel(false)
        .setOngoing(true)
        .setStyle(BigTextStyle())
        .setSound(null)
    val ongoingCallNotification: Notification = notificationBuilder.build()
    val notificationManager: NotificationManagerCompat = NotificationManagerCompat.from(this.getApplicationContext())
    // Display notification
    notificationManager.notify(NOTIFICATION_MESSAGE_ID, ongoingCallNotification)
  }

  @Override
  protected fun onDestroy() {
    super.onDestroy()
    instance = null
  }

  class PhoneUnlockBroadcastReceiver : BroadcastReceiver() {
    @Override
    fun onReceive(context: Context, intent: Intent) {
      if (intent.getAction().equals(Intent.ACTION_USER_PRESENT)) {
        dismissUnlockScreenNotification(context.getApplicationContext())
      }
    }
  }

  companion object {
    const val VOIP_CONNECTED = "connected"
    const val VOIP_ACCEPT = "pickup"
    const val VOIP_DECLINE = "declined_callee"
    private const val NOTIFICATION_MESSAGE_ID = 1337
    var instance: IncomingCallActivity? = null
    var phoneUnlockBR: PhoneUnlockBroadcastReceiver? = null
    fun dismissUnlockScreenNotification(applicationContext: Context) {
      NotificationManagerCompat.from(applicationContext).cancel(NOTIFICATION_MESSAGE_ID)
      if (phoneUnlockBR != null) {
        applicationContext.unregisterReceiver(phoneUnlockBR)
        phoneUnlockBR = null
      }
    }
  }
}
