package com.setvect.bokslcoin.autotrading.backtest.mabs.analysis;

import com.setvect.bokslcoin.autotrading.algorithm.common.TradeCommonService;
import com.setvect.bokslcoin.autotrading.algorithm.mabs.MabsMultiProperties;
import com.setvect.bokslcoin.autotrading.algorithm.websocket.TradeResult;
import com.setvect.bokslcoin.autotrading.backtest.common.BacktestHelperComponent;
import com.setvect.bokslcoin.autotrading.backtest.entity.MabsConditionEntity;
import com.setvect.bokslcoin.autotrading.backtest.entity.MabsTradeEntity;
import com.setvect.bokslcoin.autotrading.backtest.entity.PeriodType;
import com.setvect.bokslcoin.autotrading.backtest.mabs.analysis.mock.*;
import com.setvect.bokslcoin.autotrading.backtest.repository.CandleRepository;
import com.setvect.bokslcoin.autotrading.backtest.repository.MabsConditionEntityRepository;
import com.setvect.bokslcoin.autotrading.backtest.repository.MabsTradeEntityQuerydslRepository;
import com.setvect.bokslcoin.autotrading.backtest.repository.MabsTradeEntityRepository;
import com.setvect.bokslcoin.autotrading.model.Account;
import com.setvect.bokslcoin.autotrading.model.Candle;
import com.setvect.bokslcoin.autotrading.model.CandleMinute;
import com.setvect.bokslcoin.autotrading.record.entity.TradeType;
import com.setvect.bokslcoin.autotrading.record.repository.AssetHistoryRepository;
import com.setvect.bokslcoin.autotrading.record.repository.TradeRepository;
import com.setvect.bokslcoin.autotrading.slack.SlackMessageService;
import com.setvect.bokslcoin.autotrading.util.ApplicationUtil;
import com.setvect.bokslcoin.autotrading.util.DateRange;
import com.setvect.bokslcoin.autotrading.util.DateUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles("test")
@Slf4j
public class MabsTradeAnalyzerTest {
    /**
     * 투자금
     */
    public static final double CASH = 10_000_000;
    private final SlackMessageService slackMessageService = new MockSlackMessageService();
    private final MockTradeEvent tradeEvent = new MockTradeEvent(slackMessageService);
    private final MockAccountService accountService = new MockAccountService();
    private final MockOrderService orderService = new MockOrderService(new MockAccessTokenMaker(), new MockConnectionInfo());
    private final MockCandleService candleService = new MockCandleService(new MockConnectionInfo());
    @Autowired
    private MabsConditionEntityRepository mabsConditionEntityRepository;
    @Autowired
    private MabsTradeEntityRepository mabsTradeEntityRepository;
    @Autowired
    private CandleRepository candleRepository;
    @Autowired
    private AssetHistoryRepository assetHistoryRepository;
    @Autowired
    private TradeRepository tradeRepository;
    @Autowired
    private BacktestHelperComponent backtestHelperService;
    @Autowired
    private MakeBacktestReportService makeBacktestReportService;
    @Autowired
    private MabsTradeEntityQuerydslRepository mabsTradeEntityQuerydslRepository;

    private TradeCommonService tradeCommonService;
    private MockMabsMultiService mabsMultiService;

