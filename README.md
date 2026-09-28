# TapDock — LSPosed 纯模块 · 基础任务栏 Dock

一个**纯 LSPosed（Xposed）模块**：通过 hook SystemUI / 桌面进程，往屏幕顶部任务栏
（系统状态栏）的**布局内部**注入一个**黑色圆角的空 Dock 条**——不是悬浮窗，而是任务栏
自身的一部分。**基础版不显示任何内容**，只提供 Dock 骨架、靠左/靠右与显示开关，
内置「重启 SystemUI」按钮。

> 目标平台：lsp102 的 AOSP（Android 8.0+，建议 12L+；已在 Android 16 + LSPosed 上验证注入成功）

```
状态栏（任务栏）布局内部示意：

┌──────────────────────────────────────────────────┐
│ 通知图标          [▉]               网速 ␣ WiFi ␣ 电量 │  ← 靠右（默认）
│ [▉] 通知图标                       网速 ␣ WiFi ␣ 电量 │  ← 靠左
└──────────────────────────────────────────────────┘
   [▉] = 黑色圆角空 Dock 条（80% 不透明度，12dp 圆角）
```

## 功能

- **注入任务栏布局**：hook `PhoneStatusBarView.onFinishInflate`，把 Dock 条插进
  `status_bar_contents`（找不到 id 时退化为 END/START 重力摆放）
- **同时兼容 12L 任务栏**：hook 桌面进程的 `com.android.launcher3.taskbar.TaskbarView`，
  无任务栏的设备自动跳过
- **位置直接切换**：**靠左 = 任务栏布局最左（index 0）**，**靠右 = 任务栏布局最右（末尾）**，
   切换立即生效（无需重启 SystemUI）
- **配置三重保障**：广播直推 extra（即时）+ Hook 侧每 3 秒轮询 App 的
  ContentProvider（广播丢失也生效）+ XSharedPreferences 兜底（SystemUI 重启后初始状态）
- **进度显示（同一套渲染代码）**：Dock 内置进度条，三个进度源按优先级取用——
  **下载进度**（**读取下载器通知**：SystemUI 通知管线中带进度条的常驻通知，
  按所选下载器应用过滤）>
  **音乐播放进度**（安卓媒体 API：MediaSession 读取所有音乐应用的播放位置，
  SystemUI 持有 MEDIA_CONTENT_CONTROL 特权，无需选择应用）>
  **充电进度**（接电时显示电量），均无则隐藏进度条
- **进度条彩虹猫风格（可关）**：把 Dock / 面板进度条的填充换成 nyan 六色彩虹带横向流动，猫图（`cat.png` 先裁掉四周透明边、再最近邻缩放到 20dp/26dp 高）叠加骑在彩虹带前端正中，彩虹带在猫体内侧 6dp 处收口 —— 猫右边只会剩下灰色未填充轨道，不会漏出一截彩虹；纵向刻意不探到带子上方（状态栏窗口会把探出部分整块裁掉，实测过），
  左边缘也不许探到带子起点以左（那里紧挨 WiFi / VPN 状态图标）——带子比猫短时改为向右压在灰色轨道上；App 里「进度条彩虹猫风格」开关随时关掉回到单色进度条（走广播 + Provider 轮询，改完 ≤3 秒生效，不用重启 SystemUI）；流动刻意压到约 14fps 自绘，不跟 60fps 抢；条纹宽度按 `scaleX` 反算，进度 5% 时也不会被压成一条糊色；注意它本质是进度条，所以只在有进度（下载 / 音乐 / 充电）时出现
- **进度条颜色自定义**：App 内 8 色色板选择填充颜色，实时生效
- **下载器应用选择器**：多选 + 全选，Dock 只跟踪所选下载器应用的通知进度
  （不选 = 跟踪全部应用的通知）
- **Dock 状态图标（左右排布，仅激活显示）**：状态图标与进度条同一行左右排布——
  **WiFi（已连接）/ 蓝牙（已连接设备，仅开开关不算）/ 定位（实际被占用）/ VPN（连接中，钥匙图标）/
  摄像头（实际占用中）/ 麦克风（实际占用中）**，
  **只有激活才显示对应图标**，未激活完全不占位；
  WiFi/蓝牙/定位/VPN 的图标**已从系统状态栏隐藏、改由 Dock 接管显示**
  （Lineage 状态栏图标黑名单 + hook `StatusBarIconControllerImpl` 按槽位拦截并清除），
  摄像头/麦克风读取 SystemUI 隐私指示器数据源（`PrivacyItemController`），
  右上角原生隐私胶囊（`privacy_chip` / `privacy_container`）在视图层一并隐藏；
  **状态图标实时刷新**：WiFi/VPN 走 `ConnectivityManager` 网络回调、蓝牙走系统广播、
  摄像头/麦克风走隐私指示器回调、定位走 `AppOpsPrivacyItemMonitor` 的 AppOps active 事件，
  3 秒轮询退化为丢事件时的兜底；
  **播放中（封面小图出现时）连接类图标整批让位** —— WLAN / 蓝牙 / 定位 / VPN 藏起来把位置给封面，
  停止播放立刻恢复；**摄像头 / 麦克风不让位**：原生隐私胶囊本来就被本模块隐藏，Dock 再藏就等于
  没有任何占用提示了
