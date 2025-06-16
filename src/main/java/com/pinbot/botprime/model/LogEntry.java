package com.pinbot.botprime.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "log_entries")
@Data
public class LogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime timestamp;

    private String level; // INFO, WARN, ERROR

    private String message;

    private String context; // Например: TRADE, STRATEGY, SYSTEM
}
