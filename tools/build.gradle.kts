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
            "com/ricohgr3/app/looks/emulation/ColorMath.kt",
            "com/ricohgr3/app/looks/emulation/DevelopOptions.kt",
            "com/ricohgr3/app/looks/emulation/SkinTone.kt",
            "com/ricohgr3/app/looks/emulation/SceneAnalyzer.kt",
            "com/ricohgr3/app/looks/emulation/LutCube.kt",
            "com/ricohgr3/app/looks/emulation/FilmLook.kt",
            "com/ricohgr3/app/looks/emulation/FilmStockProfile.kt",
            "com/ricohgr3/app/looks/emulation/PortraSensitometry.kt",
            "com/ricohgr3/app/looks/emulation/FilmFidelityMetrics.kt",
            "com/ricohgr3/app/looks/emulation/FilmOptics.kt",
            "com/ricohgr3/app/looks/emulation/FilmLutFactory.kt",
            "com/ricohgr3/app/looks/emulation/FilmLookCatalog.kt",
            "com/ricohgr3/app/looks/emulation/PhysicalFilmGrain.kt",
        )
    }
    test {
        // Re-run the pure colour-science tests without configuring/booting Android. This is also
        // useful on calibration machines that have a JDK but no Android SDK installed.
        kotlin.srcDir("../app/src/test/java")
        kotlin.include("com/ricohgr3/app/looks/emulation/**")
    }
}

dependencies {
    testImplementation(libs.junit)
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

/** Render the local `.references` JPEG calibration set into ignored `build/reference-renders`. */
tasks.register<JavaExec>("renderReferences") {
    group = "verification"
    description = "Render adaptive film contact sheets and a scene-analysis report"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.ricohgr3.app.tools.ReferenceRendererKt")
    val referenceInput = (project.findProperty("referenceInput") as String?) ?: ".references"
    val referenceOutput =
        (project.findProperty("referenceOutput") as String?) ?: "build/reference-renders"
    args(
        rootProject.projectDir.absolutePath,
        referenceInput,
        referenceOutput,
    )
}

/** Decode/orient both JPEG and DNG camera references as 3000px, display-referred sRGB masters. */
val prepareReviewSources by tasks.registering(Exec::class) {
    group = "verification"
    description = "Prepare high-resolution oriented sRGB sources for the film review site"
    commandLine(
        "bash",
        rootProject.file("tools/prepare-review-sources.sh").absolutePath,
        rootProject.projectDir.absolutePath,
        rootProject.file("build/review-sources").absolutePath,
        "3000",
    )
}

/** Build the interactive high-resolution review site into ignored `build/film-review`. */
tasks.register<JavaExec>("renderReviewSite") {
    group = "verification"
    description = "Render all film looks at 3000px and assemble the local review website"
    val reviewInput =
        (project.findProperty("reviewInput") as String?) ?: "build/review-sources"
    val reviewOutput =
        (project.findProperty("reviewOutput") as String?) ?: "build/film-review"
    val reviewBaseline = project.findProperty("reviewBaseline") as String?
    val reviewLooks = project.findProperty("reviewLooks") as String?
    // A caller-supplied prepared input is useful for a fast, focused calibration loop. The full
    // task still prepares every JPEG/DNG reference automatically.
    if (!project.hasProperty("reviewInput")) dependsOn(prepareReviewSources)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.ricohgr3.app.tools.ReviewSiteRendererKt")
    maxHeapSize = "3g"
    args(
        rootProject.projectDir.absolutePath,
        reviewInput,
        "review-site",
        reviewOutput,
    )
    reviewBaseline?.let { args("--baseline=$it") }
    reviewLooks?.let { args("--looks=$it") }
}

/** Render high-resolution false-colour overlays for auditing selective skin isolation. */
tasks.register<JavaExec>("renderSkinMasks") {
    group = "verification"
    description = "Render exact scene-adapted skin masks for the portrait calibration loop"
    val reviewInput =
        (project.findProperty("reviewInput") as String?) ?: "build/review-sources"
    val skinMaskOutput =
        (project.findProperty("skinMaskOutput") as String?) ?: "build/skin-mask-review"
    if (!project.hasProperty("reviewInput")) dependsOn(prepareReviewSources)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.ricohgr3.app.tools.SkinMaskRendererKt")
    maxHeapSize = "2g"
    args(
        rootProject.projectDir.absolutePath,
        reviewInput,
        skinMaskOutput,
    )
}
