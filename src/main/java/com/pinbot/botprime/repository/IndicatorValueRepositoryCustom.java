package com.pinbot.botprime.repository;

import com.pinbot.botprime.persistence.IndicatorValueEntity;
import java.util.List;

public interface IndicatorValueRepositoryCustom {
    void upsertBatchArrays(List<IndicatorValueEntity> rows);
}