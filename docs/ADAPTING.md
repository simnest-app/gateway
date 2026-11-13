# 让网关在一款新手机上工作

给做适配的 agent。读完你应该知道：网关的哪些部分和机型有关，一个症状对应代码的哪里，改完怎么证明没改坏别的手机。

先按 [TESTING.md](TESTING.md) 做 L1。很多手机不用改一行代码就能用；需要改的，L1 和 L2 的失败项会告诉你是下面哪一类。

## 和机型有关的只有这几处

网关的大部分代码（配对、加密、信令、短信转接、界面）和机型无关。有关的是：

| 环节 | 网关需要系统给什么 | 代码 |
| --- | --- | --- |
| 拿到 shell | 无线调试，或者经典调试端口 | `adb/LocalAdb.kt` `adb/PairingFlow.kt` `adb/ShellLauncher.kt` |
| 音频服务起来 | `app_process` 能跑，shell 身份能建出系统上下文 | `shell/BridgeMain.java` 的 `installShellIdentity` |
| 录对方的声音 | shell 能用通话的录音源 | `shell/BridgeMain.java` 的 `ensureFactory` |
| 往通话里送声音 | 有“通话送话”输出设备，音轨能指定它 | `shell/BridgeMain.java` 的 `telephonyTx` `telephonyTrack` |
| 网关手机自己不出声 | 能把通话音量压到 0、把麦克风静音 | `shell/BridgeMain.java` 的 `Silence` |
| 知道电话的状态 | 当默认电话应用，或者读电话状态 | `calls/InCall.kt` `calls/GatewayService.kt` |
| 读卡和号码 | 订阅信息 | `numbers/SystemSims.kt` `calls/GatewayStatus.kt` |
| 权限真的生效 | 厂商在系统权限之上加的开关 | `FeatureStatus.kt` 的 `Readiness` |
| 不被后台杀掉 | 厂商的自启动、省电、冻结管理 | `SetupGuide.kt` `selftest/` 的 `FreezeWatch` |

已经适配过两类手机，它们的差别就是现成的例子，见 [DEVICES.md](DEVICES.md)：

- 小米 Redmi（联发科，MIUI，Android 13）：按安卓的标准做法就通，是默认路径。难点在 MIUI 的权限和后台管理。
- 华为 Mate 40（海思，HarmonyOS 4.2 / Android 12）：没有无线调试；通话音频的做法和默认路径不同，在 `BridgeMain.java` 里由 `HISILICON_UPLINK` 这一个开关走另一条路。那段注释写的是在真机上一条条测出来的结论，适配别的非标准手机前先读它。

## 症状对到位置

### 手机是 Android 10 或 11

装得上，但没验证过，预计不能正常通话。代码里用了更新的系统接口而没有按版本判断，清单在 `gateway/lint-baseline.xml` 里标着 `NewApi` 的那些：默认电话应用读通话状态（`calls/InCall.kt`，Android 12 的接口）、音频服务建立 shell 身份（`BridgeMain.java`，Android 12）、读卡槽和订阅变化（`numbers/SystemSims.kt`、`calls/GatewayService.kt`，Android 11）。适配这两个版本，就是给这些地方各补一条老系统上的走法，并在一台 Android 10 或 11 的手机上按 TESTING 验证。另外 Android 10 没有无线调试，只能走调试端口。

### 手机上没有“无线调试”

Android 10，或者厂商把它去掉了。走调试端口这条路：电脑上 `adb -s $S tcpip 5555`，引导里选“没有‘无线调试’？”。

代码：`LocalAdb.usesTcp` 决定一款手机默认走哪条路，现在按厂商名判断（华为、荣耀默认走端口）。新手机属于这种情况就把它加进去。

要如实写进机型说明的限制：调试端口重启后就关了，要重新连电脑开一次，重启后网关不能自己恢复通话声音。

### 无线调试有，但配对不上

