import org.gradle.kotlin.dsl.protobuf
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.*

val kotlin_version = "2.0.20"

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.20"
    id("com.google.protobuf") version "0.9.4"
}

val versionProps = Properties().apply {
    try { load(FileInputStream(layout.projectDirectory.file("api/versions.properties").asFile)) }
    catch(ex: FileNotFoundException){
        error("failed to read 'versions.properties' file: ${ex.message}")
    }
}

val protobufVersion = versionProps["protobuf"] ?: "3.21.12"
val grpcVersion = versionProps["grpc"] ?: "1.68.0"
val grpcKotlinStubsVersion = versionProps["grpc_kt_stub"] ?: "1.4.1"

val volitionSpecVersion = versionProps["volition"] ?: "0.0.0"
val buildNumber = System.getenv("VOLITION_BUILD_NUMBER")?.toInt() ?: 0
val volitionFullVersion = "$volitionSpecVersion.$buildNumber"
val volitionName = "volition-api"

repositories {
    mavenCentral()
}

allprojects {

    group = "com.empowerops"
    version = volitionFullVersion

    repositories {
        mavenCentral()
        //kotlinx.collections.immutable 0.1, 0.3 is available on mvn
        //functionale-all 1.2 not available... but is is? https://mvnrepository.com/artifact/org.funktionale/funktionale-all/1.2
        jcenter()
    }
}

subprojects {

    repositories {
        mavenCentral()
    }

//    javadoc { exclude("com/empowerops/volition/optimizer/**") }
}
//dependencies {
//    compile "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version"
//}

project("api") {

    apply(plugin = "java")
    apply(plugin = "kotlin")
    apply(plugin = "com.google.protobuf")

    sourceSets {
        main {
            java {
                // already added by plugin
//                srcDirs("build/generated/source/proto/main/java")
//                srcDirs("build/generated/source/proto/main/grpc_java")
//                srcDirs("build/generated/source/proto/main/kotlin")
//                srcDirs("build/generated/source/proto/main/grpc_kt")
            }
        }
        test {
            kotlin {
                srcDirs("src/test/kotlin")
            }
        }
    }

    java {
        withJavadocJar()
        withSourcesJar()
    }

//    java.toolchain.languageVersion.set(JavaLanguageVersion.of(11))
//
//    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
////        kotlinOptions.jvmTarget = "11"
//        //sourceCompatibility = "11"
//    }

    dependencies {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version")
        implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlin_version")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1")

        implementation("javax.annotation:javax.annotation-api:1.3.2")

//        implementation("org.jetbrains.kotlinx:kotlinx.collections.immutable:0.1")

        implementation("com.google.guava:guava:27.0.1-jre")

        implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
        implementation("io.grpc:grpc-protobuf:$grpcVersion")
        implementation("io.grpc:grpc-stub:$grpcVersion")
        implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinStubsVersion")
        implementation("io.grpc:grpc-protobuf-lite:$grpcVersion") // transitive dependency; for error handling
        implementation("com.google.api.grpc:proto-google-common-protos:2.49.0") // used for error handling
        implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")
//        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.4.3")

//        implementation("com.google.protobuf:protobuf-kotlin-lite:$protobuf_version")
    }

    protobuf {

        protoc {
            artifact = "com.google.protobuf:protoc:$protobufVersion"
        }
        plugins {
            create("grpc") {
                artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
            }
            create("grpckt") {
                artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinStubsVersion:jdk8@jar"
            }
        }

        generateProtoTasks {
            all().forEach {
                it.plugins {
                    create("grpc")
                    create("grpckt")
                }
                it.builtins {
                    create("kotlin")
                }
            }
        }
    }


//    tasks.named("generateProto") {
//        dependsOn(":api:deleteProto")
//    }

//    tasks.register<Exec>("updateDotnetVersionString") {
//        commandLine("powershell.exe", "-File", "UpdateVersionProperties.ps1", "-VersionString", volitionFullVersion)
//    }

//    tasks.getByName("assemble"){
//        dependsOn(":api:updateDotnetVersionString")
//    }

    tasks.withType<Jar>().configureEach {
        archiveBaseName.set("volition-api")

        manifest {
            attributes(
                mapOf(
                    "Specification-Title" to "Volition API",
                    "Specification-Version" to volitionSpecVersion,
                    "Specification-Vendor" to "Empower Operations Corp",
                    "Implementation-Version" to volitionFullVersion,
                    "Implementation-Vendor" to "Empower Operations Corp",
                )
            )
        }
    }

