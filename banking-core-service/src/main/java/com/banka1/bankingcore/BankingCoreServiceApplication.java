package com.banka1.bankingcore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PR_19 C19.X: konsolidovani banking-core-service — ucita 5 legacy modula
 * (account, card, transaction, transfer, verification) kao project() deps i
 * scan-uje sve {@code com.banka1} pakete tako da REST controlleri zive u istoj
 * JVM instanci.
 *
 * <p>FQN bean name generator izbegava simple-name conflict-e izmedju duplikata
 * (npr. AuthController, EmployeeController u razlicitim modulima).
 */
@SpringBootApplication(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
@ConfigurationPropertiesScan(basePackages = "com.banka1")
@EnableScheduling
@ComponentScan(
        basePackages = {"com.banka1"},
        nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class,
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.AccountServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.CardServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.TransactionServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.TransferServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.VerificationServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.UserServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.EmployeeServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.ClientServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.MarketServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.StockServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.ExchangeServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.TradingServiceApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.OrderServiceApplication")
        }
)
@EntityScan(basePackages = {
        "com.banka1.bankingcore",
        "com.banka1.account_service",
        "com.banka1.card_service",
        "com.banka1.transaction_service",
        "com.banka1.transfer",
        "com.banka1.verificationService"
})
@EnableJpaRepositories(basePackages = {
        "com.banka1.bankingcore",
        "com.banka1.account_service",
        "com.banka1.card_service",
        "com.banka1.transaction_service",
        "com.banka1.transfer",
        "com.banka1.verificationService"
})
public class BankingCoreServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankingCoreServiceApplication.class, args);
    }
}
