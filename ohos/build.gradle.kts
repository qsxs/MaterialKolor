import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    kotlin("multiplatform") apply false
    kotlin("plugin.compose") apply false
    id("dev.drewhamilton.poko") apply false
}

fun readMap(path: File) = (JsonSlurper().parse(path) as Map<*, *>).entries.associate { it.key.toString() to it.value }
fun asMap(value: Any?) = (value as Map<*, *>).entries.associate { it.key.toString() to requireNotNull(it.value) }
fun sha(path: File) = MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString("") { "%02x".format(it) }
fun leaves(path: File): List<File> = if (path.isDirectory) path.walkTopDown().filter(File::isFile).toList() else listOf(path)
fun records(files: Iterable<File>) = files.filter(File::isFile).distinct().sortedBy { it.path }.map {
    mapOf("path" to it.absolutePath, "size" to it.length(), "sha256" to sha(it))
}
fun shaBytes(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun klibPayload(file: File): Map<String, Map<String, Any>> {
    val found = mutableMapOf<String, Map<String, Any>>()
    fun add(name: String, bytes: ByteArray) {
        val normalized = name.removePrefix("commonMain/")
        if (normalized.startsWith("default/")) found[normalized] = mapOf("size" to bytes.size.toLong(), "sha256" to shaBytes(bytes))
    }
    if (file.isDirectory) leaves(file).filter(File::isFile).forEach { add(it.relativeTo(file).invariantSeparatorsPath, it.readBytes()) }
    else if (file.isFile && file.extension in listOf("jar", "klib")) ZipFile(file).use { zip ->
        zip.entries().asSequence().filter { !it.isDirectory }.forEach { add(it.name, zip.getInputStream(it).use { stream -> stream.readBytes() }) }
    }
    return found
}
val config = readMap(file("port-config.json"))
val modules = asMap(config.getValue("modules"))
val pins = readMap(file("dependency-pins.json"))
val metadataPins = readMap(file("metadata-pins.json"))
val toolRoot = file(providers.gradleProperty("portTooling").get()).resolve("toolchains/kotlin-native/2.4.20-0.1.0-08")
val metadataTools = readMap(toolRoot.resolve("metadata-toolchain.json"))
val commonizerTools = readMap(toolRoot.resolve("commonizer-toolchain.json"))
val nativeTools = readMap(toolRoot.resolve("native-compiler-toolchain.json"))
val composeTools = readMap(file("compiler-plugin-toolchain.json"))
val dependencyLock = readMap(file(providers.gradleProperty("portDependencyLock").get()))
val external = asMap(dependencyLock.getValue("dependencies"))
val poko = asMap(external.getValue("poko"))
val colormath = asMap(external.getValue("colormath"))
val portVersion = providers.gradleProperty("portVersion").get()
val portRepository = providers.gradleProperty("portRepository").get()
val launcherPins = asMap(metadataTools.getValue("launcherPins"))
val launcherArtifacts = asMap(metadataTools.getValue("launcherArtifacts"))
val fingerprint = providers.gradleProperty("portFingerprint").get()
val localProjectGavs = modules.map { (module, value) ->
    val model = asMap(value)
    "${model.getValue("group")}:$module:$portVersion"
}.toSet()
val work = file(providers.gradleProperty("portWork").get()).canonicalFile
check(readMap(work.resolve("source-manifest.json"))["fingerprint"] == fingerprint)
layout.buildDirectory.set(work.resolve("build/root"))
fun writeEvidence(name: String, value: Any) {
    val target = work.resolve("evidence/$name.json")
    target.parentFile.mkdirs()
    target.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n")
}
fun metadataLauncherRecords(configuration: Configuration): List<Map<String, Any>> {
    val artifacts = configuration.resolvedConfiguration.resolvedArtifacts.map { artifact ->
        val id = artifact.moduleVersion.id
        val ga = "${id.group}:${id.name}"
        check(launcherPins[ga] == id.version) { "Unpinned metadata launcher: $id" }
        val expected = asMap(launcherArtifacts.getValue(ga))
        val hash = sha(artifact.file)
        check(hash == expected.getValue("sha256") && artifact.file.length() == (expected.getValue("size") as Number).toLong()) {
            "Metadata launcher bytes differ: $id"
        }
        mapOf("gav" to id.toString(), "path" to artifact.file.absolutePath, "sha256" to hash, "size" to artifact.file.length())
    }
    check(artifacts.map { it.getValue("gav") }.toSet() == launcherPins.map { "${it.key}:${it.value}" }.toSet()) {
        "Metadata launcher artifact set differs"
    }
    return artifacts.sortedBy { it.getValue("gav").toString() }
}
fun publicDependencyTrace(compiler: KotlinNativeCompile, module: String, stage: String): Map<String, Any> {
    val dependency = if (module == "material-color-utilities") "poko" else "colormath"
    val item = if (dependency == "poko") poko else colormath
    val target = when (stage) { "metadata" -> "metadata"; "native-x64" -> "ohos_x64"; else -> "ohos_arm64" }
    val artifact = asMap(asMap(item.getValue("artifacts")).getValue(target))
    val expected = asMap(artifact.getValue("payload")).mapValues { (_, value) ->
        val row = asMap(value)
        mapOf("size" to (row.getValue("size") as Number).toLong(), "sha256" to row.getValue("sha256"))
    }
    val matches = compiler.libraries.files.filter { klibPayload(it) == expected }
    check(matches.size == 1) { "Compiler dependency cannot be traced to public $dependency for $stage" }
    return mapOf("dependency" to dependency, "target" to target, "compilerLibrary" to matches.single().absolutePath,
        "artifactSha256" to artifact.getValue("sha256"), "payloadFileCount" to expected.size)
}
subprojects {
    val module = name
    val model = asMap(modules.getValue(module))
    group = model.getValue("group").toString()
    version = portVersion
    layout.buildDirectory.set(work.resolve("build/$module"))
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            explicitApi()
            ohosX64()
            ohosArm64()
            sourceSets {
                val commonMain by getting {
                    // 原 JAR 布局保持完整，不裁剪、改写或删除任何上游声明。
                    kotlin.setSrcDirs(listOf(rootDir.resolve("../$module/src/commonMain/kotlin")))
                    dependencies {
                        if (module == "material-color-utilities") implementation("${poko.getValue("group")}:${poko.getValue("module")}:${poko.getValue("version")}")
                        if (module == "material-kolor") {
                            api(project(":material-color-utilities"))
                            implementation("${colormath.getValue("group")}:${colormath.getValue("module")}:${colormath.getValue("version")}")
                            listOf("org.jetbrains.compose.runtime:runtime", "org.jetbrains.compose.foundation:foundation",
                                "org.jetbrains.compose.ui:ui", "org.jetbrains.compose.material3:material3").forEach {
                                implementation("$it:${pins.getValue(it)}")
                            }
                        }
                    }
                }
                listOf("ohosX64Main", "ohosArm64Main").forEach {
                    getByName(it).dependsOn(commonMain)
                    getByName(it).kotlin.setSrcDirs(emptyList<File>())
                }
                matching { it.name.endsWith("Test") }.configureEach { kotlin.setSrcDirs(emptyList<File>()) }
            }
        }
    }
    if (module == "material-color-utilities") plugins.withId("dev.drewhamilton.poko") {
        // 公开 Gradle DependencySet 在消费和发布前移除插件准确的自动边；marker 仍为原默认值。
        configurations.named("commonMainImplementation") {
            withDependencies {
                val injected = filterIsInstance<ExternalModuleDependency>().filter {
                    it.group == "dev.drewhamilton.poko" && it.name == "poko-annotations" && it.version == "0.23.2"
                }
                removeAll(injected.toSet())
            }
        }
    }
    configurations.configureEach {
        if (name == nativeTools["configuration"] || name == commonizerTools["configuration"]) {
            val fixed = if (name == nativeTools["configuration"]) asMap(nativeTools.getValue("pins")) else launcherPins + asMap(commonizerTools.getValue("pins"))
            resolutionStrategy.eachDependency {
                val ga = "${requested.group}:${requested.name}"
                val version = fixed[ga]?.toString() ?: error("Unpinned compiler host: $ga")
                if (requested.version != version) useVersion(version)
            }
        }
        val metadata = name.endsWith("ResolvableDependenciesMetadata") || name == "allSourceSetsCompileDependenciesMetadata"
        val runtime = metadata || name.endsWith("CompileKlibraries") || name.endsWith("CompilationDependenciesMetadata")
        if (runtime && !name.contains("Test", ignoreCase = true)) resolutionStrategy.eachDependency {
            val ga = "${requested.group}:${requested.name}"
            val publicVersion = when (ga) {
                "io.github.qsxs.cpf.poko:poko-annotations", "io.github.qsxs.cpf.poko:poko-annotations-ohosx64", "io.github.qsxs.cpf.poko:poko-annotations-ohosarm64" -> poko.getValue("version").toString()
                "io.github.qsxs.cpf.colormath:colormath", "io.github.qsxs.cpf.colormath:colormath-ohosx64", "io.github.qsxs.cpf.colormath:colormath-ohosarm64" -> colormath.getValue("version").toString()
                else -> null
            }
            if (publicVersion != null) {
                check(requested.version == publicVersion) { "Public dependency version differs: $requested" }
            } else if ("$ga:${requested.version}" !in localProjectGavs) {
                check(!(requested.group == "dev.drewhamilton.poko" && requested.name.startsWith("poko-annotations")) && requested.group != "com.github.ajalt.colormath") { "Unexpected external Poko annotations: $requested" }
                val fixed = (if (metadata) metadataPins[ga] ?: pins[ga] else pins[ga])?.toString()
                    ?: error("Unpinned MaterialKolor runtime dependency: $ga:${requested.version}")
                if (requested.version != fixed) useVersion(fixed)
            }
        }
    }
    fun checkedHost(name: String, pins: Map<String, Any>, expected: Map<String, Any>): Map<String, Any> {
        val configuration = configurations.getByName(name)
        val artifacts = configuration.resolvedConfiguration.resolvedArtifacts.map { artifact ->
            val id = artifact.moduleVersion.id
            val ga = "${id.group}:${id.name}"
            val bytes = asMap(expected.getValue(ga))
            check(pins[ga] == id.version && sha(artifact.file) == bytes["sha256"] && artifact.file.length() == (bytes.getValue("size") as Number).toLong())
            mapOf("gav" to id.toString(), "file" to records(listOf(artifact.file)))
        }
        check(artifacts.map { it.getValue("gav") }.toSet() == pins.map { "${it.key}:${it.value}" }.toSet())
        return mapOf("configuration" to name, "artifacts" to artifacts)
    }
    val verifyHost = tasks.register("verifyPortHost") {
        outputs.upToDateWhen { false }
        doLast {
            val commonPins = launcherPins + asMap(commonizerTools.getValue("pins"))
            val commonArtifacts = launcherArtifacts + asMap(commonizerTools.getValue("artifacts"))
            writeEvidence("host-$module", mapOf("fingerprint" to fingerprint,
                "commonizer" to checkedHost(commonizerTools.getValue("configuration").toString(), commonPins, commonArtifacts),
                "native" to checkedHost(nativeTools.getValue("configuration").toString(), asMap(nativeTools.getValue("pins")), asMap(nativeTools.getValue("artifacts")))))
        }
    }
    tasks.withType<KotlinNativeCompile>().configureEach {
        if (name in listOf("compileCommonMainKotlinMetadata", "compileKotlinOhosX64", "compileKotlinOhosArm64")) dependsOn(verifyHost)
    }
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories { maven { name = "Port"; url = uri(portRepository) } }
            publications.withType<MavenPublication>().configureEach {
                artifact(rootProject.file("legal/$module/LICENSE")) { classifier = "license"; extension = "txt" }
                artifact(rootProject.file("legal/upstream-root/LICENSE")) { classifier = "upstream-root-license"; extension = "txt" }
                artifact(work.resolve("provenance-$module.json")) { classifier = "provenance"; extension = "json" }
                if (name == "kotlinMultiplatform") {
                    val original = asMap(asMap(readMap(rootProject.file("source-inputs.json")).getValue("modules")).getValue(module))
                    listOf("sources" to ("upstream-sources" to "jar"), "pom" to ("upstream-pom" to "xml"),
                        "gmm" to ("upstream-module" to "json")).forEach { (key, format) ->
                        artifact(rootProject.file(asMap(original.getValue(key)).getValue("path"))) { classifier = format.first; extension = format.second }
                    }
                }
                pom {
                    name.set(module)
                    description.set("Preserved ${model.getValue("upstreamVersion")} sources; private CPF OHOS dual ABI port")
                    licenses { license { name.set(model.getValue("license").toString()); url.set(model.getValue("licenseUrl").toString()) } }
                    scm { url.set(model.getValue("repository").toString()) }
                }
            }
        }
    }
    tasks.withType<PublishToMavenRepository>().configureEach {
        doFirst {
            val legal = rootProject.file("legal/$module/LICENSE")
            check(sha(legal) == model.getValue("licenseSha256")) { "Missing or changed upstream license: $module" }
            check(readMap(work.resolve("provenance-$module.json"))["fingerprint"] == fingerprint)
            listOf("metadata", "native-x64", "native-arm64").forEach { stage ->
                check(readMap(work.resolve("evidence/$stage.json"))["fingerprint"] == fingerprint) { "Required producer has not run: $stage" }
            }
        }
    }
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(rootProject.file("legal/$module")) { into("META-INF/licenses/$module") }
        from(rootProject.file("legal/upstream-root/LICENSE")) { into("META-INF/licenses/upstream-root") }
        from(work.resolve("provenance-$module.json")) { into("META-INF/upstream") }
    }
}

