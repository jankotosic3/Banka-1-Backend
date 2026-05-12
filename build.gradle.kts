// Root multi-project build script.
// Centralizuje plugin verzije, Java toolchain, Checkstyle, JaCoCo i deljene repository deklaracije
// kako bi 14+ servisa ne morali svaki da duplira istu konfiguraciju.
//
// Specifične zavisnosti i Spring Boot starter-i ostaju u svakom servisovom build.gradle.kts —
// ovde definišemo samo ono što je istinski globalno.
//
// Uvedeno u PR_02 C2.1.

// PR_19 C19.X fix: uklonjen 'org.gradle.toolchains.foojay-resolver-convention' iz
// root plugins {} bloka. To je SETTINGS plugin (vec u settings.gradle); pri
// standalone Dockerfile multi-module build-u Gradle baca:
// "Settings plugins must be applied in the settings script." Pre PR_19 ovo nije
// pravilo problem jer Dockerfile-i nikad nisu testirali multi-module pattern;
// sad sa COPY . . pristupom mora biti uklonjen.
//
// Spring Boot plugin svaki servis aktivira sam u svom build.gradle.kts.

allprojects {
    // PR_19 C19.X fix: ne forsiramo group="com.banka1" za sve subprojekte —
    // company-observability-starter mora da zadrzi group="com.library" jer ga
    // svi servisi referenciraju kao 'com.library:company-observability-starter'.
    // Pre PR_19 ovo je radilo u standalone build-u jer dep nije bio resolve-ovan
    // (build je padao prije ove provere); sa multi-module COPY . . pristupom
    // sada je vidljiv.
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenLocal()
        mavenCentral()
    }
}

subprojects {
    // Java toolchain — svi moduli koriste Java 21.
    pluginManager.withPlugin("java") {
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
    }

    // JaCoCo — automatski uključujemo plugin u svakom modulu koji već primenjuje 'java'.
    // Servisi koji nisu hteli JaCoCo (security-lib, company-observability-starter) mogu ga isključiti
    // sa disable-em u svom build.gradle.kts.
    pluginManager.withPlugin("java") {
        apply(plugin = "jacoco")
        configure<org.gradle.testing.jacoco.plugins.JacocoPluginExtension> {
            toolVersion = "0.8.12"
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            finalizedBy("jacocoTestReport")
        }

        tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.named("test"))
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }

        // PR_08 C8.3: coverage gate podignut sa 0.60 (PR_02 baseline) na 0.80 (production target).
        // Cini deo `check` task-a (gradle build fail-uje ako nije ispunjen).
        tasks.register<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>("jacocoCoverageVerification80") {
            dependsOn(tasks.named("jacocoTestReport"))
            violationRules {
                rule {
                    limit {
                        minimum = "0.80".toBigDecimal()
                    }
                }
            }
        }
        tasks.named("check").configure {
            dependsOn("jacocoCoverageVerification80")
        }
    }

    // Checkstyle — primenjuje se na svaki Java modul; konfiguracija je u root checkstyle.xml.
    pluginManager.withPlugin("java") {
        apply(plugin = "checkstyle")
        configure<CheckstyleExtension> {
            toolVersion = "10.17.0"
            configFile = rootProject.file("checkstyle.xml")
            isIgnoreFailures = true  // ne fail-uje build dok PR_10 ne počisti sve postojeće warnings
        }
    }
}
