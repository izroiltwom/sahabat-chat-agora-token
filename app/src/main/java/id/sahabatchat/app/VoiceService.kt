package id.sahabatchat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

class VoiceService : Service() {

    companion object {
        const val ROOM_NAME = "Ngomong"
        // Ganti URL di bawah ini dengan URL deployment Vercel Anda (misal: https://proyek-anda.vercel.app/issueAgoraRtcToken)
        private const val TOKEN_SERVER_URL = "https://sahabatchat-token.vercel.app/issueAgoraRtcToken"

        const val CHANNEL_ID = "voice_room_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "id.sahabatchat.app.ACTION_START"
        const val ACTION_TOGGLE_MUTE = "id.sahabatchat.app.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "id.sahabatchat.app.ACTION_TOGGLE_SPEAKER"
        const val ACTION_LEAVE = "id.sahabatchat.app.ACTION_LEAVE"
    }

    interface Listener {
        fun onStateChanged()
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceService = this@VoiceService
    }

    private val binder = LocalBinder()
    private var listener: Listener? = null

    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private var rtcEngine: RtcEngine? = null

    private var appId = ""
    var agoraUid = 0
        private set

    var isJoining = false
        private set
    var isJoined = false
        private set
    var isMuted = false
        private set
    var speakerEnabled = false // Default Speaker Nonaktif (Earpiece)
        private set

    val activePeers = mutableSetOf<Int>()

    var statusText = "Room permanen: Ngomong. Tekan masuk untuk berbicara dan mendengar."
        private set
    var participantText = "Belum terhubung ke Ngomong."
        private set

    private fun updateParticipantSummary() {
        participantText = if (activePeers.isEmpty()) {
            "Belum ada peserta lain di room Ngomong (Anda sendirian)."
        } else {
            "Peserta aktif di room (${activePeers.size}): " + activePeers.joinToString(", ") { "ID $it" }
        }
    }

