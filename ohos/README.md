# MaterialKolor 5.0.0 OHOS 独立入口

本入口直接编译同一 Fork 中 `material-kolor/src/commonMain/kotlin` 的 23 份原源和 `material-color-utilities/src/commonMain/kotlin` 的 42 份原源，不复制或改写生产源码。上游 [jordond/MaterialKolor 5.0.0](https://github.com/jordond/MaterialKolor/commit/13c67cfa5a724cb6dca7d15674b3ad2acf894853) 的两模块在同一次 producer 中使用同一版本；`material-kolor` 以 `api(project(":material-color-utilities"))` 保留模块关系。

工具链为 Kotlin/KGP/Native `2.4.20-0.1.0-08`、Compose 和 Material3 `1.12.0-0.1.0-11`、Gradle 9.5.0、JDK 17。两个模块的 common metadata 沿用同版本公共 metadata CLI，OHOS x64 和 arm64 使用标准 KGP Native。MCI 使用 Poko compiler/Gradle 插件 0.23.2，并通过公开 Pages 坐标消费 0.23.1 annotations；构建移除插件自动加入的原 0.23.2 annotations 边。主库从公开 Pages 使用事先完成独立公开消费验收的 Colormath GAV；不回退上游非 OHOS 坐标。共享工具内容锁 A 为 `d0cff88fd1ea2e4f08bb4409f1c802cca0c7f285`，与运行工作流的工具仓提交分开记录。

两模块根坐标为 `io.github.qsxs.cpf.materialkolor:material-color-utilities` 和 `io.github.qsxs.cpf.materialkolor:material-kolor`，版本格式为 `5.0.0-cpf11-preview-<sourceSHA12>-<runID>-<attempt>`；各自 GMM 选择双 Native leaf。producer 在隔离工作区依次发现任务、解析依赖、编译两个模块的 metadata 和双 Native、发布到 staging 并检查六个版本目录、POM/GMM、校验、源码和许可证。已存在的版本目录会停止写入。工作流仅上传验收通过的 staging；Pages 必须另行受控审核和发布。

## 外部依赖与公开消费者

调用手动 producer 工作流时，必须显式输入 `materialkolor_sha` 以及分别对应 Poko 和 Colormath 的 `version`、`source_sha`、`root_gmm_sha256`。两项依赖须先由主控完成独立公开消费验收；本配方不根据单个哈希推断历史 consumer 状态。producer 匿名核对对应公开 GMM、root/双 leaf 和实际制品字节，将具体版本、来源提交和 root GMM 哈希写入本次依赖锁与配方指纹。不得使用 latest、SNAPSHOT、本地 Maven、includeBuild 或其他 Fork 的源码。

在完整版本图受控发布到 `https://qsxs.github.io/cpf-maven/` 后，手动 consumer 工作流输入发布版本 `version` 和对应完整 `source_sha`。独立消费者只声明 `io.github.qsxs.cpf.materialkolor:material-kolor:<version>`，通过公开 GMM 解析同版 MCI、Poko 和 Colormath；它编译应用实际使用的 `rememberDynamicColorScheme`、`PaletteStyle.Content` 和 `ColorSpec.SpecVersion.SPEC_2025` 调用，并追踪 metadata 与双 ABI 的实际输入。它在公开 MCI 编译产物中检查六个 Poko 类的自有 `equals`、`hashCode`、`toString` metadata 声明及双 ABI 的 IR 签名和非空 IR。大证据按需无损 gzip，索引保留压缩前后大小和 SHA-256，不替代构建/发布验证。

## 原始许可事实

上游仓库根 `LICENSE` 为 MIT；MCI sources JAR 内 `commonMain/com/materialkolor/LICENSE` 是 Apache-2.0 原文。MCI 上游 POM 声明 MIT，而旧 OHOS 端口的 MCI POM 配置为 Apache-2.0；本入口沿用旧端口的 POM 配置，同时保留上述根 LICENSE、JAR 内 LICENSE、原 POM/GMM 与各自大小、SHA-256 和来源。以上仅记录不同原件和声明，不推断双许可证；未补造 NOTICE。

本目录为待独立审查和远端验收的配方。当前不能据此宣称新坐标已生产或发布、consumer 已通过、方法体已验证，亦未验证 link、runtime、设备或 Android/iOS 回归。App 引用尚未切换。
