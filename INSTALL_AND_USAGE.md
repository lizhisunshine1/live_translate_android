# 安装与使用说明

本文覆盖两件事：**怎么装到手机上**、**装上之后怎么用（记录本 / 难词 / 复习 / 词典）**。

---

## 一、安装包在哪、装哪个

| 类型 | 路径 | 用途 |
| --- | --- | --- |
| **调试安装包（当前唯一可用产物）** | `app/build/outputs/apk/debug/EyeOpener.apk` | 直接装到任何一台 Android 手机，装完即用 |
| 源码 | 仓库根目录 | 需要自己改代码时用 Android Studio 打开 |

> 打包名固定为 `EyeOpener.apk`，包名 `io.github.ztfang.eye`，版本 `1.0`（`versionCode 1`）。
> 仓库里**没有**提交 APK（GitHub 单文件 100 MB 限制，这个包有 128 MB），装包请在自己机器上按下面第 3 步构建一次，或直接用我这边已经构建好的这一份。

### 1. 直接用现成的 APK（最快）

把这一个文件 `EyeOpener.apk` 拷到手机，任意位置（推荐 `Download/`），然后在手机上点它安装：

1. 用数据线把手机连电脑，`adb push` 或直接文件管理器拖进去；
2. 手机上打开「文件管理 → 手机 → Download」，点 `EyeOpener.apk`；
3. 首次会弹「未知来源应用安装」，允许「文件管理」安装即可；
4. 安装完成，桌面出现 **EyeOpener** 图标。

命令行方式（手机已开 USB 调试）：

```bash
adb install -r -t EyeOpener.apk
```

### 2. 小米 / Redmi（HyperOS、MIUI）装不上怎么办

如果 `adb install` 报：

```
Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```

这是 MIUI 的「USB 安装」二次确认被系统安全中心瞬间取消，不是包的问题。依次确认：

1. 设置 → 更多设置 → 开发者选项 → **USB 安装**（打开）、**USB 调试（安全设置）**（打开）；
2. 同一页进 **USB 安装管理**，确认没有拦截记录；
3. 仍失败时，改走「手机上点安装包」的方式（上面第 1 步），完全绕过 adb 校验；
4. 还是不行就登录小米账号（有些机型要求账号授权后才放行 USB 安装）。

### 3. 从源码构建

环境（已在 Windows + JDK 21 + Android SDK 36 上验证）：

- JDK 21（Android Studio 自带 JBR 即可）
- Android SDK，`compileSdk 36`
- Gradle 用仓库自带 wrapper（`gradle-9.4.1`），首次构建会下依赖，约 20 分钟；之后增量 1~3 分钟

```bash
# Windows
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=%LOCALAPPDATA%\Android\sdk
gradlew.bat :app:assembleDebug

# macOS / Linux
export JAVA_HOME=/Applications/Android\ Studio.app/Contents/jbr/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/EyeOpener.apk`（约 128 MB，含 arm64-v8a / armeabi-v7a 两种原生库，32 位老机型也能装）。

跑单元测试：

```bash
gradlew.bat :domain:testDebugUnitTest :app:testDebugUnitTest
```

> 架构只有 `arm64-v8a` / `armeabi-v7a`，**x86 模拟器装不上**（会报 NO_MATCHING_ABIS），必须用真机。

---

## 二、首次启动要做的三件事

1. **给权限**：麦克风（识别说话）、通知（复习提醒）、悬浮窗（字幕浮在别的 App 上面）。悬浮窗是「在其他应用上层显示」，小米在「特殊权限」里。
2. **下识别模型**：设置 → **模型下载** → 中英文（161 MB，走 ModelScope 国内源）。不下模型，点开关会提示 `Sherpa-ONNX 模型未下载` 并自动关闭。
   - 其他语言按需下，多语种包 685 MB。
3. **选翻译引擎**：本地翻译（Google 离线，需要 Google Play 服务，**国产机一般没有**）→ 建议改成 **云端翻译** 或 **AI 翻译**（在设置里填自己的 key）。

---

