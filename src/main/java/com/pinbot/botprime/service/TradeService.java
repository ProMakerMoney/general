package com.pinbot.botprime.service;

import com.pinbot.botprime.model.Trade;
import com.pinbot.botprime.repository.TradeRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TradeService {

    private final TradeRepository tradeRepository;
    private final LogService      logService;   // сервис логирования, уже добавлен ранее

    /** Создаём сделку + фиксируем событие в журнале */
    @Transactional
    public Trade save(Trade trade) {
        Trade saved = tradeRepository.save(trade);

        logService.log(
                "INFO",
                "Создана сделка " + saved.getId() + " (" + saved.getSymbol() + ")",
                "TRADE"
        );

        return saved;
    }

    /** Чтение всех сделок (только-чтение для оптимизации транзакции) */
    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Trade> findAll() {
        return tradeRepository.findAll();
    }
}
