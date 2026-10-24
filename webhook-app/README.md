# Webhook 极简通知 App

一个体积极小（约 46KB）的 Android 应用：在手机上启动一个 HTTP 服务，接收任意来源的 Webhook 请求，把消息以通知 / 声音 / 震动 / 悬浮窗 / 语音朗读的形式推给用户，并沉淀到可复制的消息历史中。

- 包名：`com.webhook.notify`
- 版本：`2.0`（versionCode 2）
- 最低系统：Android 8.0（minSdk 26），targetSdk 34
- 依赖：**零第三方库**，纯原生 Java + Android 系统 API

---

## 一、开发环境信息

本项目刻意不使用 Gradle / Android Studio 工程结构，全部构建依赖命令行工具链。

| 项目 | 说明 |
|---|---|
| 操作系统 | Linux（远程沙箱，非交互执行，CI=true） |
| JDK | JDK 25，`javac` 以 `-source 1.8 -target 1.8` 交叉编译 |
| Android SDK | `/tmp/android-sdk`（可通过环境变量 `ANDROID_SDK` 覆盖） |
| Build-Tools | **35.0.0**（必须，见下方踩坑记录） |
| Platform | `platforms;android-34/android.jar` |
| 打包方式 | `aapt2` → `javac` → `d8` → `zip` → `zipalign` → `apksigner` |
| 签名 | 首次构建自动生成 `debug.keystore`（storepass/keypass 均为 `android`，alias `androiddebugkey`），保持签名一致以便覆盖安装 |
| 语言 | Java 8 语法，**禁止使用 lambda**（d8 在 android.jar 存根下无法 desugar `metafactory`），统一写匿名内部类 |

### 构建

```bash
bash build.sh            # 使用默认 SDK 路径
ANDROID_SDK=/path/to/sdk bash build.sh
```

产物：`Webhook.apk`（约 46KB）。

### 测试

```bash
# 标准格式（任意路径、任意方法都接受）
curl -X POST http://手机IP:8080 -d '{"text":"CPU 90%"}'

# 查询参数
curl 'http://手机IP:8080?text=hello%20world'

# 表单
curl -X POST http://手机IP:8080 -d 'text=hi+there'

# 纯文本
curl -X POST http://手机IP:8080 -d '构建完成'
```

响应统一为 `{"text":"..."}`：

- 成功：`200` + `{"text":"success"}`
- 失败（例如没有 text）：`400` + `{"text":"缺少 text 字段，请发送 {\"text\":\"内容\"}"}`

给 AI 的指令可以只写一句：「向 `http://IP:端口` 发送 POST，body 固定为 `{"text":"要通知我的内容"}`」。路径随意，AI 不需要知道具体接口。

---

## 二、开发逻辑与行为约定

### 1. 构建流水线（`build.sh` 七步）

1. `aapt2 compile --dir res -o res.zip`
2. `aapt2 link` 生成 `base.apk` 与 `R.java`
3. `javac` 编译 `src` + 生成的 `R.java`
4. `d8 --release --min-api 26` 转 DEX
5. 把 `classes.dex` 塞进 APK
6. `zipalign -f 4`
7. `apksigner` 签名输出 `Webhook.apk`

### 2. 运行时架构

| 文件 | 职责 |
|---|---|
| `ServerService.java` | 前台服务；手写 HTTP 服务器（ServerSocket 绑定 `0.0.0.0`）；解析请求行 / 头 / body，支持 `100-continue` 与大包体（上限 1MB）；分发消息到各提醒通道 |
| `MainActivity.java` | 主界面：服务开关、端口、全部功能开关、状态展示、权限引导 |
| `ClipboardActivity.java` | 剪切板页：时间倒序的消息列表，一行一条，点击复制 |
| `FloatingManager.java` | 悬浮消息条（4 秒自动消失，点击关闭） |
| `HistoryStore.java` | 消息持久化，JSON 行文件 `history.jsonl` 存于应用私有目录 |
| `Prefs.java` | SharedPreferences（`cfg`）统一封装所有开关与配置 |
| `BootReceiver.java` | 开机自启：上次处于运行态且开启自启开关时才恢复服务 |
| `res/` | 布局与 drawable；米白轻拟物视觉全部用 `layer-list` 手工实现，无图片资源 |

