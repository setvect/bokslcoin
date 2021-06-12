package com.setvect.bokslcoin.autotrading.algorithm;

import com.setvect.bokslcoin.autotrading.util.DateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 매매시 발생하는 이벤트
 */
@Service
@Slf4j
public class BasicTradeEvent implements TradeEvent {
    @Override
    public void newPeriod(ZonedDateTime startUtc) {
        LocalDateTime localDateTime = startUtc.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        log.info("새로운 매매주기: {}", DateUtil.formatDateTime(localDateTime));
    }

    @Override
    public void bid(String market, double tradePrice, double bidPrice) {
        log.info(String.format("★★★ 시장가 매수, 코인: %s, 현재가: %,.0f, 매수 금액: %,.0f,", market, tradePrice, bidPrice));
    }

    @Override
    public void ask(String market, double balance, double tradePrice, VbsStopService.AskReason reason) {
        log.info(String.format("★★★ 시장가 매도, 코인: %s 보유량: %,.0f, 현재가: %,.0f, 예상 금액: %,.0f, 매도이유: %s", market, balance, tradePrice, balance * tradePrice, reason));
    }

    @Override
    public void registerTargetPrice(double targetPrice) {
        log.info(String.format("매수 목표가: %,.0f ", targetPrice));
    }
}
