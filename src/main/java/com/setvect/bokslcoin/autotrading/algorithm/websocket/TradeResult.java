package com.setvect.bokslcoin.autotrading.algorithm.websocket;

import lombok.Getter;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Getter
@ToString
public class TradeResult {
    private String type;
    private String code;
    private double tradePrice;
    private double tradeVolume;
    private long timestamp;
    private LocalDate tradeDate;
    private LocalTime tradeTime;
    private double prevClosingPrice;

    public LocalDateTime getTradeDateTimeUtc() {
        return LocalDateTime.of(tradeDate, tradeTime);
    }

    public LocalDateTime getTradeDateTimeKst() {
        return getTradeDateTimeUtc().plusHours(9);
    }

    /**
     * @return 거래량 * 금액
     */
    public double getTotalPrice() {
        return tradePrice * tradeVolume;
    }
}

