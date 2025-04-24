plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "1.9.21"
  id("org.jetbrains.intellij") version "1.16.1"
}

group = "com.cfin.novel"
version = "1.0-SNAPSHOT"

repositories {
  mavenCentral()
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
  version.set("2023.1.5")
  type.set("IC") // Target IDE Platform
  plugins.set(listOf(/* Plugin Dependencies */))
  updateSinceUntilBuild.set(false) // 禁用自动更新版本范围
}

tasks {
  // Set the JVM compatibility versions
  withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
  }
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
  }

  withType<org.jetbrains.intellij.tasks.RunIdeTask> {
    // Add VM options
    jvmArgs = listOf("-Xmx2048m", "-Dfile.encoding=UTF-8")
  }

  patchPluginXml {
    sinceBuild.set("231")
    untilBuild.set("243.*")
  }

  signPlugin {
    certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
    privateKey.set(System.getenv("PRIVATE_KEY"))
    password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
  }

  publishPlugin {
    token.set(System.getenv("PUBLISH_TOKEN"))
  }
  
  // 添加离线构建任务
  register<Zip>("buildPluginOffline") {
    group = "intellij"
    description = "Build plugin in offline mode"
    
    dependsOn("buildPlugin")
    from("${project.buildDir}/distributions")
    include("*.zip")
    destinationDirectory.set(file("${project.buildDir}/offline-distributions"))
  }
}

// 添加一个本地安装Task
tasks.register<Copy>("installPlugin") {
  group = "intellij"
  description = "Install plugin to local IDE"
  
  dependsOn("buildPlugin")
  
  from("${project.buildDir}/distributions")
  include("*.zip")
  
  // 根据操作系统确定IDEA插件目录
  val pluginsDir = when {
    org.gradle.internal.os.OperatingSystem.current().isWindows -> 
      "${System.getProperty("user.home")}/AppData/Roaming/JetBrains/IntelliJIdea2023.1/plugins"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> 
      "${System.getProperty("user.home")}/Library/Application Support/JetBrains/IntelliJIdea2023.1/plugins"
    else -> 
      "${System.getProperty("user.home")}/.IntelliJIdea2023.1/config/plugins"
  }
  
  into(pluginsDir)
  
  doLast {
    println("Plugin installed to: $pluginsDir")
    println("Restart your IDE to apply changes")
  }
}
