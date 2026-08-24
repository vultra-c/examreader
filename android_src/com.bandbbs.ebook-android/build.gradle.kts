// Top-level build file. Plugin versions are declared in gradle/libs.versions.toml.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
