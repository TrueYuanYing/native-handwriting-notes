# 原生手写笔记与分屏接入

这里运行的是真实 Android `InkView`，保留生产笔迹、缩放、套索/擦除、每页撤销、视口缓存和版本化文档算法。不是另写 Flutter 画布。构建目标为 `dev.iko.notes.example`，与原应用分离；没有原 APK、签名、用户笔记或 Office 二进制。

## 独立运行

需要 JDK17、Android SDK36、Gradle8.13。用 Android Studio 打开本仓库，配置自己的 SDK；或设置 `JAVA_HOME`、`ANDROID_HOME` 后运行：

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
# Windows 使用 gradlew.bat
```

构建输出 `app/build/outputs/apk/debug/app-debug.apk` 仅供自行安装。本次没有安装或操作任何设备。`ExampleActivity` 提供笔、橡皮、撤销/重做与保存；为方便无手写笔试用，示例打开手指落墨，产品默认策略以宿主为准。自动保存写入示例应用私有目录。

示例重开会读取最近保存的文档；载入成功前禁落墨与保存，读失败不创建空白替代本。共享串行I/O队列跨Activity重建保持保存/读取顺序，空闲15秒释放工作线程。这个恢复边界有两项JVM回归；Activity旋转/进程死亡仍需在自己的设备补测。

同一APK还可用 `adb shell am start -n dev.iko.notes.example/app.iko.notes.ExampleActivity --ez split true` 打开原生宿主嵌入布局：左侧合成课堂文字、右侧真实InkView。它用于验证原生View嵌入，不启动ASR/录音；Flutter实际分屏接入见下方教程。

## 源码范围

`app/src/main/java/app/iko/notes` 的28个真实核心文件和一个最小 Activity 构成独立可构建闭包。NoteDocument schema3 保存笔迹、纸页与元素；DocumentStore 使用 AtomicFile，I/O应在串行后台线程执行，UI线程先取 snapshotDocument。InkView 的 detach 释放缓存。

`reference/shared/notes` 保留完整 Java 模块源码以说明书库、回收站、格式导入/导出、资源包、文档渲染和宿主协议；它还依赖 integration、PDFBox/Office/资源等，不参加本示例构建。缺失的资源与转换器不被包装为示例已支持。原独立0.4 README不是当前集成版状态。

[宿主嵌入、听写分屏与录音归属](docs/INTEGRATION.md) · [验证](VALIDATION.md) · [第三方许可](THIRD-PARTY.md)

固定纸面与高倍视口缓存有内存上限；局部擦除是沿曲线的有界中心线裁剪，非任意像素蒙版；PDF背景导出有栅格化限制。原生事件回放/JVM测试不能证明设备笔尖延迟、任意厂商手写笔兼容或长时性能。
