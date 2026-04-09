package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and querying {@link Listing} entities.
 */
public interface ListingRepository extends JpaRepository<Listing, Long> {

    /**
     * Finds one listing by its category and underlying security identifier.
     *
     * @param listingType listing category
     * @param securityId underlying security identifier
     * @return matching listing if present
     */
    Optional<Listing> findByListingTypeAndSecurityId(ListingType listingType, Long securityId);

    /**
     * Returns only the listing type for a given listing id without loading the full entity.
     * Used for lightweight access-control pre-checks before building the full response.
     *
     * @param id listing identifier
     * @return listing type if the listing exists
     */
    @Query("SELECT l.listingType FROM Listing l WHERE l.id = :id")
    Optional<ListingType> findListingTypeById(@Param("id") Long id);

    /**
     * Loads all listings of one category sorted by ticker, eagerly fetching the associated
     * stock exchange to avoid N+1 lazy-load queries during in-memory filtering.
     *
     * <p>The {@code @EntityGraph} causes a single JOIN FETCH so that {@code matchesExchange}
     * filtering in the service layer does not trigger a separate SELECT per row.
     *
     * @param listingType listing category
     * @return listings of the requested category
     */
    @EntityGraph(attributePaths = {"stockExchange"})
    List<Listing> findAllByListingTypeOrderByTickerAsc(ListingType listingType);

    /**
     * Loads all listings quoted on one stock exchange ordered by ticker.
     *
     * @param stockExchangeId stock exchange id
     * @return listings quoted on the provided exchange
     */
    List<Listing> findAllByStockExchangeIdOrderByTickerAsc(Long stockExchangeId);
}
