package com.setvect.bokslcoin.autotrading.algorithm.mabs;

import com.setvect.bokslcoin.autotrading.algorithm.AskReason;
import com.setvect.bokslcoin.autotrading.algorithm.CoinTrading;
import com.setvect.bokslcoin.autotrading.algorithm.CommonTradeHelper;
import com.setvect.bokslcoin.autotrading.algorithm.TradeEvent;
import com.setvect.bokslcoin.autotrading.algorithm.TradePeriod;
import com.setvect.bokslcoin.autotrading.exchange.AccountService;
import com.setvect.bokslcoin.autotrading.exchange.OrderService;
import com.setvect.bokslcoin.autotrading.model.Account;
import com.setvect.bokslcoin.autotrading.model.Candle;
import com.setvect.bokslcoin.autotrading.model.CandleMinute;
import com.setvect.bokslcoin.autotrading.quotation.CandleService;
import com.setvect.bokslcoin.autotrading.slack.SlackMessageService;
import com.setvect.bokslcoin.autotrading.util.DateUtil;
import com.setvect.bokslcoin.autotrading.util.MathUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 이평선 돌파 전략 + 멀티 코인
 * 코인별 동일한 현금 비율로 매매를 수행한다.
 */
@Service("mabsMulti")
@Slf4j
@RequiredArgsConstructor
public class MabsMultiService implements CoinTrading {
    private final AccountService accountService;
    private final CandleService candleService;
    private final TradeEvent tradeEvent;
    private final OrderService orderService;
    private final SlackMessageService slackMessageService;

    /**
     * 매수, 매도 대상 코인
     */
    @Value("${com.setvect.bokslcoin.autotrading.algorithm.mabsMulti.markets}")
    private List<String> markets;

    /**
     * 총 현금을 기준으로 투자 비율
     * 1은 100%, 0.5은 50% 투자
     */
    @Value("${com.setvect.bokslcoin.autotrading.algorithm.mabsMulti.investRatio}")
    private double investRatio;

    /**
     * 매매 주기
     */
    @Value("${com.setvect.bokslcoin.autotrading.algorithm.mabsMulti.tradePeriod}")
    private TradePeriod tradePeriod;


    /**
     * 상승 매수률
     */
    @Value("${com.setvect.bokslcoin.autotrading.algorithm.mabsMulti.upBuyRate}")
    private double upBuyRate;

    /**
     * 하락 매도률
     */
    @Value("${com.setvect.bokslcoin.autotrading.algorithm.mabsMulti.downSellRate}")
    private double downSellRate;

    /**
     * 단기 이동평균 기간
     */
    @Value("${com.setvect.bokslcoin.autotrading.algorithm.mabsMulti.shortPeriod}")
    private int shortPeriod;

    /**
     * 장기 이동평균 기간
     */
    @Value("${com.setvect.bokslcoin.autotrading.algorithm.mabsMulti.longPeriod}")
    private int longPeriod;

    /**
     * 슬랙 메시지 발송 시간
     */
    @Value("${com.setvect.bokslcoin.autotrading.algorithm.mabsMulti.slackTime}")
    private String slackTime;

    /**
     * 슬랙 메시지 발송 시간
     */
    @Value("${com.setvect.bokslcoin.autotrading.algorithm.mabsMulti.newMasBuy}")
    private boolean newMasBuy;

    /**
     * 해당 기간에 매매 여부 완료 여부
     * value: 코인 예) KRW-BTC, KRW-ETH, ...
     */
    private Set<String> tradeCompleteOfPeriod = new HashSet<>();

    /**
     * 코인별 일일 메시지 전달 여부
     * value: 코인 예) KRW-BTC, KRW-ETH, ...
     */
    private Set<String> messageSend = new HashSet<>();

    private int periodIdx = -1;

    /**
     * 매수 이후 고점 수익률
     */
    @Getter
    private Map<String, Double> highYield = new HashMap<>();