### 3. 消息处理链路

```
HTTP 请求（任意路径 / 任意方法；JSON / 表单 / 纯文本）
  → 取出 text，取不到则返回 400 错误 JSON
  → HistoryStore.append（按「保留条数」裁剪）
  → 常驻通知（开关：fgsNotify）
  → 消息通知（开关：notify）
  → 铃声 + 震动（开关：sound / vibrate，铃声支持自定义）
  → 悬浮窗（开关：floatMsg，需悬浮权限）
  → 语音朗读（开关：tts，系统 TTS）
```

### 4. 后台保活机制

按「前台服务 + 系统白名单 + 定时看门狗」三层叠加，目标是服务在后台时始终能收到消息：

| 层 | 做法 |
|---|---|
| 前台服务 | `startForeground` + `foregroundServiceType="specialUse"`，常驻通知（IMPORTANCE_MIN，静音）；`stopWithTask="false"`，划掉最近任务不结束服务 |
| 系统级恢复 | `START_STICKY`；`BOOT_COMPLETED` / `QUICKBOOT_POWERON` 开机恢复；`onTaskRemoved` 延时 2 秒尝试重新拉起 |
| 看门狗 | `AlarmManager.setInexactRepeating` 每 15 分钟唤醒一次，检查 `Prefs.running` 为 true 就拉起服务（被系统杀进程后能自愈） |
| 白名单引导 | 首次启动服务时若未加入电池优化白名单，弹一次引导（可拒绝）；「保活」卡片的「电池优化」可随时再去 |

`running` 标记的语义：**用户主动开启服务时置 true，主动关闭（开关 / 重置）时置 false**。服务 `onDestroy` 不清除它，这样被系统回收后看门狗才知道要恢复；端口被占用这类无法自愈的错误会主动置 false，避免无意义重试。

### 5. 关键实现决策（含踩坑记录）

- **d8 报 `Unable to find method metafactory`**：lambda 无法在 android.jar 存根下 desugar → 全项目改用匿名内部类。
- **d8 内部 NPE（`String.length()` on null）**：build-tools **34.0.0 的 d8 与 JDK 25 不兼容** → 升级到 35.0.0。
- **通知渠道声音设置不可变**：`NotificationChannel` 创建后声音/震动被系统永久锁定，改渠道 ID 也没用 → 渠道固定静音，**声音与震动由 App 自行播放**（`RingtoneManager` + `Vibrator`），彻底摆脱缓存。
- **TTS 静默失败**：部分国产机未预装语音引擎 → 启动时不打扰用户，仅当用户打开「语音朗读」开关时才试读并提示去系统设置装引擎。
- **前台服务通知无法直接隐藏**：Android 要求前台服务必须有通知 → 关闭「常驻通知」时先 `startForeground` 再 `stopForeground(REMOVE)`，服务降级为普通后台服务（用户自选，界面不做额外提示外的人工干预）。
- **消息不能只存内存**：进程被杀即丢 → `history.jsonl` 文件持久化，每条截断至 2000 字控制体积。
- **体积优先**：无 Jetpack、无 RecyclerView、无图片资源，列表用 `ListView`，图标用矢量 XML。

---

## 三、我的需求与偏好（需求基线）

开发过程中逐步明确并稳定下来的要求，后续改动需遵循：

### 体积与依赖
- **安装包越小越好**，当前 ~46KB；不引入任何第三方库，不用 Gradle。

