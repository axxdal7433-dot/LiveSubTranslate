# 灵译字幕 (LiveSubTranslate) — 安卓全局离线实时双语字幕插件

专为在手机上刷 **YouTube 原生客户端**、**TikTok 短视频**（无原生 CC 字幕 / 生肉直播）打造的 **100% 纯本地离线** 全局悬浮字幕 Android 应用。

参考并融合了 GitHub 开源项目 [R-Dilipkumar/LiveSub-Offline-Translator](https://github.com/R-Dilipkumar/LiveSub-Offline-Translator) 与 [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 的核心思路，针对刷短视频与长视频场景做了专门增强。

---

## 核心特性

1. **免 Root 系统内录 + 麦克风一键切换**
   - 默认使用 Android 10+ `AudioPlaybackCapture` 直接抓取手机内部媒体声音（**戴蓝牙/有线耳机也能正常识别，不受周围环境噪音干扰**）。
   - 若遇到个别限制内录的视频，无需退出视频 App，轻触悬浮窗顶栏即可一键切换为 `🎤 麦克风` 外放收音。
2. **双离线语音识别引擎（应用内一键下载，支持国内镜像加速）**
   - **英语极速流式模型 (`Zipformer 20M`，约 38MB)**：毫秒级边说边蹦词，超低功耗，最适合英文 YouTube 博主长视频、播客。
   - **多语种全能模型 (`SenseVoice Int8 + Silero VAD`，约 165MB)**：自动识别 **英语 / 日语 / 韩语 / 粤语 / 中文**，带说话过程实时预览 + 句尾精准定稿，最适合混刷多国视频的 **TikTok**。
3. **纯本地离线多语种翻译 (`Google ML Kit`)**
   - 支持应用内预下载 **英→中、日→中、韩→中** 离线词库（各约 30MB），下载完成后即使开启飞行模式也能秒出中文字幕，零 API 费用、零隐私上传。
4. **专为刷视频打磨的悬浮窗交互**
   - 支持手指任意拖拽位置（自动记忆高度）、横竖屏自适应宽度、双语/仅译文切换、字号与底框透明度实时调节。
   - **🔒 防误触穿透模式**：锁定时点击事件 100% 穿透给底下的 TikTok / YouTube（上下划视频、点赞、拉进度条完全不受遮挡），下拉手机通知栏可一键解锁或关闭。

---

## 如何获取 `.apk` 安装包（两种方式）

### 方式一：免装任何开发环境，用 GitHub Actions 云端免费自动打包（推荐，约 3 分钟）

即使电脑没有安装 Android Studio 或 Java，也可以让 GitHub 云服务器自动编译出手机可直接安装的 `.apk`：

1. 在 GitHub 新建一个仓库（公开或私有均可）。
2. 将当前文件夹 `d:\geminis\gemini3` 下的全部文件上传推送到仓库（包含隐藏目录 `.github/workflows/build-apk.yml`）。
   - 如果电脑安装了 Git：
     ```powershell
     cd d:\geminis\gemini3
     git init
     git add .
     git commit -m "feat: init LiveSubTranslate offline subtitle app"
     git branch -M main
     git remote add origin https://github.com/<你的用户名>/<你的仓库名>.git
     git push -u origin main
     ```
3. 打开你 GitHub 仓库顶部的 **Actions** 标签页，等待 `Build Android APK` 变成绿色勾（约 2–4 分钟，云端会自动下载最新 `sherpa-onnx` Android AAR 并完成签名打包）。
4. 点进该次构建任务，在页面底部的 **Artifacts** 区域点击下载 **`LiveSubTranslate-Android-APK`**，解压即得 `LiveSubTranslate-v1.0.0.apk`，发到安卓手机安装即可！

### 方式二：使用 Android Studio 本地编译

1. 用 **Android Studio (Jellyfish / Koala 或更新版本)** 直接打开 `d:\geminis\gemini3` 目录。
2. 等待 Gradle Sync 完成（`app/build.gradle.kts` 内置了 `downloadSherpaOnnxAar` 任务，会在构建前自动从 GitHub Releases / HuggingFace / 国内 `hf-mirror` 拉取 `app/libs/sherpa-onnx.aar`）。
3. 连接安卓手机（Android 10 及以上），点击 **Run ▶** 即可直接安装运行。

---

## 手机端首次使用 3 步指南

1. **开启权限**：打开「灵译字幕」，在卡片 1 点击开启 **悬浮窗权限** 与 **音频录制权限**。
2. **下载离线模型（仅首次需联网）**：
   - 保持「国内网络 GitHub 镜像加速」开启；
   - 主要看英文油管选 **英语极速流式模型**，常刷日韩英混杂 TikTok 选 **多语种全能模型**，点击下载；
   - 在卡片 3 点击下载 **英语→中文**（及日/韩按需）离线翻译词库。
3. **开始看视频**：点击顶部大按钮 **「启动全局双语悬浮字幕」** → 在系统弹窗中点「立即开始 / 开始录制」授权系统内录 → 直接切去打开 **YouTube** 或 **TikTok** 播放任何无字幕视频，双语字幕就会实时浮现在画面上了！
