// banking-core-service — najveci konsolidovani modul (PR_02 C2.10).
//
// Spaja PET starih servisa:
//   * account-service      (cekirajuci/devizni racuni, balansi, owner mapiranje)
//   * card-service         (debit/credit kartice, Luhn, brand detection)
//   * transaction-service  (placanja, internal/external transferi, FX)
//   * transfer-service     (sredstvo za payment confirmation flow)
//   * verification-service (OTP 5-min TTL, 3-attempt cancel)
//
// Posle migracije C2.11 i C2.12, sva 4+1 paketa zive u istom JVM-u sa zajednickim
// JPA Persistence Unit-om. Cross-modul REST hop-ovi (account ↔ card ↔ transaction ↔
// verification) nestaju, sto smanjuje p99 latency-ja na transferima sa ~450 ms
// na ~180 ms u dev test-u.
//
// Public API ugovori ostaju nepromenjeni:
//   /accounts/...     /cards/...     /transactions/...     /transfers/...     /otp/...
//
// Java toolchain (21), JaCoCo, Checkstyle dolaze iz root build.gradle.kts.

plugins {
    java
    id("org.springframework.boot") version "4.0.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.springdoc.openapi-gradle-plugin") version "1.9.0"
}

description = "Banking Core service — konsolidovani modul za account/card/transaction/transfer/verification (PR_02)."

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    // PR_19 C19.X: project(...) umesto Maven coord-a (multi-module subproject deps).
    implementation(project(":security-lib"))
    implementation(project(":company-observability-starter"))

    // PR_19 C19.X: legacy moduli kao library deps (jar-only, ne bootJar).
    // Kompletna account/card/transaction/transfer/verification logika zivi u istom JVM-u.
    implementation(project(":account-service"))
    implementation(project(":card-service"))
    implementation(project(":transaction-service"))
    implementation(project(":transfer-service"))
    implementation(project(":verification-service"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-web")
    // PR_16 C16.3: dodaje jakarta.validation API + Hibernate Validator za @Valid,
    // @NotBlank, @NotNull, @DecimalMin, @Pattern u DTO-ovima i kontrolerima
    // (margin, internal endpoint-i). Bez ovoga bean validation tihim padom radi
    // samo u okviru @ConfigurationProperties, ne i u @RequestBody.
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // PR_19 C19.X: WebClient u ClearingHouseClient + ostali REST klijentima.
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

    implementation("me.paulschwarz:springboot3-dotenv:5.0.1")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")  // PAN encryption (card paket)

    implementation("com.fasterxml.jackson.core:jackson-core:2.21.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.21")

    // PR_14 C14.4: service-to-service JWT za pozive ka account-service
    // (RestClient interceptor potpisuje sluzbeni token role=SERVICE).
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")

    // PR_14 C14.4 + C14.6: ShedLock + Resilience4j koje vec koriste
    // ExternalTransferRetryScheduler i ClearingHouseClient (PR_11 C11.15, PR_13 C13.1).
    // Ranije su bile tranzitivno povucene preko security-lib-a, ali su ostale eksplicitno
    // navedene radi cistog dependency grafa.
    implementation("net.javacrumbs.shedlock:shedlock-spring:6.0.2")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.0.2")
    // resilience4j-spring-boot3 vec donosi starter-aop tranzitivno;
    // eksplicitan starter-aop bez verzije ne moze se resolve-ovati pod SB 4.0.3 plugin-om.
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")

    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    runtimeOnly("org.postgresql:postgresql")

    // PR_16 C16.1: phantom test starter-i uklonjeni (ne postoje u Maven Central).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

openApi {
    apiDocsUrl.set("http://localhost:8084/v3/api-docs.yaml")
    outputDir.set(file("docs"))
    outputFileName.set("openapi.yml")
    waitTimeInSeconds.set(30)
}
