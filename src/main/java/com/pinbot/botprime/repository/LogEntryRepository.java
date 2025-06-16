package com.pinbot.botprime.repository;

import com.pinbot.botprime.model.LogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LogEntryRepository extends JpaRepository<LogEntry, Long> {
}