    @Test
    @DisplayName("변동성 돌파 전략 백테스트")
    public void backtest() {
        boolean saveDb = false;
        mabsMultiService = new MockMabsMultiService(tradeCommonService, tradeEvent, new MockMabsMultiProperties());
        tradeCommonService = new MockTradeCommonService(tradeEvent, accountService, orderService, candleService, tradeRepository, slackMessageService, assetHistoryRepository);

        List<MabsConditionEntity> mabsConditionEntities = makeCondition();
//        LocalDateTime baseStart = backtestHelperService.makeBaseStart(market, PeriodType.PERIOD_60, period.getRight() + 1);
        LocalDateTime baseStart = DateUtil.getLocalDateTime("2022-01-10T00:00:00");
        LocalDateTime baseEnd = DateUtil.getLocalDateTime("2022-06-02T23:59:59");
//        LocalDateTime baseStart = DateUtil.getLocalDateTime("2022-05-01T00:00:00");
//        LocalDateTime baseEnd = DateUtil.getLocalDateTime("2022-06-01T00:00:00");

        DateRange range = new DateRange(baseStart, baseEnd);

        try {
            mabsConditionEntities = backtest(mabsConditionEntities, range);
            List<Integer> conditionSeqList = getConditionSeqList(mabsConditionEntities);

            AnalysisMultiCondition analysisMultiCondition = AnalysisMultiCondition.builder()
                    .conditionIdSet(new HashSet<>(conditionSeqList))
                    .range(range)
                    .investRatio(.99)
                    .cash(14_223_714)
                    .feeSell(0.002) // 슬립피지까지 고려해 보수적으로 0.2% 수수료 측정
                    .feeBuy(0.002)
                    .build();

            makeBacktestReportService.makeReport(analysisMultiCondition);
        } finally {
            if (!saveDb && CollectionUtils.isNotEmpty(mabsConditionEntities)) {
                List<Integer> conditionSeqList = getConditionSeqList(mabsConditionEntities);
                // 결과를 삭제함
                // TODO 너무 무식한 방법이다. @Transactional를 사용해야 되는데 사용하면 속도가 매우 느리다. 해결해야됨
                mabsTradeEntityQuerydslRepository.deleteByConditionId(conditionSeqList);
                mabsConditionEntityRepository.deleteAll(mabsConditionEntities);
            }
        }
    }

    @Test
    @DisplayName("기 매매 결과에서 추가된 시세 데이터에 대한 증분 수행")
    public void backtestIncremental() {
        List<Integer> conditionSeqList = Arrays.asList(
                27288611, // KRW-BTC(2017-10-16)
                27346706, // KRW-ETH(2017-10-10)
                27403421, // KRW-XRP(2017-10-10)
                27458175, // KRW-EOS(2018-03-30)
                27508376, // KRW-ETC(2017-10-09)
                29794493, // KRW-ADA(2017-10-16)
                36879612, // KRW-MANA(2019-04-09)
                36915333, // KRW-BAT(2018-07-30)
                44399001, // KRW-BCH(2017-10-08)
                44544109  // KRW-DOT(2020-10-15)
        );

        // 완전한 거래(매수-매도 쌍)를 만들기 위해 마지막 거래가 매수인경우 거래 내역 삭제
        deleteLastBuy(conditionSeqList);

        List<MabsConditionEntity> conditionEntityList = mabsConditionEntityRepository.findAllById(conditionSeqList);

        for (MabsConditionEntity condition : conditionEntityList) {
            log.info("{}, {}, {}_{} 시작", condition.getMarket(), condition.getTradePeriod(), condition.getLongPeriod(), condition.getShortPeriod());
            List<MabsTradeEntity> tradeList = mabsTradeEntityRepository.findByCondition(condition.getMabsConditionSeq());

            LocalDateTime start = backtestHelperService.makeBaseStart(condition.getMarket(), condition.getTradePeriod(), condition.getLongPeriod() + 1);
            if (!tradeList.isEmpty()) {
                MabsTradeEntity lastTrade = tradeList.get(tradeList.size() - 1);
                checkLastSell(lastTrade);
                start = lastTrade.getTradeTimeKst();
            }
            DateRange range = new DateRange(start, LocalDateTime.now());
            List<MabsMultiBacktestRow> tradeHistory = backtest(condition, range);

            List<MabsTradeEntity> mabsTradeEntities = convert(condition, tradeHistory);
            log.info("[{}] save. range: {}, trade Count: {}", condition.getMarket(), range, mabsTradeEntities.size());
            mabsTradeEntityRepository.saveAll(mabsTradeEntities);
        }
        log.info("끝.");
    }