- **隐私占用红色警示**：摄像头或麦克风**正在被使用**时整条 Dock 底色转红，
  释放后立刻恢复黑色（同一帧内完成，不等轮询）。定位只显示图标不参与红底：
  本 ROM 把"定位隐私指示"编成了常量 `false`（`TYPE_LOCATION` 永远不会进
  `PrivacyItemController`），定位态是靠旁路 AppOps 事件拿到的，语义与相机/麦克风不同
- **全圆角（胶囊形）Dock**：圆角自动等于高度的一半
- **点击 Dock 弹出控制中心**：面板**贴屏幕顶部、水平居中**（不再跟着 Dock 摆位），
  宽度铺满、左右各留 16dp，样式对齐 **LineageOS 23.2 快捷设置的 QS pill 卡片**——
  双列网格，8 个开关各一张卡：**蓝牙 / WLAN / 热点 / 移动数据 / 定位 / 手电筒 / 摄像头 / 麦克风**；
  每张卡 = 圆角图标块 + 标题 + 「已开启/已关闭」副标题，卡体恒为中性色，开关状态只落在图标块上
  （关闭 = 灰块灰图标，开启 = 强调色块 + 对比色图标，对比色按强调色明暗自动取深/白，
  强调色直接读 SystemUI 主题 colorAccent），自动适配深/浅色模式，面板开着时每秒回读一次状态；
  **点卡片切开关，长按卡片跳该开关的系统设置页**（对齐系统 QS 的长按行为；面板自己是 overlay 窗口，
  所以长按会先收起面板再拉设置，否则面板会压在设置页上面）；
  **卡片顺序在模块 App 里排**：「排列卡片顺序」进双列网格界面，长按拖动换位，
  松手即写 `DockPrefs.cardOrder` 并广播直推 Hook 侧（Provider 轮询 / XSharedPreferences 兜底），
  面板下次展开就按新顺序铺；
  代码运行在 SystemUI 进程内直接调用系统特权 API（WlanManager、BluetoothAdapter、
  TelephonyManager、LocationMode、CameraManager 手电筒），热点走系统热点磁贴同一条路
  （反射 `TetheringManager.startTethering(TETHERING_WIFI, …)` / `stopTethering`，
  状态读 `WifiManager.isWifiApEnabled()`），摄像头/麦克风走
  Android 12+ 的 `SensorPrivacyManager`（反射），点面板外部或再点 Dock 关闭
- **展开时整屏一层实时毛玻璃**：模糊只下发一次，走全屏遮罩窗口的 SurfaceControl
  （`SyncRtSurfaceTransactionApplier` + `setBlurRegions`，Android 16 每个区 10 个 float：
  半径 / 不透明度 / 矩形 / 四个角各自的圆角半径）。面板**不再**自带一层模糊：两层矩形模糊
  互相裁切，接缝正落在面板四个角上，看起来就是"角上糊得和别处不同步"。现在面板和 Dock 同一材质——
  圆角卡片 / 胶囊只靠自身半透明底色浮在这层毛玻璃上，半径是同一个 `ControlPanel.BLUR_DP`。
  Dock 那块仍是清晰排除区（图标糊了不能看），但胶囊是圆角、排除区只能是矩形，所以四角各补一个
  方块进模糊区（与胶囊重叠的部分是纯色底，糊了看不出来）；Dock 一变形（收起/展开补间、图标增减
  改变宽度）就由 `HookDock` 的布局回调重算排除区，不会按旧位置留下清晰矩形
- **控制中心进度区**：作为**控制区下方的独立圆角区域**（不再夹在音乐卡片与开关网格之间）。
  **多项下载各占一行**：并发下载时每个下载器应用一行（按进度从高到低排，标签是应用名，
  首次解析后缓存），后面接音乐播放与充电两行；行数和顺序只在集合真变了时才增删重排，
  平时每秒只改文字和填充；全部没有进度时整块 `GONE` 不占位。Dock 那条仍是单轨，取最高进度