## 三、怎么用

### 1. 实时悬浮字幕

主界面顶部「实时翻译字幕」开关打开 → 字幕浮窗出现在任意 App 之上：

- 浮窗可拖动、可缩放；三档显示模式：只看原文 / 只看译文 / 双语；
- 收音两种：麦克风（听环境/说话），或 Android 10+ 的「应用内声音」（不外放也能翻译，需要每次授予屏幕录制权限）；
- 浮窗右上角 `设置 / 返回 / 取消`：取消 = 停止这次录音。

**一次「打开开关 → 关闭开关」= 一次记录会话**，语音和文字都会自动归档（见下）。

### 2. 记录本（主界面「记录本」按钮）

四页：

| 页 | 内容 |
| --- | --- |
| 本次 | 当前/最近一次会话：逐句时间、英文原文、中文译文，每句可单独回放那几秒语音，也可整段回放 |
| 历史 | 所有历史会话，点进去看全文 |
| 难词 | 标记过的词：音标、释义、所在句、前两句、后两句、复习进度 |
| 复习 | 今天到期的卡片，四档打分 |

**语音是默认保留的**（约 1.9 MB/分钟）。想省空间：

- 单条：记录右上角垃圾桶 → 弹层**默认就是「只删语音」**，文字全部保留；
- 全部：复习页顶部「语音占用 X MB」→ 清理，一次清掉所有语音，文字不动。
- **文字永不自动删除**，只有选「删除整条记录」并再确认一次才会消失。

### 3. 点词标难词

记录里任意一句都可点：**点到哪个词就取哪个词**（不是按空格切，标点/缩写/连字符都能取整词）。

弹层显示：单词、音标、中文释义、发音按钮（系统 TTS，离线英文音库）。

点「标记为难词」时自动存三块语境：**该句 + 前两句 + 后两句**，之后复习卡片就按这三块给你回忆线索。

### 4. 遗忘曲线复习

新词 10 分钟后首次进队列；答对依次 10 分钟 → 1 天 → 按难度系数递增（1.3~2.8 倍）；答错回到 10 分钟并降低系数；「秒懂」×1.2、「模糊」×0.8；连对 5 次且间隔 ≥60 天 → 标记「已掌握」。

想被提醒：复习页打开「每日复习提醒」，每天 20:30 发一条通知，点通知直达记录本。

### 5. 离线词典

内置 4 万常用词（ECDICT 裁剪，约 2.4 MB），按首字母分片加载，支持 `studies→study`、`running→run`、`bigger→big` 这类词形还原。查不到的词会直接提示「词典未收录」，仍可标记为难词。

---

## 四、数据放在哪

```
/sdcard/Android/data/io.github.ztfang.eye/files/records/
└── 20261121-205316/          # 一次会话
    ├── session.json          # 文本快照：起止时间、语种、逐句原文+译文+时间轴
    └── audio.wav             # 该次录音（16k/16bit/单声道）
```

导出到电脑：

```bash
adb pull /sdcard/Android/data/io.github.ztfang.eye/files/records
```

数据库 `eye.db`（Room，version 3）只做索引：`tb_session` / `tb_line` / `tb_vocab`，老版本 `tb_history` 数据在升级时保留。

---

## 五、常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| 打开开关马上又关了，日志 `MODEL_NOT_DOWNLOADED` | 没下识别模型，去「模型下载」下中英文 |
| 弹「离线翻译模型下载失败…本机缺少 Google Play 服务」 | 国产机没有 GMS，把翻译引擎改成云端翻译或 AI 翻译 |
| 字幕没字/很慢 | 说话短句、停顿明显时识别更准；长句会被 VAD 切；先确认麦克风权限 |
| 复习提醒不响 | 通知权限 + 电池优化白名单；小米要允许「自启动」 |
| 装不上（`INSTALL_FAILED_USER_RESTRICTED`） | 见上文「小米 / Redmi 装不上怎么办」 |
| 录完发现没语音 | 检查是否在记录里删过语音；语音默认是保留的，不会再自动删 |
