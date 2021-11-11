import com.google.protobuf.gradle.*
import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.plugins
import org.gradle.kotlin.dsl.protobuf

val kotlin_version = "1.4.32"

plugins {
    id("org.jetbrains.kotlin.jvm") version "1.4.32"
    id("com.google.protobuf") version "0.8.17"
}

val protobufVersion = "3.18.0"
val grpcVersion = "1.37.0"
val volitionSpecVersion = "1.3"
val volitionBuildNumber = System.getenv("BUILD_NUMBER") ?: "309"
val volitionFullVersion = "$volitionSpecVersion.$volitionBuildNumber"
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
                srcDirs("build/generated/source/proto/main/java")
                srcDirs("build/generated/source/proto/main/grpc_java")
                srcDirs("build/generated/source/proto/main/kotlin")
                srcDirs("build/generated/source/proto/main/grpc_kt")
            }
        }
    }

    java.toolchain.languageVersion.set(JavaLanguageVersion.of(11))
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "11"
        sourceCompatibility = "11"
    }

    dependencies {
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlin_version")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.3")

        implementation("javax.annotation:javax.annotation-api:1.3.2")

        implementation("com.google.guava:guava:27.0.1-jre")

        implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
        implementation("io.grpc:grpc-protobuf:$grpcVersion")
        implementation("io.grpc:grpc-stub:$grpcVersion")
        implementation("io.grpc:grpc-kotlin-stub:1.1.0")
        implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")
//        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.4.3")

//        implementation("com.google.protobuf:protobuf-kotlin-lite:$protobuf_version")
    }

    protobuf {

        protobuf.protoc {
            artifact = "com.google.protobuf:protoc:$protobufVersion"
        }
        protobuf.plugins {
            id("grpc_java") {
                artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
            }
            id("grpc_csharp") {
                path = "$rootDir/vcpkg/packages/grpc_x64-windows/tools/grpc/grpc_csharp_plugin.exe"
            }
            id("grpc_cpp") {
                path = "$rootDir/vcpkg/packages/grpc_x64-windows/tools/grpc/grpc_cpp_plugin.exe"
            }
            id("grpc_python") {
                path = "$rootDir/vcpkg/packages/grpc_x64-windows/tools/grpc/grpc_python_plugin.exe"
            }
            id("grpc_kt") {
                artifact = "io.grpc:protoc-gen-grpc-kotlin:1.2.0:jdk7@jar"
            }
        }

        protobuf.generateProtoTasks {
            ofSourceSet("main").forEach {
                it.plugins {
                    id("grpc_java")
                    id("grpc_csharp")
                    id("grpc_cpp")
                    id("grpc_python")
                    id("grpc_kt")
                }
                it.builtins {
//                    id("java") //builtin, gets angry when I repeat it
                    id("csharp")
                    id("cpp")
                    id("python")
                    id("kotlin")
                }
            }
        }
    }


//    tasks.named("generateProto") {
//        dependsOn(":api:deleteProto")
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

    java {
        withJavadocJar()
        withSourcesJar()
    }

    tasks.register<Exec>("vcpkgBootstrap") {
        workingDir("$rootDir/vcpkg")
        commandLine("$rootDir/vcpkg/bootstrap-vcpkg.bat", "-disableMetrics")
    }

    tasks.register<Copy>("vcpkgMakeManifest") {
        dependsOn(":api:vcpkgBootstrap")

        from("vcpkg.template.json")
        rename("vcpkg\\.template\\.json", "vcpkg.json") //TODO: is this a regex?
        into("$rootDir/vcpkg")
        filter { line ->
            line.replace("%volitionFullVersion%", volitionFullVersion)
                .replace("%protobufVersion%", protobufVersion)
                .replace("%grpcVersion%", grpcVersion)
        }
    }

    tasks.register<Exec>("vcpkgInstall") {
        group = "vcpkg"
        dependsOn(":api:vcpkgMakeManifest")

        workingDir("$rootDir/vcpkg")
        commandLine(
            "$rootDir/vcpkg/vcpkg.exe",
            "--feature-flags=manifests,versions",
            "--triplet=x64-windows",
            "install"
        )
    }

    tasks.register<Exec>("vcpkgIntegrate") {
        group = "vcpkg"
        dependsOn(":api:vcpkgInstall")

        workingDir("$rootDir/vcpkg")
        commandLine(
            "$rootDir/vcpkg/vcpkg.exe",
            "--feature-flags=manifests,versions",
            "--triplet=x64-windows",
            "integrate",
            "install"
        )
    }

    tasks.register<Exec>("vcpkgList") {
        group = "vcpkg"

        workingDir("$rootDir/vcpkg")
        commandLine("$rootDir/vcpkg/vcpkg.exe", "--feature-flags=manifests,versions", "--triplet=x64-windows", "list")
    }

    tasks.compileJava {
        dependsOn(":api:vcpkgInstall")
    }
}

project("oasis-reference"){

    apply(plugin = "java")
    apply(plugin = "kotlin")

    java.toolchain.languageVersion.set(JavaLanguageVersion.of(11))
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "11"
        sourceCompatibility = "11"
    }

    dependencies {

        implementation(project(":api"))
        implementation(files("babel-0.18.jar"))

        implementation(group = "org.antlr", name = "antlr4-runtime", version = "4.9.1")

        implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
        implementation("io.grpc:grpc-protobuf:$grpcVersion")
        implementation("io.grpc:grpc-stub:$grpcVersion")
        implementation("io.grpc:grpc-kotlin-stub:1.1.0")

        implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion") //<--- this depends on kotlin 1.5
        implementation(group = "org.jetbrains.kotlinx", name = "kotlinx-collections-immutable", version = "0.1")

        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.3")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-javafx:1.4.3")

        implementation("no.tornado:tornadofx:1.7.17")
        implementation("info.picocli:picocli:3.9.5")
        implementation("org.antlr:antlr4-runtime:4.8-1")

        testImplementation("org.junit.jupiter:junit-jupiter-api:5.3.1")
        testImplementation("com.nhaarman.mockitokotlin2:mockito-kotlin:2.1.0")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.3.1")
        testCompile("org.assertj:assertj-core:3.11.1")

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
        dependsOn(":oasis-reference:assemble")

        from("$buildDir/libs")
        from(configurations.runtime)
        from("$projectDir")
        include("*.exe")
        include("*.jar")

        archiveFileName.set("optimizer-reference-${archiveVersion}.zip")
        destinationDirectory.set(file("$buildDir/deliverable"))
    }

    tasks.test {
        useJUnitPlatform()
    }
}