    @Override
    public void apply() {
        // 아무 코인이나 분봉으로 조회하여 매매 주기가 변경되었는지 확인
        CandleMinute candleCheck = candleService.getMinute(1, markets.get(0));
        ZonedDateTime nowUtcZoned = candleCheck.getCandleDateTimeUtc().atZone(ZoneId.of("UTC"));
        LocalDateTime nowUtc = nowUtcZoned.toLocalDateTime();
        int currentPeriod = getCurrentPeriod(nowUtc);

        // 새로운 날짜면 매매 다시 초기화
        if (periodIdx != currentPeriod) {
            tradeEvent.newPeriod(candleCheck);
            periodIdx = currentPeriod;
            tradeCompleteOfPeriod.clear();
            messageSend.clear();
        }


        Map<String, Account> coinAccount = accountService.getMyAccountBalance();
        BigDecimal cash = BigDecimal.valueOf(Double.valueOf(coinAccount.get("KRW").getBalance()));

        // 이미 매수한 코인 갯수
        int buyCount = (int) markets.stream().filter(p -> coinAccount.get(p) != null).count();
        int rate = markets.size() - buyCount;

        double buyCash = 0;
        if (rate > 0) {
            buyCash = (cash.doubleValue() * investRatio) / rate;
        }

        for (String market : markets) {
            Account account = coinAccount.get(market);
            List<Candle> candleList = CommonTradeHelper.getCandles(candleService, market, tradePeriod, longPeriod + 1);

            coinLog(candleList);

            if (candleList.size() < longPeriod + 1) {
                log.warn("[{}] 이동평균계산을 위한 시세 데이터가 부족합니다.", market);
                continue;
            }

            if (account == null && !tradeCompleteOfPeriod.contains(market)) {
                buyCheck(buyCash, candleList);
            } else {
                sellCheck(account, candleList);
            }
        }
    }

    private void coinLog(List<Candle> candleList) {
        double maShort = CommonTradeHelper.getMa(candleList, shortPeriod);
        double maLong = CommonTradeHelper.getMa(candleList, longPeriod);
        Candle candle = candleList.get(0);
        log.debug(String.format("[%s] KST:%s, UTC: %s, 매매기준 주기: %s, 현재가: %,.2f, MA_%d: %,.2f, MA_%d: %,.2f, 단기-장기 차이: %,.2f(%.2f%%)",
                candle.getMarket(),
                DateUtil.formatDateTime(candle.getCandleDateTimeKst()),
                DateUtil.formatDateTime(candle.getCandleDateTimeUtc()),
                tradePeriod,
                candle.getTradePrice(),
                shortPeriod, maShort,
                longPeriod, maLong,
                maShort - maLong, MathUtil.getYield(maShort, maLong) * 100));
    }

    /**
     * 조건이 만족하면 매수 수행
     *
     * @param cash       매수에 사용될 현금
     * @param candleList
     */
    private void buyCheck(double cash, List<Candle> candleList) {
        double maShort = CommonTradeHelper.getMa(candleList, shortPeriod);
        double maLong = CommonTradeHelper.getMa(candleList, longPeriod);

        Candle candle = candleList.get(0);
        double buyTargetPrice = maLong + maLong * upBuyRate;
        String market = candle.getMarket();

        //(장기이평 + 장기이평 * 상승매수률) <= 단기이평
        boolean isBuy = buyTargetPrice <= maShort;
        String message = String.format("[%s] 현재 가격:%,.2f\t매수 조건: 장기이평(%d) + 장기이평(%d) * 상승매수률(%.2f%%) <= 단기이평(%d), %,.2f <= %,.2f ---> %s",
                market,
                candle.getTradePrice(),
                longPeriod,
                longPeriod,
                upBuyRate * 100,
                shortPeriod,
                buyTargetPrice,
                maShort,
                isBuy);

        if (!messageSend.contains(market)) {
            sendSlack(market, message, candle.getCandleDateTimeKst());
        }
        log.debug(message);

        if (isBuy) {
            // 직전 이동평균을 감지해 새롭게 돌파 했을 때만 매수
            boolean isBeforeBuy = isBeforeBuy(candleList);
            if (isBeforeBuy && newMasBuy) {
                log.info("[{}] 매수 안함. 새롭게 이동평균을 돌파 할때만 매수합니다.", candle.getMarket());
                return;
            }
            doBid(market, candle.getTradePrice(), cash);
        }
    }

