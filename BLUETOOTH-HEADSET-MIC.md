# 蓝牙耳机麦克风：现状与排查记录

## 现状

当前版本蓝牙耳机只用于**听音**（A2DP），麦克风始终为手机麦克风。

耳机麦克风需要 HFP/SCO 双向通路。经典蓝牙的 A2DP（单向、高音质）与 HFP（双向、通话音质）
在 Android 上互斥，启用耳机麦必须切换到通信路由，由系统建立 SCO 音频通路。

本项目实现过完整路径（通信模式 + `setCommunicationDevice` + legacy SCO + 路由后重建轨），
但未能在实测设备上产生可用的音频通路，该实现已移除。本文记录协议事实、实测观测、
已排除的假设与后续排查所需的系统级命令，供重新实现时参考。

## 协议与平台事实

| | A2DP | HFP / SCO | LE Audio |
|---|---|---|---|
| 方向 | 单向（播放） | 双向 | 双向 |
| 编码 | SBC / AAC / aptX / LDAC | CVSD 8 kHz；mSBC 16 kHz | LC3 |
| 延迟 | 100–300 ms | 约 20–100 ms | 低 |
| Android 设备类型 | `TYPE_BLUETOOTH_A2DP` | `TYPE_BLUETOOTH_SCO` | `TYPE_BLE_HEADSET` |
| 用途 | 音乐 | 通话 | 通话 / 音乐 |

- AOSP 对 SCO 会话启动条件的描述：存在 patch 到 SCO 设备的数据流、音频模式已设置、
  存在指向 SCO 设备的通路；音频框架会阻止 A2DP 与 SCO 同时存在。
- 因此"耳机麦"必然伴随通话音质（CVSD 8 kHz / mSBC 16 kHz，单声道）。
  要"高音质 + 双向"只能走 LE Audio。
- 普通录音（`MediaRecorder.AudioSource.MIC`）不会建立 SCO。蓝牙耳机连接时普通录音
  仍使用手机麦克风，这是预期行为，不能作为"耳机麦可用/不可用"的判据。

## 业界标准接法

1. 前台服务与权限：`RECORD_AUDIO`；API 31+ 另需运行时 `BLUETOOTH_CONNECT`；
   如需声明通话类前台服务则还需对应权限与类型。
2. 音频焦点：`AUDIOFOCUS_GAIN_TRANSIENT` + `USAGE_VOICE_COMMUNICATION` + `CONTENT_TYPE_SPEECH`。
3. `setMode(MODE_IN_COMMUNICATION)`。
4. 选路：API 31+ 用 `setCommunicationDevice()`（设备来自 `getAvailableCommunicationDevices()`）；
   更低版本用 `startBluetoothSco()` + `setBluetoothScoOn(true)` + `setSpeakerphoneOn(false)`。
5. 等待 `ACTION_SCO_AUDIO_STATE_UPDATED == CONNECTED`；失败或超时回退。
6. 建立 `AudioTrack`（`USAGE_VOICE_COMMUNICATION`，16 kHz 单声道）与
   `AudioRecord(VOICE_COMMUNICATION)`，建完校验 `getRoutedDevice()`。
7. 设备、模式或活跃状态变化时销毁重建并重新校验；退出时清理路由、释放焦点、模式回到 `NORMAL`。

参考实现：Twilio AudioSwitch、WebRTC `JavaAudioDeviceModule`。

## 实测观测

环境：Android 13/14 设备，经典蓝牙耳机（HFP + A2DP）。

应用侧全部回读正常：

- `ACTION_SCO_AUDIO_STATE_UPDATED` 为 `CONNECTED`，且持续保持
- `AudioManager.getMode()` 为 `MODE_IN_COMMUNICATION`
- `AudioManager.getCommunicationDevice()` 回读为 `TYPE_BLUETOOTH_SCO`
- `AudioTrack.getRoutedDevice()` 与 `AudioRecord.getRoutedDevice()` 均为 `TYPE_BLUETOOTH_SCO`
- `AudioTrack.getPlayState()` 为 `PLAYING`

同时：

- `AudioTrack.getPlaybackHeadPosition()` 恒为 0
- `AudioTrack.getUnderrunCount()` 恒为 0
- `AudioRecord` 读到的数据全为零

**推论（未验证）**：`underruns=0` 且 `head=0` 说明 AudioFlinger 从未消费这条轨，
而不是欠载；系统可能没有建立指向耳机的可用音频通路。上述观测无法区分具体原因。

## 已排除的假设

以下假设均经实测证伪：

