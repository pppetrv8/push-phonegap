package com.adobe.phonegap.push

import android.annotation.SuppressLint

@SuppressLint("NewApi")
class FCMService : FirebaseMessagingService(), PushConstants {
  private var voipNotificationActionBR: BroadcastReceiver? = null
  fun setNotification(notId: Int, message: String?) {
    var messageList: ArrayList<String?>? = messageMap.get(notId)
    if (messageList == null) {
      messageList = ArrayList<String>()
      messageMap.put(notId, messageList)
    }
    if (message!!.isEmpty()) {
      messageList.clear()
    } else {
      messageList.add(message)
    }
  }

  @Override
  fun onMessageReceived(message: RemoteMessage) {
    val from: String = message.getFrom()
    Log.d(LOG_TAG, "onMessage - from: $from")
    var extras = Bundle()
    if (message.getNotification() != null) {
      extras.putString(PushConstants.TITLE, message.getNotification().getTitle())
      extras.putString(PushConstants.MESSAGE, message.getNotification().getBody())
      extras.putString(PushConstants.SOUND, message.getNotification().getSound())
      extras.putString(PushConstants.ICON, message.getNotification().getIcon())
      extras.putString(PushConstants.COLOR, message.getNotification().getColor())
    }
    for (entry in message.getData().entrySet()) {
      extras.putString(entry.getKey(), entry.getValue())
    }
    if (extras != null && isAvailableSender(from)) {
      val applicationContext: Context = getApplicationContext()
      val prefs: SharedPreferences = applicationContext.getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH,
          Context.MODE_PRIVATE)
      val forceShow: Boolean = prefs.getBoolean(PushConstants.FORCE_SHOW, false)
      val clearBadge: Boolean = prefs.getBoolean(PushConstants.CLEAR_BADGE, false)
      val messageKey: String = prefs.getString(PushConstants.MESSAGE_KEY, PushConstants.MESSAGE)
      val titleKey: String = prefs.getString(PushConstants.TITLE_KEY, PushConstants.TITLE)
      extras = normalizeExtras(applicationContext, extras, messageKey, titleKey)
      if (clearBadge) {
        PushPlugin.setApplicationIconBadgeNumber(getApplicationContext(), 0)
      }
      if ("true".equals(message.getData().get("voip"))) {
        if ("true".equals(message.getData().get("isCancelPush"))) {
          dismissVOIPNotification()
          IncomingCallActivity.dismissUnlockScreenNotification(this.getApplicationContext())
        } else {
          showVOIPNotification(message.getData())
        }
      } else {
        // if we are in the foreground and forceShow is `false` only send data
        if (!forceShow && PushPlugin.isInForeground()) {
          Log.d(LOG_TAG, "foreground")
          extras.putBoolean(PushConstants.FOREGROUND, true)
          extras.putBoolean(PushConstants.COLDSTART, false)
          PushPlugin.sendExtras(extras)
        } else if (forceShow && PushPlugin.isInForeground()) {
          Log.d(LOG_TAG, "foreground force")
          extras.putBoolean(PushConstants.FOREGROUND, true)
          extras.putBoolean(PushConstants.COLDSTART, false)
          showNotificationIfPossible(applicationContext, extras)
        } else {
          Log.d(LOG_TAG, "background")
          extras.putBoolean(PushConstants.FOREGROUND, false)
          extras.putBoolean(PushConstants.COLDSTART, PushPlugin.isActive())
          showNotificationIfPossible(applicationContext, extras)
        }
      }
    }
  }

  // VoIP implementation
  private fun intentForLaunchActivity(): Intent {
    val pm: PackageManager = getPackageManager()
    return pm.getLaunchIntentForPackage(getApplicationContext().getPackageName())
  }

  private fun defaultRingtoneUri(): Uri {
    return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
  }

  private fun createNotificationChannel() {
    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val importance: Int = NotificationManager.IMPORTANCE_HIGH
      val channel = NotificationChannel(CHANNEL_VOIP, CHANNEL_NAME, importance)
      channel.setDescription("Channel For VOIP Calls")

      // Set ringtone to notification (>= Android O)
      val audioAttributes: AudioAttributes = Builder()
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
          .setUsage(AudioAttributes.USAGE_NOTIFICATION)
          .build()
      channel.setSound(defaultRingtoneUri(), audioAttributes)

      // Register the channel with the system; you can't change the importance
      // or other notification behaviors after this
      val notificationManager: NotificationManager = getSystemService(NotificationManager::class.java)
      notificationManager.createNotificationChannel(channel)
    }
  }

  private fun showVOIPNotification(messageData: Map<String, String>) {
    createNotificationChannel()

    // Prepare data from messageData
    var caller: String? = "Unknown caller"
    if (messageData.containsKey("caller")) {
      caller = messageData["caller"]
    }
    val callId = messageData["callId"]
    val callbackUrl = messageData["callbackUrl"]

    // Read the message title from messageData
    var title: String? = "Eingehender Anruf"
    if (messageData.containsKey("body")) {
      title = messageData["body"]
    }

    // Update Webhook status to CONNECTED
    updateWebhookVOIPStatus(callbackUrl, callId, IncomingCallActivity.VOIP_CONNECTED)

    // Intent for LockScreen or tapping on notification
    val fullScreenIntent = Intent(this, IncomingCallActivity::class.java)
    fullScreenIntent.putExtra("caller", caller)
    val fullScreenPendingIntent: PendingIntent = PendingIntent.getActivity(this, 0,
        fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // Intent for tapping on Answer
    val acceptIntent = Intent(IncomingCallActivity.VOIP_ACCEPT)
    val acceptPendingIntent: PendingIntent = PendingIntent.getBroadcast(this, 10, acceptIntent, PendingIntent.FLAG_IMMUTABLE)

    // Intent for tapping on Reject
    val declineIntent = Intent(IncomingCallActivity.VOIP_DECLINE)
    val declinePendingIntent: PendingIntent = PendingIntent.getBroadcast(this, 20, declineIntent, PendingIntent.FLAG_IMMUTABLE)
    val notificationBuilder: NotificationCompat.Builder = Builder(this, CHANNEL_VOIP)
        .setSmallIcon(getResources().getIdentifier("pushicon", "drawable", getPackageName()))
        .setContentTitle(title)
        .setContentText(caller)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_CALL) // Show main activity on lock screen or when tapping on notification
        .setFullScreenIntent(fullScreenPendingIntent, true) // Show Accept button
        .addAction(Action(0, "Annehmen",
            acceptPendingIntent)) // Show decline action
        .addAction(Action(0, "Ablehnen",
            declinePendingIntent)) // Make notification dismiss on user input action
        .setAutoCancel(true) // Cannot be swiped by user
        .setOngoing(true) // Set ringtone to notification (< Android O)
        .setSound(defaultRingtoneUri())
    val incomingCallNotification: Notification = notificationBuilder.build()
    val notificationManager: NotificationManagerCompat = NotificationManagerCompat.from(this)
    // Display notification
    notificationManager.notify(VOIP_NOTIFICATION_ID, incomingCallNotification)

    // Add broadcast receiver for notification button actions
    if (voipNotificationActionBR == null) {
      val filter = IntentFilter()
      filter.addAction(IncomingCallActivity.VOIP_ACCEPT)
      filter.addAction(IncomingCallActivity.VOIP_DECLINE)
      val appContext: Context = this.getApplicationContext()
      voipNotificationActionBR = object : BroadcastReceiver() {
        @Override
        fun onReceive(context: Context?, intent: Intent) {
          // Remove BR after responding to notification action
          appContext.unregisterReceiver(voipNotificationActionBR)
          voipNotificationActionBR = null

          // Handle action
          dismissVOIPNotification()

          // Update Webhook status to CONNECTED
          val voipStatus: String = intent.getAction()
          updateWebhookVOIPStatus(callbackUrl, callId, voipStatus)

          // Start cordova activity on answer
          if (voipStatus.equals(IncomingCallActivity.VOIP_ACCEPT)) {
            startActivity(intentForLaunchActivity())
          }
        }
      }
      appContext.registerReceiver(voipNotificationActionBR, filter)
    }
  }

  private fun dismissVOIPNotification() {
    NotificationManagerCompat.from(this).cancel(VOIP_NOTIFICATION_ID)
    if (IncomingCallActivity.instance != null) {
      IncomingCallActivity.instance.finish()
    }
  }

  fun updateWebhookVOIPStatus(url: String?, callId: String?, status: String) {
    val client = OkHttpClient()
    val urlBuilder: HttpUrl.Builder = HttpUrl.parse(url).newBuilder()
    urlBuilder.addQueryParameter("id", callId)
    urlBuilder.addQueryParameter("input", status)
    val urlBuilt: String = urlBuilder.build().toString()
    val request: Request = Builder().url(urlBuilt).build()
    client.newCall(request)
        .enqueue(object : Callback() {
          @Override
          fun onFailure(call: Call?, e: IOException?) {
            Log.d(LOG_TAG, "Update For CallId $callId and Status $status failed")
          }

          @Override
          fun onResponse(call: Call?, response: Response?) {
            Log.d(LOG_TAG, "Update For CallId $callId and Status $status successful")
          }
        })
  }

  // END of VoIP implementation
  /*
   * Change a values key in the extras bundle
   */
  private fun replaceKey(context: Context, oldKey: String, newKey: String, extras: Bundle, newExtras: Bundle) {
    var value: Object = extras.get(oldKey)
    if (value != null) {
      if (value is String) {
        value = localizeKey(context, newKey, value as String)
        newExtras.putString(newKey, value as String)
      } else if (value is Boolean) {
        newExtras.putBoolean(newKey, value as Boolean)
      } else if (value is Number) {
        newExtras.putDouble(newKey, (value as Number).doubleValue())
      } else {
        newExtras.putString(newKey, String.valueOf(value))
      }
    }
  }

  /*
   * Normalize localization for key
   */
  private fun localizeKey(context: Context, key: String, value: String): String {
    return if (key.equals(PushConstants.TITLE) || key.equals(PushConstants.MESSAGE) || key.equals(PushConstants.SUMMARY_TEXT)) {
      try {
        val localeObject = JSONObject(value)
        val localeKey: String = localeObject.getString(PushConstants.LOC_KEY)
        val localeFormatData: ArrayList<String> = ArrayList<String>()
        if (!localeObject.isNull(PushConstants.LOC_DATA)) {
          val localeData: String = localeObject.getString(PushConstants.LOC_DATA)
          val localeDataArray = JSONArray(localeData)
          for (i in 0 until localeDataArray.length()) {
            localeFormatData.add(localeDataArray.getString(i))
          }
        }
        val packageName: String = context.getPackageName()
        val resources: Resources = context.getResources()
        val resourceId: Int = resources.getIdentifier(localeKey, "string", packageName)
        if (resourceId != 0) {
          resources.getString(resourceId, localeFormatData.toArray())
        } else {
          Log.d(LOG_TAG, "can't find resource for locale key = $localeKey")
          value
        }
      } catch (e: JSONException) {
        Log.d(LOG_TAG, "no locale found for key = " + key + ", error " + e.getMessage())
        value
      }
    } else value
  }

  /*
   * Replace alternate keys with our canonical value
   */
  private fun normalizeKey(key: String, messageKey: String, titleKey: String, newExtras: Bundle): String {
    var key = key
    return if (key.equals(PushConstants.BODY) || key.equals(PushConstants.ALERT) || key.equals(PushConstants.MP_MESSAGE) || key.equals(PushConstants.GCM_NOTIFICATION_BODY)
        || key.equals(PushConstants.TWILIO_BODY) || key.equals(messageKey) || key.equals(PushConstants.AWS_PINPOINT_BODY)) {
      PushConstants.MESSAGE
    } else if (key.equals(PushConstants.TWILIO_TITLE) || key.equals(PushConstants.SUBJECT) || key.equals(titleKey)) {
      PushConstants.TITLE
    } else if (key.equals(PushConstants.MSGCNT) || key.equals(PushConstants.BADGE)) {
      PushConstants.COUNT
    } else if (key.equals(PushConstants.SOUNDNAME) || key.equals(PushConstants.TWILIO_SOUND)) {
      PushConstants.SOUND
    } else if (key.equals(PushConstants.AWS_PINPOINT_PICTURE)) {
      newExtras.putString(PushConstants.STYLE, PushConstants.STYLE_PICTURE)
      PushConstants.PICTURE
    } else if (key.startsWith(PushConstants.GCM_NOTIFICATION)) {
      key.substring(PushConstants.GCM_NOTIFICATION.length() + 1, key.length())
    } else if (key.startsWith(PushConstants.GCM_N)) {
      key.substring(PushConstants.GCM_N.length() + 1, key.length())
    } else if (key.startsWith(PushConstants.UA_PREFIX)) {
      key = key.substring(PushConstants.UA_PREFIX.length() + 1, key.length())
      key.toLowerCase()
    } else if (key.startsWith(PushConstants.AWS_PINPOINT_PREFIX)) {
      key.substring(PushConstants.AWS_PINPOINT_PREFIX.length() + 1, key.length())
    } else {
      key
    }
  }

  /*
   * Parse bundle into normalized keys.
   */
  private fun normalizeExtras(context: Context, extras: Bundle, messageKey: String, titleKey: String): Bundle {
    Log.d(LOG_TAG, "normalize extras")
    val it: Iterator<String> = extras.keySet().iterator()
    val newExtras = Bundle()
    while (it.hasNext()) {
      val key = it.next()
      Log.d(LOG_TAG, "key = $key")

      // If normalizeKeythe key is "data" or "message" and the value is a json object extract
      // This is to support parse.com and other services. Issue #147 and pull #218
      if (key.equals(PushConstants.PARSE_COM_DATA) || key.equals(PushConstants.MESSAGE) || key.equals(messageKey)) {
        val json: Object = extras.get(key)
        // Make sure data is json object stringified
        if (json is String && (json as String).startsWith("{")) {
          Log.d(LOG_TAG, "extracting nested message data from key = $key")
          try {
            // If object contains message keys promote each value to the root of the bundle
            val data = JSONObject(json as String)
            if (data.has(PushConstants.ALERT) || data.has(PushConstants.MESSAGE) || data.has(PushConstants.BODY) || data.has(PushConstants.TITLE) || data.has(messageKey)
                || data.has(titleKey)) {
              val jsonIter: Iterator<String> = data.keys()
              while (jsonIter.hasNext()) {
                var jsonKey = jsonIter.next()
                Log.d(LOG_TAG, "key = data/$jsonKey")
                var value: String = data.getString(jsonKey)
                jsonKey = normalizeKey(jsonKey, messageKey, titleKey, newExtras)
                value = localizeKey(context, jsonKey, value)
                newExtras.putString(jsonKey, value)
              }
            } else if (data.has(PushConstants.LOC_KEY) || data.has(PushConstants.LOC_DATA)) {
              val newKey = normalizeKey(key, messageKey, titleKey, newExtras)
              Log.d(LOG_TAG, "replace key $key with $newKey")
              replaceKey(context, key, newKey, extras, newExtras)
            }
          } catch (e: JSONException) {
            Log.e(LOG_TAG, "normalizeExtras: JSON exception")
          }
        } else {
          val newKey = normalizeKey(key, messageKey, titleKey, newExtras)
          Log.d(LOG_TAG, "replace key $key with $newKey")
          replaceKey(context, key, newKey, extras, newExtras)
        }
      } else if (key.equals("notification")) {
        val value: Bundle = extras.getBundle(key)
        val iterator: Iterator<String> = value.keySet().iterator()
        while (iterator.hasNext()) {
          val notifkey = iterator.next()
          Log.d(LOG_TAG, "notifkey = $notifkey")
          val newKey = normalizeKey(notifkey, messageKey, titleKey, newExtras)
          Log.d(LOG_TAG, "replace key $notifkey with $newKey")
          var valueData: String = value.getString(notifkey)
          valueData = localizeKey(context, newKey, valueData)
          newExtras.putString(newKey, valueData)
        }
        continue
        // In case we weren't working on the payload data node or the notification node,
        // normalize the key.
        // This allows to have "message" as the payload data key without colliding
        // with the other "message" key (holding the body of the payload)
        // See issue #1663
      } else {
        val newKey = normalizeKey(key, messageKey, titleKey, newExtras)
        Log.d(LOG_TAG, "replace key $key with $newKey")
        replaceKey(context, key, newKey, extras, newExtras)
      }
    } // while
    return newExtras
  }

  private fun extractBadgeCount(extras: Bundle?): Int {
    var count = -1
    val msgcnt: String = extras.getString(PushConstants.COUNT)
    try {
      if (msgcnt != null) {
        count = Integer.parseInt(msgcnt)
      }
    } catch (e: NumberFormatException) {
      Log.e(LOG_TAG, e.getLocalizedMessage(), e)
    }
    return count
  }

  private fun showNotificationIfPossible(context: Context, extras: Bundle?) {

    // Send a notification if there is a message or title, otherwise just send data
    val message: String = extras.getString(PushConstants.MESSAGE)
    val title: String = extras.getString(PushConstants.TITLE)
    val contentAvailable: String = extras.getString(PushConstants.CONTENT_AVAILABLE)
    val forceStart: String = extras.getString(PushConstants.FORCE_START)
    val badgeCount = extractBadgeCount(extras)
    if (badgeCount >= 0) {
      Log.d(LOG_TAG, "count =[$badgeCount]")
      PushPlugin.setApplicationIconBadgeNumber(context, badgeCount)
    }
    if (badgeCount == 0) {
      val mNotificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      mNotificationManager.cancelAll()
    }
    Log.d(LOG_TAG, "message =[$message]")
    Log.d(LOG_TAG, "title =[$title]")
    Log.d(LOG_TAG, "contentAvailable =[$contentAvailable]")
    Log.d(LOG_TAG, "forceStart =[$forceStart]")
    if (message != null && message.length() !== 0 || title != null && title.length() !== 0) {
      Log.d(LOG_TAG, "create notification")
      if (title == null || title.isEmpty()) {
        extras.putString(PushConstants.TITLE, getAppName(this))
      }
      createNotification(context, extras)
    }
    if (!PushPlugin.isActive() && "1".equals(forceStart)) {
      Log.d(LOG_TAG, "app is not running but we should start it and put in background")
      val intent = Intent(this, PushHandlerActivity::class.java)
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      intent.putExtra(PushConstants.PUSH_BUNDLE, extras)
      intent.putExtra(PushConstants.START_IN_BACKGROUND, true)
      intent.putExtra(PushConstants.FOREGROUND, false)
      startActivity(intent)
    } else if ("1".equals(contentAvailable)) {
      Log.d(LOG_TAG, "app is not running and content available true")
      Log.d(LOG_TAG, "send notification event")
      PushPlugin.sendExtras(extras)
    }
  }

  fun createNotification(context: Context, extras: Bundle?) {
    val mNotificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val appName = getAppName(this)
    val packageName: String = context.getPackageName()
    val resources: Resources = context.getResources()
    val notId = parseInt(PushConstants.NOT_ID, extras)
    val notificationIntent = Intent(this, PushHandlerActivity::class.java)
    notificationIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    notificationIntent.putExtra(PushConstants.PUSH_BUNDLE, extras)
    notificationIntent.putExtra(PushConstants.NOT_ID, notId)
    val random = SecureRandom()
    var requestCode: Int = random.nextInt()
    val contentIntent: PendingIntent = PendingIntent.getActivity(this, requestCode, notificationIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val dismissedNotificationIntent = Intent(this, PushDismissedHandler::class.java)
    dismissedNotificationIntent.putExtra(PushConstants.PUSH_BUNDLE, extras)
    dismissedNotificationIntent.putExtra(PushConstants.NOT_ID, notId)
    dismissedNotificationIntent.putExtra(PushConstants.DISMISSED, true)
    dismissedNotificationIntent.setAction(PushConstants.PUSH_DISMISSED)
    requestCode = random.nextInt()
    val deleteIntent: PendingIntent = PendingIntent.getBroadcast(this, requestCode, dismissedNotificationIntent,
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    var mBuilder: NotificationCompat.Builder? = null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      var channelID: String = extras.getString(PushConstants.ANDROID_CHANNEL_ID)

      // if the push payload specifies a channel use it
      if (channelID != null) {
        mBuilder = Builder(context, channelID)
      } else {
        val channels: List<NotificationChannel> = mNotificationManager.getNotificationChannels()
        channelID = if (channels.size() === 1) {
          channels[0].getId()
        } else {
          extras.getString(PushConstants.ANDROID_CHANNEL_ID, PushConstants.DEFAULT_CHANNEL_ID)
        }
        Log.d(LOG_TAG, "Using channel ID = $channelID")
        mBuilder = Builder(context, channelID)
      }
    } else {
      mBuilder = Builder(context)
    }
    mBuilder.setWhen(System.currentTimeMillis()).setContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
        .setTicker(fromHtml(extras.getString(PushConstants.TITLE))).setContentIntent(contentIntent).setDeleteIntent(deleteIntent)
        .setAutoCancel(true)
    val prefs: SharedPreferences = context.getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH, Context.MODE_PRIVATE)
    val localIcon: String = prefs.getString(PushConstants.ICON, null)
    val localIconColor: String = prefs.getString(PushConstants.ICON_COLOR, null)
    val soundOption: Boolean = prefs.getBoolean(PushConstants.SOUND, true)
    val vibrateOption: Boolean = prefs.getBoolean(PushConstants.VIBRATE, true)
    Log.d(LOG_TAG, "stored icon=$localIcon")
    Log.d(LOG_TAG, "stored iconColor=$localIconColor")
    Log.d(LOG_TAG, "stored sound=$soundOption")
    Log.d(LOG_TAG, "stored vibrate=$vibrateOption")

    /*
     * Notification Vibration
     */setNotificationVibration(extras, vibrateOption, mBuilder)

    /*
     * Notification Icon Color
     *
     * Sets the small-icon background color of the notification.
     * To use, add the `iconColor` key to plugin android options
     *
     */setNotificationIconColor(extras.getString(PushConstants.COLOR), mBuilder, localIconColor)

    /*
     * Notification Icon
     *
     * Sets the small-icon of the notification.
     *
     * - checks the plugin options for `icon` key
     * - if none, uses the application icon
     *
     * The icon value must be a string that maps to a drawable resource.
     * If no resource is found, falls
     *
     */setNotificationSmallIcon(context, extras, packageName, resources, mBuilder, localIcon)

    /*
     * Notification Large-Icon
     *
     * Sets the large-icon of the notification
     *
     * - checks the gcm data for the `image` key
     * - checks to see if remote image, loads it.
     * - checks to see if assets image, Loads It.
     * - checks to see if resource image, LOADS IT!
     * - if none, we don't set the large icon
     *
     */setNotificationLargeIcon(extras, packageName, resources, mBuilder)

    /*
     * Notification Sound
     */if (soundOption) {
      setNotificationSound(context, extras, mBuilder)
    }

    /*
     *  LED Notification
     */setNotificationLedColor(extras, mBuilder)

    /*
     *  Priority Notification
     */setNotificationPriority(extras, mBuilder)

    /*
     * Notification message
     */setNotificationMessage(notId, extras, mBuilder)

    /*
     * Notification count
     */setNotificationCount(context, extras, mBuilder)

    /*
     *  Notification ongoing
     */setNotificationOngoing(extras, mBuilder)

    /*
     * Notification count
     */setVisibility(context, extras, mBuilder)

    /*
     * Notification add actions
     */createActions(extras, mBuilder, resources, packageName, notId)
    mNotificationManager.notify(appName, notId, mBuilder.build())
  }

  private fun updateIntent(intent: Intent?, callback: String, extras: Bundle?, foreground: Boolean, notId: Int) {
    intent.putExtra(PushConstants.CALLBACK, callback)
    intent.putExtra(PushConstants.PUSH_BUNDLE, extras)
    intent.putExtra(PushConstants.FOREGROUND, foreground)
    intent.putExtra(PushConstants.NOT_ID, notId)
  }

  private fun createActions(extras: Bundle?, mBuilder: NotificationCompat.Builder?, resources: Resources,
                packageName: String, notId: Int) {
    Log.d(LOG_TAG, "create actions: with in-line")
    val actions: String = extras.getString(PushConstants.ACTIONS)
    if (actions != null) {
      try {
        val actionsArray = JSONArray(actions)
        val wActions: ArrayList<NotificationCompat.Action> = ArrayList<NotificationCompat.Action>()
        for (i in 0 until actionsArray.length()) {
          val min = 1
          val max = 2000000000
          val random = SecureRandom()
          val uniquePendingIntentRequestCode: Int = random.nextInt(max - min + 1) + min
          Log.d(LOG_TAG, "adding action")
          val action: JSONObject = actionsArray.getJSONObject(i)
          Log.d(LOG_TAG, "adding callback = " + action.getString(PushConstants.CALLBACK))
          val foreground: Boolean = action.optBoolean(PushConstants.FOREGROUND, true)
          val inline: Boolean = action.optBoolean("inline", false)
          var intent: Intent? = null
          var pIntent: PendingIntent? = null
          if (inline) {
            Log.d(LOG_TAG, "Version: " + android.os.Build.VERSION.SDK_INT + " = " + android.os.Build.VERSION_CODES.M)
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.M) {
              Log.d(LOG_TAG, "push activity")
              intent = Intent(this, PushHandlerActivity::class.java)
            } else {
              Log.d(LOG_TAG, "push receiver")
              intent = Intent(this, BackgroundActionButtonHandler::class.java)
            }
            updateIntent(intent, action.getString(PushConstants.CALLBACK), extras, foreground, notId)
            pIntent = if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.M) {
              Log.d(LOG_TAG, "push activity for notId $notId")
              PendingIntent.getActivity(this, uniquePendingIntentRequestCode, intent,
                  PendingIntent.FLAG_ONE_SHOT)
            } else {
              Log.d(LOG_TAG, "push receiver for notId $notId")
              PendingIntent.getBroadcast(this, uniquePendingIntentRequestCode, intent,
                  PendingIntent.FLAG_ONE_SHOT)
            }
          } else if (foreground) {
            intent = Intent(this, PushHandlerActivity::class.java)
            updateIntent(intent, action.getString(PushConstants.CALLBACK), extras, foreground, notId)
            pIntent = PendingIntent.getActivity(this, uniquePendingIntentRequestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT)
          } else {

            // Only add on platform levels that support FLAG_MUTABLE
            val flag: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            if (getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.S &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              intent = Intent(this, OnNotificationReceiverActivity::class.java)
              updateIntent(intent, action.getString(PushConstants.CALLBACK), extras, foreground, notId)
              pIntent = PendingIntent.getActivity(this, uniquePendingIntentRequestCode, intent, flag)
            } else {
              intent = Intent(this, BackgroundActionButtonHandler::class.java)
              updateIntent(intent, action.getString(PushConstants.CALLBACK), extras, foreground, notId)
              pIntent = PendingIntent.getBroadcast(this, uniquePendingIntentRequestCode, intent, flag)
            }
          }
          val actionBuilder: NotificationCompat.Action.Builder = Builder(
              getImageId(resources, action.optString(PushConstants.ICON, ""), packageName), action.getString(PushConstants.TITLE), pIntent)
          var remoteInput: RemoteInput? = null
          if (inline) {
            Log.d(LOG_TAG, "create remote input")
            val replyLabel: String = action.optString(PushConstants.INLINE_REPLY_LABEL, "Enter your reply here")
            remoteInput = Builder(PushConstants.INLINE_REPLY).setLabel(replyLabel).build()
            actionBuilder.addRemoteInput(remoteInput)
          }
          var wAction: NotificationCompat.Action? = actionBuilder.build()
          wActions.add(actionBuilder.build())
          if (inline) {
            mBuilder.addAction(wAction)
          } else {
            mBuilder.addAction(getImageId(resources, action.optString(PushConstants.ICON, ""), packageName), action.getString(PushConstants.TITLE),
                pIntent)
          }
          wAction = null
          pIntent = null
        }
        mBuilder.extend(WearableExtender().addActions(wActions))
        wActions.clear()
      } catch (e: JSONException) {
        // nope
      }
    }
  }

  private fun setNotificationCount(context: Context, extras: Bundle?, mBuilder: NotificationCompat.Builder?) {
    val count = extractBadgeCount(extras)
    if (count >= 0) {
      Log.d(LOG_TAG, "count =[$count]")
      mBuilder.setNumber(count)
    }
  }

  private fun setVisibility(context: Context, extras: Bundle?, mBuilder: NotificationCompat.Builder?) {
    val visibilityStr: String = extras.getString(PushConstants.VISIBILITY)
    if (visibilityStr != null) {
      try {
        val visibility: Integer = Integer.parseInt(visibilityStr)
        if (visibility >= NotificationCompat.VISIBILITY_SECRET && visibility <= NotificationCompat.VISIBILITY_PUBLIC) {
          mBuilder.setVisibility(visibility)
        } else {
          Log.e(LOG_TAG, "Visibility parameter must be between -1 and 1")
        }
      } catch (e: NumberFormatException) {
        e.printStackTrace()
      }
    }
  }

  private fun setNotificationVibration(extras: Bundle?, vibrateOption: Boolean, mBuilder: NotificationCompat.Builder?) {
    val vibrationPattern: String = extras.getString(PushConstants.VIBRATION_PATTERN)
    if (vibrationPattern != null) {
      val items: Array<String> = vibrationPattern.replaceAll("\\[", "").replaceAll("\\]", "").split(",")
      val results = LongArray(items.size)
      for (i in items.indices) {
        try {
          results[i] = Long.parseLong(items[i].trim())
        } catch (nfe: NumberFormatException) {
        }
      }
      mBuilder.setVibrate(results)
    } else {
      if (vibrateOption) {
        mBuilder.setDefaults(Notification.DEFAULT_VIBRATE)
      }
    }
  }

  private fun setNotificationOngoing(extras: Bundle?, mBuilder: NotificationCompat.Builder?) {
    val ongoing: Boolean = Boolean.parseBoolean(extras.getString(PushConstants.ONGOING, "false"))
    mBuilder.setOngoing(ongoing)
  }

  private fun setNotificationMessage(notId: Int, extras: Bundle?, mBuilder: NotificationCompat.Builder?) {
    val message: String = extras.getString(PushConstants.MESSAGE)
    val style: String = extras.getString(PushConstants.STYLE, PushConstants.STYLE_TEXT)
    if (PushConstants.STYLE_INBOX.equals(style)) {
      setNotification(notId, message)
      mBuilder.setContentText(fromHtml(message))
      val messageList: ArrayList<String> = messageMap.get(notId)
      val sizeList: Integer = messageList.size()
      if (sizeList > 1) {
        val sizeListMessage: String = sizeList.toString()
        var stacking: String = sizeList.toString() + " more"
        if (extras.getString(PushConstants.SUMMARY_TEXT) != null) {
          stacking = extras.getString(PushConstants.SUMMARY_TEXT)
          stacking = stacking.replace("%n%", sizeListMessage)
        }
        val notificationInbox: NotificationCompat.InboxStyle = InboxStyle()
            .setBigContentTitle(fromHtml(extras.getString(PushConstants.TITLE))).setSummaryText(fromHtml(stacking))
        for (i in messageList.size() - 1 downTo 0) {
          notificationInbox.addLine(fromHtml(messageList.get(i)))
        }
        mBuilder.setStyle(notificationInbox)
      } else {
        val bigText: NotificationCompat.BigTextStyle = BigTextStyle()
        if (message != null) {
          bigText.bigText(fromHtml(message))
          bigText.setBigContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
          mBuilder.setStyle(bigText)
        }
      }
    } else if (PushConstants.STYLE_PICTURE.equals(style)) {
      setNotification(notId, "")
      val bigPicture: NotificationCompat.BigPictureStyle = BigPictureStyle()
      bigPicture.bigPicture(getBitmapFromURL(extras.getString(PushConstants.PICTURE)))
      bigPicture.setBigContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
      bigPicture.setSummaryText(fromHtml(extras.getString(PushConstants.SUMMARY_TEXT)))
      mBuilder.setContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
      mBuilder.setContentText(fromHtml(message))
      mBuilder.setStyle(bigPicture)
    } else {
      setNotification(notId, "")
      val bigText: NotificationCompat.BigTextStyle = BigTextStyle()
      if (message != null) {
        mBuilder.setContentText(fromHtml(message))
        bigText.bigText(fromHtml(message))
        bigText.setBigContentTitle(fromHtml(extras.getString(PushConstants.TITLE)))
        val summaryText: String = extras.getString(PushConstants.SUMMARY_TEXT)
        if (summaryText != null) {
          bigText.setSummaryText(fromHtml(summaryText))
        }
        mBuilder.setStyle(bigText)
      }
      /*
    else {
      mBuilder.setContentText("<missing message content>");
    }
    */
    }
  }

  private fun setNotificationSound(context: Context, extras: Bundle?, mBuilder: NotificationCompat.Builder?) {
    var soundname: String = extras.getString(PushConstants.SOUNDNAME)
    if (soundname == null) {
      soundname = extras.getString(PushConstants.SOUND)
    }
    if (PushConstants.SOUND_RINGTONE.equals(soundname)) {
      mBuilder.setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI)
    } else if (soundname != null && !soundname.contentEquals(PushConstants.SOUND_DEFAULT)) {
      val sound: Uri = Uri
          .parse((ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.getPackageName()).toString() + "/raw/" + soundname)
      Log.d(LOG_TAG, sound.toString())
      mBuilder.setSound(sound)
    } else {
      mBuilder.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
    }
  }

  private fun setNotificationLedColor(extras: Bundle?, mBuilder: NotificationCompat.Builder?) {
    val ledColor: String = extras.getString(PushConstants.LED_COLOR)
    if (ledColor != null) {
      // Converts parse Int Array from ledColor
      val items: Array<String> = ledColor.replaceAll("\\[", "").replaceAll("\\]", "").split(",")
      val results = IntArray(items.size)
      for (i in items.indices) {
        try {
          results[i] = Integer.parseInt(items[i].trim())
        } catch (nfe: NumberFormatException) {
        }
      }
      if (results.size == 4) {
        mBuilder.setLights(Color.argb(results[0], results[1], results[2], results[3]), 500, 500)
      } else {
        Log.e(LOG_TAG, "ledColor parameter must be an array of length == 4 (ARGB)")
      }
    }
  }

  private fun setNotificationPriority(extras: Bundle?, mBuilder: NotificationCompat.Builder?) {
    val priorityStr: String = extras.getString(PushConstants.PRIORITY)
    if (priorityStr != null) {
      try {
        val priority: Integer = Integer.parseInt(priorityStr)
        if (priority >= NotificationCompat.PRIORITY_MIN && priority <= NotificationCompat.PRIORITY_MAX) {
          mBuilder.setPriority(priority)
        } else {
          Log.e(LOG_TAG, "Priority parameter must be between -2 and 2")
        }
      } catch (e: NumberFormatException) {
        e.printStackTrace()
      }
    }
  }

  private fun getCircleBitmap(bitmap: Bitmap?): Bitmap? {
    if (bitmap == null) {
      return null
    }
    val output: Bitmap = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val color: Int = Color.RED
    val paint = Paint()
    val rect = Rect(0, 0, bitmap.getWidth(), bitmap.getHeight())
    val rectF = RectF(rect)
    paint.setAntiAlias(true)
    canvas.drawARGB(0, 0, 0, 0)
    paint.setColor(color)
    val cx: Float = bitmap.getWidth() / 2
    val cy: Float = bitmap.getHeight() / 2
    val radius = if (cx < cy) cx else cy
    canvas.drawCircle(cx, cy, radius, paint)
    paint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.SRC_IN))
    canvas.drawBitmap(bitmap, rect, rect, paint)
    bitmap.recycle()
    return output
  }

  private fun setNotificationLargeIcon(extras: Bundle?, packageName: String, resources: Resources,
                     mBuilder: NotificationCompat.Builder?) {
    val gcmLargeIcon: String = extras.getString(PushConstants.IMAGE) // from gcm
    val imageType: String = extras.getString(PushConstants.IMAGE_TYPE, PushConstants.IMAGE_TYPE_SQUARE)
    if (gcmLargeIcon != null && !"".equals(gcmLargeIcon)) {
      if (gcmLargeIcon.startsWith("http://") || gcmLargeIcon.startsWith("https://")) {
        val bitmap: Bitmap? = getBitmapFromURL(gcmLargeIcon)
        if (PushConstants.IMAGE_TYPE_SQUARE.equalsIgnoreCase(imageType)) {
          mBuilder.setLargeIcon(bitmap)
        } else {
          val bm: Bitmap? = getCircleBitmap(bitmap)
          mBuilder.setLargeIcon(bm)
        }
        Log.d(LOG_TAG, "using remote large-icon from gcm")
      } else {
        val assetManager: AssetManager = getAssets()
        val istr: InputStream
        try {
          istr = assetManager.open(gcmLargeIcon)
          val bitmap: Bitmap = BitmapFactory.decodeStream(istr)
          if (PushConstants.IMAGE_TYPE_SQUARE.equalsIgnoreCase(imageType)) {
            mBuilder.setLargeIcon(bitmap)
          } else {
            val bm: Bitmap? = getCircleBitmap(bitmap)
            mBuilder.setLargeIcon(bm)
          }
          Log.d(LOG_TAG, "using assets large-icon from gcm")
        } catch (e: IOException) {
          var largeIconId = 0
          largeIconId = getImageId(resources, gcmLargeIcon, packageName)
          if (largeIconId != 0) {
            val largeIconBitmap: Bitmap = BitmapFactory.decodeResource(resources, largeIconId)
            mBuilder.setLargeIcon(largeIconBitmap)
            Log.d(LOG_TAG, "using resources large-icon from gcm")
          } else {
            Log.d(LOG_TAG, "Not setting large icon")
          }
        }
      }
    }
  }

  private fun getImageId(resources: Resources, icon: String, packageName: String): Int {
    var iconId: Int = resources.getIdentifier(icon, PushConstants.DRAWABLE, packageName)
    if (iconId == 0) {
      iconId = resources.getIdentifier(icon, "mipmap", packageName)
    }
    return iconId
  }

  private fun setNotificationSmallIcon(context: Context, extras: Bundle?, packageName: String, resources: Resources,
                     mBuilder: NotificationCompat.Builder?, localIcon: String?) {
    var iconId = 0
    val icon: String = extras.getString(PushConstants.ICON)
    if (icon != null && !"".equals(icon)) {
      iconId = getImageId(resources, icon, packageName)
      Log.d(LOG_TAG, "using icon from plugin options")
    } else if (localIcon != null && !"".equals(localIcon)) {
      iconId = getImageId(resources, localIcon, packageName)
      Log.d(LOG_TAG, "using icon from plugin options")
    }
    if (iconId == 0) {
      Log.d(LOG_TAG, "no icon resource found - using application icon")
      iconId = context.getApplicationInfo().icon
    }
    mBuilder.setSmallIcon(iconId)
  }

  private fun setNotificationIconColor(color: String?, mBuilder: NotificationCompat.Builder?, localIconColor: String?) {
    var iconColor = 0
    if (color != null && !"".equals(color)) {
      try {
        iconColor = Color.parseColor(color)
      } catch (e: IllegalArgumentException) {
        Log.e(LOG_TAG, "couldn't parse color from android options")
      }
    } else if (localIconColor != null && !"".equals(localIconColor)) {
      try {
        iconColor = Color.parseColor(localIconColor)
      } catch (e: IllegalArgumentException) {
        Log.e(LOG_TAG, "couldn't parse color from android options")
      }
    }
    if (iconColor != 0) {
      mBuilder.setColor(iconColor)
    }
  }

  fun getBitmapFromURL(strURL: String?): Bitmap? {
    return try {
      val url = URL(strURL)
      val connection: HttpURLConnection = url.openConnection() as HttpURLConnection
      connection.setConnectTimeout(15000)
      connection.setDoInput(true)
      connection.connect()
      val input: InputStream = connection.getInputStream()
      BitmapFactory.decodeStream(input)
    } catch (e: IOException) {
      e.printStackTrace()
      null
    }
  }

  private fun parseInt(value: String, extras: Bundle?): Int {
    var retval = 0
    try {
      retval = Integer.parseInt(extras.getString(value))
    } catch (e: NumberFormatException) {
      Log.e(LOG_TAG, "Number format exception - Error parsing " + value + ": " + e.getMessage())
    } catch (e: Exception) {
      Log.e(LOG_TAG, "Number format exception - Error parsing " + value + ": " + e.getMessage())
    }
    return retval
  }

  private fun fromHtml(source: String?): Spanned? {
    return if (source != null) Html.fromHtml(source) else null
  }

  private fun isAvailableSender(from: String): Boolean {
    val sharedPref: SharedPreferences = getApplicationContext().getSharedPreferences(PushPlugin.COM_ADOBE_PHONEGAP_PUSH,
        Context.MODE_PRIVATE)
    val savedSenderID: String = sharedPref.getString(PushConstants.SENDER_ID, "")
    Log.d(LOG_TAG, "sender id = $savedSenderID")
    return from.equals(savedSenderID) || from.startsWith("/topics/")
  }

  companion object {
    private const val LOG_TAG = "Push_FCMService"
    private val messageMap: HashMap<Integer, ArrayList<String>> = HashMap<Integer, ArrayList<String>>()

    // VoIP
    private const val CHANNEL_VOIP = "Voip"
    private const val CHANNEL_NAME = "TCVoip"
    const val VOIP_NOTIFICATION_ID = 168697
    fun getAppName(context: Context): String {
      val appName: CharSequence = context.getPackageManager().getApplicationLabel(context.getApplicationInfo())
      return appName as String
    }
  }
}
