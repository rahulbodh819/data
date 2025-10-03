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
import android.os.VibrationAttributes
import android.os.VibrationAttributes.FLAG_BYPASS_INTERRUPTION_POLICY
import android.os.VibratorManager
import android.telephony.TelephonyManager
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
    private var vibrator: Vibrator? = null
    private var isVibrating = false
    private val vibrationHandler = Handler(Looper.getMainLooper())
    private var vibrationRunnable: Runnable? = null

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private lateinit var appPrefs : SharedPreferences
    private lateinit var flutterPrefs : SharedPreferences
    private var ringtoneUri : Uri ? = null
    private var android12RingtonePlayer: MediaPlayer? = null

    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    companion object {
        const val DEFAULT_NOTIFICATION_ID = 3456
        const val DEFAULT_CHANNEL_ID = "livekit_example_foreground"
        const val INCOMING_CALL_CHANNEL_ID = "incoming_call_channel_v15"
        const val ACTION_ACCEPT_CALL = "com.example.livekitprepsapp.ACCEPT_CALL"
        const val ACTION_REJECT_CALL = "com.example.livekitprepsapp.REJECT_CALL"
        const val ACTION_HANG_UP = "com.example.livekitperpsapp.HANG_UP"

        private val url = Endpoints.LiveKitKeys.LIVEKIT_SOCKET_URL
        private val e2eekey = Endpoints.LiveKitKeys.LIVEKIT_ROOM_KEY

        var ringtone: Ringtone? = null
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
                    stopAndroid12Ringtone()
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

        stopAndroid12Ringtone()
        stopVibration()
        cancelIncomingCallTimeout()

        val userId = uuid
        SocketManager.emitCallEnded(this, channelId, userId, "Hang up by $role", false)
        cancelOngoingCallNotifications()
    }

    private fun cancelOngoingCallNotifications() {
        try {
            stopVibration() // Add this
            stopAndroid12Ringtone()

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(INCOMING_CALL_NOTIFICATION_ID)
            notificationManager.cancel(DEFAULT_NOTIFICATION_ID)
            notificationManager.cancelAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error canceling notifications: ${e.message}")
        }

        releaseLocks()
        stopSelfWithLogging("7")
    }

    private fun dismissIncomingCall(reason: String) {
        if(isTimeoutCancelled) return
        Log.d(TAG, "Call dismissed due to: $reason")

        stopVibration() // Add this
        stopAndroid12Ringtone()

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
        playLoopingRingtone()

    }


    private fun playLoopingRingtone() {
        try {
            stopAndroid12Ringtone()

            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Check for active phone calls
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED) {
                    val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                    if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) {
                        Log.w(TAG, "Phone call in progress - skipping ringtone")
                        return
                    }
                }
            }

            // START VIBRATION BEFORE requesting audio focus
            startVibration()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            // CRITICAL: Modified audio focus listener to NOT stop vibration
            val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.d(TAG, "Audio focus lost - stopping ringtone but KEEPING vibration")
                        // Only stop ringtone, NOT vibration
                        android12RingtonePlayer?.let { player ->
                            if (player.isPlaying) {
                                player.stop()
                            }
                            player.release()
                        }
                        android12RingtonePlayer = null
                        // Note: We do NOT call stopVibration() here
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.d(TAG, "Temporary audio focus loss - pausing ringtone")
                        android12RingtonePlayer?.pause()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d(TAG, "Audio focus regained - resuming ringtone")
                        android12RingtonePlayer?.takeIf { !it.isPlaying }?.start()
                    }
                }
            }

            val result: Int? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .setWillPauseWhenDucked(false)
                    .build()
                audioManager?.requestAudioFocus(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }

            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "Audio focus denied - vibration will continue")
            }

            // Setup MediaPlayer for ringtone
            val uri = ringtoneUri ?: Uri.parse("android.resource://${packageName}/${R.raw.call_ringtone}")

            android12RingtonePlayer = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setDataSource(this@ForegroundService, uri)
                isLooping = true
                setOnPreparedListener { mp -> mp.start() }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    // Only stop ringtone on error, keep vibration
                    stopAndroid12Ringtone()
                    false
                }
                prepareAsync()
            }

            Log.d(TAG, "Ringtone setup complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ringtone", e)
            // Ensure vibration continues even if ringtone fails
            if (!isVibrating) {
                startVibration()
            }
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

            abandonAudioFocus()

            // NOTE: Do NOT call stopVibration() here
            // Vibration is managed separately

            Log.d(TAG, "Ringtone stopped (vibration continues)")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone", e)
        }
    }
    private fun abandonAudioFocus() {
        try {
            val am = audioManager ?: getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { request ->
                    am?.abandonAudioFocusRequest(request)
                    Log.d(TAG, "Audio focus abandoned (API 26+)")
                }
            } else {
                // For pre-O, you need to pass the same listener you used when requesting
                // Since we don't store it, just pass null (this is acceptable)
                @Suppress("DEPRECATION")
                am?.abandonAudioFocus(null)
                Log.d(TAG, "Audio focus abandoned (legacy)")
            }

            audioFocusRequest = null
        } catch (e: Exception) {
            Log.e(TAG, "Error abandoning audio focus", e)
        }
    }




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

        stopVibration() // Ensure vibration stops
        stopAndroid12Ringtone()

        mainHandler.removeCallbacksAndMessages(null)
        vibrationHandler.removeCallbacksAndMessages(null)
        cancelIncomingCallTimeout()

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
            Log.d(TAG, "=== Starting Continuous Vibration ===")

            // Stop any existing vibration
            stopVibration()

            // Get vibrator based on Android version
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator == null || !vibrator!!.hasVibrator()) {
                Log.e(TAG, "Vibrator not available")
                return
            }

            // Pattern: vibrate 1s, pause 0.5s, repeat
            val pattern = longArrayOf(0, 1000, 500)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    vibrator!!.hasAmplitudeControl()) {
                    // With amplitude control
                    val amplitudes = intArrayOf(0, 255, 0)
                    VibrationEffect.createWaveform(pattern, amplitudes, 0) // 0 = repeat from start
                } else {
                    // Without amplitude control
                    VibrationEffect.createWaveform(pattern, 0) // 0 = repeat
                }

                // Use appropriate attributes based on Android version
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attributes = VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_RINGTONE)
                        .setFlags(FLAG_BYPASS_INTERRUPTION_POLICY)
                        .build()
                    vibrator!!.vibrate(effect, attributes)
                } else {
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    vibrator!!.vibrate(effect, audioAttributes)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator!!.vibrate(pattern, 0) // 0 = repeat indefinitely
            }

            isVibrating = true
            Log.d(TAG, "Vibration started successfully")

            // Safety mechanism: Restart vibration if it stops unexpectedly
            startVibrationMonitor()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start vibration: ${e.message}", e)
        }
    }

    private fun startVibrationMonitor() {
        vibrationRunnable?.let { vibrationHandler.removeCallbacks(it) }

        vibrationRunnable = object : Runnable {
            override fun run() {
                if (isVibrating && vibrator != null) {
                    // Check if we're still supposed to be vibrating
                    // Restart if needed (defensive programming)
                    try {
                        // Re-trigger vibration pattern to ensure continuity
                        val pattern = longArrayOf(0, 1000, 500)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val effect = VibrationEffect.createWaveform(pattern, 0)
                            vibrator!!.vibrate(effect)
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator!!.vibrate(pattern, 0)
                        }

                        Log.d(TAG, "Vibration monitor: Re-triggered vibration")
                    } catch (e: Exception) {
                        Log.e(TAG, "Vibration monitor error: ${e.message}")
                    }

                    // Check again in 10 seconds
                    vibrationHandler.postDelayed(this, 10000)
                }
            }
        }
    }


    // IMPROVED: Better vibration stopping with verification
    private fun stopVibration() {
        try {
            Log.d(TAG, "Stopping vibration")

            // Cancel monitoring
            vibrationRunnable?.let { vibrationHandler.removeCallbacks(it) }
            vibrationRunnable = null

            // Cancel vibration
            vibrator?.cancel()
            vibrator = null
            isVibrating = false

            Log.d(TAG, "Vibration stopped")
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
