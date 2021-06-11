package com.setvect.bokslcoin.autotrading.algorithm;

import lombok.extern.slf4j.Slf4j;

/**
 * 매매시 발생하는 이벤트
 */
@Slf4j
public class BasicTradeEvent implements TradeEvent {
    @Override
    public void bid(String market, double currentPrice, double askPrice) {
        log.info(String.format("★★★ 시장가 매수, 코인: %s, 현재가: %,f, 매수 금액: %,f,", market, currentPrice, askPrice));
    }

    @Override
    public void ask(String market, double balance, double currentPrice, VbsStopService.AskReason reason) {
        log.info(String.format("★★★ 시장가 매도, 코인: %s 보유량: %,f, 현재가: %,f, 예상 금액: %,f, 매도이유: %s", market, balance, currentPrice, balance * currentPrice, reason));
    }

    @Override
    public void registerTargetPrice(double targetPrice) {
        log.info(String.format("매수 목표가: %,.2f ", targetPrice));
    }
}