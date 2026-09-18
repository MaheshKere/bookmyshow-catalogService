package com.bookmyshow.catalog.movie;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "movies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Movie {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 4000)
    private String description;
    @Column(nullable = false, length = 50)
    private String language;
    @Column(nullable = false, length = 100)
    private String genre;
    @Column(nullable = false)
    private int durationMinutes;
    @Column(nullable = false)
    private LocalDate releaseDate;
    @Column(nullable = false)
    private boolean active;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public Movie(String title, String description, String language, String genre,
                 int durationMinutes, LocalDate releaseDate, boolean active) {
        update(title, description, language, genre, durationMinutes, releaseDate, active);
    }

    public void update(String title, String description, String language, String genre,
                       int durationMinutes, LocalDate releaseDate, boolean active) {
        this.title = title;
        this.description = description;
        this.language = language;
        this.genre = genre;
        this.durationMinutes = durationMinutes;
        this.releaseDate = releaseDate;
        this.active = active;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