- **Dock 通知数徽标**：最左侧一个圆形徽标显示当前活动通知条数（超过 99 显示 `99+`，0 条时不占位），
  数据源直接挂在 SystemUI 自己的通知管线上：`NotificationListener.onListenerConnected` 时用
  `getActiveNotifications()` 补一次全量（否则漏掉"钩子装好之前就挂着"的通知），
  之后靠 `onNotificationPosted` / `onNotificationRemoved` 增量维护 `pkg:id` 集合；
  分组汇总条目（`FLAG_GROUP_SUMMARY`）不单独计数；徽标算作 Dock 内容，会压过时钟显示
- **控制中心状态区**：控制区与进度区之间一条圆角读数行，五格等分 = 蜂窝信号 / 电量 / WLAN 信号 /
  网络上下行 / 当前页面帧率，跟着面板的 1 秒节拍刷新。数据源都挑的是免权限的：电量取 `ACTION_BATTERY_CHANGED`
  粘性广播（充电时后缀"充电"），WLAN 取 `WifiManager.connectionInfo.rssi` 折算 0~4 格
  （脱敏只针对 SSID/BSSID，rssi 不受定位权限影响），上下行是 `TrafficStats` 累计字节两次采样差分。
  蜂窝信号用 @hide 的 `TelephonyManager.getSignalStrength()` + `SignalStrength.getLevel()`，
  **宿主进程没有 `READ_PHONE_STATE` 时会抛 SecurityException**（dumpsys 显示桌面进程就是
  `granted=false`，SystemUI 紧凑 Dock 那次实测读到 `蜂窝 3/4`），
  此时该格显示 `--` 并把探针标记为死，不再每秒撞异常；面板打开时 `[TapDock] status area: ...`
  把四格读数原样打一次日志，便于不碰屏幕也能核对（首格网速要等第二个采样点，建面板那一帧是 `--`）
- **帧率一格 = `FpsMonitor`**：在桌面和 SystemUI 两个进程里按方法名挂私有方法
  `Choreographer#doFrame`，每轮 +1；面板的 1 秒节拍调 `sample()` 把累计帧数按真实间隔折算成每秒并清零。
  数的是"这一秒真画了多少帧"，**静止画面不排帧所以会显示 0**，不是屏幕刷新率；
  宿主是被接管的两个进程，所以主页 / 抽屉 / 最近任务读的是桌面进程，状态栏与面板读的是 SystemUI，
  第三方应用里的帧率拿不到（那要 root 跑 `dumpsys gfxinfo`）。`sample()` 一段只能调一次，别在别处再调
- **音乐控制卡片**：面板顶部有媒体会话时出现——封面 / 曲目 / 艺术家 + 进度条 +
  **左「当前播放位置」右「总时长」**（`m:ss`，超一小时自动带小时位）+ 上一首 / 播放暂停 / 下一首，
  每秒随 tick 推进（播放位置按 `lastPositionUpdateTime` 和倍速外推，不等回调）
- **展开时 Dock 收成"返回"胶囊**：面板一展开，Dock 只留一颗左箭头图标（点整条 Dock 仍是开/关），
  宽度从实测值补间到 `2×padding + 图标`（220ms，fast-out-slow-in 贝塞尔），面板收起时再补间回
  展开前记下的宽度并交回 `WRAP_CONTENT`；徽标/状态图标/进度条/时钟在收起期间全部让位，
  恢复时靠作废 `lastStates`/`lastPercent` 缓存把状态刷回来
- **播放音乐时 Dock 背景 = 模糊专辑封面**：App 里「播放音乐时 Dock 背景显示模糊封面」可关。
  封面先缩到 10×10 再双线性放大成胶囊尺寸（RenderScript 在 API 31 已移除，
  `RenderEffect` 会连图标一起糊），两端按胶囊圆角裁掉，再压一层 65% 黑保证白色图标可读；
  背景优先级 = 隐私告警红 > 模糊封面 > 纯色胶囊，换曲（`pkg|title` 变了）才重算一次，
  收起 / 放大的宽度动画期间背景用 `Gravity.FILL` 跟着缩放不会露出旧尺寸
- **进度条左侧封面小图**：App 里「播放音乐时在进度条左侧显示封面小图」，默认开，与背景模糊各自独立。
  位置在状态图标之后、进度轨道之前（`iconSize` 见方、4dp 圆角），源位图先中间取方再缩到目标尺寸，
  只有换曲（`pkg|title` 令牌变了）才重算位图；非播放 / 关掉开关 / 控制中心展开时都 `GONE` 让位。
  注意 `createBitmap` 在源图本就是正方形时会直接返回同一个对象，所以回收中间产物前要先比引用，
  否则会把 metadata 的位图 recycle 掉；时钟与它抢同一段剩余宽度，播放中优先给封面
