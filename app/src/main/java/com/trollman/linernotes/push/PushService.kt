package com.trollman.linernotes.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.trollman.linernotes.MainActivity
import com.trollman.linernotes.R
import com.trollman.linernotes.cloud.saveToken

/**
 * "A set has arrived" (deliverDueSends and acceptInvite in functions/index.js). In the background
 * Android shows the notification itself and a tap opens MainActivity with the sendId; in the
 * foreground it arrives here and is shown the same way.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        saveToken(uid, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return
        show(this, n.title ?: "A set has arrived", n.body ?: "", message.data["sendId"], message.data["pairId"])
    }

    companion object {
        const val CHANNEL = "sends"
        const val EXTRA_SEND_ID = "sendId"
        /** A monthly reminder carries the pair it's for. */
        const val EXTRA_PAIR_ID = "pairId"

        /** The one notification channel, made at start-up (LinerNotesApp). */
        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Sets you receive", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When someone's set of albums arrives in your inbox."
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        fun show(context: Context, title: String, body: String, sendId: String?, pairId: String? = null) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply {
                    if (sendId != null) putExtra(EXTRA_SEND_ID, sendId)
                    if (pairId != null) putExtra(EXTRA_PAIR_ID, pairId)
                }
            val pending = PendingIntent.getActivity(
                context,
                (sendId ?: pairId ?: "inbox").hashCode(),
                open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify((sendId ?: pairId ?: title).hashCode(), notification) }
        }
    }
}