配对流程在 `adb/PairingFlow.kt` 和 `adb/PairingOverlay.kt`。系统的配对对话框一离开就关闭、配对码随即作废，所以网关在对话框上方浮一个小框让用户填六位码（需要“显示悬浮窗”权限），也可以填在通知的回复栏里；配对时系统会新开一个本机端口，网关靠前后对比端口找到它。厂商改过无线调试页面（入口位置、配对码的展示方式）、隐藏通知回复栏、或者不给悬浮窗权限时，这里会对不上。先确认是哪一步：`SetupGuide.kt` 里每一步都有“已完成”的判断，卡在哪步屏幕上看得出来。

### 音频服务起不来

`tools/start-bridge.sh` 的输出里没有 `bridge ready`。常见原因：

- `installShellIdentity` 里用反射调系统内部类（做法来自 scrcpy），新的 Android 版本或厂商改过这些类时会抛异常。报错的前几行会指出是哪个方法。
- 加载 WebRTC 的原生库失败（`UnsatisfiedLinkError`）：安装包只带 arm64，32 位系统不支持。

### 送话通道打不开

`tx warm failed`。

- `TX_ROUTE_UNAVAILABLE`：系统的输出设备列表里没有 `TYPE_TELEPHONY`。看 `device.txt` 末尾的音频策略摘录：有没有 `AUDIO_DEVICE_OUT_TELEPHONY_TX`。连音频策略里都没有的话，这款手机的通话声音不经过安卓的音频系统（调制解调器直连编解码芯片），目前没有已知的办法，结论就是“不支持”，照实提交。
- `TX_ROUTE_REJECTED`：设备在，`setPreferredDevice` 被拒绝。看系统日志里音频策略对这次请求说了什么（`adb logcat -d | grep -i -E "audio_policy|APM"`）。

### 通话接通了，iPhone 听不到对方

`session … ended` 那一行的 `downlink` 非静音帧接近 0。录音源在这款手机上不出声。

默认用 `VOICE_DOWNLINK`、8 kHz 单声道。华为上它只响一秒就没了，改用 `VOICE_CALL` 立体声、16 kHz（见 `ensureFactory` 的两个分支）。新手机先试这两种哪种有声音。`device.txt` 里音频策略的输入设备和它支持的配置能给线索。

### 通话接通了，对方听不到 iPhone

`uplink` 数字正常（声音写进去了），但对方手机里没声音。这是日志看不出来、只有人能发现的一类，所以 L2 要求有人确认听到了。

华为上的原因：只有“写送话通道的那条音轨是整台手机上唯一在播的音轨”时，声音才到得了对方；通话库自己还开着一条播放音轨（哪怕只播静音）就会把路由抢到听筒上。所以在那类手机上把通话库的播放音轨换成了一个不出声的替身（`PacedSink`）。另外还有两条顺序要求，都写在 `HISILICON_UPLINK` 的注释里：录音要先于送话音轨打开；送话音轨不能断流。

新手机出现这个症状，先试着把它加进 `HISILICON_UPLINK` 这条路看是否解决；不解决再单独分析。

### 通话时网关手机自己出声，或者把屋里的声音送进了通话

`Silence` 用系统内部接口把通话音量压到 0（公开接口的最小值是 1），并把麦克风静音，每半秒重申一次。厂商改了这些内部接口时会在日志里留下 `silence enforce failed`。这一项不能马虎：网关放在家里，漏音就是把家里的声音送给了通话对方。

### 权限给了却不生效

小米的 MIUI 在系统权限之上还有一层自己的开关（例如“发送短信”系统显示已允许，MIUI 那层却是拒绝）。`FeatureStatus.kt` 的 `Readiness` 两层都查，并把用户带到对的设置页。别的厂商有类似机制时照这个样子加。

另一个例子：MIUI 每次重启或更新应用都会收回“接听电话”，网关配对后用 shell 把这一项补回来（`ShellLauncher.startBridge`）。只补这一项，别的不补。

### 熄屏后掉线

