pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LiteChunk"

// ChunkBridge (the conversion engine, its loader and the public SDK) is a separate repository that
// this one consumes as a git submodule. It is pulled in as a composite build instead of a published
// AAR so a change to the engine is compiled here directly -- no publish step, no version to bump
// while iterating. Override the location for a checkout living somewhere else:
//   ./gradlew assembleDebug -Pchunkbridge.sdk.dir=../ChunkBridge-sdk
val chunkbridgeDir = providers.gradleProperty("chunkbridge.sdk.dir").getOrElse("chunkbridge")
includeBuild(file(chunkbridgeDir)) {
    dependencySubstitution {
        substitute(module("com.frenetic.chunkbridge:chunkbridge")).using(project(":library"))
        substitute(module("com.frenetic.chunkbridge:api")).using(project(":api"))
    }
}

include(":app")
