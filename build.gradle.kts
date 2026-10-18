allprojects {
    group = "dev.oreo.modulith"
    version = "0.5.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "java-library")

    if (name != "example-plugin" && name != "modulith-gradle-plugin") {
        apply(plugin = "maven-publish")

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("mavenJava") {
                    from(components["java"])
                }
            }

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
    }

    extensions.configure<JavaPluginExtension> {
        // Compile with the running JDK targeting Java 21 bytecode (no separate toolchain needed).
        withSourcesJar()
        withJavadocJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}