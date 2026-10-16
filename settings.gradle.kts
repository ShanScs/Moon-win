pluginManagement {
    repositories {
        // 本地仓库（仅当目录存在时启用，用于离线/代理受限环境）
        val localManual = file("/home/hatch/workspace/local-repo-manual")
        if (localManual.isDirectory) maven { url = uri(localManual) }
        val localRepo = file("/home/hatch/workspace/local-repo")
        if (localRepo.isDirectory) maven { url = uri(localRepo) }
        maven("https://repo.maven.apache.org/maven2/")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        val localManual = file("/home/hatch/workspace/local-repo-manual")
        if (localManual.isDirectory) maven { url = uri(localManual) }
        val localRepo = file("/home/hatch/workspace/local-repo")
        if (localRepo.isDirectory) maven { url = uri(localRepo) }
        google()
        maven("https://repo.maven.apache.org/maven2/")
    }
}

rootProject.name = "moon-pc"
include(":composeApp")