// 真实 OptionalExpectation 失败后，仅四个 common producer 使用同版公共 metadata 入口。
gradle.projectsEvaluated {
    check(metadataTools["compilerVersion"] == config["kotlin"])
    subprojects.forEach { child ->
        val kmp = child.extensions.getByType<KotlinMultiplatformExtension>()
        val compilation = kmp.targets.getByName("metadata").compilations.getByName("commonMain")
        val original = child.tasks.named<KotlinNativeCompile>("compileCommonMainKotlinMetadata").get()
        child.tasks.getByName("allMetadataJar")
        val originalDependencies = child.provider { original.taskDependencies.getDependencies(original).toList() }
        val launcher = child.configurations.create("commonMetadataCliLauncher") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isVisible = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, child.objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, child.objects.named(Category.LIBRARY))
            }
            resolutionStrategy.eachDependency {
                val ga = "${requested.group}:${requested.name}"
                val fixed = launcherPins[ga]?.toString() ?: error("Unreviewed metadata launcher dependency: $ga")
                if (requested.version != fixed) useVersion(fixed)
            }
        }
        child.dependencies.add(launcher.name, "org.jetbrains.kotlin:kotlin-compiler-embeddable:${metadataTools.getValue("compilerVersion")}")
        val output = child.layout.buildDirectory.dir("metadata-cli/commonMain/klib")
        val argumentsFile = child.layout.buildDirectory.file("metadata-cli/commonMain/arguments.txt")
        val producerFile = child.layout.buildDirectory.file("metadata-cli/commonMain/producer.json")
        val libraries = child.files(child.provider { original.libraries })
        val plugins = child.files(child.provider { original.compilerPluginClasspath })
        val sourceFiles = child.files(child.provider { original.sources })
        val compilerArguments = child.provider {
            val options = original.compilerOptions
            buildList {
                addAll(listOf("-classpath", libraries.files.sortedBy { it.path }.joinToString(File.pathSeparator) { it.invariantSeparatorsPath },
                    "-d", output.get().asFile.invariantSeparatorsPath, "-module-name", "${child.group}:${child.name}_commonMain",
                    "-Xtarget-platform=${metadataTools.getValue("targetPlatform")}", "-Xmulti-platform"))
                options.languageVersion.orNull?.let { addAll(listOf("-language-version", it.version)) }
                options.apiVersion.orNull?.let { addAll(listOf("-api-version", it.version)) }
                options.optIn.get().sorted().forEach { add("-opt-in=$it") }
                val freeArguments = options.freeCompilerArgs.get()
                addAll(freeArguments)
                if (freeArguments.none { it.startsWith("-Xexplicit-api=") }) {
                    when (kmp.explicitApi) {
                        ExplicitApiMode.Strict -> add("-Xexplicit-api=strict")
                        ExplicitApiMode.Warning -> add("-Xexplicit-api=warning")
                        else -> Unit
                    }
                }
                if (!plugins.isEmpty) add("-Xplugin=" + plugins.files.sortedBy { it.path }.joinToString(",") { it.invariantSeparatorsPath })
                original.compilerPluginCommandLine.forEach { addAll(listOf("-P", it)) }
                addAll(sourceFiles.files.sortedBy { it.path }.map { it.invariantSeparatorsPath })
            }
        }
        val cli = child.tasks.register<JavaExec>("compileCommonMainWithMetadataCli") {
            group = "build"
            description = "使用同版本公共 metadata 编译器编译未改写的 commonMain。"
            dependsOn(originalDependencies)
            dependsOn("transformCommonMainDependenciesMetadata")
            dependsOn("verifyPortHost")
            classpath = launcher
            mainClass.set(metadataTools.getValue("mainClass").toString())
            setExecutable(File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").absolutePath)
            inputs.files(sourceFiles, libraries, plugins, launcher)
            inputs.file(work.resolve("source-manifest.json"))
            inputs.file(toolRoot.resolve("metadata-toolchain.json"))
            inputs.property("compilerArguments", compilerArguments)
            outputs.dir(output)
            outputs.file(argumentsFile)
            outputs.file(producerFile)
            args("@" + argumentsFile.get().asFile.absolutePath)
            var actualLauncher = emptyList<Map<String, Any>>()
            doFirst {
                val binding = compilation.output.classesDirs
                check(binding.files == setOf(output.get().asFile) &&
                    binding.buildDependencies.getDependencies(this).map { it.path }.toSet() == setOf(path) && !original.enabled) {
                    "Common metadata output/builtBy was not replaced exclusively: ${child.path}"
                }
                val expected = (readMap(work.resolve("source-manifest.json")).getValue("members") as List<*>)
                    .map(::asMap).filter { it["module"] == child.name && it.getValue("member").toString().startsWith("commonMain/") &&
                        it.getValue("member").toString().endsWith(".kt") }
                    .associate { file(it.getValue("path")).canonicalFile to it.getValue("sha256").toString() }
                check(sourceFiles.files.map { it.canonicalFile }.toSet() == expected.keys &&
                    expected.all { (source, hash) -> sha(source) == hash }) { "Original common compiler sources differ: ${child.path}" }
                actualLauncher = metadataLauncherRecords(launcher)
                val argFile = argumentsFile.get().asFile
                argFile.parentFile.mkdirs()
                argFile.writeText(compilerArguments.get().joinToString("\n", postfix = "\n") {
                    "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                })
            }
            doLast {
                check(executionResult.get().exitValue == 0)
                val root = output.get().asFile
                check(root.resolve("default/manifest").isFile && root.resolve("default/linkdata/module").length() > 0) {
                    "Public metadata compiler did not produce a Klib: ${child.path}"
                }
                val data = mapOf("kind" to metadataTools.getValue("producer"), "module" to child.name,
                    "task" to path, "exitCode" to executionResult.get().exitValue, "compilerVersion" to metadataTools.getValue("compilerVersion"),
                    "mainClass" to mainClass.get(), "javaExecutable" to executable, "launcherArguments" to args,
                    "arguments" to compilerArguments.get(), "argumentsFile" to records(listOf(argumentsFile.get().asFile)).single(),
                    "launcher" to actualLauncher, "sources" to records(sourceFiles.files),
                    "sourceManifestSha256" to sha(work.resolve("source-manifest.json")), "plugins" to records(plugins.files),
                    "pluginOptions" to original.compilerPluginCommandLine,
                    "languageOptions" to mapOf("languageVersion" to original.compilerOptions.languageVersion.orNull?.version,
                        "apiVersion" to original.compilerOptions.apiVersion.orNull?.version, "optIns" to original.compilerOptions.optIn.get().sorted(),
                        "freeCompilerArgs" to original.compilerOptions.freeCompilerArgs.get(), "explicitApi" to kmp.explicitApi?.name),
                    "libraries" to libraries.files.sortedBy { it.path }.map {
                        mapOf("path" to it.absolutePath, "files" to records(leaves(it)))
                    }, "libraryBuiltBy" to libraries.buildDependencies.getDependencies(this).map { it.path }.sorted(),
                    "output" to root.absolutePath, "outputs" to records(leaves(root)),
                    "outputBinding" to mapOf("roots" to compilation.output.classesDirs.files.map { it.absolutePath },
                        "builtBy" to compilation.output.classesDirs.buildDependencies.getDependencies(this).map { it.path }.sorted()),
                    "retiredTask" to mapOf("path" to original.path, "enabled" to original.enabled))
                producerFile.get().asFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(data)) + "\n")
            }
        }
        original.enabled = false
        compilation.output.classesDirs.setFrom(output)
        compilation.output.classesDirs.setBuiltBy(listOf(cli))
    }
}