//    tasks.register<Exec>("vcpkgBootstrap") {
//        // regarding 'working dir',
//        // the --X-install-path is experimental,
//        // some of the vcpkg docs (sorry I cant remember where)
//        // sait it was important to start vcpkg in the vcpkg root;
//        // this is apparently what is commonly tested for so
//        // rather than get creative, I'm simply going to dump my files into their default folders,
//        // and manage this under a gitignore.
//        workingDir("$rootDir/vcpkg")
//        commandLine("$rootDir/vcpkg/bootstrap-vcpkg.bat", "-disableMetrics")
//    }

//    tasks.register("vcpkgMakeManifest") {
//        val lines = Files.readAllLines(Paths.get("$rootDir/vcpp-client-reference/vcpkg.template.json"))
//        val updatedLines = lines.map { it
//            .replace("%volitionFullVersion%", volitionFullVersion)
//            .replace("%protobufVersion%", protobufVersion)
//            .replace("%grpcVersion%", grpcVersion)
//        }
//        Files.write(Paths.get("$rootDir/vcpp-client-reference/vcpkg.json"), updatedLines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
//    }

//    tasks.register<Exec>("vcpkgInstall") {
//        group = "vcpkg"
//        dependsOn(":api:vcpkgMakeManifest", ":api:vcpkgBootstrap")
//
//        workingDir("$rootDir/vcpkg")
//        commandLine(
//            "$rootDir/vcpkg/vcpkg.exe",
//            "--feature-flags=manifests,versions",
//            "--triplet=x64-windows",
//            "install"
//        )
//    }
//
//    tasks.register<Exec>("vcpkgIntegrate") {
//        group = "vcpkg"
//        dependsOn(":api:vcpkgInstall")
//
//        workingDir("$rootDir/vcpkg")
//        commandLine(
//            "$rootDir/vcpkg/vcpkg.exe",
//            "--feature-flags=manifests,versions",
//            "--triplet=x64-windows",
//            "integrate",
//            "install"
//        )
//    }
//
//    tasks.register<Exec>("vcpkgList") {
//        group = "vcpkg"
//
//        workingDir("$rootDir/vcpkg")
//        commandLine("$rootDir/vcpkg/vcpkg.exe", "--feature-flags=manifests,versions", "--triplet=x64-windows", "list")
//    }
}

project("oasis-reference"){

    apply(plugin = "java")
    apply(plugin = "kotlin")

//    java.toolchain.languageVersion.set(JavaLanguageVersion.of(11))
//    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
//        kotlinOptions.jvmTarget = "11"
//        sourceCompatibility = "11"
//    }

    repositories {

    }

    dependencies {

        implementation(project(":api"))
        implementation(files("babel-0.20.jar"))

        implementation(group = "org.antlr", name = "antlr4-runtime", version = "4.13.2")

        implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
        implementation("io.grpc:grpc-protobuf:$grpcVersion")
        implementation("io.grpc:grpc-stub:$grpcVersion")
        implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinStubsVersion")

        implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion") //<--- this depends on kotlin 1.5
        implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-collections-immutable", version = "0.3.8")

        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.6.1")

        implementation("no.tornado:tornadofx:1.7.17")
        implementation("info.picocli:picocli:3.9.5")
        implementation("org.antlr:antlr4-runtime:4.8-1")

        testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
        testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.1.0")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
        testImplementation("org.assertj:assertj-core:3.11.1")

//        compile group: "org.funktionale", name: "funktionale-all", version: "1.2"
    }

    java {
        manifest {
            attributes(mapOf(
                "Main-Class" to "com.empowerops.volition.ref_oasis.OptimizerCLIKt",
                "Specification-Title" to "Volition API",
                "Specification-Version" to volitionSpecVersion,
                "Specification-Vendor" to "Empower Operations Corp",
                "Implementation-Version" to volitionFullVersion,
                "Implementation-Vendor" to "Empower Operations Corp",
            ))
        }
    }

    tasks.register<Zip>("deliverable") {
        group = "build"
        dependsOn(":oasis-reference:assemble")

        from("$buildDir/libs")
        from(configurations.runtimeOnly)
        from("$projectDir")
        include("*.exe")
        include("*.jar")

        archiveFileName.set("optimizer-reference-${volitionFullVersion}.zip")
        destinationDirectory.set(file("$buildDir/deliverable"))
    }

    tasks.test {
        useJUnitPlatform()
    }

    //https://stackoverflow.com/questions/41794914/how-to-create-the-fat-jar-with-gradle-kotlin-script
//    val fatJar = task("fatJar", type = Jar::class) {
//        archiveBaseName.set("${project.name}-fat")
//        from(configurations.runtimeClasspath.get().map({ if (it.isDirectory) it else zipTree(it) }))
//        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
//    }
//    tasks {
//        "assemble" {
//            dependsOn(fatJar)
//        }
//    }
}