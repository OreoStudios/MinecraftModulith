plugins {
    id("java-gradle-plugin")
    id("maven-publish")
}

publishing {
    repositories {
        maven {
            name = "oreostudios"
            url = uri("https://maven.oreostudios.fr/oreostudioslib")
            credentials {
                username = providers.gradleProperty("oreostudiosUsername")
                    .orElse(providers.environmentVariable("MAVEN_USERNAME"))
                    .orNull
                password = providers.gradleProperty("oreostudiosPassword")
                    .orElse(providers.environmentVariable("MAVEN_PASSWORD"))
                    .orNull
            }
        }
    }
}

gradlePlugin {
    plugins {
        create("minecraftModulith") {
            id = "dev.oreo.minecraft-modulith"
            implementationClass = "dev.oreo.modulith.gradle.MinecraftModulithPlugin"
            displayName = "MinecraftModulith"
            description = "Compile-time module verification and module documentation"
        }
    }
}
