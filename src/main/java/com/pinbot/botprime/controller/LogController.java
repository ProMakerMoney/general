package com.pinbot.botprime.controller;

import com.pinbot.botprime.model.LogEntry;
import com.pinbot.botprime.repository.LogEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {

    private final LogEntryRepository repo;

    @GetMapping
    public Page<LogEntry> list(
            @RequestParam(defaultValue = "0")  int    page,
            @RequestParam(defaultValue = "20") int    size,
            @RequestParam(required = false)    String level,
            @RequestParam(required = false)    String context
    ) {

        Specification<LogEntry> spec = (root, q, cb) -> {
            var p = cb.conjunction();
            if (level   != null) p = cb.and(p, cb.equal(root.get("level"),   level));
            if (context != null) p = cb.and(p, cb.equal(root.get("context"), context));
            return p;
        };

        return repo.findAll(spec, PageRequest.of(page, size));
    }
}
