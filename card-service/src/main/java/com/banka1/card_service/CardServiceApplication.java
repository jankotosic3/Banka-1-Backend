package com.banka1.card_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Card Service microservice.
 */
@SpringBootApplication
public class CardServiceApplication {

	/**
	 * Starts the Spring Boot application.
	 *
	 * @param args command line arguments
	 */
	public static void main(String[] args) {
		SpringApplication.run(CardServiceApplication.class, args);
	}
}
