package com.banka1.card_service.rabbitMQ;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for RabbitMQ infrastructure.
 * Declares the queue, topic exchange, binding, and JSON converter used to publish
 * card-related notification events to the notification service.
 *
 * All connection parameters and resource names are resolved from environment
 * variables via {@code application.properties} so that no credentials are hardcoded.
 */
@Configuration
public class RabbitConfig {

    /** Name of the RabbitMQ queue that receives notification messages. */
    @Value("${rabbitmq.queue}")
    private String queueName;

    /** Name of the topic exchange to which messages are published. */
    @Value("${rabbitmq.exchange}")
    private String exchangeName;

    /** Routing key that binds the exchange to the queue. */
    @Value("${rabbitmq.routing-key}")
    private String routingKey;

    /** Hostname of the RabbitMQ server. */
    @Value("${spring.rabbitmq.host}")
    private String rabbitHost;

    /** Port of the RabbitMQ server. */
    @Value("${spring.rabbitmq.port}")
    private int rabbitPort;

    /** Username for authenticating with the RabbitMQ server. */
    @Value("${spring.rabbitmq.username}")
    private String rabbitUsername;

    /** Password for authenticating with the RabbitMQ server. */
    @Value("${spring.rabbitmq.password}")
    private String rabbitPassword;

    /**
     * Creates the RabbitMQ connection factory using the configured host and credentials.
     *
     * @return configured connection factory
     */
    @Bean
    public ConnectionFactory connectionFactory() {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(rabbitHost);
        connectionFactory.setPort(rabbitPort);
        connectionFactory.setUsername(rabbitUsername);
        connectionFactory.setPassword(rabbitPassword);
        return connectionFactory;
    }

    /**
     * Creates the {@link RabbitTemplate} with the JSON message converter wired in,
     * so that all outbound messages are serialized as JSON.
     *
     * @param connectionFactory AMQP connection factory
     * @param jacksonMessageConverter JSON message converter
     * @return configured RabbitMQ template
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jacksonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jacksonMessageConverter);
        return template;
    }

    /**
     * Registers a Jackson-based message converter so that messages are serialized as JSON,
     * matching the format expected by the notification service.
     *
     * @return JSON message converter
     */
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    /**
     * Declares a durable notification queue.
     * Durable queues survive broker restarts, preventing message loss.
     *
     * @return durable notification queue
     */
    @Bean
    public Queue queue() {
        return new Queue(queueName, true);
    }

    /**
     * Declares the topic exchange used for routing notification messages.
     *
     * @return topic exchange
     */
    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(exchangeName);
    }

    /**
     * Binds the notification queue to the topic exchange with the configured routing key.
     *
     * @param queue notification queue
     * @param topicExchange topic exchange
     * @return queue-to-exchange binding
     */
    @Bean
    public Binding binding(Queue queue, TopicExchange topicExchange) {
        return BindingBuilder.bind(queue).to(topicExchange).with(routingKey);
    }
}
