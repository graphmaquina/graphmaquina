// Experimental and opt-in: settings.gradle.kts includes this module only with -PgraphmaquinaRust, so the default
// build, its check task and every published artifact stay exactly as they are and never need a Rust toolchain.
plugins {
    id("graphmaquina.java-conventions")
}
dependencies {
    implementation(project(":graphmaquina-core"))
    testImplementation(project(":graphmaquina-test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

val nativeDir = layout.projectDirectory.dir("native")
val libraryName = when {
    System.getProperty("os.name").startsWith("Mac") -> "libgraphmaquina_rust.dylib"
    System.getProperty("os.name").startsWith("Windows") -> "graphmaquina_rust.dll"
    else -> "libgraphmaquina_rust.so"
}
val nativeLibrary = nativeDir.file("target/release/$libraryName")

// The toolchain is pinned by native/rust-toolchain.toml, so rustup picks the same Rust release on every machine.
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    group = "build"
    description = "Builds the Rust kernels as a JNI library with the pinned toolchain"
    workingDir(nativeDir)
    commandLine("cargo", "build", "--release", "--locked")
    inputs.files(nativeDir.file("Cargo.toml"), nativeDir.file("Cargo.lock"), nativeDir.file("rust-toolchain.toml"))
    inputs.dir(nativeDir.dir("src"))
    outputs.file(nativeLibrary)
}

val cargoTest = tasks.register<Exec>("cargoTest") {
    group = "verification"
    description = "Checks the Rust formatting, runs the Rust kernel tests and clippy with warnings as errors"
    workingDir(nativeDir)
    commandLine("sh", "-c",
        "cargo fmt --check && cargo test --locked && cargo clippy --locked --all-targets -- -D warnings")
    inputs.files(nativeDir.file("Cargo.toml"), nativeDir.file("Cargo.lock"), nativeDir.file("rust-toolchain.toml"),
        nativeDir.file("rustfmt.toml"))
    inputs.dir(nativeDir.dir("src"))
    inputs.dir(nativeDir.dir("tests"))
    outputs.upToDateWhen { false }
}

tasks.test {
    dependsOn(cargoBuild, cargoTest)
    systemProperty("graphmaquina.rust.library", nativeLibrary.asFile.absolutePath)
    // The library is read through a path, so it is declared as an input: a rebuilt kernel reruns the tests.
    inputs.file(nativeLibrary).withPropertyName("nativeLibrary")
    // JDK 24 and later warn when unnamed code loads a native library unless native access is granted to it.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
