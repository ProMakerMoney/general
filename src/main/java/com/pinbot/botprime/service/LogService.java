package com.pinbot.botprime.service;

import com.pinbot.botprime.model.LogEntry;
import com.pinbot.botprime.repository.LogEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LogService {

    private final LogEntryRepository logRepository;

    public void log(String level, String message, String context) {
        LogEntry entry = new LogEntry();
        entry.setTimestamp(LocalDateTime.now());
        entry.setLevel(level);
        entry.setMessage(message);
        entry.setContext(context);
        logRepository.save(entry);
    }
}