    private List<MabsConditionEntity> makeCondition() {
        List<String> markets = Arrays.asList("KRW-BTC", "KRW-ETH", "KRW-XRP", "KRW-EOS", "KRW-ETC", "KRW-ADA", "KRW-MANA", "KRW-BAT", "KRW-BCH", "KRW-DOT");
//        List<String> markets = Arrays.asList("KRW-BTC");

        List<Pair<Integer, Integer>> periodList = new ArrayList<>();
        periodList.add(new ImmutablePair<>(13, 64));

        List<MabsConditionEntity> mabsConditionEntities = new ArrayList<>();
        for (Pair<Integer, Integer> period : periodList) {
            for (String market : markets) {
                log.info("{} - {} start", period, market);
                MabsConditionEntity condition = MabsConditionEntity.builder()
                        .market(market)
                        .tradePeriod(PeriodType.PERIOD_60)
                        .upBuyRate(0.01)
                        .downSellRate(0.01)
                        .shortPeriod(period.getLeft())
                        .longPeriod(period.getRight())
                        .loseStopRate(0.5)
                        .comment(null)
                        .build();
                mabsConditionEntities.add(condition);
            }
        }
        return mabsConditionEntities;
    }

    @NotNull
    private List<Integer> getConditionSeqList(List<MabsConditionEntity> mabsConditionEntities) {
        return mabsConditionEntities.stream()
                .map(MabsConditionEntity::getMabsConditionSeq)
                .collect(Collectors.toList());
    }

    public List<MabsConditionEntity> backtest(List<MabsConditionEntity> mabsConditionEntities, DateRange range) {
        for (MabsConditionEntity condition : mabsConditionEntities) {
            mabsConditionEntityRepository.save(condition);
            List<MabsMultiBacktestRow> tradeHistory = backtest(condition, range);

            List<MabsTradeEntity> mabsTradeEntities = convert(condition, tradeHistory);
            mabsTradeEntityRepository.saveAll(mabsTradeEntities);
        }
        return mabsConditionEntities;
    }


    private void checkLastSell(MabsTradeEntity lastTrade) {
        if (lastTrade.getTradeType() == TradeType.BUY) {
            throw new RuntimeException(String.format("마지막 거래가 BUY인 항목이 있음. tradeSeq: %s", lastTrade.getTradeSeq()));
        }
    }

    /**
     * 거래 내역의 마지막이 매수인경우 해당 거래를 삭제
     *
     * @param conditionSeqList 거래 조건 일련번호
     */
    private void deleteLastBuy(List<Integer> conditionSeqList) {
        List<MabsConditionEntity> conditionEntityList = mabsConditionEntityRepository.findAllById(conditionSeqList);

        for (MabsConditionEntity condition : conditionEntityList) {
            List<MabsTradeEntity> tradeList = mabsTradeEntityRepository.findByCondition(condition.getMabsConditionSeq());
            if (tradeList.isEmpty()) {
                continue;
            }
            MabsTradeEntity lastTrade = tradeList.get(tradeList.size() - 1);
            log.info("count: {}, last: {} -> {} ", tradeList.size(), lastTrade.getTradeType(), lastTrade.getTradeTimeKst());
            if (lastTrade.getTradeType() == TradeType.SELL) {
                continue;
            }

            log.info("Delete Last Buy: {} {}", lastTrade.getTradeSeq(), lastTrade.getTradeTimeKst());
            mabsTradeEntityRepository.deleteById(lastTrade.getTradeSeq());
        }
    }

    /**
     * @param condition    거래 조건
     * @param tradeHistory 거래 이력
     * @return 거래 내역 entity 변환
     */
    private List<MabsTradeEntity> convert(MabsConditionEntity condition, List<MabsMultiBacktestRow> tradeHistory) {
        return tradeHistory.stream().map(p -> MabsTradeEntity.builder()
                .mabsConditionEntity(condition)
                .tradeType(p.getTradeEvent())
                .highYield(p.getHighYield())
                .lowYield(p.getLowYield())
                .maShort(p.getMaShort())
                .maLong(p.getMaLong())
                .yield(p.getRealYield())
                .unitPrice(p.getTradeEvent() == TradeType.BUY ? p.getBidPrice() : p.getAskPrice())
                .sellReason(p.getAskReason())
                .tradeTimeKst(p.getCandle().getCandleDateTimeKst())
                .build()).collect(Collectors.toList());
    }