    private val rtcHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            isJoining = false
            isJoined = true
            statusText = "Berhasil masuk! Obrolan suara Ngomong aktif."
            activePeers.clear()
            updateParticipantSummary()
            updateNotification()
            notifyListener()
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            activePeers.add(uid)
            updateParticipantSummary()
            updateNotification()
            notifyListener()
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            activePeers.remove(uid)
            updateParticipantSummary()
            updateNotification()
            notifyListener()
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            if (isJoined || isJoining) {
                statusText = when (state) {
                    Constants.CONNECTION_STATE_CONNECTING -> "Menghubungkan ke Ngomong..."
                    Constants.CONNECTION_STATE_CONNECTED -> "Koneksi suara tersambung."
                    Constants.CONNECTION_STATE_RECONNECTING -> "Koneksi terganggu. Mencoba menyambungkan kembali..."
                    Constants.CONNECTION_STATE_DISCONNECTED -> "Koneksi suara terputus."
                    Constants.CONNECTION_STATE_FAILED -> "Koneksi gagal. Silakan keluar lalu coba lagi."
                    else -> "Status koneksi berubah (kode $state, alasan $reason)."
                }
                updateNotification()
                notifyListener()
            }
        }

        override fun onTokenPrivilegeWillExpire(token: String?) {
            renewAgoraToken()
        }

        override fun onError(err: Int) {
            statusText = "Agora melaporkan kesalahan (kode $err)."
            updateNotification()
            notifyListener()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
        notifyListener()
    }

    private fun notifyListener() {
        listener?.onStateChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START -> {
                startForegroundWithNotification()
                if (!isJoined && !isJoining) {
                    beginJoin()
                }
            }
            ACTION_TOGGLE_MUTE -> {
                toggleMute()
            }
            ACTION_TOGGLE_SPEAKER -> {
                toggleSpeaker()
            }
            ACTION_LEAVE -> {
                leaveRoom("Anda telah keluar dari Ngomong.")
                stopForegroundService()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Obrolan Suara Ngomong"
            val descriptionText = "Notifikasi obrolan suara latar belakang Sahabat Chat"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, VoiceRoomActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, VoiceService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            1,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val leaveIntent = Intent(this, VoiceService::class.java).apply {
            action = ACTION_LEAVE
        }
        val leavePendingIntent = PendingIntent.getService(
            this,
            2,
            leaveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteLabel = if (isMuted) "Unmute Mic" else "Mute Mic"

        val contentTitle = "Sahabat Chat - Ngomong"
        val contentBody = when {
            isJoined -> if (isMuted) "Suara Aktif (Mikrofon Mati)" else "Suara Aktif (Mikrofon Menyala)"
            isJoining -> "Sedang Menghubungkan..."
            else -> "Terputus"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentBody)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, muteLabel, mutePendingIntent)
            .addAction(0, "Keluar", leavePendingIntent)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    fun beginJoin() {
        if (isJoining || isJoined) return

        isJoining = true
        statusText = "Memeriksa akun dan mengambil konfigurasi Agora..."
        updateNotification()
        notifyListener()

        if (firebaseAuth.currentUser != null) {
            loadAgoraConfig()
        } else {
            firebaseAuth.signInAnonymously()
                .addOnSuccessListener { loadAgoraConfig() }
                .addOnFailureListener {
                    failJoin("Autentikasi Firebase gagal: ${it.localizedMessage}")
                }
        }
    }

    private fun loadAgoraConfig() {
        firestore.collection("settings").document("agora_config")
            .get()
            .addOnSuccessListener { document ->
                if (!document.exists()) {
                    failJoin("Dokumen settings/agora_config belum dibuat di Firestore.")
                    return@addOnSuccessListener
                }
                val configuredAppId = document.getString("appId")?.trim().orEmpty()
                if (!configuredAppId.matches(Regex("^[A-Fa-f0-9]{32}$"))) {
                    failJoin("App ID Agora tidak valid.")
                    return@addOnSuccessListener
                }
                appId = configuredAppId
                issueTokenAndConnect()
            }
            .addOnFailureListener {
                failJoin("Gagal membaca konfigurasi Firestore: ${it.localizedMessage}")
            }
    }

    private fun issueTokenAndConnect() {
        agoraUid = SecureRandom().nextInt(Int.MAX_VALUE - 1) + 1
        statusText = "Meminta token suara dari server..."
        updateNotification()
        notifyListener()

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(TOKEN_SERVER_URL)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")

                val requestBody = JSONObject().apply {
                    put("channelName", ROOM_NAME)
                    put("uid", agoraUid)
                }.toString()

                connection.outputStream.use { output ->
                    output.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                val inputStream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val responseBody = inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()

                if (responseCode !in 200..299) {
                    throw Exception("Server token HTTP $responseCode: $responseBody")
                }

                val json = JSONObject(responseBody)
                val token = json.optString("token", "")
                val serverAppId = json.optString("appId", "")

                if (token.isBlank()) {
                    throw Exception("Server tidak mengirim token Agora.")
                }
                if (serverAppId != appId) {
                    throw Exception("App ID dari server tidak cocok.")
                }

                connectAgora(token)

            } catch (error: Exception) {
                failJoin("Server token gagal dihubungi: ${error.localizedMessage ?: error.javaClass.simpleName}")
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun connectAgora(token: String) {
        try {
            val normalizedAppId = appId.trim()
            val normalizedToken = token.trim()

            if (!normalizedAppId.matches(Regex("^[A-Fa-f0-9]{32}$"))) {
                throw IllegalArgumentException("App ID Agora tidak valid.")
            }
            if (normalizedToken.isBlank()) {
                throw IllegalArgumentException("Token Agora kosong.")
            }

            statusText = "Menyiapkan mesin suara Agora..."
            updateNotification()
            notifyListener()

            rtcEngine = RtcEngine.create(
                RtcEngineConfig().apply {
                    mContext = applicationContext
                    mAppId = normalizedAppId
                    mEventHandler = rtcHandler
                }
            )

            val engine = rtcEngine ?: throw IllegalStateException("Agora engine tidak berhasil dibuat.")

            engine.setChannelProfile(Constants.CHANNEL_PROFILE_COMMUNICATION)
            engine.disableVideo()
            engine.enableAudio()
            // Profil audio HD & meeting scenario setara WhatsApp (jernih, keras, AGC, AEC, ANS aktif)
            engine.setAudioProfile(Constants.AUDIO_PROFILE_MUSIC_STANDARD, Constants.AUDIO_SCENARIO_MEETING)
            engine.setEnableSpeakerphone(speakerEnabled)

            val options = ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_COMMUNICATION
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                publishMicrophoneTrack = true
                autoSubscribeAudio = true
                publishCameraTrack = false
                autoSubscribeVideo = false
            }

            val result = engine.joinChannel(normalizedToken, ROOM_NAME, agoraUid, options)
            if (result != 0) {
                failJoin("Agora menolak permintaan masuk (kode $result).")
            } else {
                statusText = "Permintaan masuk ke Ngomong dikirim..."
                updateNotification()
                notifyListener()
            }
        } catch (error: Exception) {
            failJoin("Inisialisasi Agora gagal: ${error.localizedMessage ?: error.javaClass.simpleName}")
        }
    }

    private fun renewAgoraToken() {
        if (!isJoined) return

        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(TOKEN_SERVER_URL)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")

                val requestBody = JSONObject().apply {
                    put("channelName", ROOM_NAME)
                    put("uid", agoraUid)
                }.toString()

                connection.outputStream.use { output ->
                    output.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode in 200..299) {
                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseBody)
                    val token = json.optString("token", "")
                    if (token.isNotBlank()) {
                        rtcEngine?.renewToken(token)
                    }
                }
            } catch (_: Exception) {
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    fun toggleMute() {
        if (!isJoined) return
        isMuted = !isMuted
        rtcEngine?.muteLocalAudioStream(isMuted)
        updateNotification()
        notifyListener()
    }

    fun toggleSpeaker() {
        if (!isJoined) return
        speakerEnabled = !speakerEnabled
        rtcEngine?.setEnableSpeakerphone(speakerEnabled)
        updateNotification()
        notifyListener()
    }

    fun leaveRoom(message: String) {
        try {
            rtcEngine?.leaveChannel()
        } catch (_: Exception) {
        }
        try {
            if (rtcEngine != null) {
                RtcEngine.destroy()
            }
        } catch (_: Exception) {
        }
        rtcEngine = null

        isJoining = false
        isJoined = false
        isMuted = false
        speakerEnabled = false
        activePeers.clear()

        statusText = message
        participantText = "Belum terhubung ke Ngomong."

        notifyListener()
        stopForegroundService()
    }

    private fun failJoin(message: String) {
        leaveRoom(message)
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        leaveRoom("Sesi suara ditutup.")
        super.onDestroy()
    }
}
