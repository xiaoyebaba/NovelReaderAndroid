# 竹简阅读

一个原生 Android 小说阅读器 MVP，使用 Kotlin + Jetpack Compose 构建。

## 当前功能

- 本地书架
- 导入 `.txt` 小说
- 自动识别常见章节标题
- 阅读进度保存到章节和段落
- 字号、行距、纸色/青绿/夜间背景
- 支持 UTF-8、GB18030、GBK、UTF-16 文本解码
- 支持滚动、左右翻页和无动画翻页
- 支持 WebDAV 备份/恢复书架、阅读进度和 TXT 正文
- 支持从 GitHub Releases 检查新版 APK

## 运行方式

1. 用 Android Studio 打开 `NovelReaderAndroid` 文件夹。
2. 等待 Gradle 同步完成。
3. 连接安卓设备或启动模拟器。
4. 运行 `app`。

当前项目使用 Android Studio 内置 JDK 和 Gradle Wrapper 构建。

## GitHub 更新发布方式

1. 在 GitHub 仓库创建 Release，例如 `v0.4.1`。
2. 把生成的 APK 上传到 Release assets。
3. App 中填写 GitHub 用户名/组织名、仓库名，点击“检查更新”。
4. App 会读取最新 Release，并打开匹配到的 APK 下载链接。

## 下一步建议

- 加入 `.epub` 支持。
- 用 Room 替换当前的 SharedPreferences 元数据存储。
- 增加目录抽屉、书内搜索、阅读统计。
- 增加应用内下载进度和安装引导页。
