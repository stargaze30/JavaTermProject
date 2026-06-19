package TermProject;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.swing.JPanel;

class CandleChartPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_DAILY_VISIBLE_CANDLES = 45;
    private static final int CHART_SCROLL_STEP = 10;

    private String assetLabel = "-";
    private ChartPeriod period = ChartPeriod.DAILY;
    private transient List<CandleData> candles = Collections.emptyList();
    private boolean intradayIncluded;
    private int visibleStartIndex;
    private int visibleCandleCount;

    public void setCandles(String assetLabel, ChartPeriod period, List<CandleData> candles,
            boolean intradayIncluded) {
        this.assetLabel = assetLabel == null ? "-" : assetLabel;
        this.period = period == null ? ChartPeriod.DAILY : period;
        this.candles = candles == null ? Collections.emptyList() : candles;
        this.intradayIncluded = intradayIncluded;
        resetVisibleRangeToLatest();
        repaint();
    }

    public void updateLastCandleWithCurrentPrice(int currentPrice) {
        if (period != ChartPeriod.DAILY || currentPrice <= 1) {
            return;
        }

        boolean latestVisible = isLatestRangeVisible();
        String today = LocalDate.now(ZoneId.of("Asia/Seoul"))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        if (candles.isEmpty()) {
            ArrayList<CandleData> updatedCandles = new ArrayList<CandleData>();
            updatedCandles.add(new CandleData(today, currentPrice, currentPrice,
                    currentPrice, currentPrice));
            candles = updatedCandles;
        } else if (today.equals(candles.get(candles.size() - 1).getDate())) {
            candles.get(candles.size() - 1).updateWithCurrentPrice(currentPrice);
        } else {
            ArrayList<CandleData> updatedCandles = new ArrayList<CandleData>(candles);
            updatedCandles.add(new CandleData(today, currentPrice, currentPrice,
                    currentPrice, currentPrice));
            candles = updatedCandles;
            if (latestVisible) {
                resetVisibleRangeToLatest();
            }
        }
        intradayIncluded = true;
        repaint();
    }

    public void showPreviousRange() {
        if (candles.isEmpty()) {
            return;
        }
        visibleStartIndex = Math.max(0, visibleStartIndex - CHART_SCROLL_STEP);
        repaint();
    }

    public void showNextRange() {
        if (candles.isEmpty()) {
            return;
        }
        int maxStart = Math.max(0, candles.size() - visibleCandleCount);
        visibleStartIndex = Math.min(maxStart, visibleStartIndex + CHART_SCROLL_STEP);
        repaint();
    }

    public void showLatestRange() {
        resetVisibleRangeToLatest();
        repaint();
    }

    private void resetVisibleRangeToLatest() {
        if (period == ChartPeriod.DAILY) {
            visibleCandleCount = Math.min(DEFAULT_DAILY_VISIBLE_CANDLES, candles.size());
        } else {
            visibleCandleCount = candles.size();
        }
        visibleStartIndex = Math.max(0, candles.size() - visibleCandleCount);
    }

    private boolean isLatestRangeVisible() {
        return visibleStartIndex + visibleCandleCount >= candles.size();
    }

    private List<CandleData> getVisibleCandles() {
        if (candles.isEmpty()) {
            return Collections.emptyList();
        }
        int safeCount = Math.max(1, Math.min(visibleCandleCount, candles.size()));
        int start = Math.max(0, Math.min(visibleStartIndex, candles.size() - safeCount));
        int end = Math.min(candles.size(), start + safeCount);
        return candles.subList(start, end);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        int width = getWidth();
        int height = getHeight();
        int leftPadding = 80;
        int rightPadding = 30;
        int topPadding = 60;
        int bottomPadding = 55;
        int chartLeft = leftPadding;
        int chartRight = width - rightPadding;
        int chartTop = topPadding;
        int chartBottom = height - bottomPadding;
        int chartWidth = Math.max(1, chartRight - chartLeft);
        int chartHeight = Math.max(1, chartBottom - chartTop);

        g2.setColor(Color.DARK_GRAY);
        String title = assetLabel + " | " + period.getDisplayName();
        List<CandleData> visibleCandles = getVisibleCandles();
        if (!visibleCandles.isEmpty()) {
            title += " | " + formatDateLabel(visibleCandles.get(0).getDate(), period)
                    + " ~ "
                    + formatDateLabel(visibleCandles.get(visibleCandles.size() - 1).getDate(), period);
        }
        g2.drawString(title, chartLeft, 20);

        if (intradayIncluded) {
            g2.setColor(Color.GRAY);
            g2.drawString("장중 진행 캔들 반영", chartLeft, 40);
        }

        if (visibleCandles.isEmpty()) {
            g2.setColor(Color.GRAY);
            g2.drawString("Not enough candle data", chartLeft, height / 2);
            return;
        }

        int minPrice = Integer.MAX_VALUE;
        int maxPrice = Integer.MIN_VALUE;

        for (CandleData candle : visibleCandles) {
            minPrice = Math.min(minPrice, candle.getLowPrice());
            maxPrice = Math.max(maxPrice, candle.getHighPrice());
        }

        if (minPrice == maxPrice) {
            minPrice = Math.max(0, minPrice - 1);
            maxPrice = maxPrice + 1;
        }

        int range = maxPrice - minPrice;
        int pricePadding = Math.max(1, range / 20);
        maxPrice += pricePadding;
        minPrice = Math.max(0, minPrice - pricePadding);

        g2.setColor(Color.LIGHT_GRAY);
        g2.drawLine(chartLeft, chartTop, chartLeft, chartBottom);
        g2.drawLine(chartLeft, chartBottom, chartRight, chartBottom);
        drawPriceLabels(g2, minPrice, maxPrice, chartLeft, chartTop, chartHeight);

        int candleWidth = Math.max(3, Math.min(12, chartWidth / Math.max(1, visibleCandles.size()) - 2));
        g2.setStroke(new BasicStroke(1f));

        for (int i = 0; i < visibleCandles.size(); i++) {
            CandleData candle = visibleCandles.get(i);
            int x = chartLeft + (int) ((double) chartWidth * i / Math.max(1, visibleCandles.size() - 1));
            int highY = priceToY(candle.getHighPrice(), minPrice, maxPrice, chartTop, chartHeight);
            int lowY = priceToY(candle.getLowPrice(), minPrice, maxPrice, chartTop, chartHeight);
            int openY = priceToY(candle.getOpenPrice(), minPrice, maxPrice, chartTop, chartHeight);
            int closeY = priceToY(candle.getClosePrice(), minPrice, maxPrice, chartTop, chartHeight);
            int bodyTop = Math.min(openY, closeY);
            int bodyHeight = Math.max(1, Math.abs(openY - closeY));

            g2.setColor(candle.isRising() ? Color.RED : Color.BLUE);
            g2.drawLine(x, highY, x, lowY);
            g2.fillRect(x - candleWidth / 2, bodyTop, candleWidth, bodyHeight);
        }

        drawDateLabels(g2, chartLeft, chartBottom, chartWidth, visibleCandles);
    }

    private int priceToY(int price, int minPrice, int maxPrice, int top, int chartHeight) {
        return top + chartHeight
                - (int) ((double) (price - minPrice) / (maxPrice - minPrice) * chartHeight);
    }

    private void drawPriceLabels(Graphics2D g2, int minPrice, int maxPrice,
            int chartLeft, int chartTop, int chartHeight) {
        g2.setColor(Color.DARK_GRAY);
        int labelCount = 4;

        for (int i = 0; i <= labelCount; i++) {
            int price = maxPrice - (int) ((double) (maxPrice - minPrice) * i / labelCount);
            int y = chartTop + (int) ((double) chartHeight * i / labelCount);
            String label = String.format("%,d", price);
            int labelWidth = g2.getFontMetrics().stringWidth(label);
            g2.drawString(label, chartLeft - labelWidth - 8, y + 4);

            g2.setColor(new Color(230, 230, 230));
            g2.drawLine(chartLeft, y, getWidth() - 30, y);
            g2.setColor(Color.DARK_GRAY);
        }
    }

    private void drawDateLabels(Graphics2D g2, int left, int axisY, int chartWidth,
            List<CandleData> visibleCandles) {
        if (visibleCandles.isEmpty()) {
            return;
        }

        g2.setColor(Color.DARK_GRAY);
        int step = Math.max(1, (int) Math.ceil(visibleCandles.size() / 6.0));
        int lastIndex = visibleCandles.size() - 1;
        int lastLabelSkipDistance = Math.max(2, step / 2);

        for (int i = 0; i < visibleCandles.size(); i += step) {
            if (lastIndex - i < lastLabelSkipDistance) {
                continue;
            }
            drawDateLabel(g2, i, left, axisY, chartWidth, visibleCandles);
        }
        drawDateLabel(g2, lastIndex, left, axisY, chartWidth, visibleCandles);
    }

    private void drawDateLabel(Graphics2D g2, int index, int left, int axisY, int chartWidth,
            List<CandleData> visibleCandles) {
        int x = left + (int) ((double) chartWidth * index / Math.max(1, visibleCandles.size() - 1));
        String label = formatDateLabel(visibleCandles.get(index).getDate(), period);
        int labelWidth = g2.getFontMetrics().stringWidth(label);
        int drawX = x - labelWidth / 2;

        if (index == visibleCandles.size() - 1) {
            drawX = Math.min(drawX, getWidth() - labelWidth - 5);
        }
        drawX = Math.max(5, drawX);
        g2.drawString(label, drawX, axisY + 22);
    }

    private String formatDateLabel(String date, ChartPeriod period) {
        if (date == null || date.length() < 6) {
            return date == null ? "-" : date;
        }

        if (period == ChartPeriod.YEARLY) {
            return date.substring(0, 4);
        }
        if (period == ChartPeriod.MONTHLY) {
            return date.substring(0, 4) + "-" + date.substring(4, 6);
        }
        if (date.length() >= 8) {
            return date.substring(0, 4) + "-" + date.substring(4, 6)
                    + "-" + date.substring(6, 8);
        }
        return date;
    }
}
