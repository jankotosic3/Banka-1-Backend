package app.entities;

/**
 * Supported employee notification events.
 *
 * <p>These values come from RabbitMQ messages and decide
 * which email subject and template will be used.
 */
public enum NotificationType {
    /**
     * Notification sent when a new employee account is created.
     */
    EMPLOYEE_CREATED,
    /**
     * Notification sent when an employee requests a password reset.
     */
    EMPLOYEE_PASSWORD_RESET,
    /**
     * Notification sent when an employee account is deactivated.
     */
    EMPLOYEE_ACCOUNT_DEACTIVATED,
    /**
     * Notification sent when a new client account is created (activation email).
     */
    CLIENT_CREATED,
    /**
     * Notification sent when a client requests a password reset.
     */
    CLIENT_PASSWORD_RESET,
    /**
     * Fallback value used to persist unsupported or invalid incoming messages.
     */
    UNKNOWN;

    /** RabbitMQ routing key for employee creation events. */
    public static final String ROUTING_KEY_EMPLOYEE_CREATED = "employee.created";
    /** RabbitMQ routing key for password reset events. */
    public static final String ROUTING_KEY_EMPLOYEE_PASSWORD_RESET = "employee.password_reset";
    /** RabbitMQ routing key for account deactivation events. */
    public static final String ROUTING_KEY_EMPLOYEE_ACCOUNT_DEACTIVATED = "employee.account_deactivated";
    /** RabbitMQ routing key for client creation/activation events. */
    public static final String ROUTING_KEY_CLIENT_CREATED = "client.created";
    /** RabbitMQ routing key for client password reset events. */
    public static final String ROUTING_KEY_CLIENT_PASSWORD_RESET = "client.password_reset";
}
