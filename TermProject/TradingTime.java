package TermProject;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

class TradingTime {
    static final String KRX_TRADING_TIME_TEXT = "KRX 거래 가능 시간: 평일 08:30~18:00";

    private TradingTime() {
    }

    static KRXSession getCurrentSession() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));
        DayOfWeek day = now.getDayOfWeek();

        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return KRXSession.CLOSED;
        }
        return getSessionByTime(now.toLocalTime());
    }

    static boolean isKrxTradingTime() {
        KRXSession session = getCurrentSession();
        return session != KRXSession.CLOSED
                && session != KRXSession.AFTER_MARKET_WAITING;
    }

    static String getKrxTradingStatusText() {
        return "현재 상태: " + getCurrentSession().getDisplayText();
    }

    private static KRXSession getSessionByTime(LocalTime time) {
        if (isBetween(time, LocalTime.of(8, 30), LocalTime.of(8, 40))) {
            return KRXSession.PRE_MARKET_CLOSE_AND_OPENING_AUCTION;
        }
        if (isBetween(time, LocalTime.of(8, 40), LocalTime.of(9, 0))) {
            return KRXSession.OPENING_AUCTION;
        }
        if (isBetween(time, LocalTime.of(9, 0), LocalTime.of(15, 20))) {
            return KRXSession.REGULAR_MARKET;
        }
        if (isBetween(time, LocalTime.of(15, 20), LocalTime.of(15, 30))) {
            return KRXSession.CLOSING_AUCTION;
        }
        if (isBetween(time, LocalTime.of(15, 30), LocalTime.of(15, 40))) {
            return KRXSession.AFTER_MARKET_WAITING;
        }
        if (isBetween(time, LocalTime.of(15, 40), LocalTime.of(16, 0))) {
            return KRXSession.AFTER_MARKET_CLOSE;
        }
        if (isBetween(time, LocalTime.of(16, 0), LocalTime.of(18, 0))) {
            return KRXSession.AFTER_HOURS_SINGLE_PRICE;
        }
        return KRXSession.CLOSED;
    }

    private static boolean isBetween(LocalTime time, LocalTime start, LocalTime end) {
        return !time.isBefore(start) && time.isBefore(end);
    }
}

enum KRXSession {
    PRE_MARKET_CLOSE_AND_OPENING_AUCTION(
            "장전 시간외 종가 + 장 시작 동시호가",
            "08:30 ~ 08:40",
            "전일 종가 거래와 시초가 결정을 위한 동시호가 접수가 함께 진행됩니다."),
    OPENING_AUCTION(
            "장 시작 동시호가",
            "08:40 ~ 09:00",
            "시초가 결정을 위한 주문 접수 시간입니다."),
    REGULAR_MARKET(
            "정규장",
            "09:00 ~ 15:20",
            "실시간 가격으로 거래됩니다."),
    CLOSING_AUCTION(
            "장 마감 동시호가",
            "15:20 ~ 15:30",
            "종가 결정을 위한 주문 접수 시간입니다."),
    AFTER_MARKET_WAITING(
            "장후 시간외 종가 대기",
            "15:30 ~ 15:40",
            "정규장 종료 후 장후 시간외 종가 시작 전입니다."),
    AFTER_MARKET_CLOSE(
            "장후 시간외 종가",
            "15:40 ~ 16:00",
            "당일 종가로 거래됩니다."),
    AFTER_HOURS_SINGLE_PRICE(
            "시간외 단일가",
            "16:00 ~ 18:00",
            "당일 종가 대비 ±10% 범위에서 10분 단위로 체결됩니다."),
    CLOSED(
            "거래 불가",
            "",
            "현재는 거래 시간이 아닙니다.");

    private final String title;
    private final String timeRange;
    private final String description;

    KRXSession(String title, String timeRange, String description) {
        this.title = title;
        this.timeRange = timeRange;
        this.description = description;
    }

    String getTitle() {
        return title;
    }

    String getTimeRange() {
        return timeRange;
    }

    String getDescription() {
        return description;
    }

    String getDisplayText() {
        if (timeRange.isBlank()) {
            return title + " (" + description + ")";
        }
        return title + " (" + timeRange + " / " + description + ")";
    }
}

class TradingException extends Exception {
    private static final long serialVersionUID = 1L;

    public TradingException(String message) {
        super(message);
    }
}
