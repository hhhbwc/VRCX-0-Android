pluginManagement {
    repositories {
        // 阿里云 Gradle 插件镜像（Maven Central 在本机被拦，返回 403）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // google() first: the adaptive navigation suite is missing from the
        // aliyun mirror (404) but present on Google Maven.
        google()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        // Maven Central mirror -- the real one answers 403 on this machine.
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "VRCX0"
include(":app")
