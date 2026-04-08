package com.banka1.order.repository;

import com.banka1.order.entity.ActuaryInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link ActuaryInfo} entities.
 * Provides lookup by employee ID, which is the natural key for actuary records.
 */
@Repository
public interface ActuaryInfoRepository extends JpaRepository<ActuaryInfo, Long> {

    /**
     * Finds the actuary record for a given employee.
     *
     * @param employeeId the employee's identifier from employee-service
     * @return the actuary info if it exists
     */
    Optional<ActuaryInfo> findByEmployeeId(Long employeeId);

    /**
     * Loads actuary rows for a set of employee identifiers in one repository call.
     *
     * @param employeeIds employee identifiers from employee-service
     * @return matching actuary info rows
     */
    List<ActuaryInfo> findByEmployeeIdIn(Collection<Long> employeeIds);
}
