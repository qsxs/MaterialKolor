import groovy.json.JsonSlurper

pluginManagement {
    repositories {
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-develop/")
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/")
        gradlePluginPortal()
        mavenCentral()
    }
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version "2.4.20-0.1.0-08"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.20-0.1.0-08"
        id("dev.drewhamilton.poko") version "0.23.2"
    }
}
fun json(file: File) = JsonSlurper().parse(file) as Map<*, *>
val pins = json(file("dependency-pins.json"))
val metadataPins = json(file("metadata-pins.json"))
val toolRoot = file(providers.gradleProperty("portTooling").get()).resolve("toolchains/kotlin-native/2.4.20-0.1.0-08")
val launcher = json(toolRoot.resolve("metadata-toolchain.json"))["launcherPins"] as Map<*, *>
val commonizer = json(toolRoot.resolve("commonizer-toolchain.json"))["pins"] as Map<*, *>
val host = json(toolRoot.resolve("native-compiler-toolchain.json"))["pins"] as Map<*, *>
val compose = json(file("compiler-plugin-toolchain.json"))["pins"] as Map<*, *>
val allowed = pins.entries + metadataPins.entries + launcher.entries + commonizer.entries + host.entries + compose.entries
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { name = "CpfPages"; url = uri("https://qsxs.github.io/cpf-maven/") } }
            filter { includeGroup("io.github.qsxs.cpf.poko"); includeGroup("io.github.qsxs.cpf.colormath") }
        }
        google { content { metadataPins.forEach { (ga, version) ->
            val parts = ga.toString().split(":")
            if (parts[0].startsWith("androidx.")) includeVersion(parts[0], parts[1], version.toString())
        } } }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-develop/") {
            content { allowed.forEach { (ga, version) -> val parts = ga.toString().split(":"); includeVersion(parts[0], parts[1], version.toString()) } }
        }
        maven("https://maven.eazytec-cloud.com/nexus/repository/maven-public/") {
            content { allowed.forEach { (ga, version) -> val parts = ga.toString().split(":"); includeVersion(parts[0], parts[1], version.toString()) } }
        }
        mavenCentral()
    }
}
rootProject.name = "materialkolor-ohos-port"
include(":material-color-utilities", ":material-kolor")
