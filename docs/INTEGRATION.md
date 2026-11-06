# 嵌入宿主、可见时长与录音归属

## 最小原生嵌入

复用本仓库 app 中的核心包，在 Activity/Fragment 中挂载真实 View：

```java
InkView ink = new InkView(context);
ink.setDocument(NoteDocument.create("合成笔记"));
ink.setFingerDrawing(false); // 产品按实际手写笔策略选择
container.addView(ink, new FrameLayout.LayoutParams(-1, -1));
ink.setTool("pen");
// UI线程取快照；后台串行保存。
NoteDocument snapshot = ink.snapshotDocument();
io.execute(() -> { try { store.save(snapshot); } catch (IOException e) { /* 提示并保留快照 */ } });
```

不把正在绘制的对象交给后台直接改；切本/切页先结束手势、取快照、处理保存失败。onPause 保存并暂停外部录音；容器移除触发 InkView detach 缓存释放。示例 Activity 可直接运行上述同一个 View，嵌入时只替换容器与工具栏。

## Flutter 集成的实际实现

`NotesScreen/NotesPane → NotesCoordinator → com.iko.study/notes → NotesBridge/NotesWorkspaceHost → NotesWorkspace`。Android 把保留的原生子树放在 Flutter 上方的物理像素矩形中；笔迹绘制与输入不跨 Dart bridge。布局变化传递正确像素边界，隐藏时停止显示与访问区间，不把0大小容器当真实可见。

参考代码在 `reference/flutter`、`reference/android`。完整接入需要转发 resume/pause/stop/configurationChanged/activityResult/saveState；releaseWorkspace 先检查忙状态并 flush，成功再 dispose。主题由宿主跟随，集成版隐藏独立应用主题入口，纸张/墨水设置保留。

## 听写分屏与停留时长

Coordinator 只在笔记真实可见且有活动课堂时计时，使用单调时钟，15秒checkpoint，保存为课堂关联访问元数据。切页、换课堂、关闭和dispose结束当前区间，取消timer/subscription与channel handler。不能用“路由存在”代替可见停留。

独立笔记录音走 `live.startRecordingOnly`，不初始化ASR，noteOwner记录 noteId/pageId。听写已运行时复用同一个音频采集，录音归课堂，不给每本笔记复制录音或重复开麦。独立录音暂停后换本才更新归属。

录音编码/清晰度由同一输入链、设备麦克风和编解码器决定，分屏不等于提高收音质量。全模块的真实关联测试在原工程 `notes_audio_ownership_test`、`notes_recording_session_test`、`notes_recording_persistence_test`、`notes_coordinator_test`。本次独立墨水示例不包含听写引擎或录音权限，需在自己的完整宿主另外补测。

## 完整导入导出扩展

书库/回收站、PDF/PNG/.ikonote、外部笔记格式与资源包的真实实现已放 reference，不能仅复制 MainActivity。应连同 integration 接口、已核许可的 PDFBox Android 依赖、受控资源下载、文件大小/路径限制和对应许可证接入。Office/MPL运行库、字体与第三方模板未随此仓库分发。来源不明的资源先保留配置接口，不将其改成MIT。
