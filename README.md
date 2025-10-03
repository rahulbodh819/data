# data


package com.elysion.baatein

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import com.elysion.baatein.model.BundleArgs
import com.elysion.baatein.model.SocketResponse
import com.elysion.baatein.model.StressTest
import com.elysion.baatein.network.Endpoints
import com.elysion.baatein.network.SocketManager
import com.elysion.baatein.utils.CallTimeoutListener
import com.elysion.baatein.utils.SecureStorageReader
import com.elysion.baatein.viewModels.ViewModelProviderSingleton
import com.software.baatein.R
import com.google.gson.Gson
import io.socket.client.Ack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.MediaPlayer

class ForegroundService : Service() {

    private var token : String = ""
    private var isIncomingCall = false
    private var channelId : String = ""
    private var uuid : String = ""
    private var timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var lastChannelId: String? = null
    private var INCOMING_CALL_NOTIFICATION_ID = 0
    private var role : String = ""
    private var hasShownIncomingNotification = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var keyguardLock: KeyguardManager.KeyguardLock
    private lateinit var fullScreenIntent: Intent

    private var isTimeoutCancelled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    var timeoutListener: CallTimeoutListener? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private lateinit var appPrefs : SharedPreferences
    private lateinit var flutterPrefs : SharedPreferences
    private var ringtoneUri : Uri ? = null
    private var android12RingtonePlayer: MediaPlayer? = null

