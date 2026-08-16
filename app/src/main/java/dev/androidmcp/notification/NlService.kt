package dev.androidmcp.notification

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * 通知监听服务（P4 将填充 read_notifications / dismiss_notification 的实现）。
 * 用户在系统设置中授予通知使用权后由系统绑定。
 */
class NlService : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    companion object {
        @Volatile
        private var instance: NlService? = null

        fun isRunning(): Boolean = instance != null

        /** 系统授权状态，不依赖系统是否已来得及回调 onListenerConnected。 */
        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

        fun get(): NlService? = instance

        /** P4 使用：当前活跃通知快照。 */
        fun activeNotifications(): Array<StatusBarNotification> =
            instance?.activeNotifications ?: emptyArray()
    }
}
