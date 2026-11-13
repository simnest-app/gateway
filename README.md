# SimNest Gateway

SimNest 网关：装在一台插着 SIM 卡的安卓手机上，把这张卡的来电、拨出和短信转给你的 iPhone。手机留在家里，人在外面照样用这个号码接打电话、收发短信。

The SimNest gateway runs on an Android phone that holds your SIM card and passes that card's calls and text messages on to your iPhones, so the number keeps working wherever you are while the phone stays at home.

## 它是整个 SimNest 的哪一部分

| 部分 | 是否开源 | 说明 |
| --- | --- | --- |
| 网关（本仓库） | GPL-3.0 | 安卓应用，接管 SIM 卡的通话和短信 |
| 自建中继部署包 | GPL-3.0 | 另一个仓库，通话连不通时用自己的机器转发 |
| iPhone 应用 | 不开源 | 接听、拨号、短信的界面 |
| 核心服务 | 不开源 | 推送通知、设备配对、信令和加密内容的转交 |

通话声音和短信内容在网关和 iPhone 之间端到端加密（HPKE：X25519、HKDF-SHA256、AES-128-GCM，加设备签名）；核心服务只转交，读不到内容。网关开源，是为了让你能看清装在自己手机上、拿着通话和短信权限的这个程序到底做了什么，也方便大家一起补不同机型的适配。

## 现状

- 开发和验收用的机型：Redmi（Android 13，MIUI）。华为 Mate 40 Pro+（HarmonyOS）基本可用。其他机型没有验证过。
- 需要 Android 12 及以上，arm64。安装包在 Android 10、11 上也装得上，但代码里有十几处用了 Android 11、12 才有的系统接口（通话状态、音频服务的身份、读卡），在那两个版本上没验证过，预计通话相关的功能会出错。适配它们是一项单独的工作。
- 网关要配合 SimNest 的 iPhone 应用和核心服务使用。自己编译的安装包里没有核心服务的地址，装上后不能配对；官方安装包见本仓库的 Releases。
- 界面有简体中文、繁体中文和英文。

## 它在手机上要什么

网关需要的权限比普通应用多，每一项都在应用里说明了用途，由你在系统弹窗里允许：

- 电话、通话记录、拨打电话、短信、通讯录：转接来电和短信、让 iPhone 看到来电号码、经网关拨出。
- 默认电话应用和默认短信应用：知道对方何时接听；避免系统给每条外发短信弹确认框。
- 本机调试（无线调试）：网关用它在本机启动一个 shell 权限的音频服务，把通话声音接到 iPhone。这是通话有声音的前提。
- 修改安全设置：只用来在重启或换 Wi-Fi 后重新打开无线调试。
- 开机自启、前台服务：手机重启后网关自己恢复。

网关不自动拨号。它替 iPhone 拨出和发短信时，只听主人和主人授权的家人。

## 编译

需要 JDK 17 和 Android SDK（compileSdk 37，NDK 29.0.14206865，CMake 3.31.6）。

```bash
scripts/fetch-deps.sh
```

```bash
./gradlew :gateway:testDebugUnitTest :gateway:assembleDebug
```

`scripts/fetch-deps.sh` 下载不在 Maven 上的那一个依赖（sherpa-onnx 的 AAR）并核对校验和。

核心服务的地址、证书指纹和发布签名都不在仓库里，由编译的人放在本机：

- `~/.cloudsim/service/<名字>.properties`：`address`、`cert_sha256`、`service_id`、`moved_to`，用 `-Pcloudsim.service=<名字>` 选择。没有这个文件也能编译，只是装上后连不到任何服务。
- `~/.cloudsim/gateway-release-signing/signing.properties`：发布签名。没有就只能打 debug 包。

## 更新

官方安装包可以自己更新：网关查本仓库和 Gitee 镜像的最新 Release，下载后核对包名、版本和签名（必须和已装的相同），再安装。默认只提示，可以在“关于”里打开自动更新。

## 源码说明

- 代码注释是英文，里面提到的 `docs/architecture/*.md` 是内部设计文档，目前没有随仓库公开。
- 源码的包名是 `dev.cloudsim.gateway`（项目早期的名字），安装包的标识是 `app.simnest.gateway`。
- `gateway/src/main/java/.../shell/BridgeMain.java` 是那个 shell 权限的音频服务；`gateway/src/main/kotlin/.../calls/` 是通话转接；`sms/` 是短信；`crypto/` 和 `protocol/` 是加密和签名；`mcp/` 是给你自己的程序用的 MCP 服务器；`selftest/` 是自检；`update/` 是更新。

## 反馈

问题和机型适配请提 Issue。提交日志前请留意：网关的诊断日志不记电话号码和短信内容，但请自己再看一眼。

## 许可

GPL-3.0，见 [LICENSE](LICENSE)。第三方组件见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
