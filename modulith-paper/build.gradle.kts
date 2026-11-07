val paperApiVersion: String by project

dependencies {
    api(project(":modulith-core"))
    implementation("io.github.classgraph:classgraph:4.8.179")
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}