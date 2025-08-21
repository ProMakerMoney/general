package com.pinbot.botprime.repository;

import com.pinbot.botprime.model.IndicatorValue;
import com.pinbot.botprime.model.IndicatorValueId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndicatorValueRepository extends JpaRepository<IndicatorValue, IndicatorValueId> {
}