- **Dock 空位时钟**：状态图标和进度条都没东西可显示时，Dock 让位给 `HH:mm:ss` 时钟
  （等宽字体，避免秒数变化导致宽度抖；独立 1 秒节拍，不借用 3 秒配置轮询），
  一旦有图标或进度立刻换回来；展开控制中心时同样让位 —— 收起分支必须调 `updateClock(false)`
  把 `clockVisible` 一起落下，只把视图置 GONE 的话下一秒节拍会把时间重新点亮
- **状态区 CPU 全核心图**（App 里「控制中心状态区显示 CPU 全核心占用图」，**默认关**）：读数行下面
  再补一行竖柱，一核一柱，柱高 = 该核这一段的占用百分比，宽度等分所以 8 核 / 12 核都塞得下。
  数据来自 `CpuSource` 读 `/proc/stat` 的 `cpu0..cpuN` 累计 ticks 做差分（`busy = user+nice+system+irq+softirq+steal`，
  `idle = idle+iowait`），聚合行 `cpu ` 第 4 个字符不是数字，正好和 `intr` / `processes` 一起被同一个判断跳过；
  内核只给累计值，所以**第一次 `sample()` 只能建基准**，面板重新打开时会 `CpuSource.reset()` 重开一段，
  否则第一帧读数会把"面板关着的这段时间"平均进去。读不到 / 还没基准时返回空数组 = 整块不画，不画 0% 骗人
- **开/关控制中心动画 = macOS Genie（GPU 网格形变）**：面板拍成纹理切成 64×64 网格，
  每个顶点从"源矩形里的自己"走到"Dock 矩形里同一归一化位置的自己"，出发时机按离 Dock
  那条边的距离错开（近端先走、远端拖尾），于是内容被拉成连续漏斗吸进 Dock —— 不是 scaleX/scaleY，
  也不用淡出冒充。引擎在 `app/src/main/java/com/lsp102/tapdock/genie/`（四向 `GenieDirection`、
  全参可调 `GenieConfig`、GLES2 `GenieRenderer`、`GenieView`、全屏 `GenieOverlay`），
  形变公式在 `assets/genie_vertex.glsl`；顶点缓冲只存 (u,v) 建一次，每帧只更新 uniform；
  恢复动画反向播；GL 或加窗任一环节失败即退回瞬时显示；`GenieConfig.debugGrid` 可叠加真实形变网格线框
- **App 适配系统主题色**：Material You 动态取色（Android 12+）+ DayNight 深浅色跟随系统
- **显示开关**：随时隐藏/恢复 Dock 条，立即生效
- **重启 SystemUI 按钮**：通过 `su` 杀掉 SystemUI 进程自动拉起，模块启用后一键生效

## 使用步骤

1. 在 **LSPosed 管理器**中启用 TapDock 模块
2. 作用域勾选：**系统界面 (com.android.systemui)** 和 **桌面 (com.android.launcher3)**
3. 打开 TapDock，点「重启 SystemUI」
4. 之后改位置/开关直接点「重启 SystemUI」或等广播实时生效（位置与开关为实时生效，
   无需每次重启；仅首次启用模块时需要重启）

## 构建

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

环境要求：JDK 17+，Android SDK（compileSdk 35）。
仓库 `settings.gradle` 已把阿里云镜像放在 Maven Central 之前（直连 Central 不稳定时兜底），
Xposed API 使用 `compileOnly 'de.robv.android.xposed:api:82'`（运行时由 LSPosed 注入）。

## 工程结构