    /**
     * 조건이 만족하면 매도
     *
     * @param account
     */
    private void sellCheck(Account account, List<Candle> candleList) {
        String market = account.getMarket();

        Candle candle = candleList.get(0);
        double rate = getYield(candle, account);
        highYield.put(market, Math.max(highYield.getOrDefault(market, 0.0), rate));

        double maShort = CommonTradeHelper.getMa(candleList, shortPeriod);
        double maLong = CommonTradeHelper.getMa(candleList, longPeriod);
        double sellTargetPrice = maShort + maShort * downSellRate;

        String message1 = String.format("[%s] 현재 가격:%,.2f\t매입단가: %,.2f, 투자금: %,.0f, 수익율: %.2f%%, 최고 수익률: %.2f%%",
                candle.getMarket(),
                candle.getTradePrice(),
                Double.valueOf(account.getAvgBuyPrice()),
                account.getInvestCash(),
                rate * 100,
                highYield.get(market) * 100
        );
        log.debug(message1);

        // 장기이평 >= (단기이평 + 단기이평 * 하락매도률)
        boolean isSell = maLong >= sellTargetPrice;
        String message2 = String.format("[%s] 매도 조건: 장기이평(%d) >= 단기이평(%d) + 단기이평(%d) * 하락매도률(%.2f%%), %,.2f >= %,.2f ---> %s",
                candle.getMarket(),
                longPeriod,
                shortPeriod,
                shortPeriod,
                downSellRate * 100,
                maLong,
                sellTargetPrice,
                isSell);
        log.debug(message2);

        sendSlack(market, message1 + "\n" + message2, candle.getCandleDateTimeKst());

        if (isSell) {
            doAsk(market, candle.getTradePrice(), Double.valueOf(account.getBalance()), AskReason.MA_DOWN);
        }
    }

    private boolean isEnough(double maShort, double maLong) {
        return maShort == 0 || maLong == 0;
    }


    private int getCurrentPeriod(LocalDateTime nowUtc) {
        int dayHourMinuteSum = nowUtc.getDayOfMonth() * 1440 + nowUtc.getHour() * 60 + nowUtc.getMinute();
        int currentPeriod = dayHourMinuteSum / tradePeriod.getTotal();
        return currentPeriod;
    }


    private void sendSlack(String market, String message, LocalDateTime kst) {
        LocalTime time = DateUtil.getLocalTime(slackTime, "HH:mm");

        // 정해진 시간에 메시지 보냄
        if (time.getHour() == kst.getHour() && time.getMinute() == kst.getMinute()) {
            slackMessageService.sendMessage(message);
            messageSend.add(market);
        }
    }

    private void doBid(String market, double tradePrice, double bidPrice) {
//        orderService.callOrderBidByMarket(market, ApplicationUtil.toNumberString(bidPrice));
        tradeEvent.bid(market, tradePrice, bidPrice);
    }

    private void doAsk(String market, double currentPrice, double balance, AskReason maDown) {
//        orderService.callOrderAskByMarket(market, ApplicationUtil.toNumberString(balance));
        tradeEvent.ask(market, balance, currentPrice, maDown);
        highYield.put(market, 0.0);
        tradeCompleteOfPeriod.add(market);
    }


    /**
     * @param candleList
     * @return 이동 평균에서 직전 매수 조건 이면 true, 아니면 false
     */
    private boolean isBeforeBuy(List<Candle> candleList) {
        // 한단계전에 매수 조건이였는지 확인
        List<Candle> beforeCandleList = candleList.subList(1, candleList.size());
        double maShortBefore = CommonTradeHelper.getMa(beforeCandleList, shortPeriod);
        double maLongBefore = CommonTradeHelper.getMa(beforeCandleList, longPeriod);
        double buyTargetPrice = maLongBefore + maLongBefore * upBuyRate;
        //(장기이평 + 장기이평 * 상승매수률) <= 단기이평
        boolean isBuy = buyTargetPrice <= maShortBefore;
        return isBuy;
    }


    private double getYield(Candle candle, Account account) {
        double avgPrice = Double.valueOf(account.getAvgBuyPrice());
        double diff = candle.getTradePrice() - avgPrice;
        return diff / avgPrice;
    }
}
