package com.bookmyshow.catalog.show;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Optional;

public interface ShowRepository extends JpaRepository<Show, Long> {
    @Override
    @EntityGraph(attributePaths = {"movie", "screen", "screen.theater"})
    Optional<Show> findById(Long id);

    // Fetch only to-one associations so SQL pagination remains safe.
    // Explicit string casts keep an absent city typed correctly for PostgreSQL lower().
    @EntityGraph(attributePaths = {"movie", "screen", "screen.theater"})
    @Query("""
            select s from Show s
            where (:movieId is null or s.movie.id = :movieId)
              and (:theaterId is null or s.screen.theater.id = :theaterId)
              and (cast(:city as string) is null or lower(s.screen.theater.city) = lower(cast(:city as string)))
              and s.startTime >= :from and s.startTime < :until
            """)
    Page<Show> search(@Param("movieId") Long movieId, @Param("theaterId") Long theaterId,
                      @Param("city") String city, @Param("from") Instant from,
                      @Param("until") Instant until, Pageable pageable);
}