    companion object {
        const val DEFAULT_NOTIFICATION_ID = 3456
        const val DEFAULT_CHANNEL_ID = "livekit_example_foreground"
        const val INCOMING_CALL_CHANNEL_ID = "incoming_call_channel_v16"
        const val ACTION_ACCEPT_CALL = "com.example.livekitprepsapp.ACCEPT_CALL"
        const val ACTION_REJECT_CALL = "com.example.livekitprepsapp.REJECT_CALL"
        const val ACTION_HANG_UP = "com.example.livekitperpsapp.HANG_UP"

        private val url = Endpoints.LiveKitKeys.LIVEKIT_SOCKET_URL
        private val e2eekey = Endpoints.LiveKitKeys.LIVEKIT_ROOM_KEY

        var ringtone: Ringtone? = null
        var vibrator: Vibrator? = null
        const val TAG = "ForegroundService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Keep CPU running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "com.elysion.baatein:call_wake_lock"
        )
        try {
            wakeLock.acquire(10 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wakeLock: ${e.message}")
        }

        // Temporarily disable keyguard
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardLock = km.newKeyguardLock("com.elysion.baatein:call_keyguard_lock")
        try {
            keyguardLock.disableKeyguard()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable keyguard: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: $intent")

        try {

            if (intent == null) {
                Log.e(TAG, "Intent is null, cannot start service")
                return START_NOT_STICKY
            }

            initializeProperties(intent)

            initializeSocketManager(channelId, uuid)

            // Handle specific actions
            when (intent?.action) {
                "com.elysion.baatein.CANCEL_TIMEOUT" -> {
                    cancelIncomingCallTimeout()
                    stopRingtone()
                    stopVibration()
                    stopSelfWithLogging("2")
                    return START_NOT_STICKY
                }

                ACTION_ACCEPT_CALL -> {
                    handleCallAccept()
                    cancelIncomingCallTimeout()
                    hasShownIncomingNotification = false
                    return START_NOT_STICKY
                }

                ACTION_REJECT_CALL -> {
                    handleCallReject()
                    SocketManager.destroy()
                    cancelIncomingCallTimeout()
                    hasShownIncomingNotification = false
                    return START_NOT_STICKY
                }

                ACTION_HANG_UP -> {
                    handleCallHangUp()
                    SocketManager.destroy()
                    hasShownIncomingNotification = false
                    return START_NOT_STICKY
                }
            }

            // Show placeholder foreground notification
            createNotificationChannel()


            val safeCallerName = getCallerName(intent , role)

            val person = Person.Builder()
                .setName(safeCallerName)
                .setImportant(true)
                .build()

            val args = BundleArgs(
                url = url,
                token = token,
                uuid = uuid,
                role = role,
                e2eeOn = false,
                e2eeKey = e2eekey,
                channelId = channelId,
                stressTest = StressTest.None
            )

            // Show appropriate notification
            if (isIncomingCall) {
                if (channelId == lastChannelId || hasShownIncomingNotification) {
                    Log.d(TAG, "Already showing incoming call for same channelId: $channelId")
                    cancelOngoingCallNotifications()
                }

                lastChannelId = channelId
                hasShownIncomingNotification = true

                // For greater than android 12
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    showIncomingCallNotification(safeCallerName, person, args, channelId)
                } else {
                    ShowIncomingNotification(safeCallerName, person, args, channelId)
                }
            } else {
                Log.d(TAG, "Starting outgoing call notification for channelId: $channelId")
                val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
                cancelIncomingCallTimeout()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    showOngoingCallNotification(person, args, safeCallerName)
                } else {
                    ShowOngoingNotification(person, args, safeCallerName)
                }
            }

        }catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
            stopSelfWithLogging("1")
        }

        return START_STICKY
    }

    private fun getCallerName(intent: Intent?, role: String): String {
        val sharedPreferences = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
        val callerName = when (role) {
            "user", "" -> sharedPreferences.getString("receiverName", "Unknown Caller")
            else -> sharedPreferences.getString(Endpoints.SharedPrefKeys.CALLER_NAME, "Unknown Caller")
        }
        Log.d(TAG, "Caller name: $callerName for role: $role")
        return callerName ?: "Unknown Caller"
    }

    private fun initializeSocketManager(channelId : String, uuid : String){
        CoroutineScope(Dispatchers.Main).launch {
            try {
                SocketManager.initialize(this@ForegroundService, channelId, uuid)
                delay(200)
                SocketManager.joinChannelIfNeeded(channelId) {
                    SocketManager.registerCallEndListener(channelId) { data ->
                        if (data.optBoolean("isEnded")) {
                            Log.w(TAG, "Received evenet isEnded from socket. Trying to cancel ongoing notifiacation")
                            SocketManager.destroy()
                            cancelOngoingCallNotifications()
                        }
                    }
                }
            }catch (e : Exception){
                Log.e(TAG, "SocketManager initialization failed: ${e.message}")
                stopSelfWithLogging("3")
                cancelOngoingCallNotifications()
            }
        }
    }

    private fun initializeProperties(intent: Intent?) {
        try {

            flutterPrefs = getSharedPreferences(Endpoints.FLUTTER_SHARED_PREFS, Context.MODE_PRIVATE)
            appPrefs = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)

            // Always prefer Flutter values
            role = flutterPrefs.getString("flutter.role", null)
                ?: ""
            Log.d(TAG, "Role (from FlutterPrefs): $role")

            // First check intent, then Flutter prefs, fallback App prefs
            channelId = intent?.getStringExtra("channelId")
                ?: flutterPrefs.getString("flutter.channelId", null)
                        ?: appPrefs.getString(Endpoints.SharedPrefKeys.CHANNEL_ID, "")
                        ?: ""
            Log.d(TAG, "Final channelId: '$channelId'")

            if (channelId.isEmpty()) {
                Log.e(TAG, "Channel ID is empty, cannot proceed")
                stopSelfWithLogging("6")
                return
            }

            isIncomingCall = intent?.getBooleanExtra("isIncomingCall", false) ?: false
            INCOMING_CALL_NOTIFICATION_ID = abs(channelId.hashCode())

            uuid = flutterPrefs.getString("flutter.uuid", null)
                ?: appPrefs.getString(Endpoints.SharedPrefKeys.USER_ID, "")
                        ?: ""
            Log.d(TAG, "UUID (from FlutterPrefs): $uuid")

            if (uuid.isEmpty()) {
                Log.e(TAG, "UUID is empty, cannot initialize SocketManager")
            }

        }catch (e : Exception){
            Log.e(TAG, "Failed to initialize properties: ${e.message}")
        }
    }

    private fun handleCallAccept() {
        Log.d(TAG, "Handling call accept")

        stopRingtone()
        stopVibration()
        stopAndroid12Ringtone()
        cancelIncomingCallTimeout()

        val appPrefs = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
        val callType = appPrefs.getString("callType", "call") ?: "call"

        SocketManager.emitCallAccepted(this, channelId, Ack { ackArgs ->
            if (ackArgs.isNullOrEmpty()) {
                Log.w(TAG, "accept_call ACK response is empty or null")
                return@Ack
            }

            val responseJson = ackArgs[0] as? JSONObject
            if (responseJson == null) {
                Log.e(TAG, "ACK response is not a JSONObject")
                return@Ack
            } else {
                Log.d(TAG, "ACK response: $responseJson")
            }

            val socketResponse = try {
                Gson().fromJson(responseJson.toString(), SocketResponse::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse SocketResponse", e)
                return@Ack
            }

            val livekitToken = socketResponse.data?.token ?: run {
                Log.e(TAG, "Token missing in SocketResponse")
                return@Ack
            }

            val livekitSocketURL = socketResponse.data?.livekitSocketURL ?: run {
                Log.e(TAG, "URL missing in SocketResponse")
                return@Ack
            }

            val balance = socketResponse.data?.balance ?: run {
                Log.e(TAG, "Balance missing in SocketResponse")
                return@Ack
            }

            val rpm = socketResponse.data?.call?.rpm ?: run {
                Log.e(TAG, "RPM missing in SocketResponse")
                return@Ack
            }

            Log.d(TAG, "Balance: $balance")
            Log.d(TAG, "RPM: $rpm")
            appPrefs.edit().apply {
                putInt("walletBalance", balance.toFloat().toInt())
                putInt("callRpm", rpm.toFloat().toInt())
                apply()
            }

            val args = BundleArgs(
                url = livekitSocketURL,
                role = role,
                uuid = uuid,
                token = livekitToken,
                e2eeOn = false,
                e2eeKey = channelId,
                channelId = channelId,
                stressTest = StressTest.None
            )

            val activityIntent = if (callType == "call") {
                Log.d(TAG, "Starting audio call activity")
                Intent(this, AudioCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("launchedFromCall", true)
                    putExtra(AudioCallActivity.KEY_ARGS, args)
                }
            } else {
                Log.d(TAG, "Starting video call activity")
                Intent(this, VideoCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("launchedFromCall", true)
                    putExtra(VideoCallActivity.KEY_ARGS, args)
                }
            }

            try {
                startActivity(activityIntent)
                Log.d(TAG, "Activity started successfully")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: Cannot start activity from background: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start activity: ${e.message}")
            }
        })

        cancelOngoingCallNotifications()
    }

    private fun handleCallReject() {
        Log.d(TAG, "Handling call reject")

        stopRingtone()
        stopVibration()
        stopAndroid12Ringtone()
        cancelIncomingCallTimeout()

        val userId = uuid
        if (channelId.isEmpty() || channelId.isBlank()) {
            channelId = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
                .getString(Endpoints.SharedPrefKeys.CHANNEL_ID, "") ?: ""
        }

        Log.d(TAG, "Channel ID: $channelId")

        SocketManager.emitCallEnded(this, channelId, userId, "rejected by $role", true)
        cancelOngoingCallNotifications()
    }

    private fun handleCallHangUp() {
        Log.d(TAG, "Handling call hang up")

        stopRingtone()
        stopVibration()
        cancelIncomingCallTimeout()

        val userId = uuid
        SocketManager.emitCallEnded(this, channelId, userId, "Hang up by $role", false)
        cancelOngoingCallNotifications()
    }

    private fun cancelOngoingCallNotifications() {
        try {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
            notificationManager.cancel(DEFAULT_NOTIFICATION_ID)
            notificationManager.cancelAll()
        } catch (e: Exception) {
            Log.e(TAG, "NOTIDFICATION CANCELING ERROR: ${e.message}")
        }

        stopRingtone()
        stopVibration()
        stopAndroid12Ringtone()
        releaseLocks()
        stopSelfWithLogging("7")
    }

    private fun dismissIncomingCall(reason: String) {
        if(isTimeoutCancelled ) return
        Log.d(TAG, "Call dismissed due to: $reason")
        SocketManager.emitCallEnded(this, channelId, uuid, reason, true)
        cancelOngoingCallNotifications()
        stopSelfWithLogging("1")
    }

    private fun cancelIncomingCallTimeout() {
        isTimeoutCancelled = true
        Log.d(TAG, "Canceling incoming call timeout")
        timeoutRunnable?.let {
            timeoutHandler?.removeCallbacks(it)
        }
    }

    private fun ShowIncomingNotification(
        callerName: String,
        person: Person,
        args: BundleArgs,
        channelId: String
    ) {
        val callType = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE).getString(
            "callType",
            "call"
        )

        val acceptIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_ACCEPT_CALL
        }

        val answerPendingIntent = PendingIntent.getService(
            this, 1001, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_REJECT_CALL
        }

        val declinePendingIntent = PendingIntent.getService(
            this, 1, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lockScreenManager = getSystemService(KeyguardManager::class.java)
        val isLocked = lockScreenManager?.isKeyguardLocked ?: false

        val lockAnswerIntent = Intent(this, HandleCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("launchedFromCall", true)
            putExtra("acceptCall", true)
            putExtra(AudioCallActivity.KEY_ARGS, args)
        }

        val lockAnswerPendingIntent = PendingIntent.getActivity(
            this, 1002, lockAnswerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(this, HandleCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("channelId", channelId)
            putExtra("show_over_keyguard", true)
            putExtra(AudioCallActivity.KEY_ARGS, args)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 2, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (callType == "video") {
            "Incoming Video Call from $callerName"
        } else {
            "Incoming Audio Call from $callerName"
        }

        val builder = Notification.Builder(this, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Baatein")
            .setContentText(contentText)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(false)
            .setPriority(Notification.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(50000)

        if (!isLocked) {
            builder.addAction(R.drawable.outline_call_end_24, "Decline", declinePendingIntent)
            builder.addAction(R.drawable.outline_call_24, "Accept", answerPendingIntent)
        } else {
            // On lock screen, answer action goes to HandleCallActivity
            builder.addAction(R.drawable.outline_call_end_24, "Decline", declinePendingIntent)
            builder.addAction(R.drawable.outline_call_24, "Accept", lockAnswerPendingIntent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    person,
                    declinePendingIntent,
                    answerPendingIntent
                )
            )
        }

        val notification = builder.build()
        notification.flags = notification.flags or
//                Notification.FLAG_INSISTENT or
                Notification.FLAG_NO_CLEAR

        startForeground(
            INCOMING_CALL_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )

        wakeUpScreen()
        handleRingtoneForVersion()
        startVibration()
        setupCallTimeout()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun showIncomingCallNotification(
        callerName: String,
        person: Person,
        args: BundleArgs,
        channelId: String
    ) {
        val callType = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE).getString(
            "callType",
            "call"
        )

        val acceptIntent = Intent(
            this,
            if (callType == "video") VideoCallActivity::class.java else AudioCallActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("launchedFromCall", true)
            putExtra("acceptCall", true)
            putExtra(VideoCallActivity.KEY_ARGS, args)
        }

        val answerPendingIntent = PendingIntent.getActivity(
            this, 1001, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declinePendingIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ForegroundService::class.java).apply { action = ACTION_REJECT_CALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // --- LOCK SCREEN ONLY: launch HandleCallActivity ---
        val lockScreenManager = getSystemService(KeyguardManager::class.java)
        val isLocked = lockScreenManager?.isKeyguardLocked ?: false

        val lockAnswerIntent = Intent(this, HandleCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("launchedFromCall", true)
            putExtra("acceptCall", true)
            putExtra(AudioCallActivity.KEY_ARGS, args)
        }

        val lockAnswerPendingIntent = PendingIntent.getActivity(
            this, 1002, lockAnswerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(this, HandleCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION // ADDED: Prevents user interaction delays

            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("channelId", channelId)
            putExtra("show_over_keyguard", true) // ADDED: Force show over keyguard
            putExtra(AudioCallActivity.KEY_ARGS, args)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 2, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (callType == "video") {
            "Incoming Video Call from $callerName"
        } else {
            "Incoming Audio Call from $callerName"
        }

        val notificationBuilder = Notification.Builder(this, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Baatein")
            .setContentText(contentText)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColorized(true)
            .setTimeoutAfter(50000)

        // --- Show buttons only if device unlocked ---
        if (!isLocked) {
            notificationBuilder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    person,
                    declinePendingIntent,
                    answerPendingIntent
                )
            )
        } else {
            // On lock screen, answer action goes to HandleCallActivity
            notificationBuilder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    person,
                    declinePendingIntent,
                    lockAnswerPendingIntent
                )
            )
        }

        val notification = notificationBuilder.build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR

        startForeground(
            INCOMING_CALL_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notification)

        wakeUpScreen()
        startVibration()
        handleRingtoneForVersion()
        setupCallTimeout()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun showOngoingCallNotification(person: Person, args: BundleArgs, callerName: String) {
        val hangUpPendingIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ForegroundService::class.java).apply { action = ACTION_HANG_UP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callType = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE).getString(
            "callType",
            "call"
        )
        val role = getSharedPreferences(Endpoints.FLUTTER_SHARED_PREFS, Context.MODE_PRIVATE)
            .getString("flutter.role", "")

        val sharedPreferences = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
        val isThroughFullScreenNotification = sharedPreferences.getBoolean(Endpoints.SharedPrefKeys.IS_THROUGH_FULL_SCREEN_NOTIFICATION, false)

        fullScreenIntent = Intent(
            this,
            if (isThroughFullScreenNotification) {
                HandleCallActivity::class.java
            } else {
                if (callType == "video") VideoCallActivity::class.java else AudioCallActivity::class.java
            }
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("launchedFromCall", true)
            putExtra(VideoCallActivity.KEY_ARGS, args)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 3, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Baatein")
            .setContentText("You're in a call")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, false)
            .setPriority(Notification.PRIORITY_HIGH)
            .setStyle(Notification.CallStyle.forOngoingCall(person, hangUpPendingIntent))
            .build()

        startForeground(
            DEFAULT_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )
    }

    private fun ShowOngoingNotification(person: Person, args: BundleArgs, callerName: String) {
        val hangUpIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_HANG_UP
        }

        val hangUpPendingIntent = PendingIntent.getService(
            this, 2, hangUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callType = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
            .getString("callType", "call")

        val role = getSharedPreferences(Endpoints.FLUTTER_SHARED_PREFS, Context.MODE_PRIVATE)
            .getString("flutter.role", "")

        val sharedPreferences = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
        val isThroughFullScreenNotification = sharedPreferences.getBoolean(Endpoints.SharedPrefKeys.IS_THROUGH_FULL_SCREEN_NOTIFICATION, false)

        fullScreenIntent = Intent(
            this,
            if (isThroughFullScreenNotification) {
                HandleCallActivity::class.java
            } else {
                if (callType == "video") VideoCallActivity::class.java else AudioCallActivity::class.java
            }
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("launchedFromCall", true)
            putExtra(VideoCallActivity.KEY_ARGS, args)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 3, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Baatein")
            .setContentText("You're in a call")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, false)
            .setAutoCancel(false)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        builder.addAction(R.drawable.outline_call_end_24, "Hang Up", hangUpPendingIntent)

        val notification = builder.build()
        startForeground(
            DEFAULT_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
                application, RingtoneManager.TYPE_RINGTONE
            )

            if (ringtoneUri == null) {
                ringtoneUri = Uri.parse("android.resource://${packageName}/${R.raw.call_ringtone}")
            }

            Log.d(TAG, "Ringtone URI: $ringtoneUri")

            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_RING)
                .build()

            val incomingCallChannel = NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "Channel for incoming call notifications"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                setBypassDnd(true)
                setSound(null, null)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setImportance(NotificationManager.IMPORTANCE_MAX)
                }
            }

            val ongoingCallChannel = NotificationChannel(
                DEFAULT_CHANNEL_ID,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Channel for ongoing call notifications"
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            }

            notificationManager.createNotificationChannel(incomingCallChannel)
            notificationManager.createNotificationChannel(ongoingCallChannel)
        }
    }

    private fun handleRingtoneForVersion() {
        playManualRingtoneForAndroid12andBelow()
//        when {
//            Build.VERSION.SDK_INT == Build.VERSION_CODES.S -> {
//                // Android 12 - use manual ringtone
//                Log.d(TAG, "Starting manual ringtone for Android 12")
//                playManualRingtoneForAndroid12andBelow()
//            }
//            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
//                // Android 13+ - channel sound works fine
//                Log.d(TAG, "Using channel sound for Android 13+")
//                // Channel sound will handle it automatically
//            }
//            else -> {
//                // Pre-Android 12 - channel sound works
//                Log.d(TAG, "Using channel sound for pre-Android 12")
//                playManualRingtoneForAndroid12andBelow()
//            }
//        }
    }

    private fun playManualRingtoneForAndroid12andBelow() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Request audio focus specifically for Android 12
            val result = audioManager.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> stopAndroid12Ringtone()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // Keep playing for incoming calls
                        }
                    }
                },
                AudioManager.STREAM_RING,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                android12RingtonePlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(AudioManager.STREAM_RING)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )

                    try {
                        setDataSource(this@ForegroundService, ringtoneUri!!)
                        isLooping = true
                        setOnPreparedListener { mp ->
                            mp.start()
                            Log.d(TAG, "Android 12 manual ringtone started")
                        }
                        setOnErrorListener { mp, what, extra ->
                            Log.e(TAG, "Android 12 ringtone error: what=$what, extra=$extra")
                            false
                        }
                        prepareAsync()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to prepare Android 12 ringtone: ${e.message}")
                        release()
                        android12RingtonePlayer = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Android 12 ringtone: ${e.message}")
        }
    }

    private fun stopAndroid12Ringtone() {
        try {
            android12RingtonePlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            android12RingtonePlayer = null

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocus(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Android 12 ringtone: ${e.message}")
        }
    }

    // IMPROVED: Better ringtone handling with proper audio focus
    private fun playRingtone() {
        try {
            // ADDED: Request audio focus for ringtone
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* Handle focus changes */ }
                    .build()

                audioManager.requestAudioFocus(focusRequest)
            } else {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                val ringtoneUri = Uri.parse("android.resource://${packageName}/${R.raw.call_ringtone}")
                ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)

                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_RING) // ADDED: Proper stream type
                    .build()

                ringtone?.isLooping = true // ADDED: Loop ringtone
                ringtone?.play() ?: Log.w(TAG, "Ringtone is null, cannot play")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringtone: ${e.message}", e)
        }
    }

    private fun stopRingtone() {
        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            ringtone = null

            // ADDED: Release audio focus
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocus(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone: ${e.message}")
        }
    }


