# MaterialKolor 5.0.0 OHOS 独立入口

本入口直接编译同一 Fork 中 `material-kolor/src/commonMain/kotlin` 的 23 份原源和 `material-color-utilities/src/commonMain/kotlin` 的 42 份原源，不复制或改写生产源码。上游 [jordond/MaterialKolor 5.0.0](https://github.com/jordond/MaterialKolor/commit/13c67cfa5a724cb6dca7d15674b3ad2acf894853) 的两模块在同一次 producer 中使用同一版本；`material-kolor` 以 `api(project(":material-color-utilities"))` 保留模块关系。

工具链为 Kotlin/KGP/Native `2.4.20-0.1.0-08`、Compose 和 Material3 `1.12.0-0.1.0-11`、Gradle 9.5.0、JDK 17。两个模块的 common metadata 沿用同版本公共 metadata CLI，OHOS x64 和 arm64 使用标准 KGP Native。MCI 使用 Poko compiler/Gradle 插件 0.23.2，并通过公开 Pages 坐标消费 0.23.1 annotations；构建移除插件自动加入的原 0.23.2 annotations 边。主库从公开 Pages 使用事先完成独立公开消费验收的 Colormath GAV；不回退上游非 OHOS 坐标。共享工具内容锁 A 为 `d0cff88fd1ea2e4f08bb4409f1c802cca0c7f285`，与运行工作流的工具仓提交分开记录。

两模块根坐标为 `io.github.qsxs.cpf.materialkolor:material-color-utilities` 和 `io.github.qsxs.cpf.materialkolor:material-kolor`，版本格式为 `5.0.0-cpf11-preview-<sourceSHA12>-<runID>-<attempt>`；各自 GMM 选择双 Native leaf。producer 在隔离工作区依次发现任务、解析依赖、编译两个模块的 metadata 和双 Native、发布到 staging 并检查六个版本目录、POM/GMM、校验、源码和许可证。已存在的版本目录会停止写入。工作流在成功时上传验收通过的 staging，另上传证据；Pages 由维护者核对后手动发布。

## 外部依赖与公开消费者

调用手动 producer 工作流时，必须显式输入 `materialkolor_sha` 以及分别对应 Poko 和 Colormath 的 `version`、`source_sha`、`root_gmm_sha256`。两项依赖须先由主控完成独立公开消费验收；本配方不根据单个哈希推断历史 consumer 状态。producer 匿名核对对应公开 GMM、root/双 leaf 和实际制品字节，将具体版本、来源提交和 root GMM 哈希写入本次依赖锁与配方指纹。不得使用 latest、SNAPSHOT、本地 Maven、includeBuild 或其他 Fork 的源码。

在完整版本图受控发布到 `https://qsxs.github.io/cpf-maven/` 后，手动 consumer 工作流输入发布版本 `version` 和对应完整 `source_sha`。独立消费者只声明 `io.github.qsxs.cpf.materialkolor:material-kolor:<version>`，通过公开 GMM 解析同版 MCI、Poko 和 Colormath；它编译应用实际使用的 `rememberDynamicColorScheme`、`PaletteStyle.Content` 和 `ColorSpec.SpecVersion.SPEC_2025` 调用，并追踪 metadata 与双 ABI 的实际输入。它在公开 MCI 编译产物中检查六个 Poko 类的自有 `equals`、`hashCode`、`toString` metadata 声明及双 ABI 的 IR 签名和非空 IR。大证据按需无损 gzip，索引保留压缩前后大小和 SHA-256，不替代构建/发布验证。

## 原始许可事实

上游仓库根 `LICENSE` 和主模块发布 POM 均为 MIT；MCI sources JAR 内 `commonMain/com/materialkolor/LICENSE` 是 Apache-2.0 原文。MCI 上游 POM 声明 MIT，而现 CPF MCI POM 沿用旧 OHOS 端口配置声明 Apache-2.0；本入口保留该 POM 配置，同时保留上述根 LICENSE、JAR 内 LICENSE、原 POM/GMM 与各自大小、SHA-256 和来源。以上仅记录不同原件和声明，不推断双许可证；未补造 NOTICE。

## 已验版本与公开消费

[Fork source `c5ef77766b6098677d541e82f362b666a8a0fff2`](https://github.com/qsxs/MaterialKolor/commit/c5ef77766b6098677d541e82f362b666a8a0fff2) 经 [producer 40672306467/1](https://github.com/qsxs/cpf-port-tooling/actions/runs/40672306467/attempts/1) 完成生产和 staging 验证；生产工作流提交为 `b606affac816befd3b33269e57d9b79b89c31b0f`。两模块版本均为 `5.0.0-cpf11-preview-c5ef77766b60-40672306467-1`：

- `io.github.qsxs.cpf.materialkolor:material-kolor:5.0.0-cpf11-preview-c5ef77766b60-40672306467-1`（[根 POM](https://qsxs.github.io/cpf-maven/io/github/qsxs/cpf/materialkolor/material-kolor/5.0.0-cpf11-preview-c5ef77766b60-40672306467-1/material-kolor-5.0.0-cpf11-preview-c5ef77766b60-40672306467-1.pom)）
- `io.github.qsxs.cpf.materialkolor:material-color-utilities:5.0.0-cpf11-preview-c5ef77766b60-40672306467-1`（[根 POM](https://qsxs.github.io/cpf-maven/io/github/qsxs/cpf/materialkolor/material-color-utilities/5.0.0-cpf11-preview-c5ef77766b60-40672306467-1/material-color-utilities-5.0.0-cpf11-preview-c5ef77766b60-40672306467-1.pom)）

临时 Maven 根 `https://qsxs.github.io/cpf-maven/`（[GitHub 制品仓库](https://github.com/qsxs/cpf-maven)）。[Pages 制品提交 `b2652094e033`](https://github.com/qsxs/cpf-maven/commit/b2652094e03379619138c7ec83ff614d5863a2a1) 的 280 个文件、1,994,693 字节与 staging 经匿名 HTTP 逐字节对齐。

[consumer 40675388144/1](https://github.com/qsxs/cpf-port-tooling/actions/runs/40675388144/attempts/1) 使用工作流提交 `abadbfb0b5c33b35dfafdb966e8e759276c5de45` 和 Gradle 9.5.0，只直接声明 `material-kolor` 根，传递解析 MCI、Poko 与 Colormath；真实主题 API 探针通过 common metadata、OHOS x64 和 arm64 编译。39 条公开 HTTP 记录（38 个不同 URL）与各自 staging 记录对齐。Producer 和 consumer 各检查 54 项：6 个 MCI 类的 `equals`、`hashCode`、`toString` 在三个目标的 metadata 声明与签名，双 Native 另检查 IR 签名及非空 IR。

未检查生成方法体；只验证 Gradle 9.5.0 消费，Gradle 8.11.1 消费、link、runtime、设备和 Android/iOS 回归均未运行。App 引用尚未切换。
