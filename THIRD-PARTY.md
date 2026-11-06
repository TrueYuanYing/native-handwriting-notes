# 第三方来源与发布边界

根 LICENSE 只覆盖本人有权许可的代码与本次原创适配/文档；第三方实现、许可证文字、商标与资源不因此改为MIT。没有把“可以查看的网页/目录”当成可以统一再许可的素材库。依赖安装时以锁文件和包内许可为准。

28文件独立墨水闭包为本项目Java实现，不包含竞品APK或付费纸页。Gradle Wrapper为Apache-2.0工具，全文与NOTICE在GRADLE-LICENSE/GRADLE-NOTICE。JVM测试使用JUnit4（EPL1.0）与org.json（具体版本随构建依赖许可），运行时使用Android系统API。

reference/licenses保留PDFBox Apache2/NOTICE、inkterop-MIT（Caleb/cable729）、goodnotes-parser-MIT、flexcil-viewer-MIT。NotebookWire等研究/派生实现按相关许可说明保留归属，不能将其上游归属抹掉。完整Office模块还涉及LibreOffice/MPL2与其它第三方运行库，本仓库未分发该二进制、字体、模板、PDFBox AAR或资源包。参考Java源码展示调用接口，不表示这些运行库已经全部随附或能独立构建。

`reference/shared/notes/src/main/java/org/libreoffice/kit/` 为LibreOffice Java接口来源文件，按各文件头与MPL-2.0保留原许可，全文在reference/licenses/OFFICE-MPL-2.0.txt；根MIT不覆盖这些上游文件。未随附LibreOffice原生库或Office资源包。
