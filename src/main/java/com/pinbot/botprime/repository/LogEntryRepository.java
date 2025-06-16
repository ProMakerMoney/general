package com.pinbot.botprime.repository;

import com.pinbot.botprime.model.LogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface LogEntryRepository
        extends JpaRepository<LogEntry, Long>,
        JpaSpecificationExecutor<LogEntry> {
}