//    private fun startVibration() {
//        try {
//            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
//
//            // IMPROVED: More noticeable vibration pattern
//            val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
//
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                val effect = VibrationEffect.createWaveform(pattern, 0) // CHANGED: Loop vibration
//                vibrator?.vibrate(effect)
//            } else {
//                vibrator?.vibrate(pattern, 0) // CHANGED: Loop vibration
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to start vibration: ${e.message}")
//        }
//    }
//
//    private fun stopVibration() {
//        vibrator?.cancel()
//        vibrator = null
//    }

    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "com.elysion.baatein:incoming_call_screen_wake"
            )
            if (!screenWakeLock.isHeld) {
                screenWakeLock.acquire(15000) // Reduced duration

                Handler(Looper.getMainLooper()).postDelayed({
                    if (screenWakeLock.isHeld) {
                        screenWakeLock.release()
                    }
                }, 15000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake screen: ${e.message}")
        }
    }

    private fun setupCallTimeout() {
        timeoutRunnable = Runnable {
            dismissIncomingCall("Call not answered")
            timeoutListener?.onCallTimeout()

        }
        timeoutRunnable?.let {
            timeoutHandler?.postDelayed(it, 50000)
        } ?: Log.e(TAG, "Failed to set up call timeout: timeoutRunnable is null")
    }

    private fun releaseLocks() {
        try {
            if (wakeLock.isHeld) wakeLock.release()
            keyguardLock.reenableKeyguard()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks: ${e.message}")
        }
    }

    override fun onDestroy() {

        Log.d(TAG, "=== onDestroy() called ===")
//        Log.d(TAG, "Service uptime: ${System.currentTimeMillis() - serviceStartTime}ms")

        // Print full stack trace to see what called onDestroy
        val stackTrace = Thread.currentThread().stackTrace
        stackTrace.forEachIndexed { index, element ->
            Log.d(TAG, "Stack[$index]: ${element.className}.${element.methodName}:${element.lineNumber}")
        }

        Log.d(TAG, "Current notification ID: $INCOMING_CALL_NOTIFICATION_ID")
        Log.d(TAG, "Has shown notification: $hasShownIncomingNotification")
        Log.d(TAG, "Channel ID: $channelId")


        Log.d(TAG, "onDestroy called")
        mainHandler.removeCallbacksAndMessages(null)
        cancelIncomingCallTimeout()
        stopRingtone()
//        stopVibration()
//        releaseLocks()
        super.onDestroy()
    }

    private fun checkVibrationPermissions(): Boolean {
        val hasVibrationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(android.Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permission automatically granted on older versions
        }

        Log.d(TAG, "Vibration permission granted: $hasVibrationPermission")
        return hasVibrationPermission
    }

    private fun checkDeviceVibrationCapability(): Boolean {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        val hasVibrator = vibrator?.hasVibrator() ?: false
        Log.d(TAG, "Device has vibrator: $hasVibrator")
        return hasVibrator
    }

    private fun checkDNDSettings(): Boolean {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val currentInterruptionFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.currentInterruptionFilter
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }

        val isDNDActive = currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        Log.d(TAG, "Do Not Disturb active: $isDNDActive, Filter: $currentInterruptionFilter")
        return !isDNDActive // Return true if DND is NOT active
    }

    private fun startVibration() {
        try {
            return
            Log.d(TAG, "Starting vibration...")

            // Check prerequisites
            if (!checkVibrationPermissions()) {
                Log.e(TAG, "Vibration permission not granted")
                return
            }

            if (!checkDeviceVibrationCapability()) {
                Log.e(TAG, "Device doesn't support vibration")
                return
            }

            if (!checkDNDSettings()) {
                Log.w(TAG, "Do Not Disturb is active - vibration may be blocked")
            }

            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

            if (vibrator == null) {
                Log.e(TAG, "Vibrator service is null")
                return
            }

            // Check if device supports vibration
            if (!vibrator!!.hasVibrator()) {
                Log.e(TAG, "Device does not have vibrator capability")
                return
            }

            // IMPROVED: Better vibration pattern and amplitude
            val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000) // More noticeable pattern

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "Using VibrationEffect for Android O+")

                // Try with amplitude control first
                try {
                    val amplitudes = intArrayOf(0, 255, 0, package com.elysion.baatein

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.annotation.RequiresApi
import com.elysion.baatein.model.BundleArgs
import com.elysion.baatein.model.SocketResponse
import com.elysion.baatein.model.StressTest
import com.elysion.baatein.network.Endpoints
import com.elysion.baatein.network.SocketManager
import com.elysion.baatein.utils.CallTimeoutListener
import com.elysion.baatein.utils.SecureStorageReader
import com.elysion.baatein.viewModels.ViewModelProviderSingleton
import com.software.baatein.R
import com.google.gson.Gson
import io.socket.client.Ack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import android.media.AudioManager
import android.media.AudioFocusRequest
import android.media.MediaPlayer

class ForegroundService : Service() {

    private var token : String = ""
    private var isIncomingCall = false
    private var channelId : String = ""
    private var uuid : String = ""
    private var timeoutHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var lastChannelId: String? = null
    private var INCOMING_CALL_NOTIFICATION_ID = 0
    private var role : String = ""
    private var hasShownIncomingNotification = false
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var keyguardLock: KeyguardManager.KeyguardLock
    private lateinit var fullScreenIntent: Intent

    private var isTimeoutCancelled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    var timeoutListener: CallTimeoutListener? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private lateinit var appPrefs : SharedPreferences
    private lateinit var flutterPrefs : SharedPreferences
    private var ringtoneUri : Uri ? = null
    private var android12RingtonePlayer: MediaPlayer? = null

    companion object {
        const val DEFAULT_NOTIFICATION_ID = 3456
        const val DEFAULT_CHANNEL_ID = "livekit_example_foreground"
        const val INCOMING_CALL_CHANNEL_ID = "incoming_call_channel_v16"
        const val ACTION_ACCEPT_CALL = "com.example.livekitprepsapp.ACCEPT_CALL"
        const val ACTION_REJECT_CALL = "com.example.livekitprepsapp.REJECT_CALL"
        const val ACTION_HANG_UP = "com.example.livekitperpsapp.HANG_UP"

        private val url = Endpoints.LiveKitKeys.LIVEKIT_SOCKET_URL
        private val e2eekey = Endpoints.LiveKitKeys.LIVEKIT_ROOM_KEY

        var ringtone: Ringtone? = null
        var vibrator: Vibrator? = null
        const val TAG = "ForegroundService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Keep CPU running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "com.elysion.baatein:call_wake_lock"
        )
        try {
            wakeLock.acquire(10 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wakeLock: ${e.message}")
        }

        // Temporarily disable keyguard
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        keyguardLock = km.newKeyguardLock("com.elysion.baatein:call_keyguard_lock")
        try {
            keyguardLock.disableKeyguard()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable keyguard: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: $intent")

        try {

            if (intent == null) {
                Log.e(TAG, "Intent is null, cannot start service")
                return START_NOT_STICKY
            }

            initializeProperties(intent)

            initializeSocketManager(channelId, uuid)

            // Handle specific actions
            when (intent?.action) {
                "com.elysion.baatein.CANCEL_TIMEOUT" -> {
                    cancelIncomingCallTimeout()
                    stopRingtone()
                    stopVibration()
                    stopSelfWithLogging("2")
                    return START_NOT_STICKY
                }

                ACTION_ACCEPT_CALL -> {
                    handleCallAccept()
                    cancelIncomingCallTimeout()
                    hasShownIncomingNotification = false
                    return START_NOT_STICKY
                }

                ACTION_REJECT_CALL -> {
                    handleCallReject()
                    SocketManager.destroy()
                    cancelIncomingCallTimeout()
                    hasShownIncomingNotification = false
                    return START_NOT_STICKY
                }

                ACTION_HANG_UP -> {
                    handleCallHangUp()
                    SocketManager.destroy()
                    hasShownIncomingNotification = false
                    return START_NOT_STICKY
                }
            }

            // Show placeholder foreground notification
            createNotificationChannel()


            val safeCallerName = getCallerName(intent , role)

            val person = Person.Builder()
                .setName(safeCallerName)
                .setImportant(true)
                .build()

            val args = BundleArgs(
                url = url,
                token = token,
                uuid = uuid,
                role = role,
                e2eeOn = false,
                e2eeKey = e2eekey,
                channelId = channelId,
                stressTest = StressTest.None
            )

            // Show appropriate notification
            if (isIncomingCall) {
                if (channelId == lastChannelId || hasShownIncomingNotification) {
                    Log.d(TAG, "Already showing incoming call for same channelId: $channelId")
                    cancelOngoingCallNotifications()
                }

                lastChannelId = channelId
                hasShownIncomingNotification = true

                // For greater than android 12
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    showIncomingCallNotification(safeCallerName, person, args, channelId)
                } else {
                    ShowIncomingNotification(safeCallerName, person, args, channelId)
                }
            } else {
                Log.d(TAG, "Starting outgoing call notification for channelId: $channelId")
                val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
                cancelIncomingCallTimeout()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    showOngoingCallNotification(person, args, safeCallerName)
                } else {
                    ShowOngoingNotification(person, args, safeCallerName)
                }
            }

        }catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
            stopSelfWithLogging("1")
        }

        return START_STICKY
    }

    private fun getCallerName(intent: Intent?, role: String): String {
        val sharedPreferences = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
        val callerName = when (role) {
            "user", "" -> sharedPreferences.getString("receiverName", "Unknown Caller")
            else -> sharedPreferences.getString(Endpoints.SharedPrefKeys.CALLER_NAME, "Unknown Caller")
        }
        Log.d(TAG, "Caller name: $callerName for role: $role")
        return callerName ?: "Unknown Caller"
    }

    private fun initializeSocketManager(channelId : String, uuid : String){
        CoroutineScope(Dispatchers.Main).launch {
            try {
                SocketManager.initialize(this@ForegroundService, channelId, uuid)
                delay(200)
                SocketManager.joinChannelIfNeeded(channelId) {
                    SocketManager.registerCallEndListener(channelId) { data ->
                        if (data.optBoolean("isEnded")) {
                            Log.w(TAG, "Received evenet isEnded from socket. Trying to cancel ongoing notifiacation")
                            SocketManager.destroy()
                            cancelOngoingCallNotifications()
                        }
                    }
                }
            }catch (e : Exception){
                Log.e(TAG, "SocketManager initialization failed: ${e.message}")
                stopSelfWithLogging("3")
                cancelOngoingCallNotifications()
            }
        }
    }

    private fun initializeProperties(intent: Intent?) {
        try {

            flutterPrefs = getSharedPreferences(Endpoints.FLUTTER_SHARED_PREFS, Context.MODE_PRIVATE)
            appPrefs = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)

            // Always prefer Flutter values
            role = flutterPrefs.getString("flutter.role", null)
                ?: ""
            Log.d(TAG, "Role (from FlutterPrefs): $role")

            // First check intent, then Flutter prefs, fallback App prefs
            channelId = intent?.getStringExtra("channelId")
                ?: flutterPrefs.getString("flutter.channelId", null)
                        ?: appPrefs.getString(Endpoints.SharedPrefKeys.CHANNEL_ID, "")
                        ?: ""
            Log.d(TAG, "Final channelId: '$channelId'")

            if (channelId.isEmpty()) {
                Log.e(TAG, "Channel ID is empty, cannot proceed")
                stopSelfWithLogging("6")
                return
            }

            isIncomingCall = intent?.getBooleanExtra("isIncomingCall", false) ?: false
            INCOMING_CALL_NOTIFICATION_ID = abs(channelId.hashCode())

            uuid = flutterPrefs.getString("flutter.uuid", null)
                ?: appPrefs.getString(Endpoints.SharedPrefKeys.USER_ID, "")
                        ?: ""
            Log.d(TAG, "UUID (from FlutterPrefs): $uuid")

            if (uuid.isEmpty()) {
                Log.e(TAG, "UUID is empty, cannot initialize SocketManager")
            }

        }catch (e : Exception){
            Log.e(TAG, "Failed to initialize properties: ${e.message}")
        }
    }

    private fun handleCallAccept() {
        Log.d(TAG, "Handling call accept")

        stopRingtone()
        stopVibration()
        stopAndroid12Ringtone()
        cancelIncomingCallTimeout()

        val appPrefs = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
        val callType = appPrefs.getString("callType", "call") ?: "call"

        SocketManager.emitCallAccepted(this, channelId, Ack { ackArgs ->
            if (ackArgs.isNullOrEmpty()) {
                Log.w(TAG, "accept_call ACK response is empty or null")
                return@Ack
            }

            val responseJson = ackArgs[0] as? JSONObject
            if (responseJson == null) {
                Log.e(TAG, "ACK response is not a JSONObject")
                return@Ack
            } else {
                Log.d(TAG, "ACK response: $responseJson")
            }

            val socketResponse = try {
                Gson().fromJson(responseJson.toString(), SocketResponse::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse SocketResponse", e)
                return@Ack
            }

            val livekitToken = socketResponse.data?.token ?: run {
                Log.e(TAG, "Token missing in SocketResponse")
                return@Ack
            }

            val livekitSocketURL = socketResponse.data?.livekitSocketURL ?: run {
                Log.e(TAG, "URL missing in SocketResponse")
                return@Ack
            }

            val balance = socketResponse.data?.balance ?: run {
                Log.e(TAG, "Balance missing in SocketResponse")
                return@Ack
            }

            val rpm = socketResponse.data?.call?.rpm ?: run {
                Log.e(TAG, "RPM missing in SocketResponse")
                return@Ack
            }

            Log.d(TAG, "Balance: $balance")
            Log.d(TAG, "RPM: $rpm")
            appPrefs.edit().apply {
                putInt("walletBalance", balance.toFloat().toInt())
                putInt("callRpm", rpm.toFloat().toInt())
                apply()
            }

            val args = BundleArgs(
                url = livekitSocketURL,
                role = role,
                uuid = uuid,
                token = livekitToken,
                e2eeOn = false,
                e2eeKey = channelId,
                channelId = channelId,
                stressTest = StressTest.None
            )

            val activityIntent = if (callType == "call") {
                Log.d(TAG, "Starting audio call activity")
                Intent(this, AudioCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("launchedFromCall", true)
                    putExtra(AudioCallActivity.KEY_ARGS, args)
                }
            } else {
                Log.d(TAG, "Starting video call activity")
                Intent(this, VideoCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra("launchedFromCall", true)
                    putExtra(VideoCallActivity.KEY_ARGS, args)
                }
            }

            try {
                startActivity(activityIntent)
                Log.d(TAG, "Activity started successfully")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException: Cannot start activity from background: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start activity: ${e.message}")
            }
        })

        cancelOngoingCallNotifications()
    }

    private fun handleCallReject() {
        Log.d(TAG, "Handling call reject")

        stopRingtone()
        stopVibration()
        stopAndroid12Ringtone()
        cancelIncomingCallTimeout()

        val userId = uuid
        if (channelId.isEmpty() || channelId.isBlank()) {
            channelId = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
                .getString(Endpoints.SharedPrefKeys.CHANNEL_ID, "") ?: ""
        }

        Log.d(TAG, "Channel ID: $channelId")

        SocketManager.emitCallEnded(this, channelId, userId, "rejected by $role", true)
        cancelOngoingCallNotifications()
    }

    private fun handleCallHangUp() {
        Log.d(TAG, "Handling call hang up")

        stopRingtone()
        stopVibration()
        cancelIncomingCallTimeout()

        val userId = uuid
        SocketManager.emitCallEnded(this, channelId, userId, "Hang up by $role", false)
        cancelOngoingCallNotifications()
    }

    private fun cancelOngoingCallNotifications() {
        try {
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
            notificationManager.cancel(DEFAULT_NOTIFICATION_ID)
            notificationManager.cancelAll()
        } catch (e: Exception) {
            Log.e(TAG, "NOTIDFICATION CANCELING ERROR: ${e.message}")
        }

        stopRingtone()
        stopVibration()
        stopAndroid12Ringtone()
        releaseLocks()
        stopSelfWithLogging("7")
    }

    private fun dismissIncomingCall(reason: String) {
        if(isTimeoutCancelled ) return
        Log.d(TAG, "Call dismissed due to: $reason")
        SocketManager.emitCallEnded(this, channelId, uuid, reason, true)
        cancelOngoingCallNotifications()
        stopSelfWithLogging("1")
    }

    private fun cancelIncomingCallTimeout() {
        isTimeoutCancelled = true
        Log.d(TAG, "Canceling incoming call timeout")
        timeoutRunnable?.let {
            timeoutHandler?.removeCallbacks(it)
        }
    }

    private fun ShowIncomingNotification(
        callerName: String,
        person: Person,
        args: BundleArgs,
        channelId: String
    ) {
        val callType = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE).getString(
            "callType",
            "call"
        )

        val acceptIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_ACCEPT_CALL
        }

        val answerPendingIntent = PendingIntent.getService(
            this, 1001, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_REJECT_CALL
        }

        val declinePendingIntent = PendingIntent.getService(
            this, 1, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lockScreenManager = getSystemService(KeyguardManager::class.java)
        val isLocked = lockScreenManager?.isKeyguardLocked ?: false

        val lockAnswerIntent = Intent(this, HandleCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("launchedFromCall", true)
            putExtra("acceptCall", true)
            putExtra(AudioCallActivity.KEY_ARGS, args)
        }

        val lockAnswerPendingIntent = PendingIntent.getActivity(
            this, 1002, lockAnswerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(this, HandleCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("channelId", channelId)
            putExtra("show_over_keyguard", true)
            putExtra(AudioCallActivity.KEY_ARGS, args)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 2, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (callType == "video") {
            "Incoming Video Call from $callerName"
        } else {
            "Incoming Audio Call from $callerName"
        }

        val builder = Notification.Builder(this, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Baatein")
            .setContentText(contentText)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(false)
            .setPriority(Notification.PRIORITY_MAX)
            .setDefaults(Notification.DEFAULT_ALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setTimeoutAfter(50000)

        if (!isLocked) {
            builder.addAction(R.drawable.outline_call_end_24, "Decline", declinePendingIntent)
            builder.addAction(R.drawable.outline_call_24, "Accept", answerPendingIntent)
        } else {
            // On lock screen, answer action goes to HandleCallActivity
            builder.addAction(R.drawable.outline_call_end_24, "Decline", declinePendingIntent)
            builder.addAction(R.drawable.outline_call_24, "Accept", lockAnswerPendingIntent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    person,
                    declinePendingIntent,
                    answerPendingIntent
                )
            )
        }

        val notification = builder.build()
        notification.flags = notification.flags or
//                Notification.FLAG_INSISTENT or
                Notification.FLAG_NO_CLEAR

        startForeground(
            INCOMING_CALL_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )

        wakeUpScreen()
        handleRingtoneForVersion()
        startVibration()
        setupCallTimeout()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun showIncomingCallNotification(
        callerName: String,
        person: Person,
        args: BundleArgs,
        channelId: String
    ) {
        val callType = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE).getString(
            "callType",
            "call"
        )

        val acceptIntent = Intent(
            this,
            if (callType == "video") VideoCallActivity::class.java else AudioCallActivity::class.java
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("launchedFromCall", true)
            putExtra("acceptCall", true)
            putExtra(VideoCallActivity.KEY_ARGS, args)
        }

        val answerPendingIntent = PendingIntent.getActivity(
            this, 1001, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declinePendingIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ForegroundService::class.java).apply { action = ACTION_REJECT_CALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // --- LOCK SCREEN ONLY: launch HandleCallActivity ---
        val lockScreenManager = getSystemService(KeyguardManager::class.java)
        val isLocked = lockScreenManager?.isKeyguardLocked ?: false

        val lockAnswerIntent = Intent(this, HandleCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("launchedFromCall", true)
            putExtra("acceptCall", true)
            putExtra(AudioCallActivity.KEY_ARGS, args)
        }

        val lockAnswerPendingIntent = PendingIntent.getActivity(
            this, 1002, lockAnswerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fullScreenIntent = Intent(this, HandleCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION // ADDED: Prevents user interaction delays

            putExtra("callerName", callerName)
            putExtra("callType", callType)
            putExtra("channelId", channelId)
            putExtra("show_over_keyguard", true) // ADDED: Force show over keyguard
            putExtra(AudioCallActivity.KEY_ARGS, args)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 2, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (callType == "video") {
            "Incoming Video Call from $callerName"
        } else {
            "Incoming Audio Call from $callerName"
        }

        val notificationBuilder = Notification.Builder(this, INCOMING_CALL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Baatein")
            .setContentText(contentText)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setPriority(Notification.PRIORITY_MAX)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColorized(true)
            .setTimeoutAfter(50000)

        // --- Show buttons only if device unlocked ---
        if (!isLocked) {
            notificationBuilder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    person,
                    declinePendingIntent,
                    answerPendingIntent
                )
            )
        } else {
            // On lock screen, answer action goes to HandleCallActivity
            notificationBuilder.setStyle(
                Notification.CallStyle.forIncomingCall(
                    person,
                    declinePendingIntent,
                    lockAnswerPendingIntent
                )
            )
        }

        val notification = notificationBuilder.build()
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR

        startForeground(
            INCOMING_CALL_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(INCOMING_CALL_NOTIFICATION_ID, notification)

        wakeUpScreen()
        startVibration()
        handleRingtoneForVersion()
        setupCallTimeout()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun showOngoingCallNotification(person: Person, args: BundleArgs, callerName: String) {
        val hangUpPendingIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ForegroundService::class.java).apply { action = ACTION_HANG_UP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callType = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE).getString(
            "callType",
            "call"
        )
        val role = getSharedPreferences(Endpoints.FLUTTER_SHARED_PREFS, Context.MODE_PRIVATE)
            .getString("flutter.role", "")

        val sharedPreferences = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
        val isThroughFullScreenNotification = sharedPreferences.getBoolean(Endpoints.SharedPrefKeys.IS_THROUGH_FULL_SCREEN_NOTIFICATION, false)

        fullScreenIntent = Intent(
            this,
            if (isThroughFullScreenNotification) {
                HandleCallActivity::class.java
            } else {
                if (callType == "video") VideoCallActivity::class.java else AudioCallActivity::class.java
            }
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("launchedFromCall", true)
            putExtra(VideoCallActivity.KEY_ARGS, args)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 3, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Baatein")
            .setContentText("You're in a call")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(true)
            .setFullScreenIntent(fullScreenPendingIntent, false)
            .setPriority(Notification.PRIORITY_HIGH)
            .setStyle(Notification.CallStyle.forOngoingCall(person, hangUpPendingIntent))
            .build()

        startForeground(
            DEFAULT_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )
    }

    private fun ShowOngoingNotification(person: Person, args: BundleArgs, callerName: String) {
        val hangUpIntent = Intent(this, ForegroundService::class.java).apply {
            action = ACTION_HANG_UP
        }

        val hangUpPendingIntent = PendingIntent.getService(
            this, 2, hangUpIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callType = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
            .getString("callType", "call")

        val role = getSharedPreferences(Endpoints.FLUTTER_SHARED_PREFS, Context.MODE_PRIVATE)
            .getString("flutter.role", "")

        val sharedPreferences = getSharedPreferences(Endpoints.APP_PREFS, Context.MODE_PRIVATE)
        val isThroughFullScreenNotification = sharedPreferences.getBoolean(Endpoints.SharedPrefKeys.IS_THROUGH_FULL_SCREEN_NOTIFICATION, false)

        fullScreenIntent = Intent(
            this,
            if (isThroughFullScreenNotification) {
                HandleCallActivity::class.java
            } else {
                if (callType == "video") VideoCallActivity::class.java else AudioCallActivity::class.java
            }
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("launchedFromCall", true)
            putExtra(VideoCallActivity.KEY_ARGS, args)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 3, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = Notification.Builder(this, DEFAULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Baatein")
            .setContentText("You're in a call")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPendingIntent, false)
            .setAutoCancel(false)
            .setPriority(Notification.PRIORITY_HIGH)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        builder.addAction(R.drawable.outline_call_end_24, "Hang Up", hangUpPendingIntent)

        val notification = builder.build()
        startForeground(
            DEFAULT_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ringtoneUri = RingtoneManager.getActualDefaultRingtoneUri(
                application, RingtoneManager.TYPE_RINGTONE
            )

            if (ringtoneUri == null) {
                ringtoneUri = Uri.parse("android.resource://${packageName}/${R.raw.call_ringtone}")
            }

            Log.d(TAG, "Ringtone URI: $ringtoneUri")

            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setLegacyStreamType(AudioManager.STREAM_RING)
                .build()

            val incomingCallChannel = NotificationChannel(
                INCOMING_CALL_CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "Channel for incoming call notifications"
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(true)
                setBypassDnd(true)
                setSound(null, null)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setAllowBubbles(true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setImportance(NotificationManager.IMPORTANCE_MAX)
                }
            }

            val ongoingCallChannel = NotificationChannel(
                DEFAULT_CHANNEL_ID,
                "Ongoing Calls",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Channel for ongoing call notifications"
                enableLights(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            }

            notificationManager.createNotificationChannel(incomingCallChannel)
            notificationManager.createNotificationChannel(ongoingCallChannel)
        }
    }

    private fun handleRingtoneForVersion() {
        playManualRingtoneForAndroid12andBelow()
//        when {
//            Build.VERSION.SDK_INT == Build.VERSION_CODES.S -> {
//                // Android 12 - use manual ringtone
//                Log.d(TAG, "Starting manual ringtone for Android 12")
//                playManualRingtoneForAndroid12andBelow()
//            }
//            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
//                // Android 13+ - channel sound works fine
//                Log.d(TAG, "Using channel sound for Android 13+")
//                // Channel sound will handle it automatically
//            }
//            else -> {
//                // Pre-Android 12 - channel sound works
//                Log.d(TAG, "Using channel sound for pre-Android 12")
//                playManualRingtoneForAndroid12andBelow()
//            }
//        }
    }

    private fun playManualRingtoneForAndroid12andBelow() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Request audio focus specifically for Android 12
            val result = audioManager.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> stopAndroid12Ringtone()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            // Keep playing for incoming calls
                        }
                    }
                },
                AudioManager.STREAM_RING,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                android12RingtonePlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(AudioManager.STREAM_RING)
                            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                            .build()
                    )

                    try {
                        setDataSource(this@ForegroundService, ringtoneUri!!)
                        isLooping = true
                        setOnPreparedListener { mp ->
                            mp.start()
                            Log.d(TAG, "Android 12 manual ringtone started")
                        }
                        setOnErrorListener { mp, what, extra ->
                            Log.e(TAG, "Android 12 ringtone error: what=$what, extra=$extra")
                            false
                        }
                        prepareAsync()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to prepare Android 12 ringtone: ${e.message}")
                        release()
                        android12RingtonePlayer = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Android 12 ringtone: ${e.message}")
        }
    }

    private fun stopAndroid12Ringtone() {
        try {
            android12RingtonePlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            android12RingtonePlayer = null

            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocus(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping Android 12 ringtone: ${e.message}")
        }
    }

    // IMPROVED: Better ringtone handling with proper audio focus
    private fun playRingtone() {
        try {
            // ADDED: Request audio focus for ringtone
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* Handle focus changes */ }
                    .build()

                audioManager.requestAudioFocus(focusRequest)
            } else {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                val ringtoneUri = Uri.parse("android.resource://${packageName}/${R.raw.call_ringtone}")
                ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)

                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_RING) // ADDED: Proper stream type
                    .build()

                ringtone?.isLooping = true // ADDED: Loop ringtone
                ringtone?.play() ?: Log.w(TAG, "Ringtone is null, cannot play")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ringtone: ${e.message}", e)
        }
    }

    private fun stopRingtone() {
        try {
            ringtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            ringtone = null

            // ADDED: Release audio focus
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocus(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone: ${e.message}")
        }
    }


//    private fun startVibration() {
//        try {
//            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
//
//            // IMPROVED: More noticeable vibration pattern
//            val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
//
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                val effect = VibrationEffect.createWaveform(pattern, 0) // CHANGED: Loop vibration
//                vibrator?.vibrate(effect)
//            } else {
//                vibrator?.vibrate(pattern, 0) // CHANGED: Loop vibration
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to start vibration: ${e.message}")
//        }
//    }
//
//    private fun stopVibration() {
//        vibrator?.cancel()
//        vibrator = null
//    }

    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "com.elysion.baatein:incoming_call_screen_wake"
            )
            if (!screenWakeLock.isHeld) {
                screenWakeLock.acquire(15000) // Reduced duration

                Handler(Looper.getMainLooper()).postDelayed({
                    if (screenWakeLock.isHeld) {
                        screenWakeLock.release()
                    }
                }, 15000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wake screen: ${e.message}")
        }
    }

    private fun setupCallTimeout() {
        timeoutRunnable = Runnable {
            dismissIncomingCall("Call not answered")
            timeoutListener?.onCallTimeout()

        }
        timeoutRunnable?.let {
            timeoutHandler?.postDelayed(it, 50000)
        } ?: Log.e(TAG, "Failed to set up call timeout: timeoutRunnable is null")
    }

    private fun releaseLocks() {
        try {
            if (wakeLock.isHeld) wakeLock.release()
            keyguardLock.reenableKeyguard()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks: ${e.message}")
        }
    }

    override fun onDestroy() {

        Log.d(TAG, "=== onDestroy() called ===")
//        Log.d(TAG, "Service uptime: ${System.currentTimeMillis() - serviceStartTime}ms")

        // Print full stack trace to see what called onDestroy
        val stackTrace = Thread.currentThread().stackTrace
        stackTrace.forEachIndexed { index, element ->
            Log.d(TAG, "Stack[$index]: ${element.className}.${element.methodName}:${element.lineNumber}")
        }

        Log.d(TAG, "Current notification ID: $INCOMING_CALL_NOTIFICATION_ID")
        Log.d(TAG, "Has shown notification: $hasShownIncomingNotification")
        Log.d(TAG, "Channel ID: $channelId")


        Log.d(TAG, "onDestroy called")
        mainHandler.removeCallbacksAndMessages(null)
        cancelIncomingCallTimeout()
        stopRingtone()
//        stopVibration()
//        releaseLocks()
        super.onDestroy()
    }

    private fun checkVibrationPermissions(): Boolean {
        val hasVibrationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            checkSelfPermission(android.Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permission automatically granted on older versions
        }

        Log.d(TAG, "Vibration permission granted: $hasVibrationPermission")
        return hasVibrationPermission
    }

    private fun checkDeviceVibrationCapability(): Boolean {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        val hasVibrator = vibrator?.hasVibrator() ?: false
        Log.d(TAG, "Device has vibrator: $hasVibrator")
        return hasVibrator
    }

    private fun checkDNDSettings(): Boolean {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val currentInterruptionFilter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.currentInterruptionFilter
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }

        val isDNDActive = currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        Log.d(TAG, "Do Not Disturb active: $isDNDActive, Filter: $currentInterruptionFilter")
        return !isDNDActive // Return true if DND is NOT active
    }

    private fun startVibration() {
        try {
            return
            Log.d(TAG, "Starting vibration...")

            // Check prerequisites
            if (!checkVibrationPermissions()) {
                Log.e(TAG, "Vibration permission not granted")
                return
            }

            if (!checkDeviceVibrationCapability()) {
                Log.e(TAG, "Device doesn't support vibration")
                return
            }

            if (!checkDNDSettings()) {
                Log.w(TAG, "Do Not Disturb is active - vibration may be blocked")
            }

            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

            if (vibrator == null) {
                Log.e(TAG, "Vibrator service is null")
                return
            }

            // Check if device supports vibration
            if (!vibrator!!.hasVibrator()) {
                Log.e(TAG, "Device does not have vibrator capability")
                return
            }

            // IMPROVED: Better vibration pattern and amplitude
            val pattern = longArrayOf(0, 1000, 500, 1000, 500, 1000) // More noticeable pattern

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.d(TAG, "Using VibrationEffect for Android O+")

                // Try with amplitude control first
                try {
                    val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255) // Max amplitude
                    val effect = VibrationEffect.createWaveform(pattern, amplitudes, 0) // 0 = repeat
                    vibrator!!.vibrate(effect)
                    Log.d(TAG, "Vibration started with amplitude control")
                } catch (e: Exception) {
                    Log.w(TAG, "Amplitude control failed, trying without: ${e.message}")
                    // Fallback without amplitude
                    val effect = VibrationEffect.createWaveform(pattern, 0)
                    vibrator!!.vibrate(effect)
                    Log.d(TAG, "Vibration started without amplitude control")
                }
            } else {
                Log.d(TAG, "Using legacy vibrate for older Android")
                vibrator!!.vibrate(pattern, 0) // 0 = repeat
                Log.d(TAG, "Legacy vibration started")
            }


        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting vibration: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting vibration: ${e.message}", e)
        }
    }

    // IMPROVED: Better vibration stopping with verification
    private fun stopVibration() {
        try {
            return
            Log.d(TAG, "stopVibration() called from: ${Thread.currentThread().stackTrace[3].methodName}")
            Log.d(TAG, "Stack trace: ${Thread.currentThread().stackTrace.contentToString()}")

            Log.d(TAG, "Stopping vibration...")
            vibrator?.let { vib ->
                vib.cancel()
                Log.d(TAG, "Vibration cancelled")


            } ?: Log.w(TAG, "Vibrator was null when trying to stop")

            vibrator = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration: ${e.message}")
        }
    }


    private fun stopSelfWithLogging(reason: String) {
        Log.d(TAG, "=== STOPPING SERVICE: $reason ===")
        Log.d(TAG, "Call stack:")
        Thread.currentThread().stackTrace.forEach {
            Log.d(TAG, "  ${it.className}.${it.methodName}:${it.lineNumber}")
        }
        try{
            stopSelf()
        }catch (e:Exception){
            Log.e(TAG, "FAILED STOP SELF: ${e.message}")

        }
    }
}255, 0, 255) // Max amplitude
                    val effect = VibrationEffect.createWaveform(pattern, amplitudes, 0) // 0 = repeat
                    vibrator!!.vibrate(effect)
                    Log.d(TAG, "Vibration started with amplitude control")
                } catch (e: Exception) {
                    Log.w(TAG, "Amplitude control failed, trying without: ${e.message}")
                    // Fallback without amplitude
                    val effect = VibrationEffect.createWaveform(pattern, 0)
                    vibrator!!.vibrate(effect)
                    Log.d(TAG, "Vibration started without amplitude control")
                }
            } else {
                Log.d(TAG, "Using legacy vibrate for older Android")
                vibrator!!.vibrate(pattern, 0) // 0 = repeat
                Log.d(TAG, "Legacy vibration started")
            }


        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting vibration: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting vibration: ${e.message}", e)
        }
    }

    // IMPROVED: Better vibration stopping with verification
    private fun stopVibration() {
        try {
            return
            Log.d(TAG, "stopVibration() called from: ${Thread.currentThread().stackTrace[3].methodName}")
            Log.d(TAG, "Stack trace: ${Thread.currentThread().stackTrace.contentToString()}")

            Log.d(TAG, "Stopping vibration...")
            vibrator?.let { vib ->
                vib.cancel()
                Log.d(TAG, "Vibration cancelled")


            } ?: Log.w(TAG, "Vibrator was null when trying to stop")

            vibrator = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping vibration: ${e.message}")
        }
    }


    private fun stopSelfWithLogging(reason: String) {
        Log.d(TAG, "=== STOPPING SERVICE: $reason ===")
        Log.d(TAG, "Call stack:")
        Thread.currentThread().stackTrace.forEach {
            Log.d(TAG, "  ${it.className}.${it.methodName}:${it.lineNumber}")
        }
        try{
            stopSelf()
        }catch (e:Exception){
            Log.e(TAG, "FAILED STOP SELF: ${e.message}")

        }
    }
}
