package com.pinbot.botprime.repository;


import com.pinbot.botprime.persistence.IndicatorValueEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IndicatorValueRepository
        extends JpaRepository<IndicatorValueEntity, Void>,
        IndicatorValueRepositoryCustom {
    // УДАЛИТЬ старый @Modifying @Query upsertBatch(...) — он и вызывает переполнение параметров
}