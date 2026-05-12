package com.banka1.security.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * PR_06 C6.1: shared ShedLock konfiguracija.
 *
 * <p>EnableSchedulerLock sa default lock-at-most-for=10m fallback. Servisi
 * pojedinacno overrride-uju per-task na @SchedulerLock anotaciji.
 *
 * <p>Auto-uvozi se u svaki Spring Boot servis kroz security-lib module dependency.
 * ShedLock se aktivira samo ako je classpath ima net.javacrumbs.shedlock klase,
 * pa servisi koji nemaju scheduled task-ove ne placaju nikakvu cenu.
 */
@Configuration
@ConditionalOnClass(name = "net.javacrumbs.shedlock.core.LockProvider")
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()  // koristi DB clock umesto JVM-a (sprecava clock-skew probleme)
                        .build()
        );
    }
}
