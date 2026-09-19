package com.bookmyshow.catalog.screen;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScreenRepository extends JpaRepository<Screen, Long> {
    Page<Screen> findByTheaterId(Long theaterId, Pageable pageable);
}
