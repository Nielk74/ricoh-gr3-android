plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

/**
 * The film-develop core (`looks/emulation`) is **pure Kotlin with no Android deps** (verified),
 * so this JVM tool compiles those sources directly rather than depending on the Android `:app`
 * module. That lets the preview renderer run on a plain JVM in CI — decode a JPEG with
 * `javax.imageio`, run the real `DevelopPipeline`, and write PNGs — with zero Android/emulator.
 */
sourceSets {
    main {
        // Pull in ONLY the pure-Kotlin colour-science files from :app — NOT
        // FilmLookLoader/DevelopEngine, which touch Android (`Context`, `Bitmap`). The include
        // filter is scoped to the app src root; this module's own sources live in the default
        // `src/main/kotlin` and are unaffected.
        kotlin.srcDir("../app/src/main/java")
        kotlin.include(
            "com/ricohgr3/app/tools/**",
            "com/ricohgr3/app/looks/emulation/DevelopPipeline.kt",
            "com/ricohgr3/app/looks/emulation/LutCube.kt",
            "com/ricohgr3/app/looks/emulation/FilmLook.kt",
            "com/ricohgr3/app/looks/emulation/FilmLutFactory.kt",
            "com/ricohgr3/app/looks/emulation/FilmLookCatalog.kt",
        )
    }
}

application {
    mainClass.set("com.ricohgr3.app.tools.PreviewRendererKt")
}

/** Render the emulation preview strip from the committed GR III sample into `docs/previews/`. */
tasks.register<JavaExec>("renderPreviews") {
    group = "documentation"
    description = "Render each film emulation over the sample photo into docs/previews/*.png"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.ricohgr3.app.tools.PreviewRendererKt")
    // repoRoot, sample image, LUT asset dir, output dir
    args(
        rootProject.projectDir.absolutePath,
        "docs/preview-src/griii-sample.jpg",
        "app/src/main/assets/luts",
        "docs/previews",
    )
}
