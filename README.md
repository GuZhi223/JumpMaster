# JumpMaster

微信「跳一跳」Android 辅助工具。屏幕截图 → 图像识别定位棋子与目标方块 → 依据按压系数换算触控时长 → 执行长按。

全部逻辑运行在手机上，不需要电脑参与，也不修改游戏本体。

---

## 三种工作模式

| 模式 | 截屏方式 | 触摸注入方式 | 前置条件 |
| --- | --- | --- | --- |
| **本地模式** (`LOCAL`) | MediaProjection 录屏授权 | 无障碍服务 `dispatchGesture` | 授予悬浮窗 + 截屏 + 无障碍服务 |
| **ADB 模式** (`ADB`) | 无线 ADB `screencap` | 无线 ADB `input swipe` | 开发者选项 → 无线调试，配对一次即可 |
| **Root 模式** (`ROOT`) | `su -c screencap -p` | `su -c input swipe` | 设备已 Root，并在 Root 管理器中授权 |

设计上的差别：

- **本地模式**最省事，但对系统版本敏感——Android 12+ 起后台截屏与手势注入都更严格，部分 ROM 需要保活。
- **ADB 模式**最稳，代价是需要开启无线调试；应用内置了一个悬浮面板，可以直接停留在「无线调试」设置页上方填写 IP / 端口 / 配对码，不用来回切应用。
- **Root 模式**最直接，不需要调试授权，也不需要无障碍服务，但要求设备已 Root。

---

## 工作流程

```
截图 ──► 识别棋子底部中心 & 目标方块上沿中心
     ──► 计算像素距离 distance
     ──► pressTime = distance × pressCoefficient
     ──► 长按（input swipe 同点起止 + 时长）
     ──► 随机等待 ──► 下一轮
```

- 棋子识别依赖顶部轮廓与主体色块（`ImageAnalyzer`）。
- 连续跳跃若干次后会随机插入 5–10 秒休息，降低被风控的概率。
- 调试界面可以叠加显示识别到的棋子、目标点与预测距离。

---

## 环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | **17**（AGP 8.x 要求，17/21 均可） |
| Android SDK | Platform **36** + Build-Tools 36 |
| Gradle | 由 Wrapper 自动下载（8.14.3） |
| AGP / Kotlin | 8.13.2 / 2.2.21 |
| minSdk / targetSdk | 26 / 34 |

`targetSdk` 刻意停留在 34：Android 15/16 进一步收紧了后台前台服务启动与悬浮窗行为，而本工具恰恰依赖这两点。

---

## 构建

### 1. 指定 SDK 路径

在仓库根目录新建 `local.properties`（已被 git 忽略，不要提交）：

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

如果用 Android Studio 打开过项目，这一步会自动完成。

### 2. 指定 JDK

Gradle 需要一个 17 以上的 JDK。项目里**不再硬编码** JDK 路径，请用环境变量指定：

```bash
export JAVA_HOME="/path/to/jdk-17"
```

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-17"
```

### 3. 编译

```bash
./gradlew assembleDebug      # 调试包（自动用 debug keystore 签名）
./gradlew assembleRelease    # 发布包
./gradlew test               # 单元测试
```

产物位置：

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`（未配置签名时为 `app-release-unsigned.apk`）

### 4. 发布签名（可选）

在仓库根目录新建 `keystore.properties`（已被 git 忽略）：

```properties
storeFile=../keys/release.jks
storePassword=******
keyAlias=jumpmaster
keyPassword=******
```

存在该文件时 `assembleRelease` 会自动签名；不存在则退化为未签名 APK，`adb install -r` 仍可安装。

---

## 使用

1. 安装 APK，首次启动按提示授予**悬浮窗**与（本地模式下）**无障碍服务**权限。
2. 在「设置」里选择工作模式：
   - 通用参数：按压系数 `pressCoefficient`、跳跃间隔、棋子头部直径、是否震动 / 存档截图。
   - ADB 模式：点「打开无线调试 + 悬浮填写」，在系统无线调试页完成配对。
   - Root 模式：点「检测并请求 Root 权限」。
3. 回到桌面打开微信「跳一跳」，点击悬浮窗上的开始按钮。
4. 悬浮窗实时显示状态、跳跃次数、上次按压时长与距离。

### 调参建议

- **按压系数**是最关键的参数。跳近了就增大，跳远了就减小，每次调整 2% 左右。
- 换分辨率不需要手动改 `screenWidth/Height`，`DeviceConfig.forScreen()` 会按 1080p 基准缩放，并用 `wm density` 校准棋子直径。
- Root / ADB 模式下建议先打开「保存截图」，用调试界面确认识别点是否准确。

---

## 项目结构

```
app/src/main/java/com/jumpmaster/app/
├── MainActivity.kt              # 权限流程 + Compose 宿主
├── core/
│   ├── ImageAnalyzer.kt         # 棋子 / 目标识别
│   ├── JumpController.kt        # 截屏→识别→按压主循环（三后端）
│   ├── JumpTiming.kt            # 距离 → 按压时长换算
│   ├── JumpTouch.kt             # 滑动起点 / 终点计算
│   ├── DeviceConfig.kt          # 分辨率预设与参数模型
│   ├── adb/                     # 无线 ADB 连接与 shell 通道
│   └── root/                    # 本地 su shell 通道
├── service/
│   ├── ScreenCaptureService.kt       # MediaProjection 截屏
│   ├── TouchAccessibilityService.kt  # 无障碍手势注入
│   ├── FloatingWindowService.kt      # 游戏内悬浮控制面板
│   └── AdbSetupOverlayService.kt     # 无线 ADB 配对悬浮面板
├── ui/                          # Compose 界面（主页 / 设置 / 调试）
└── data/ConfigRepository.kt     # SharedPreferences 持久化
```

`orig_wechat_jump_auto.py` 是原始 Python 实现的存档，仅作算法对照，不参与构建。

---

## 常见问题

**Gradle 报 `Failed to store cache entry ... (拒绝访问)`**
本机 `~/.gradle/caches/build-cache-1` 写不进去。项目已默认 `org.gradle.caching=false`；确认目录可写后再改成 `true`。

**`./gradlew` 报 `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`**
`gradle/wrapper/gradle-wrapper.jar` 丢失（旧版 `.gitignore` 里的 `*.jar` 会把它一起忽略掉，已修复）。执行 `gradle wrapper` 重新生成即可。

**本地模式点了开始却一直提示切换游戏**
OpenGL 渲染的界面在 MediaProjection 上可能拿到黑帧；Android 12+ 还会限制后台录屏。改用 ADB 或 Root 模式。

**ADB 模式连接失败**
无线调试的「配对端口」和「连接端口」是两个不同的端口，悬浮面板里都要填。配对成功后连接端口会变化，重新读一次。

---

## 致谢与声明

- 识别算法与参数预设参考 [wangshub/wechat_jump_game](https://github.com/wangshub/wechat_jump_game)（Python 原版）。
- 无线 ADB 能力基于 [flyfishxu/kadb](https://github.com/flyfishxu/kadb)。

本项目仅供学习 Android 图像处理、无障碍服务与 ADB 协议使用。请勿用于任何破坏游戏公平性的场景，由此产生的一切后果由使用者自行承担。
