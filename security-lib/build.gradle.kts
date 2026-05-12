plugins {
	id("java-library")
	id("maven-publish")
	id("io.spring.dependency-management") version "1.1.7"
	checkstyle
}

group = "com.banka1"
version = "0.0.1-SNAPSHOT"
description = "Library for authorizing users of BANKA1 system"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			from(components["java"])
		}
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}
dependencyManagement {
//	imports {
//		mavenBom("org.springframework.boot:spring-boot-dependencies:3.4.3")
//	}
}

dependencies {
	api(platform("org.springframework.boot:spring-boot-dependencies:3.4.3"))
	api("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
	implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:3.4.3")

	// PR_19 C19.X fix: security-lib classes (Resilience4jConfig, ShedLockConfig,
	// AuthorizationHeaderMaskingFilter, JmbgConverter) referenciraju ove API-je.
	// Pre PR_19 Dockerfile-i nisu kompilirali security-lib (postojeci bin je bio
	// na disku pre nego sto je multi-module build introduktovan); sada se kompilira
	// pa eksplicitne dep-je moramo da deklariramo.
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
	implementation("net.javacrumbs.shedlock:shedlock-spring:6.0.2")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.0.2")
	// PR_19 C19.X: jakarta.persistence (JmbgConverter @Converter + AttributeConverter).
	implementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
	compileOnly("org.projectlombok:lombok:1.18.34")
	annotationProcessor("org.projectlombok:lombok:1.18.34")

	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-web")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

checkstyle {
	configFile = rootProject.file("checkstyle.xml")
}

tasks.withType<org.gradle.api.plugins.quality.Checkstyle>().configureEach {
	ignoreFailures = true
}

tasks.withType<Test> {
	useJUnitPlatform()
	classpath += sourceSets.main.get().output
}

