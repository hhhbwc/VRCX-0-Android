pluginManagement {
    repositories {
        // 阿里云 Gradle 插件镜像（本机访问 Maven Central 返回 403）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // google() 必须在前：阿里云镜像缺部分 Google 组件（404）
        google()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

rootProject.name = "VRCX0Tablet"
include(":app")
