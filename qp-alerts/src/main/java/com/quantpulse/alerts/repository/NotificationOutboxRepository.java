package com.quantpulse.alerts.repository;

import com.quantpulse.alerts.domain.NotificationOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutbox, Long> {

    @Query("select n from NotificationOutbox n where n.deliveredAt is null order by n.createdAt asc")
    List<NotificationOutbox> findPending(Pageable pageable);

    @Query("select count(n) from NotificationOutbox n where n.deliveredAt is null")
    long countPending();
}