| 假设 | 实验 | 结果 |
|---|---|---|
| 轨道采样率与 HAL 不匹配 | voice 轨改为 16 kHz，本地 3:1 重采样 | `head` 仍为 0 |
| 远端数据稀疏导致输出未启动 | 诊断包在切路由后写 3 秒连续 440 Hz 正弦 | 仍无声音，`head` 仍为 0 |
| A2DP 占用导致 SCO 无法建立 | 切路由前先销毁媒体轨 | 无效 |
| 缺少旧版 SCO 调用 | 追加 `startBluetoothSco` + `setBluetoothScoOn(true)` + `setSpeakerphoneOn(false)` | 无效 |
| 权限不足 | API 31+ 运行时授予并显式校验 `BLUETOOTH_CONNECT` | 已授权，观测不变 |
| 前台服务类型不被识别 | 追加 `phoneCall` 类型与 `MANAGE_OWN_CALLS` | 无效 |
| 轨构造方式不合适 | 三种构造轮换（属性 + 低延迟 / 属性 / legacy `STREAM_VOICE_CALL`） | 三种均 `head=0` |

未验证的假设：

- **耳机连接时序**：日志中耳机均在连接后约 2 秒内切换路由，而其他应用通常在耳机稳定后
  才发起通话。"先连接耳机等待十余秒再启动应用"这一判别实验未执行。

## 未验证的候选方向

以下均为推测，不代表已定位：

- 应用侧的调用组合或时序不满足系统的某个隐含前置条件
- 与音频焦点、前台服务类型或应用身份相关的系统判定
- 系统建立音频通路（patch）的过程未按预期执行
- 音频 HAL 对第三方应用的额外要求
- 其他

同机对照：蜂窝电话与微信语音通话中耳机麦克风可用（两者都会建立 SCO 通信通路）。
这只说明该设备允许第三方应用建立 SCO，不能说明差异具体在哪里。

## 复现与取证

在应用运行、蓝牙耳机已连接、通话进行中时采集：

```bash
adb shell dumpsys audio              > audio.txt
adb shell dumpsys media.audio_policy > policy.txt
adb shell dumpsys media.audio_flinger > flinger.txt
adb shell dumpsys bluetooth_manager  > bt.txt
adb logcat -v threadtime -b all      > log.txt
```

判读重点：

| 检查项 | 位置 | 含义 |
|---|---|---|
| 是否存在指向耳机的 patch，`mState` 是否 `ACTIVE` | `policy.txt` | 音频通路是否真的建立 |
| 轨挂在哪个 output thread，`Devices:` 是 `0x2` 还是 `0x10/0x20/0x40` | `flinger.txt` | 轨是否绑定到 SCO 输出 |
| output thread 的 `Standby:` 与轨的 `active:` | `flinger.txt` | AudioFlinger 是否在混音 |
| 轨实际的 format / sample rate / channel mask | `flinger.txt` | 是否与 HAL 声明一致 |
| `Audio mode:` 与 `Communication route clients:` 的 top 客户端 | `audio.txt` | 通信路由归属是否为本应用 |
| SCO patch 的 `sampling rate` 与 HFP 编码 | `policy.txt` / `bt.txt` | 宽带语音协商结果 |

三种分支对应不同修法：

- patch 不存在或非 `ACTIVE` → 先查音频通路建立的条件，而不是改轨构造
- patch 存在但轨不在其上 → 调整建轨时机（等待通路就绪后重建）
- patch 存在且轨在其上但仍 `head=0` → 检查 HAL 与格式，必要时评估 Telecom
  自管理通话（`PhoneAccount` + `ConnectionService`）

## 修复切入点

- `app/src/main/kotlin/dev/tsdroid/bridge/AudioBridge.kt`
  - `initAudioTrack()` / `buildAudioTrack()`：播放轨构造
  - `createAudioRecord()` / `tryCreateAudioRecord()`：采集源选择
  - `startPlaybackLoop()`：写侧与播放头观测
- 需要新增的路由管理：`AudioDeviceCallback` 监听、模式切换、通信设备断言、
  SCO 状态广播接收、失败回退
- `app/src/main/AndroidManifest.xml`：`BLUETOOTH_CONNECT`（API 31+ 运行时）与需要的
  前台服务类型及对应权限
- 参考本文件"业界标准接法"一节，按状态机实现，不要依赖自报字段判断成功

## 参考

- AOSP《音频托管 SCO 重新架构》<https://source.android.com/docs/core/audio/sco-audio-mgmt>
- Android《Audio Manager self-managed call guide》<https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-manager>
- WebRTC `WebRtcAudioTrack` / `WebRtcAudioRecord` <https://chromium.googlesource.com/external/webrtc>
- Twilio AudioSwitch <https://github.com/twilio/audioswitch>