### 服务能力
- 启动一个 Webhook 服务，接收他人发来的请求。
- 有**总开关**控制监听启停；**端口可修改**，**首次启动随机分配 10000 以上**的端口，之后沿用；**默认监听所有 IP**（`0.0.0.0`）。
- 开关旁文案随状态变化：未运行显示「启动服务」，运行中显示「停止服务」。
- 卡片布局：「已停止/运行中」状态与「监听端口」同卡；「剪切板」与「启动服务」开关同卡，两个按钮各占一半。
- 状态卡不再重复显示 `0.0.0.0:端口`，接收地址（本机 IP:端口）以灰色小字**紧跟在状态文字后面**，与「已停止/服务运行中」同一行。
- 底部提供「重置服务」按钮：确认后停止服务并清空全部设置与消息记录，端口重新随机。
- 支持常驻后台、开机自启。

### 消息格式与响应
- **请求体默认且推荐 `{"text":"消息内容"}`**，不要求 title。
- 任意路径、任意方法都接受；JSON / 表单 / 查询参数 / 纯文本均可。
- 响应统一 `{"text":"..."}`：成功 `200 + success`，失败 `400 + 原因`。

### 提醒方式（全部必须可独立开关）
- **「提醒」与「保活」都采用胶囊卡片式双列网格，一排两个**；「自定义铃声」因需展示当前铃声名，独占一整行。
- 通知栏：拆成两个开关——**常驻通知**（服务状态那条）与**消息通知**（收到消息那条）。
- 声音、震动。
- 自定义铃声：走**系统铃声选择器**，选完即时试听。
- 悬浮：只需**悬浮消息条**一种，悬浮球已按需求裁掉。
- 语音朗读：调用系统 TTS 把消息读出来。

### 消息留存与剪切板
- 消息必须**持久化**，杀进程 / 重启后仍在。
- **保留条数可设置，默认 50 条**（范围 1–500）。
- 独立的剪切板页：入口在「剪切板 / 启动服务」卡片内、与开关各占一半；页面类似输入法剪贴板，**时间倒序**，**不论消息多长都只占一行**，**点击一行即复制全文**。

### 视觉与交互偏好
- 圆润大圆角、米白色高级感、轻微 3D 的轻拟物风格（参考锤子科技 / 苹果的克制质感）。
- 配色：背景 `#F0EADC`、卡片 `#F7F2E7`、文字 `#3E3930`、主色 `#7C9A6D`。
- 卡片 24dp 圆角，双层高光 + 暖影模拟凹凸；输入框内凹；开关为自定义拟物样式；运行时状态灯亮绿色。
- 文案中文、简洁，不使用 emoji。

### 交付习惯
- 每次改动后**重新构建并自检**（签名、Manifest 注册、关键类是否打进 dex）。
- 交付时说明改动点与需要用户确认/操作的地方（如权限、音量、勿扰模式）。
- 遇到不是代码问题的情况（如系统音量、勿扰），明确告知，不盲目改代码。

---

## 四、权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | 监听端口收发请求 |
| `POST_NOTIFICATIONS` | Android 13+ 发通知 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | 常驻前台服务 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 / 悬浮球 |
| `VIBRATE` | 震动提醒 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 电池优化白名单引导 |

---

## 五、已知取舍

- 关闭「常驻通知」后服务变为普通后台服务，系统内存紧张时更容易回收；需长期稳定挂后台时建议保持开启（界面已加提示）。
- 看门狗为 15 分钟周期的最坏恢复时间：服务被系统杀掉后，最长约 15 分钟才会被拉起，期间消息会漏收。这期间来不了的消息无法补发。
- 国产 ROM（MIUI / EMUI / ColorOS 等）可能额外限制后台启动与自启动，需用户在系统设置中手动允许；App 只能做引导，无法绕过。
- 语音朗读依赖系统 TTS 引擎，缺失时需用户自行安装。
- 历史消息时间只显示 `HH:mm`，跨天查看需结合上下文判断。
- 自定义铃声 URI 依赖系统媒体库授权，极端情况下换机/清理媒体库后可能失效，回落到默认通知音。
