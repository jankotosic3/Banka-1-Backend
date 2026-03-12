package app.repository;

import app.entities.NotificationDelivery;
import app.entities.NotificationDeliveryStatus;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link NotificationDelivery} records.
 */
@Repository
public interface NotificationDeliveryRepository
        extends JpaRepository<@NonNull NotificationDelivery, @NonNull String> {
    /**
     * Finds a delivery by its internal UUID identifier.
     *
     * @param deliveryId internal delivery identifier
     * @return matching delivery if it exists
     */
    Optional<@NonNull NotificationDelivery> findByDeliveryId(String deliveryId);

    /**
     * Returns all deliveries currently in the given status.
     *
     * @param status lifecycle status filter
     * @return matching deliveries
     */
    List<@NonNull NotificationDelivery> findAllByStatus(NotificationDeliveryStatus status);

    /**
     * Returns one page of deliveries currently in the given status.
     *
     * @param status lifecycle status filter
     * @param pageable requested page configuration
     * @return matching delivery page
     */
    Page<@NonNull NotificationDelivery> findAllByStatus(
            NotificationDeliveryStatus status,
            Pageable pageable
    );

}
