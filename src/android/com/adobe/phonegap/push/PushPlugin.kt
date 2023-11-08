package com.adobe.phonegap.push

import android.annotation.TargetApi

class PushPlugin : CordovaPlugin(), PushConstants {
  private val applicationContext: Context
    /**
     * Gets the application context from cordova's main activity.
     *
     * @return the application context
     */
    private get() = this.cordova.getActivity().getApplicationContext()

  @TargetApi(26)
  @Throws(JSONException::class)
  private fun listChannels(): JSONArray {
    val channels = JSONArray()
    // only call on Android O and above
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager: NotificationManager = cordova.getActivity()
          .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val notificationChannels: List<NotificationChannel> = notificationManager.getNotificationChannels()
      for (notificationChannel in notificationChannels) {
        val channel = JSONObject()
        channel.put(PushConstants.CHANNEL_ID, notificationChannel.getId())
        channel.put(PushConstants.CHANNEL_DESCRIPTION, notificationChannel.getDescription())
        channels.put(channel)
      }
    }
    return channels
  }

  @TargetApi(26)
  private fun deleteChannel(channelId: String) {
    // only call on Android O and above
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager: NotificationManager = cordova.getActivity()
          .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      notificationManager.deleteNotificationChannel(channelId)
    }
  }

  @TargetApi(26)
  @Throws(JSONException::class)
  private fun createChannel(channel: JSONObject?) {
    // only call on Android O and above
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager: NotificationManager = cordova.getActivity()
          .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val packageName: String = applicationContext.getPackageName()
      val mChannel = NotificationChannel(channel.getString(PushConstants.CHANNEL_ID),
          channel.optString(PushConstants.CHANNEL_DESCRIPTION, ""),
          channel.optInt(PushConstants.CHANNEL_IMPORTANCE, NotificationManager.IMPORTANCE_DEFAULT))
      val lightColor: Int = channel.optInt(PushConstants.CHANNEL_LIGHT_COLOR, -1)
      if (lightColor != -1) {
        mChannel.setLightColor(lightColor)
      }
      val visibility: Int = channel.optInt(PushConstants.VISIBILITY, NotificationCompat.VISIBILITY_PUBLIC)
      mChannel.setLockscreenVisibility(visibility)
      val badge: Boolean = channel.optBoolean(PushConstants.BADGE, true)
      mChannel.setShowBadge(badge)
      val sound: String = channel.optString(PushConstants.SOUND, "default")
      val audioAttributes: AudioAttributes = Builder()
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build()
      if (PushConstants.SOUND_RINGTONE.equals(sound)) {
        mChannel.setSound(android.provider.Settings.System.DEFAULT_RINGTONE_URI, audioAttributes)
      } else if (sound != null && sound.isEmpty()) {
        // Disable sound for this notification channel if an empty string is passed.
        // https://stackoverflow.com/a/47144981/6194193
        mChannel.setSound(null, null)
      } else if (sound != null && !sound.contentEquals(PushConstants.SOUND_DEFAULT)) {
        val soundUri: Uri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + packageName + "/raw/" + sound)
        mChannel.setSound(soundUri, audioAttributes)
      } else {
        mChannel.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, audioAttributes)
      }

      // If vibration settings is an array set vibration pattern, else set enable
      // vibration.
      val pattern: JSONArray = channel.optJSONArray(PushConstants.CHANNEL_VIBRATION)
      if (pattern != null) {
        val patternLength: Int = pattern.length()
        val patternArray = LongArray(patternLength)
        for (i in 0 until patternLength) {
          patternArray[i] = pattern.optLong(i)
        }
        mChannel.setVibrationPattern(patternArray)
      } else {
        val vibrate: Boolean = channel.optBoolean(PushConstants.CHANNEL_VIBRATION, true)
        mChannel.enableVibration(vibrate)
      }
      notificationManager.createNotificationChannel(mChannel)
    }
  }

  @TargetApi(26)
  private fun createDefaultNotificationChannelIfNeeded(options: JSONObject?) {
    var id: String
    // only call on Android O and above
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val notificationManager: NotificationManager = cordova.getActivity()
          .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channels: List<NotificationChannel> = notificationManager.getNotificationChannels()
      for (i in 0 until channels.size()) {
        id = channels[i].getId()
        if (id.equals(PushConstants.DEFAULT_CHANNEL_ID)) {
          return
        }
      }
      try {
        options.put(PushConstants.CHANNEL_ID, PushConstants.DEFAULT_CHANNEL_ID)
        options.putOpt(PushConstants.CHANNEL_DESCRIPTION, "PhoneGap PushPlugin")
        createChannel(options)
      } catch (e: JSONException) {
        Log.e(LOG_TAG, "execute: Got JSON Exception " + e.getMessage())
      }
    }
  }

  @Override
  fun execute(action: String, data: JSONArray, callbackContext: CallbackContext): Boolean {
    Log.v(LOG_TAG, "execute: action=$action")
    gWebView = this.webView
    if (PushConstants.INITIALIZE.equals(action)) {
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          pushContext = callbackContext
          var jo: JSONObject? = null
          Log.v(LOG_TAG, "execute: data=" + data.toString())
          val sharedPref: SharedPreferences = applicationContext.getSharedPreferences(PushConstants.COM_ADOBE_PHONEGAP_PUSH,
              Context.MODE_PRIVATE)
          var token: String? = null
          var senderID: String? = null
          try {
            jo = data.getJSONObject(0).getJSONObject(PushConstants.ANDROID)

            // If no NotificationChannels exist create the default one
            createDefaultNotificationChannelIfNeeded(jo)
            Log.v(LOG_TAG, "execute: jo=" + jo.toString())
            senderID = getStringResourceByName(PushConstants.GCM_DEFAULT_SENDER_ID)
            Log.v(LOG_TAG, "execute: senderID=$senderID")
            try {
              token = FirebaseInstanceId.getInstance().getToken()
            } catch (e: IllegalStateException) {
              Log.e(LOG_TAG, "Exception raised while getting Firebase token " + e.getMessage())
            }
            if (token == null) {
              try {
                token = FirebaseInstanceId.getInstance().getToken(senderID, PushConstants.FCM)
              } catch (e: IllegalStateException) {
                Log.e(LOG_TAG, "Exception raised while getting Firebase token " + e.getMessage())
              }
            }
            if (!"".equals(token)) {
              val json: JSONObject = JSONObject().put(PushConstants.REGISTRATION_ID, token)
              json.put(PushConstants.REGISTRATION_TYPE, PushConstants.FCM)
              Log.v(LOG_TAG, "onRegistered: " + json.toString())
              val topics: JSONArray = jo.optJSONArray(PushConstants.TOPICS)
              subscribeToTopics(topics, registration_id)
              sendEvent(json)
            } else {
              callbackContext.error("Empty registration ID received from FCM")
              return
            }
          } catch (e: JSONException) {
            Log.e(LOG_TAG, "execute: Got JSON Exception " + e.getMessage())
            callbackContext.error(e.getMessage())
          } catch (e: IOException) {
            Log.e(LOG_TAG, "execute: Got IO Exception " + e.getMessage())
            callbackContext.error(e.getMessage())
          } catch (e: Resources.NotFoundException) {
            Log.e(LOG_TAG, "execute: Got Resources NotFoundException " + e.getMessage())
            callbackContext.error(e.getMessage())
          }
          if (jo != null) {
            val editor: SharedPreferences.Editor = sharedPref.edit()
            try {
              editor.putString(PushConstants.ICON, jo.getString(PushConstants.ICON))
            } catch (e: JSONException) {
              Log.d(LOG_TAG, "no icon option")
            }
            try {
              editor.putString(PushConstants.ICON_COLOR, jo.getString(PushConstants.ICON_COLOR))
            } catch (e: JSONException) {
              Log.d(LOG_TAG, "no iconColor option")
            }
            val clearBadge: Boolean = jo.optBoolean(PushConstants.CLEAR_BADGE, false)
            if (clearBadge) {
              setApplicationIconBadgeNumber(applicationContext, 0)
            }
            editor.putBoolean(PushConstants.SOUND, jo.optBoolean(PushConstants.SOUND, true))
            editor.putBoolean(PushConstants.VIBRATE, jo.optBoolean(PushConstants.VIBRATE, true))
            editor.putBoolean(PushConstants.CLEAR_BADGE, clearBadge)
            editor.putBoolean(PushConstants.CLEAR_NOTIFICATIONS, jo.optBoolean(PushConstants.CLEAR_NOTIFICATIONS, true))
            editor.putBoolean(PushConstants.FORCE_SHOW, jo.optBoolean(PushConstants.FORCE_SHOW, false))
            editor.putString(PushConstants.SENDER_ID, senderID)
            editor.putString(PushConstants.MESSAGE_KEY, jo.optString(PushConstants.MESSAGE_KEY))
            editor.putString(PushConstants.TITLE_KEY, jo.optString(PushConstants.TITLE_KEY))
            editor.commit()
          }
          if (!gCachedExtras.isEmpty()) {
            Log.v(LOG_TAG, "sending cached extras")
            synchronized(gCachedExtras) {
              val gCachedExtrasIterator: Iterator<Bundle> = gCachedExtras.iterator()
              while (gCachedExtrasIterator.hasNext()) {
                sendExtras(gCachedExtrasIterator.next())
              }
            }
            gCachedExtras.clear()
          }
        }
      })
    } else if (PushConstants.UNREGISTER.equals(action)) {
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          try {
            val sharedPref: SharedPreferences = applicationContext.getSharedPreferences(PushConstants.COM_ADOBE_PHONEGAP_PUSH,
                Context.MODE_PRIVATE)
            val topics: JSONArray = data.optJSONArray(0)
            if (topics != null && !"".equals(registration_id)) {
              unsubscribeFromTopics(topics, registration_id)
            } else {
              FirebaseInstanceId.getInstance().deleteInstanceId()
              Log.v(LOG_TAG, "UNREGISTER")

              // Remove shared prefs
              val editor: SharedPreferences.Editor = sharedPref.edit()
              editor.remove(PushConstants.SOUND)
              editor.remove(PushConstants.VIBRATE)
              editor.remove(PushConstants.CLEAR_BADGE)
              editor.remove(PushConstants.CLEAR_NOTIFICATIONS)
              editor.remove(PushConstants.FORCE_SHOW)
              editor.remove(PushConstants.SENDER_ID)
              editor.commit()
            }
            callbackContext.success()
          } catch (e: IOException) {
            Log.e(LOG_TAG, "execute: Got JSON Exception " + e.getMessage())
            callbackContext.error(e.getMessage())
          }
        }
      })
    } else if (PushConstants.FINISH.equals(action)) {
      callbackContext.success()
    } else if (PushConstants.HAS_PERMISSION.equals(action)) {
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          val jo = JSONObject()
          try {
            Log.d(LOG_TAG,
                "has permission: " + NotificationManagerCompat.from(applicationContext).areNotificationsEnabled())
            jo.put("isEnabled", NotificationManagerCompat.from(applicationContext).areNotificationsEnabled())
            val pluginResult = PluginResult(PluginResult.Status.OK, jo)
            pluginResult.setKeepCallback(true)
            callbackContext.sendPluginResult(pluginResult)
          } catch (e: UnknownError) {
            callbackContext.error(e.getMessage())
          } catch (e: JSONException) {
            callbackContext.error(e.getMessage())
          }
        }
      })
    } else if (PushConstants.SET_APPLICATION_ICON_BADGE_NUMBER.equals(action)) {
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          Log.v(LOG_TAG, "setApplicationIconBadgeNumber: data=" + data.toString())
          try {
            setApplicationIconBadgeNumber(applicationContext, data.getJSONObject(0).getInt(PushConstants.BADGE))
          } catch (e: JSONException) {
            callbackContext.error(e.getMessage())
          }
          callbackContext.success()
        }
      })
    } else if (PushConstants.GET_APPLICATION_ICON_BADGE_NUMBER.equals(action)) {
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          Log.v(LOG_TAG, "getApplicationIconBadgeNumber")
          callbackContext.success(getApplicationIconBadgeNumber(applicationContext))
        }
      })
    } else if (PushConstants.CLEAR_ALL_NOTIFICATIONS.equals(action)) {
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          Log.v(LOG_TAG, "clearAllNotifications")
          clearAllNotifications()
          callbackContext.success()
        }
      })
    } else if (PushConstants.SUBSCRIBE.equals(action)) {
      // Subscribing for a topic
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          try {
            val topic: String = data.getString(0)
            subscribeToTopic(topic, registration_id)
            callbackContext.success()
          } catch (e: JSONException) {
            callbackContext.error(e.getMessage())
          }
        }
      })
    } else if (PushConstants.UNSUBSCRIBE.equals(action)) {
      // un-subscribing for a topic
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          try {
            val topic: String = data.getString(0)
            unsubscribeFromTopic(topic, registration_id)
            callbackContext.success()
          } catch (e: JSONException) {
            callbackContext.error(e.getMessage())
          }
        }
      })
    } else if (PushConstants.CREATE_CHANNEL.equals(action)) {
      // un-subscribing for a topic
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          try {
            // call create channel
            createChannel(data.getJSONObject(0))
            callbackContext.success()
          } catch (e: JSONException) {
            callbackContext.error(e.getMessage())
          }
        }
      })
    } else if (PushConstants.DELETE_CHANNEL.equals(action)) {
      // un-subscribing for a topic
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          try {
            val channelId: String = data.getString(0)
            deleteChannel(channelId)
            callbackContext.success()
          } catch (e: JSONException) {
            callbackContext.error(e.getMessage())
          }
        }
      })
    } else if (PushConstants.LIST_CHANNELS.equals(action)) {
      // un-subscribing for a topic
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          try {
            callbackContext.success(listChannels())
          } catch (e: JSONException) {
            callbackContext.error(e.getMessage())
          }
        }
      })
    } else if (PushConstants.CLEAR_NOTIFICATION.equals(action)) {
      // clearing a single notification
      cordova.getThreadPool().execute(object : Runnable() {
        fun run() {
          try {
            Log.v(LOG_TAG, "clearNotification")
            val id: Int = data.getInt(0)
            clearNotification(id)
            callbackContext.success()
          } catch (e: JSONException) {
            callbackContext.error(e.getMessage())
          }
        }
      })
    } else {
      Log.e(LOG_TAG, "Invalid action : $action")
      callbackContext.sendPluginResult(PluginResult(PluginResult.Status.INVALID_ACTION))
      return false
    }
    return true
  }

  @Override
  fun initialize(cordova: CordovaInterface?, webView: CordovaWebView?) {
    super.initialize(cordova, webView)
    isInForeground = true
  }

  @Override
  fun onPause(multitasking: Boolean) {
    super.onPause(multitasking)
    isInForeground = false
    val prefs: SharedPreferences = applicationContext.getSharedPreferences(PushConstants.COM_ADOBE_PHONEGAP_PUSH,
        Context.MODE_PRIVATE)
    if (prefs.getBoolean(PushConstants.CLEAR_NOTIFICATIONS, true)) {
      clearAllNotifications()
    }
  }

  @Override
  fun onResume(multitasking: Boolean) {
    super.onResume(multitasking)
    isInForeground = true
  }

  @Override
  fun onDestroy() {
    super.onDestroy()
    isInForeground = false
    gWebView = null
  }

  private fun clearAllNotifications() {
    val notificationManager: NotificationManager = cordova.getActivity()
        .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notificationManager.cancelAll()
  }

  private fun clearNotification(id: Int) {
    val notificationManager: NotificationManager = cordova.getActivity()
        .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val appName = this.cordova.getActivity().getPackageManager()
        .getApplicationLabel(this.cordova.getActivity().getApplicationInfo()) as String
    notificationManager.cancel(appName, id)
  }

  private fun subscribeToTopics(topics: JSONArray?, registrationToken: String) {
    if (topics != null) {
      var topic: String? = null
      for (i in 0 until topics.length()) {
        topic = topics.optString(i, null)
        subscribeToTopic(topic, registrationToken)
      }
    }
  }

  private fun subscribeToTopic(topic: String?, registrationToken: String) {
    if (topic != null) {
      Log.d(LOG_TAG, "Subscribing to topic: $topic")
      FirebaseMessaging.getInstance().subscribeToTopic(topic)
    }
  }

  private fun unsubscribeFromTopics(topics: JSONArray?, registrationToken: String) {
    if (topics != null) {
      var topic: String? = null
      for (i in 0 until topics.length()) {
        topic = topics.optString(i, null)
        unsubscribeFromTopic(topic, registrationToken)
      }
    }
  }

  private fun unsubscribeFromTopic(topic: String?, registrationToken: String) {
    if (topic != null) {
      Log.d(LOG_TAG, "Unsubscribing to topic: $topic")
      FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
    }
  }

  private fun getStringResourceByName(aString: String): String {
    val activity: Activity = cordova.getActivity()
    val packageName: String = activity.getPackageName()
    val resId: Int = activity.getResources().getIdentifier(aString, "string", packageName)
    return activity.getString(resId)
  }

  companion object {
    const val LOG_TAG = "Push_Plugin"
    private var pushContext: CallbackContext? = null
    private var gWebView: CordovaWebView? = null
    private val gCachedExtras: List<Bundle> = Collections.synchronizedList(ArrayList<Bundle>())
    var isInForeground = false
      private set
    private var registration_id = ""
    fun sendEvent(_json: JSONObject?) {
      val pluginResult = PluginResult(PluginResult.Status.OK, _json)
      pluginResult.setKeepCallback(true)
      if (pushContext != null) {
        pushContext.sendPluginResult(pluginResult)
      }
    }

    fun sendError(message: String?) {
      val pluginResult = PluginResult(PluginResult.Status.ERROR, message)
      pluginResult.setKeepCallback(true)
      if (pushContext != null) {
        pushContext.sendPluginResult(pluginResult)
      }
    }

    /*
   * Sends the pushbundle extras to the client application. If the client
   * application isn't currently active and the no-cache flag is not set, it is
   * cached for later processing.
   */
    fun sendExtras(extras: Bundle?) {
      if (extras != null) {
        val noCache: String = extras.getString(PushConstants.NO_CACHE)
        if (gWebView != null) {
          sendEvent(convertBundleToJson(extras))
        } else if (!"1".equals(noCache)) {
          Log.v(LOG_TAG, "sendExtras: caching extras to send at a later time.")
          gCachedExtras.add(extras)
        }
      }
    }

    /*
   * Retrives badge count from SharedPreferences
   */
    fun getApplicationIconBadgeNumber(context: Context): Int {
      val settings: SharedPreferences = context.getSharedPreferences(PushConstants.BADGE, Context.MODE_PRIVATE)
      return settings.getInt(PushConstants.BADGE, 0)
    }

    /*
   * Sets badge count on application icon and in SharedPreferences
   */
    fun setApplicationIconBadgeNumber(context: Context, badgeCount: Int) {
      if (badgeCount > 0) {
        ShortcutBadger.applyCount(context, badgeCount)
      } else {
        ShortcutBadger.removeCount(context)
      }
      val editor: SharedPreferences.Editor = context.getSharedPreferences(PushConstants.BADGE, Context.MODE_PRIVATE).edit()
      editor.putInt(PushConstants.BADGE, Math.max(badgeCount, 0))
      editor.apply()
    }

    /*
   * serializes a bundle to JSON.
   */
    private fun convertBundleToJson(extras: Bundle): JSONObject? {
      Log.d(LOG_TAG, "convert extras to json")
      try {
        val json = JSONObject()
        val additionalData = JSONObject()

        // Add any keys that need to be in top level json to this set
        val jsonKeySet: HashSet<String> = HashSet()
        Collections.addAll(jsonKeySet, PushConstants.TITLE, PushConstants.MESSAGE, PushConstants.COUNT, PushConstants.SOUND, PushConstants.IMAGE)
        val it: Iterator<String> = extras.keySet().iterator()
        while (it.hasNext()) {
          val key = it.next()
          val value: Object = extras.get(key)
          Log.d(LOG_TAG, "key = $key")
          if (jsonKeySet.contains(key)) {
            json.put(key, value)
          } else if (key.equals(PushConstants.COLDSTART)) {
            additionalData.put(key, extras.getBoolean(PushConstants.COLDSTART))
          } else if (key.equals(PushConstants.FOREGROUND)) {
            additionalData.put(key, extras.getBoolean(PushConstants.FOREGROUND))
          } else if (key.equals(PushConstants.DISMISSED)) {
            additionalData.put(key, extras.getBoolean(PushConstants.DISMISSED))
          } else if (value is String) {
            val strValue = value as String
            try {
              // Try to figure out if the value is another JSON object
              if (strValue.startsWith("{")) {
                additionalData.put(key, JSONObject(strValue))
              } else if (strValue.startsWith("[")) {
                additionalData.put(key, JSONArray(strValue))
              } else {
                additionalData.put(key, value)
              }
            } catch (e: Exception) {
              additionalData.put(key, value)
            }
          }
        } // while
        json.put(PushConstants.ADDITIONAL_DATA, additionalData)
        Log.v(LOG_TAG, "extrasToJSON: " + json.toString())
        return json
      } catch (e: JSONException) {
        Log.e(LOG_TAG, "extrasToJSON: JSON exception")
      }
      return null
    }

    val isInBackground: Boolean
      get() = !isInForeground
    @JvmStatic
    val isActive: Boolean
      get() = gWebView != null

    protected fun setRegistrationID(token: String) {
      registration_id = token
    }
  }
}