```
TAPDOCK/
├── app/src/main/java/com/lsp102/tapdock/
│   ├── MainActivity.kt        # 显示开关 + 靠左/靠右 + 进度颜色 + 重启 SystemUI（su）
│   ├── DownloadPickerActivity.kt # 下载进度应用选择器（多选 / 全选）
│   ├── CardSpec.kt            # 卡片元数据（id / 标签 / 图标 / 长按跳的设置页），App 与面板共用
│   ├── CardOrderActivity.kt   # 卡片排序界面（双列网格 + 长按拖动换位）
│   ├── DockPrefs.kt           # 配置持久化（hook_enabled / dock_align / progress_color / download_packages / card_order）
│   ├── DockConfigProvider.kt  # 导出配置 Provider（Hook 侧轮询读取，兜底通道）
│   ├── HookSync.kt            # 配置变化 → 广播【携带 extra】即时推送
│   ├── TapDockApp.kt          # DynamicColors 系统主题色适配
│   └── xposed/
│       ├── MainHook.kt        # Xposed 入口（按包名分发 hook）
│       ├── StatusBarHook.kt   # SystemUI 状态栏注入
│       ├── TaskbarHook.kt     # 12L 任务栏注入（桌面进程）
│       ├── ProgressSource.kt  # 充电/下载进度源（音乐进度在 HookDock 内经 MediaSession 读取）
│       ├── CpuSource.kt       # /proc/stat 每核 ticks 差分 → 全核心占用百分比
│       ├── CpuChartView.kt    # 状态区的每核竖柱画布（只画不测）
│       ├── FpsMonitor.kt      # Choreographer#doFrame 计数 → 当前页面帧率
│       ├── PrivacyMonitor.kt  # 摄像头/麦克风占用数据源（旁观 PrivacyItemController 的回调）
│       ├── NyanFillView.kt   # 进度条填充：单色圆角条 / nyan 彩虹带流动
│       ├── ControlPanel.kt    # 点击 Dock 弹出的控制中心（Lineage QS 卡片风格 · 8 卡双列 · 状态区 · 长按跳设置）
│       └── HookDock.kt        # Dock 视图（黑色胶囊 + 进度条 + 三重配置同步）
├── app/src/main/assets/xposed_init   # 模块入口声明
└── app/src/main/res/values/arrays.xml # LSPosed 作用域列表
```

关键机制：

- **配置直推**：App 侧每次修改都通过 `HOOK_RELOAD` 广播**直接携带配置 extra**
  （`show` / `align`），Hook 侧收到后立即生效
- **轮询兜底（解决"改了没效果"）**：Hook 侧每 3 秒查询
  `content://com.lsp102.tapdock.config/state`，即使广播丢失、
  XSharedPreferences 失效，App 里的修改也必然在 3 秒内生效
- **进度同一套代码**：`ProgressSource.snapshot()` 只需返回 0..100 百分比，
  渲染统一由 `HookDock.applyProgress()` 完成；充电源用电池粘性广播，
  下载源用系统 DownloadManager 的运行中任务
- **注入确认**：Hook 侧注入成功后回发 `HOOK_ACTIVE` 广播，App 界面显示「已注入 ✓」
- **模块资源加载**：Hook 代码运行在宿主（SystemUI/桌面）进程里，直接用 R 常量
  解析资源会落到宿主资源表上（图标必然加载失败），因此所有 Dock 图标、
  控制中心磁贴图标与文案都通过 `createPackageContext("com.lsp102.tapdock")`
  的模块资源上下文加载（`ModuleRes`）
- **重启 SystemUI**：`su -c "kill $(pidof com.android.systemui)"`，失败时回退
  `killall`；LSPosed 环境本身具备 root

## 可调项

| 选项 | 默认值 |
|---|---|
| 显示任务栏 Dock | 开 |
| Dock 位置（靠左 / 靠右） | 靠右 |
| 进度显示 | Dock 取最高进度；面板进度区按下载器应用各一行 + 音乐 + 充电，均无则隐藏进度条、Dock 改显示时钟 |
| Dock 封面背景 | 默认开；播放中用 10×10 双线性放大的模糊封面 + 65% 黑遮罩，隐私告警时让位给红底 |
| 进度条彩虹猫 | 默认关；开启后填充变流动彩虹 + 前端猫图（App 内开关） |
| Dock 状态图标 | WiFi/蓝牙/定位/VPN（开启/连接即显示，接管系统状态栏图标）+ 摄像头/麦克风（实际占用时显示），全部事件驱动；播放中连接类图标让位给封面小图 |
| Dock 封面小图 | 默认开；播放中显示在进度条左侧，`iconSize` 见方 + 4dp 圆角，换曲才重算位图 |
| 背景 | 黑色，80% 不透明度，胶囊圆角；摄像头/麦克风占用时转红 `#D32F2F`（代码内常量） |
| Dock 条宽度 | 状态栏 ≥122dp / 任务栏 ≥156dp，随内容自适应（代码内常量） |
| 控制中心面板 | 贴屏幕顶部、水平居中，宽度 = 屏宽 − 左右各 16dp，8 张卡片双列等分；顺序在 App 里排（`dock_prefs` 的 `card_order`）；状态区默认只有读数行，CPU 全核心图在 App 里开（默认关） |
| 开/关面板动画 | 开 320ms（缩放 0.82→1 + 淡入 + 1.4 回弹）/ 关 200ms（缩回 + 淡出），代码内常量 |
