package com.shop.inventory.infrastructure.persistence;

import com.shop.inventory.domain.model.Reservation;
import com.shop.inventory.domain.repository.ReservationRepository;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data sinh implementation; đồng thời là implementation của port domain. */
public interface ReservationJpaRepository extends JpaRepository<Reservation, Long>, ReservationRepository {
}
