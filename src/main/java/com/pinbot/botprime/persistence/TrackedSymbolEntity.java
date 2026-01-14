package com.pinbot.botprime.persistence;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
        name = "tracked_symbol",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_tracked_symbol_symbol_timeframe", columnNames = {"symbol", "timeframe"})
        }
)
public class TrackedSymbolEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "symbol", nullable = false, length = 50)
    private String symbol;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /** Stored as Bybit API value, e.g. "30", "D" */
    @Column(name = "timeframe", nullable = false, length = 20)
    private String timeframe;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
