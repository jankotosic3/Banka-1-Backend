package com.banka1.bankingcore.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RabbitConfig {

    @Bean
    public MessageConverter bankingCoreJacksonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    @Primary
    public RabbitTemplate bankingCoreRabbitTemplate(ConnectionFactory connectionFactory,
                                                    MessageConverter bankingCoreJacksonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(bankingCoreJacksonMessageConverter);
        return template;
    }
}