    /**
     * @param condition 조건
     * @param range     백테스트 범위(UTC 기준)
     * @return 거래 내역
     */
    private List<MabsMultiBacktestRow> backtest(MabsConditionEntity condition, DateRange range) {
        // key: market, value: 자산
        Map<String, Account> accountMap = new HashMap<>();

        Account cashAccount = new Account();
        cashAccount.setCurrency("KRW");
        cashAccount.setBalance(ApplicationUtil.toNumberString(CASH));
        accountMap.put("KRW", cashAccount);

        Account acc = new Account();
        String market = condition.getMarket();
        String[] tokens = market.split("-");
        acc.setUnitCurrency(tokens[0]);
        acc.setCurrency(tokens[1]);
        acc.setBalance("0");
        accountMap.put(market, acc);

        // Key: market, value: 시세 정보
        Map<String, CurrentPrice> priceMap = new HashMap<>();

        injectionFieldValue(condition);
        List<MabsMultiBacktestRow> tradeHistory = new ArrayList<>();

        tradeEvent.setPriceMap(priceMap);
        tradeEvent.setAccountMap(accountMap);
        tradeEvent.setTradeHistory(tradeHistory);

        accountService.setAccountMap(accountMap);

        LocalDateTime current = range.getFrom();
        LocalDateTime to = range.getTo();
        CandleDataProvider candleDataProvider = new CandleDataProvider(candleRepository);

        candleService.setCandleDataProvider(candleDataProvider);

        while (current.isBefore(to) || current.equals(to)) {
            candleDataProvider.setCurrentTime(current);
            CandleMinute candle = candleDataProvider.getCurrentCandle(condition.getMarket());
            if (candle == null) {
                current = current.plusMinutes(1);
                continue;
            }

            TradeResult tradeResult = TradeResult.builder()
                    .type("trade")
                    .code(candle.getMarket())
                    .tradePrice(candle.getTradePrice())
                    .tradeDate(candle.getCandleDateTimeUtc().toLocalDate())
                    .tradeTime(candle.getCandleDateTimeUtc().toLocalTime())
                    // 백테스트에서는 의미없는값
                    .timestamp(0L)
                    .prevClosingPrice(0)
                    .tradeVolume(0)
                    .build();

            mabsMultiService.tradeEvent(tradeResult);
            current = current.plusMinutes(1);
        }
        return tradeHistory;
    }

    private void injectionFieldValue(MabsConditionEntity condition) {
        ReflectionTestUtils.setField(tradeCommonService, "coinByCandles", new HashMap<>());
        ReflectionTestUtils.setField(tradeCommonService, "assetHistoryRepository", this.assetHistoryRepository);
        ReflectionTestUtils.setField(tradeCommonService, "tradeRepository", this.tradeRepository);
        ReflectionTestUtils.setField(mabsMultiService, "tradeCommonService", this.tradeCommonService);
        ReflectionTestUtils.setField(mabsMultiService, "periodIdx", -1);

        MabsMultiProperties properties = mabsMultiService.getProperties();
        properties.setMarkets(Collections.singletonList(condition.getMarket()));
        properties.setMaxBuyCount(1);
        properties.setInvestRatio(0.99);
        properties.setUpBuyRate(condition.getUpBuyRate());
        properties.setLoseStopRate(condition.getLoseStopRate());
        properties.setDownSellRate(condition.getDownSellRate());
        properties.setPeriodType(condition.getTradePeriod());
        properties.setShortPeriod(condition.getShortPeriod());
        properties.setLongPeriod(condition.getLongPeriod());
        properties.setNewMasBuy(true);

        ReflectionTestUtils.setField(mabsMultiService, "properties", properties);

    }

    @RequiredArgsConstructor
    @Getter
    public static class CurrentPrice {
        final Candle candle;
        final double maShort;
        final double maLong;
    }


}
