package dev.androidmcp.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.androidmcp.tools.McpTool
import dev.androidmcp.tools.impl.BatteryTool
import dev.androidmcp.tools.impl.BatchTool
import dev.androidmcp.tools.impl.ClickTool
import dev.androidmcp.tools.impl.DeleteFileTool
import dev.androidmcp.tools.impl.DeviceInfoTool
import dev.androidmcp.tools.impl.DismissNotificationTool
import dev.androidmcp.tools.impl.DndTool
import dev.androidmcp.tools.impl.FindElementTool
import dev.androidmcp.tools.impl.FlashlightTool
import dev.androidmcp.tools.impl.GestureTool
import dev.androidmcp.tools.impl.GetBrightnessTool
import dev.androidmcp.tools.impl.GetClipboardTool
import dev.androidmcp.tools.impl.GetCurrentAppTool
import dev.androidmcp.tools.impl.GetLocationTool
import dev.androidmcp.tools.impl.GetLogcatTool
import dev.androidmcp.tools.impl.GetNetworkInfoTool
import dev.androidmcp.tools.impl.GetUiTreeTool
import dev.androidmcp.tools.impl.GetVolumeTool
import dev.androidmcp.tools.impl.GlobalActionTool
import dev.androidmcp.tools.impl.InputTextTool
import dev.androidmcp.tools.impl.KeyEventTool
import dev.androidmcp.tools.impl.LaunchAppTool
import dev.androidmcp.tools.impl.ListAppsTool
import dev.androidmcp.tools.impl.ListFilesTool
import dev.androidmcp.tools.impl.ListSensorsTool
import dev.androidmcp.tools.impl.ListSmsTool
import dev.androidmcp.tools.impl.LongClickTool
import dev.androidmcp.tools.impl.MakeCallTool
import dev.androidmcp.tools.impl.MediaControlTool
import dev.androidmcp.tools.impl.NowPlayingTool
import dev.androidmcp.tools.impl.OpenAppSettingsTool
import dev.androidmcp.tools.impl.OpenUrlTool
import dev.androidmcp.tools.impl.PmCommandTool
import dev.androidmcp.tools.impl.QueryContactsTool
import dev.androidmcp.tools.impl.ReadCallLogTool
import dev.androidmcp.tools.impl.ReadFileTool
import dev.androidmcp.tools.impl.ReadNotificationsTool
import dev.androidmcp.tools.impl.ReadSensorTool
import dev.androidmcp.tools.impl.RingerModeTool
import dev.androidmcp.tools.impl.RunShellTool
import dev.androidmcp.tools.impl.ScreenshotTool
import dev.androidmcp.tools.impl.ScrollTool
import dev.androidmcp.tools.impl.SendSmsTool
import dev.androidmcp.tools.impl.SetAlarmTool
import dev.androidmcp.tools.impl.SetBrightnessTool
import dev.androidmcp.tools.impl.SetClipboardTool
import dev.androidmcp.tools.impl.SetTimerTool
import dev.androidmcp.tools.impl.SetVolumeTool
import dev.androidmcp.tools.impl.SettingsGetTool
import dev.androidmcp.tools.impl.SettingsPutTool
import dev.androidmcp.tools.impl.SpeakTool
import dev.androidmcp.tools.impl.SwipeTool
import dev.androidmcp.tools.impl.ToastTool
import dev.androidmcp.tools.impl.VibrateTool
import dev.androidmcp.tools.impl.WaitForTool
import dev.androidmcp.tools.impl.WakeScreenTool
import dev.androidmcp.tools.impl.WriteFileTool
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** 全部 MCP 工具在此登记（新增工具时把实现类加进来即可）。 */
    @Provides
    @Singleton
    fun provideTools(
        batchExecute: BatchTool,
        deviceInfo: DeviceInfoTool,
        battery: BatteryTool,
        getClipboard: GetClipboardTool,
        setClipboard: SetClipboardTool,
        toast: ToastTool,
        launchApp: LaunchAppTool,
        openUrl: OpenUrlTool,
        networkInfo: GetNetworkInfoTool,
        // 无障碍引擎 + UI 自动化工具组
        screenshot: ScreenshotTool,
        getUiTree: GetUiTreeTool,
        findElement: FindElementTool,
        click: ClickTool,
        longClick: LongClickTool,
        inputText: InputTextTool,
        swipe: SwipeTool,
        scroll: ScrollTool,
        gesture: GestureTool,
        keyEvent: KeyEventTool,
        globalAction: GlobalActionTool,
        waitFor: WaitForTool,
        getCurrentApp: GetCurrentAppTool,
        // 系统与读取工具组
        getVolume: GetVolumeTool,
        setVolume: SetVolumeTool,
        getBrightness: GetBrightnessTool,
        setBrightness: SetBrightnessTool,
        ringerMode: RingerModeTool,
        dnd: DndTool,
        flashlight: FlashlightTool,
        vibrate: VibrateTool,
        wakeScreen: WakeScreenTool,
        listApps: ListAppsTool,
        openAppSettings: OpenAppSettingsTool,
        setAlarm: SetAlarmTool,
        setTimer: SetTimerTool,
        speak: SpeakTool,
        getLocation: GetLocationTool,
        listSensors: ListSensorsTool,
        readSensor: ReadSensorTool,
        // 文件工具组
        listFiles: ListFilesTool,
        readFile: ReadFileTool,
        writeFile: WriteFileTool,
        deleteFile: DeleteFileTool,
        // 通信工具组（默认关闭）
        readNotifications: ReadNotificationsTool,
        dismissNotification: DismissNotificationTool,
        sendSms: SendSmsTool,
        listSms: ListSmsTool,
        readCallLog: ReadCallLogTool,
        queryContacts: QueryContactsTool,
        makeCall: MakeCallTool,
        mediaControl: MediaControlTool,
        nowPlaying: NowPlayingTool,
        // Shizuku 高级工具组（默认关闭）
        runShell: RunShellTool,
        getLogcat: GetLogcatTool,
        settingsGet: SettingsGetTool,
        settingsPut: SettingsPutTool,
        pmCommand: PmCommandTool,
    ): Set<McpTool> = linkedSetOf(
        batchExecute, deviceInfo, battery, getClipboard, setClipboard, toast, launchApp, openUrl, networkInfo,
        screenshot, getUiTree, findElement, click, longClick, inputText, swipe, scroll, gesture,
        keyEvent, globalAction, waitFor, getCurrentApp,
        getVolume, setVolume, getBrightness, setBrightness, ringerMode, dnd, flashlight, vibrate,
        wakeScreen, listApps, openAppSettings, setAlarm, setTimer, speak, getLocation, listSensors,
        readSensor,
        listFiles, readFile, writeFile, deleteFile,
        readNotifications, dismissNotification, sendSms, listSms, readCallLog, queryContacts,
        makeCall, mediaControl, nowPlaying,
        runShell, getLogcat, settingsGet, settingsPut, pmCommand,
    )
}