厂商的省电管理把网关冻结了。安卓自己的电池优化白名单挡不住。华为要在“应用启动管理”里改成手动；小米要允许自启动、省电策略设为无限制。

`SetupGuide.kt` 里按厂商给出对应的步骤和直达按钮。新厂商要补的是：这一步叫什么、在系统设置的哪里、能不能用 Intent 直达。网关读不到这些设置的值，只能事后发现：`FreezeWatch` 记录进程被系统停住的次数，自检里会报。

### 读不到卡的状态或号码

`GatewayStatus.line` 读每张卡的服务状态，读不到时改看有没有注册到运营商网络。`numbers/SystemSims.kt` 读本机号码，很多卡读不出来，由主人在 iPhone 上补填，这不算故障。

## 怎么改才不会改坏别的手机

1. **新路径要有条件。** 机型相关的改动放在一个按厂商、型号或芯片判断的条件后面，像 `HISILICON_UPLINK` 那样。默认路径（现在是小米那条）的行为一个字节都不变。条件尽量窄：能按型号就不要按厂商，除非你有不止一款的证据。
2. **条件旁边写明依据。** 注释里写：在哪款手机、哪个系统版本上、看到了什么、所以这样做。指向 `evidence/` 里对应的目录。没有实测依据的猜测不要写成结论。
3. **能脱离手机测的逻辑要有单元测试。** 判断、解析、状态机这类。只有真机才能验证的部分靠证据。
4. **不为适配加权限、加依赖、加网络地址。** 见 AGENTS.md 的规矩。
5. **界面文字不写机型名。** 用“这台手机”“本机”；新加的文字要补英文和繁体（`I18nTables.kt`，测试 `LTest` 会检查两张表的键一致）。

## 编译带服务配置的包

官方服务的地址和证书指纹不在仓库里，由编译的人放在本机。没有它也能编译，装上后界面都在，但不能配对，也就做不了 L2。

配置文件是 `~/.cloudsim/service/m1.properties`，四个键：`address`（主机名和端口）、`cert_sha256`（服务证书的 SHA-256，十六进制）、`service_id`、`moved_to`（可空）。网关只能连 SimNest 的核心服务：配对、来电通知、信令都经它，而它和 iPhone 应用都不开源，不能换成自己搭的一套。这个配置文件不对外提供；iPhone 应用目前也只在内部邀请测试。所以带服务配置的包、以及 L2 的测试，现在只有维护者和受邀的测试者能做。

没有它你仍然可以：用官方安装包做 L1；改代码后编不带服务配置的包，验证“音频服务起得来、送话通道打得开”这类不需要配对的部分（TESTING 的 L1 第 2 步）；把证据和改动提交上来，由有条件的人接着做 L2。

自己编译的包和官方包签名不同：

- 手机上已有官方包时装不上，要先卸载官方包，配对和设置都会清掉。
- 装了自己编译的包之后，应用内的“检查更新”不会把它更新成官方包（签名不同会被拒绝），这是有意的。测完想换回官方包，同样先卸载。

调试包这样编：

```bash
./gradlew :gateway:assembleDebug
```

```bash
adb -s $S install gateway/build/outputs/apk/debug/gateway-debug.apk
```

只改了音频服务（`BridgeMain.java`）时，不用重装应用也能试：把新编的包推到手机上，让音频服务从它启动。这样不会触发小米“重装就收回权限”的麻烦。

```bash
BRIDGE_APK=gateway/build/outputs/apk/debug/gateway-debug.apk tools/start-bridge.sh $S
```

注意网关应用会定时检查音频服务是不是从它自己的安装包启动的，不是的话会重启它；所以这个办法适合“启动一下看日志”，要打完整的电话还是得装包。

## 做完之后

按 TESTING 重新走 L1 和 L2，取证，`check` 通过，然后按 [CONTRIBUTING.md](../CONTRIBUTING.md) 提交。结论是“不支持”也请提交证据和你试过的办法。