tasks.register("resolveMaterialKolor") {
    doLast {
        check(gradle.gradleVersion == config.getValue("gradle").toString())
        writeEvidence("resolve", mapOf("fingerprint" to fingerprint,
            "metadataLaunchers" to subprojects.map { child -> mapOf("module" to child.name,
                "artifacts" to metadataLauncherRecords(child.configurations.getByName("commonMetadataCliLauncher"))) },
            "configurations" to subprojects.flatMap { child ->
            listOf("commonMainResolvableDependenciesMetadata", "ohosX64CompileKlibraries", "ohosArm64CompileKlibraries").map { name ->
                val configuration = child.configurations.getByName(name)
                val metadata = name.endsWith("ResolvableDependenciesMetadata")
                val result = configuration.incoming.resolutionResult
                mapOf("module" to child.name, "configuration" to name,
                    "edges" to result.allDependencies.map { edge ->
                        if (edge !is ResolvedDependencyResult) {
                            throw GradleException("Unresolved dependency: $edge", (edge as? UnresolvedDependencyResult)?.failure)
                        }
                        mapOf("requested" to edge.requested.displayName, "selected" to edge.selected.id.displayName)
                    }, "artifacts" to configuration.incoming.artifacts.artifacts.map { artifact ->
                        val id = artifact.id.componentIdentifier
                        if (id is ModuleComponentIdentifier) {
                            val ga = "${id.group}:${id.module}"
                            val externalVersion = when (ga) {
                                "io.github.qsxs.cpf.poko:poko-annotations", "io.github.qsxs.cpf.poko:poko-annotations-ohosx64", "io.github.qsxs.cpf.poko:poko-annotations-ohosarm64" -> poko.getValue("version")
                                "io.github.qsxs.cpf.colormath:colormath", "io.github.qsxs.cpf.colormath:colormath-ohosx64", "io.github.qsxs.cpf.colormath:colormath-ohosarm64" -> colormath.getValue("version")
                                else -> null
                            }
                            check((externalVersion ?: if (metadata) metadataPins[ga] ?: pins[ga] else pins[ga]) == id.version)
                            if (externalVersion != null) {
                                val public = if (id.group == "io.github.qsxs.cpf.poko") poko else colormath
                                val target = when (name) {
                                    "commonMainResolvableDependenciesMetadata" -> "metadata"
                                    "ohosX64CompileKlibraries" -> "ohos_x64"
                                    else -> "ohos_arm64"
                                }
                                val expectedArtifact = asMap(asMap(public.getValue("artifacts")).getValue(target))
                                check(sha(artifact.file) == expectedArtifact["sha256"] &&
                                    artifact.file.length() == (expectedArtifact.getValue("size") as Number).toLong()) {
                                    "Resolved public artifact differs from anonymous HTTP: $id"
                                }
                                val attributes = artifact.variant.attributes
                                val usage = attributes.keySet().associate { it.name to attributes.getAttribute(it).toString() }
                                if (target == "metadata") check(usage["org.gradle.usage"] == "kotlin-metadata" &&
                                    usage["org.jetbrains.kotlin.platform.type"] == "common")
                                else check(usage["org.gradle.usage"] == "kotlin-api" &&
                                    usage["org.jetbrains.kotlin.native.target"] == target)
                            }
                            if (metadataPins.containsKey(ga)) {
                                val attrs = artifact.variant.attributes
                                check(metadata && attrs.keySet().associate { it.name to attrs.getAttribute(it).toString() }.let {
                                    val common = it["org.gradle.usage"] == "kotlin-metadata" && it["org.jetbrains.kotlin.platform.type"] == "common"
                                    val javaAlias = ga == "androidx.arch.core:core-common" && id.version == "2.2.0" &&
                                        it["org.gradle.usage"] in listOf("java-api", "java-runtime") &&
                                        it["org.jetbrains.kotlin.platform.type"] in listOf(null, "jvm") && it["org.gradle.category"] == "library"
                                    (common || javaAlias) && it["org.jetbrains.kotlin.native.target"] == null
                                }) { "Metadata supplement selected outside common metadata: $id" }
                            }
                        }
                        mapOf("component" to id.displayName, "attributes" to artifact.variant.attributes.keySet().associate {
                            it.name to artifact.variant.attributes.getAttribute(it).toString()
                        }, "files" to records(leaves(artifact.file)))
                    })
            }
        }))
    }
}
fun recordStage(name: String, target: String?) {
    tasks.register(name) {
        val producers = subprojects.flatMap { child ->
            if (target == null) listOf("${child.path}:compileCommonMainWithMetadataCli", "${child.path}:allMetadataJar")
            else listOf("${child.path}:compileKotlin$target")
        }
        dependsOn(producers)
        outputs.upToDateWhen { false }
        doLast {
            val stage = if (target == null) "metadata" else if (target == "OhosX64") "native-x64" else "native-arm64"
            writeEvidence(stage, mapOf("fingerprint" to fingerprint, "producers" to producers.map { path ->
                val producer = project.tasks.getByPath(path)
                val state = producer.state
                check(state.executed && producer.enabled && !state.noSource && state.failure == null &&
                    state.skipMessage in listOf(null, "UP-TO-DATE", "FROM-CACHE")) { "Producer did not execute: $path" }
                val outputs = records(producer.outputs.files.files.flatMap(::leaves))
                check(outputs.isNotEmpty()) { "Missing output: $path" }
                val compiler = if (producer is KotlinNativeCompile) producer else if (producer.name == "compileCommonMainWithMetadataCli")
                    producer.project.tasks.named<KotlinNativeCompile>("compileCommonMainKotlinMetadata").get() else null
                val sourceFiles = if (compiler != null) compiler.sources.files.filter { it.isFile && it.extension == "kt" }
                    else emptyList<File>()
                if (compiler != null) {
                    val expected = (readMap(work.resolve("source-manifest.json")).getValue("members") as List<*>)
                        .map(::asMap).filter { it["module"] == producer.project.name && it.getValue("member").toString().endsWith(".kt") }
                    val expectedFiles = expected.associate { file(it.getValue("path")).canonicalFile to it.getValue("sha256").toString() }
                    check(sourceFiles.map { it.canonicalFile }.toSet() == expectedFiles.keys &&
                        expectedFiles.all { (source, hash) -> sha(source) == hash }) { "Compiled upstream source set/bytes differ: $path" }
                }
                if (compiler != null) {
                    val pluginFiles = compiler.compilerPluginClasspath?.files.orEmpty()
                    val expectedHash = if (producer.project.name == "material-color-utilities") "7bdd55014b8e0d8115e99c49d1167c8d85bf1367fdec59ef4af28d0788a114c6"
                        else "4ffe7f38089fa4acdda5d67fa3a858b9fc2fe7ed1f2e58bedbf8d3447c15df39"
                    val expectedSize = if (producer.project.name == "material-color-utilities") 88604L else 870498L
                    check(pluginFiles.size == 1 && pluginFiles.single().length() == expectedSize && sha(pluginFiles.single()) == expectedHash) {
                        "Compiler plugin bytes differ: $path"
                    }
                    if (producer.project.name == "material-color-utilities") check(
                        "plugin:dev.drewhamilton.poko:enabled=true" in compiler.compilerPluginCommandLine &&
                        "plugin:dev.drewhamilton.poko:pokoAnnotation=dev/drewhamilton/poko/Poko" in compiler.compilerPluginCommandLine)
                }
                val publicTrace = if (compiler == null) null else publicDependencyTrace(compiler, producer.project.name, stage)
                mapOf("task" to path, "outputs" to outputs, "publicDependencyTrace" to publicTrace,
                    "sources" to records(sourceFiles),
                    "plugins" to if (compiler != null) records(compiler.compilerPluginClasspath?.files.orEmpty()) else emptyList<Any>(),
                    "pluginOptions" to if (compiler != null) compiler.compilerPluginCommandLine else emptyList<String>(),
                    "commonMetadataCli" to if (producer.name == "compileCommonMainWithMetadataCli")
                        readMap(producer.project.layout.buildDirectory.file("metadata-cli/commonMain/producer.json").get().asFile) else null,
                    "directDependencies" to producer.taskDependencies.getDependencies(producer).map { it.path }.sorted())
            }))
        }
    }
}
recordStage("recordMaterialKolorMetadata", null)
recordStage("recordMaterialKolorX64", "OhosX64")
recordStage("recordMaterialKolorArm64", "OhosArm64")
tasks.register("publishMaterialKolor") { dependsOn(subprojects.map { "${it.path}:publishAllPublicationsToPortRepository" }) }
