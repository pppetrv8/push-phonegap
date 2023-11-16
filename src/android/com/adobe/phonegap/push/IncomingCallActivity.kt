package com.adobe.phonegap.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat

private const val POST_NOTIFICATIONS_REQUEST_CODE = 8234

class IncomingCallActivity : Activity() {

    var caller: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            resources.getIdentifier("activity_incoming_call", "layout", packageName)
        )
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        instance = this

        caller = intent?.extras?.getString("caller") ?: ""
        (findViewById<TextView>(resources.getIdentifier("tvCaller", "id", packageName))).text =
            caller
        val btnAccept: Button =
            findViewById(resources.getIdentifier("btnAccept", "id", packageName))
        val btnDecline: Button =
            findViewById(resources.getIdentifier("btnDecline", "id", packageName))
        btnAccept.setOnClickListener { v -> requestPhoneUnlock() }
        btnDecline.setOnClickListener { v -> declineIncomingVoIP() }
        val animatedCircle: ImageView =
            findViewById(resources.getIdentifier("ivAnimatedCircle", "id", getPackageName()))
        val drawableCompat = AnimatedVectorDrawableCompat.create(
            this, resources.getIdentifier(
                "circle_animation_avd", "drawable",
                packageName
            )
        )
        animatedCircle.setImageDrawable(drawableCompat)
        drawableCompat?.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
            private val fHandler = Handler(Looper.getMainLooper())
            override fun onAnimationEnd(drawable: Drawable?) {
                super.onAnimationEnd(drawable)
                if (instance != null) {
                    fHandler.post(drawableCompat::start)
                }
            }
        })
        drawableCompat?.start()
    }

    override fun onBackPressed() {
        // Do nothing on back button
    }

    private fun requestPhoneUnlock() {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val context = this.applicationContext
        if (km.isKeyguardLocked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        super.onDismissSucceeded()
                        acceptIncomingVoIP()
                    }

                    override fun onDismissCancelled() {
                        super.onDismissCancelled()
                    }

                    override fun onDismissError() {
                        super.onDismissError()
                    }
                })
            } else {
                acceptIncomingVoIP()
                if (km.isKeyguardSecure) {
                    // Register receiver for dismissing "Unlock Screen" notification
                    phoneUnlockBR = PhoneUnlockBroadcastReceiver()
                    val filter = IntentFilter()
                    filter.addAction(Intent.ACTION_USER_PRESENT)
                    phoneUnlockBR?.apply {
                        context?.registerReceiver(this as BroadcastReceiver, filter)
                    }
                    showUnlockScreenNotification()
                } else {
                    val myLock: KeyguardManager.KeyguardLock = km.newKeyguardLock("AnswerCall")
                    myLock?.disableKeyguard()
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

    private fun declineIncomingVoIP() {
        val declineIntent = Intent(VOIP_DECLINE)
        sendBroadcast(declineIntent)
    }

    @SuppressLint("MissingPermission")
    private fun showUnlockScreenNotification() {
        val notificationBuilder = NotificationCompat.Builder(this, PushConstants.DEFAULT_CHANNEL_ID)
            .setSmallIcon(resources.getIdentifier("pushicon", "drawable", packageName))
            .setContentTitle("Ongoing call with $caller")
            .setContentText("Please unlock your device to continue")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(false)
            .setOngoing(true)
            .setStyle(NotificationCompat.BigTextStyle())
            .setSound(null)
        val ongoingCallNotification = notificationBuilder.build()
        val notificationManager = NotificationManagerCompat.from(this.applicationContext)
        // Display notification
        if (!isPostNotificationsGranted()) {
            requestPostNotifications()
        } else {
            notificationManager.notify(NOTIFICATION_MESSAGE_ID, ongoingCallNotification)
        }
    }

    private fun isPostNotificationsGranted(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                POST_NOTIFICATIONS_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == POST_NOTIFICATIONS_REQUEST_CODE &&
            grantResults.getOrNull(0) == PackageManager.PERMISSION_GRANTED
        ) {
            showUnlockScreenNotification()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    class PhoneUnlockBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action.equals(Intent.ACTION_USER_PRESENT)) {
                dismissUnlockScreenNotification(context.applicationContext)
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
