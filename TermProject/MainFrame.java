package TermProject;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final String KOSPI_CHART_CODE = "KOSPI";
    private static final String KOSDAQ_CHART_CODE = "KOSDAQ";
    private static final boolean DEBUG_REALTIME_UNKNOWN_ASSET = false;
    private static final int INITIAL_PRICE_CORRECTION_DELAY_MS = 850;

    private final transient Market market;
    private final transient User user;
    private final transient TradingService tradingService;
    private final transient FileManager fileManager;
    private final transient AssetMaster assetMaster;
    private transient MarketDataProvider marketDataProvider;

    private final DefaultTableModel marketTableModel;
    private final DefaultTableModel portfolioTableModel;
    private final DefaultTableModel historyTableModel;
    private final JTable marketTable;
    private final JTable portfolioTable;
    private final JTable historyTable;
    private final JTextArea logArea;
    private final JLabel statusLabel;
    private final JLabel webSocketStatusLabel;
    private final JLabel tradingStatusLabel;
    private final JLabel currentSessionLabel;
    private final JSpinner buyQuantitySpinner;
    private final JSpinner sellQuantitySpinner;
    private final JComboBox<String> chartAssetComboBox;
    private final JComboBox<ChartPeriod> chartPeriodComboBox;
    private final CandleChartPanel chartPanel;
    private final JLabel selectedCodeLabel;
    private final JLabel detailCodeLabel;
    private final JLabel detailNameLabel;
    private final JLabel detailTypeLabel;
    private final JLabel detailPriceLabel;
    private final JLabel detailQuantityLabel;
    private final JLabel detailAveragePriceLabel;
    private final JLabel detailProfitLossLabel;
    private final JLabel detailReturnRateLabel;
    private final JLabel cashBalanceLabel;
    private final JLabel assetValueLabel;
    private final JLabel totalAccountValueLabel;
    private final JLabel totalDepositedCashLabel;
    private final JLabel totalProfitLossLabel;
    private final JLabel totalReturnRateLabel;
    private final JLabel stockRateLabel;
    private final JLabel etfRateLabel;
    private final JLabel cashRateLabel;
    private JButton updateSelectedButton;
    private JButton updateAllButton;
    private JButton addAssetButton;
    private JButton refreshChartButton;
    private JButton buyButton;
    private JButton sellButton;
    private JButton buyMaxButton;
    private JButton sellMaxButton;
    private boolean apiTaskRunning;
    private javax.swing.Timer tradingStateTimer;
    private transient RealtimePriceClient webSocketPriceProvider;
    private String loadedChartCode;
    private ChartPeriod loadedChartPeriod;
    private boolean initialPriceCorrectionStarted;

    public MainFrame(Market market, User user, TradingService tradingService,
            FileManager fileManager, MarketDataProvider marketDataProvider,
            AssetMaster assetMaster) {
        this.market = market;
        this.user = user;
        this.tradingService = tradingService;
        this.fileManager = fileManager;
        this.marketDataProvider = marketDataProvider;
        this.assetMaster = assetMaster;

        marketTableModel = createTableModel(new String[] {"종목코드", "종목명", "구분", "현재가"});
        portfolioTableModel = createTableModel(new String[] {"종목코드", "종목명", "구분", "수량",
                "평균매수가", "현재가", "매입금액", "평가금액", "평가손익", "수익률"});
        historyTableModel = createTableModel(new String[] {"시간", "구분", "종목코드", "종목명",
                "수량", "체결가"});
        marketTable = new JTable(marketTableModel);
        portfolioTable = new JTable(portfolioTableModel);
        historyTable = new JTable(historyTableModel);
        logArea = new JTextArea(7, 40);
        statusLabel = new JLabel("준비 완료");
        webSocketStatusLabel = new JLabel("WebSocket: 연결 안 됨");
        tradingStatusLabel = new JLabel(TradingTime.KRX_TRADING_TIME_TEXT);
        currentSessionLabel = new JLabel("현재: " + TradingTime.getCurrentSession().getTitle());
        buyQuantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1));
        sellQuantitySpinner = new JSpinner(new SpinnerNumberModel(1, 1, Integer.MAX_VALUE, 1));
        chartAssetComboBox = new JComboBox<String>();
        chartPeriodComboBox = new JComboBox<ChartPeriod>(ChartPeriod.values());
        chartPanel = new CandleChartPanel();
        selectedCodeLabel = new JLabel("-");
        detailCodeLabel = new JLabel("-");
        detailNameLabel = new JLabel("-");
        detailTypeLabel = new JLabel("-");
        detailPriceLabel = new JLabel("-");
        detailQuantityLabel = new JLabel("-");
        detailAveragePriceLabel = new JLabel("-");
        detailProfitLossLabel = new JLabel("-");
        detailReturnRateLabel = new JLabel("-");
        cashBalanceLabel = new JLabel("-");
        assetValueLabel = new JLabel("-");
        totalAccountValueLabel = new JLabel("-");
        totalDepositedCashLabel = new JLabel("-");
        totalProfitLossLabel = new JLabel("-");
        totalReturnRateLabel = new JLabel("-");
        stockRateLabel = new JLabel("-");
        etfRateLabel = new JLabel("-");
        cashRateLabel = new JLabel("-");

        setTitle("Mini Trading System");
        setSize(1200, 750);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        setJMenuBar(createMenuBar());
        configureTables();

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("시장", createMarketTab());
        tabs.addTab("포트폴리오", createPortfolioTab());
        tabs.addTab("거래내역", createHistoryTab());
        tabs.addTab("차트", createChartTab());
        add(tabs, BorderLayout.CENTER);

        refreshAll();
        startTradingStateTimer();
        startApiFeaturesIfConfigured(true);
    }

    @SuppressWarnings("serial")
    private DefaultTableModel createTableModel(String[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        JMenu settingMenu = new JMenu("설정");
        JMenuItem apiConfigItem = new JMenuItem("KIS API 설정");

        apiConfigItem.addActionListener(e -> showApiConfigDialog());
        settingMenu.add(apiConfigItem);
        menuBar.add(settingMenu);
        return menuBar;
    }

    private void startApiFeaturesIfConfigured(boolean showDialogIfMissing) {
        if (marketDataProvider.isConfigured()) {
            connectWebSocket();
            backfillMissingPricesAsync();
            return;
        }

        appendLog("KIS API 설정이 필요합니다.");
        if (showDialogIfMissing) {
            SwingUtilities.invokeLater(() -> showApiConfigDialog());
        }
    }

    private void showApiConfigDialog() {
        JTextField appKeyField = new JTextField(32);
        JPasswordField appSecretField = new JPasswordField(32);
        String[] savedConfig = FileManager.loadApiConfig();

        if (savedConfig != null) {
            appKeyField.setText(savedConfig[0]);
            appSecretField.setText(savedConfig[1]);
        }

        JPanel panel = new JPanel(new GridLayout(4, 1, 6, 6));
        panel.add(new JLabel("App Key"));
        panel.add(appKeyField);
        panel.add(new JLabel("App Secret"));
        panel.add(appSecretField);

        int result = JOptionPane.showConfirmDialog(this, panel, "KIS API 설정",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        String appKey = appKeyField.getText().trim();
        String appSecret = new String(appSecretField.getPassword()).trim();

        if (appKey.isBlank() || appSecret.isBlank()) {
            showError("App Key와 App Secret을 입력하세요.");
            return;
        }
        saveApiConfigAfterValidation(appKey, appSecret);
    }

    private void saveApiConfigAfterValidation(String appKey, String appSecret) {
        if (!beginApiTask("KIS API 설정 확인 중...")) {
            return;
        }

        new SwingWorker<KisMarketDataProvider, Void>() {
            @Override
            protected KisMarketDataProvider doInBackground() throws TradingException {
                KisMarketDataProvider provider = new KisMarketDataProvider(appKey, appSecret);
                provider.getApprovalKey();
                return provider;
            }

            @Override
            protected void done() {
                try {
                    KisMarketDataProvider provider = get();
                    FileManager.saveApiConfig(appKey, appSecret);
                    closeWebSocket();
                    marketDataProvider = provider;
                    initialPriceCorrectionStarted = false;
                    appendLog("KIS API 설정 저장 완료.");
                    setBusy(false, readyStatusText());
                    connectWebSocket();
                    backfillMissingPricesAsync();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setBusy(false, readyStatusText());
                } catch (ExecutionException e) {
                    System.err.println("KIS API 설정 확인 실패: " + getErrorMessage(e));
                    appendLog("KIS API 설정 확인 실패: API 키를 확인하세요.");
                    JOptionPane.showMessageDialog(MainFrame.this,
                            "KIS API 설정 확인 실패: API 키를 확인하세요.",
                            "KIS API 설정", JOptionPane.WARNING_MESSAGE);
                    setBusy(false, readyStatusText());
                }
            }
        }.execute();
    }

    private void configureTables() {
        Font tableFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        Font headerFont = new Font(Font.SANS_SERIF, Font.BOLD, 13);
        configureTableStyle(marketTable, tableFont, headerFont);
        configureTableStyle(portfolioTable, tableFont, headerFont);
        configureTableStyle(historyTable, tableFont, headerFont);

        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(JLabel.RIGHT);
        marketTable.getColumnModel().getColumn(3).setCellRenderer(rightRenderer);

        for (int column = 3; column <= 9; column++) {
            portfolioTable.getColumnModel().getColumn(column).setCellRenderer(rightRenderer);
        }

        ProfitColorRenderer profitRenderer = new ProfitColorRenderer();
        portfolioTable.getColumnModel().getColumn(8).setCellRenderer(profitRenderer);
        portfolioTable.getColumnModel().getColumn(9).setCellRenderer(profitRenderer);

        historyTable.getColumnModel().getColumn(4).setCellRenderer(rightRenderer);
        historyTable.getColumnModel().getColumn(5).setCellRenderer(rightRenderer);
    }

    private void configureTableStyle(JTable table, Font tableFont, Font headerFont) {
        table.setFont(tableFont);
        table.setRowHeight(24);
        table.getTableHeader().setFont(headerFont);
    }

    private JPanel createMarketTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        marketTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        marketTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                resetOrderQuantities();
                updateSelectedDetail();
                updateTradingButtonsState();
            }
        });

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(marketTable), createDetailPanel());
        splitPane.setResizeWeight(0.68);

        JPanel bottomPanel = new JPanel(new BorderLayout(6, 6));
        bottomPanel.add(createOrderPanel(), BorderLayout.NORTH);
        logArea.setEditable(false);
        bottomPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);
        JPanel statusPanel = new JPanel(new GridLayout(1, 3, 12, 0));
        statusPanel.add(statusLabel);
        statusPanel.add(tradingStatusLabel);
        statusPanel.add(webSocketStatusLabel);
        bottomPanel.add(statusPanel, BorderLayout.SOUTH);

        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(splitPane, BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createDetailPanel() {
        JPanel panel = new JPanel(new GridLayout(8, 2, 6, 8));
        panel.setBorder(BorderFactory.createTitledBorder("선택 종목"));
        Font detailFont = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
        addDetailRow(panel, "종목", detailCodeLabel, detailFont);
        addDetailRow(panel, "종목명", detailNameLabel, detailFont);
        addDetailRow(panel, "종류", detailTypeLabel, detailFont);
        addDetailRow(panel, "현재가", detailPriceLabel, detailFont);
        addDetailRow(panel, "보유수량", detailQuantityLabel, detailFont);
        addDetailRow(panel, "평균매수가", detailAveragePriceLabel, detailFont);
        addDetailRow(panel, "평가손익", detailProfitLossLabel, detailFont);
        addDetailRow(panel, "수익률", detailReturnRateLabel, detailFont);
        return panel;
    }

    private void addDetailRow(JPanel panel, String title, JLabel valueLabel, Font font) {
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(font);
        valueLabel.setFont(font);
        panel.add(titleLabel);
        panel.add(valueLabel);
    }

    private JPanel createOrderPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("주문"));
        updateSelectedButton = new JButton("선택 종목 갱신");
        updateAllButton = new JButton("전체 갱신");
        buyButton = new JButton("매수");
        sellButton = new JButton("매도");
        buyMaxButton = new JButton("최대 매수");
        sellMaxButton = new JButton("최대 매도");
        addAssetButton = new JButton("종목 추가");
        JButton saveCloseButton = new JButton("저장 후 닫기");

        updateSelectedButton.addActionListener(e -> updateSelectedPrice());
        updateAllButton.addActionListener(e -> updateAllPrices());
        buyButton.addActionListener(e -> executeTrade(true));
        sellButton.addActionListener(e -> executeTrade(false));
        buyMaxButton.addActionListener(e -> setMaxBuyQuantity());
        sellMaxButton.addActionListener(e -> setMaxSellQuantity());
        addAssetButton.addActionListener(e -> showAddAssetDialog());
        saveCloseButton.addActionListener(e -> saveAndClose());

        JPanel actionRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actionRow.add(new JLabel("선택"));
        actionRow.add(selectedCodeLabel);
        actionRow.add(addAssetButton);
        actionRow.add(saveCloseButton);

        JPanel buyRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buyRow.add(new JLabel("매수 수량"));
        buyRow.add(buyQuantitySpinner);
        buyRow.add(buyMaxButton);
        buyRow.add(buyButton);
        buyRow.add(currentSessionLabel);

        JPanel sellRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sellRow.add(new JLabel("매도 수량"));
        sellRow.add(sellQuantitySpinner);
        sellRow.add(sellMaxButton);
        sellRow.add(sellButton);

        JPanel orderRows = new JPanel(new GridLayout(3, 1));
        orderRows.add(actionRow);
        orderRows.add(buyRow);
        orderRows.add(sellRow);
        panel.add(orderRows, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createPortfolioTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        portfolioTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JButton refreshButton = new JButton("새로고침");
        JButton saveCloseButton = new JButton("저장 후 닫기");
        refreshButton.addActionListener(e -> refreshAll());
        saveCloseButton.addActionListener(e -> saveAndClose());

        JPanel summaryPanel = new JPanel(new GridLayout(5, 4, 8, 6));
        summaryPanel.setBorder(BorderFactory.createTitledBorder("계좌 요약"));
        addSummaryRow(summaryPanel, "예수금", cashBalanceLabel);
        addSummaryRow(summaryPanel, "평가금액", assetValueLabel);
        addSummaryRow(summaryPanel, "총 자산", totalAccountValueLabel);
        addSummaryRow(summaryPanel, "투입원금", totalDepositedCashLabel);
        addSummaryRow(summaryPanel, "평가손익", totalProfitLossLabel);
        addSummaryRow(summaryPanel, "수익률", totalReturnRateLabel);
        addSummaryRow(summaryPanel, "주식 비중", stockRateLabel);
        addSummaryRow(summaryPanel, "ETF 비중", etfRateLabel);
        addSummaryRow(summaryPanel, "현금 비중", cashRateLabel);

        JPanel depositPanel = createDepositPanel();
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(refreshButton);
        buttonPanel.add(saveCloseButton);

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(summaryPanel, BorderLayout.CENTER);
        bottomPanel.add(depositPanel, BorderLayout.WEST);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);

        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JScrollPane(portfolioTable), BorderLayout.CENTER);
        panel.add(bottomPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createDepositPanel() {
        JPanel panel = new JPanel(new GridLayout(4, 1, 4, 4));
        panel.setBorder(BorderFactory.createTitledBorder("예수금 추가"));
        JButton add500kButton = new JButton("+50만원");
        JButton add1mButton = new JButton("+100만원");
        JButton add5mButton = new JButton("+500만원");
        JButton customButton = new JButton("직접입력");

        add500kButton.addActionListener(e -> addCashBalance(500000));
        add1mButton.addActionListener(e -> addCashBalance(1000000));
        add5mButton.addActionListener(e -> addCashBalance(5000000));
        customButton.addActionListener(e -> addCustomCashBalance());

        panel.add(add500kButton);
        panel.add(add1mButton);
        panel.add(add5mButton);
        panel.add(customButton);
        return panel;
    }

    private JPanel createHistoryTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JButton refreshButton = new JButton("거래내역 새로고침");
        refreshButton.addActionListener(e -> refreshHistoryTable());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(refreshButton);

        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JScrollPane(historyTable), BorderLayout.CENTER);
        panel.add(buttonPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createChartTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        refreshChartButton = new JButton("차트 새로고침");
        refreshChartButton.addActionListener(e -> loadCandleChart());
        JButton previousChartButton = new JButton("이전");
        JButton nextChartButton = new JButton("다음");
        JButton latestChartButton = new JButton("최신");
        previousChartButton.addActionListener(e -> chartPanel.showPreviousRange());
        nextChartButton.addActionListener(e -> chartPanel.showNextRange());
        latestChartButton.addActionListener(e -> chartPanel.showLatestRange());

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.setBorder(BorderFactory.createTitledBorder("차트"));
        controlPanel.add(new JLabel("종목"));
        controlPanel.add(chartAssetComboBox);
        controlPanel.add(new JLabel("기간"));
        controlPanel.add(chartPeriodComboBox);
        controlPanel.add(refreshChartButton);
        controlPanel.add(previousChartButton);
        controlPanel.add(nextChartButton);
        controlPanel.add(latestChartButton);

        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(controlPanel, BorderLayout.NORTH);
        panel.add(chartPanel, BorderLayout.CENTER);
        return panel;
    }

    private void addSummaryRow(JPanel panel, String title, JLabel valueLabel) {
        panel.add(new JLabel(title));
        panel.add(valueLabel);
    }

    private void refreshAll() {
        refreshMarketTable();
        refreshPortfolioTable();
        refreshHistoryTable();
        refreshChartAssetOptions();
        refreshSummary();
        updateSelectedDetail();
        updateTradingButtonsState();
    }

    private void refreshMarketTable() {
        String selectedCode = getSelectedCode();
        marketTableModel.setRowCount(0);

        for (Asset asset : market.getListedAssets()) {
            marketTableModel.addRow(new Object[] {
                    asset.getCode(),
                    asset.getName(),
                    asset.getType(),
                    priceText(asset.getPrice())
            });
        }

        if (selectedCode != null) {
            selectMarketRow(selectedCode);
        }
    }

    private void updateMarketPriceCell(String assetCode) {
        Asset asset = market.findAsset(assetCode);

        if (asset == null) {
            return;
        }

        for (int row = 0; row < marketTableModel.getRowCount(); row++) {
            if (asset.getCode().equals(marketTableModel.getValueAt(row, 0))) {
                marketTableModel.setValueAt(priceText(asset.getPrice()), row, 3);
                return;
            }
        }
        refreshMarketTable();
    }

    private void refreshPortfolioTable() {
        portfolioTableModel.setRowCount(0);

        for (Map.Entry<String, Holding> entry : user.getPortfolio().entrySet()) {
            Holding holding = entry.getValue();
            Asset asset = market.findAsset(entry.getKey());

            if (asset == null) {
                long buyCost = (long) holding.getAveragePrice() * holding.getQuantity();
                portfolioTableModel.addRow(new Object[] {holding.getAssetCode(), "Not listed",
                        "-", formatNumber(holding.getQuantity()),
                        formatNumber(holding.getAveragePrice()), "-", formatNumber(buyCost),
                        "-", "-", "-"});
                continue;
            }

            long value = (long) asset.getPrice() * holding.getQuantity();
            long buyCost = (long) holding.getAveragePrice() * holding.getQuantity();
            long profitLoss = value - buyCost;
            double returnRate = buyCost == 0 ? 0.0 : (double) profitLoss / buyCost * 100;

            portfolioTableModel.addRow(new Object[] {
                    asset.getCode(),
                    asset.getName(),
                    asset.getType(),
                    formatNumber(holding.getQuantity()),
                    formatNumber(holding.getAveragePrice()),
                    priceText(asset.getPrice()),
                    formatNumber(buyCost),
                    formatNumber(value),
                    formatSignedLong(profitLoss),
                    formatSignedRate(returnRate)
            });
        }
    }

    private void refreshSummary() {
        PortfolioSummary summary = calculateSummary();
        cashBalanceLabel.setText(formatNumber(user.getBalance()) + " won");
        assetValueLabel.setText(formatNumber(summary.assetValue) + " won");
        totalAccountValueLabel.setText(formatNumber(summary.totalAccountValue) + " won");
        totalDepositedCashLabel.setText(formatNumber(summary.totalDepositedCash) + " won");
        totalProfitLossLabel.setText(formatSignedLong(summary.totalProfitLoss) + " won");
        totalReturnRateLabel.setText(formatSignedRate(summary.totalReturnRate));
        stockRateLabel.setText(formatRate(summary.stockRate));
        etfRateLabel.setText(formatRate(summary.etfRate));
        cashRateLabel.setText(formatRate(summary.cashRate));
    }

    private void refreshHistoryTable() {
        historyTableModel.setRowCount(0);

        for (String[] row : fileManager.loadTransactionHistoryRows()) {
            Asset asset = market.findAsset(row[2]);
            String assetName = asset == null ? "-" : asset.getName();
            historyTableModel.addRow(new Object[] {
                    row[0], row[1], row[2], assetName, row[3], row[4]
            });
        }
    }

    private void refreshChartAssetOptions() {
        String selectedCode = extractAssetCode((String) chartAssetComboBox.getSelectedItem());
        chartAssetComboBox.removeAllItems();
        chartAssetComboBox.addItem(KOSPI_CHART_CODE);
        chartAssetComboBox.addItem(KOSDAQ_CHART_CODE);

        for (Asset asset : market.getListedAssets()) {
            chartAssetComboBox.addItem(displayAsset(asset));
        }

        if (selectedCode != null) {
            selectChartAsset(selectedCode);
        }
    }

    private void loadCandleChart() {
        String selectedText = (String) chartAssetComboBox.getSelectedItem();
        String code = extractAssetCode(selectedText);
        ChartPeriod period = (ChartPeriod) chartPeriodComboBox.getSelectedItem();

        if (code == null) {
            chartPanel.setCandles("-", ChartPeriod.DAILY, Collections.emptyList(), false);
            return;
        }
        if (period == null) {
            period = ChartPeriod.DAILY;
        }
        if (!marketDataProvider.isConfigured()) {
            showError("KIS API 설정이 필요합니다.");
            return;
        }
        if (!beginApiTask("차트 조회 중...")) {
            return;
        }

        ChartPeriod selectedPeriod = period;
        boolean indexChart = isIndexChartCode(code);
        Asset chartAsset = indexChart ? null : market.findAsset(code);
        String chartLabel = getChartLabel(code);
        appendLog(chartLabel + " " + selectedPeriod.getDisplayName() + " 차트 조회 시작");

        new SwingWorker<List<CandleData>, Void>() {
            private boolean intradayIncluded;

            @Override
            protected List<CandleData> doInBackground() throws TradingException {
                ArrayList<CandleData> candles = indexChart
                        ? new ArrayList<CandleData>(
                                loadIndexCandlesWithRetry(code, selectedPeriod))
                        : new ArrayList<CandleData>(
                                loadChartCandlesWithRetry(chartAsset, selectedPeriod));
                applyIntradayCandleIfNeeded(code, selectedPeriod, candles);
                return candles;
            }

            @Override
            protected void done() {
                try {
                    List<CandleData> candles = get();
                    chartPanel.setCandles(chartLabel, selectedPeriod, candles, intradayIncluded);
                    loadedChartCode = code;
                    loadedChartPeriod = selectedPeriod;
                    appendLog(chartLabel + " " + getChartPeriodLogName(selectedPeriod)
                            + " 차트 조회 완료: " + candles.size() + "개 캔들");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("차트 조회가 중단되었습니다.");
                } catch (ExecutionException e) {
                    showError(getErrorMessage(e));
                }
                setBusy(false, readyStatusText());
            }

            private void applyIntradayCandleIfNeeded(String assetCode, ChartPeriod period,
                    ArrayList<CandleData> candles) {
                if (indexChart) {
                    return;
                }
                // 장중 진행 캔들 보정은 일봉에서만 적용
                if (period != ChartPeriod.DAILY) {
                    return;
                }
                if (!TradingTime.isKrxTradingTime()) {
                    return;
                }

                Asset asset = market.findAsset(assetCode);
                if (asset == null) {
                    return;
                }

                try {
                    int currentPrice = marketDataProvider.getCurrentPrice(asset);
                    String today = LocalDate.now(ZoneId.of("Asia/Seoul"))
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

                    if (!candles.isEmpty() && isToday(candles.get(candles.size() - 1).getDate())) {
                        candles.get(candles.size() - 1).updateWithCurrentPrice(currentPrice);
                    } else {
                        candles.add(new CandleData(today, currentPrice, currentPrice,
                                currentPrice, currentPrice));
                        appendLog("오늘 일봉 데이터가 없어 현재가 기반 임시 캔들을 추가했습니다.");
                    }
                    intradayIncluded = true;
                } catch (TradingException e) {
                    appendLog("현재가 반영 실패: " + e.getMessage());
                }
            }
        }.execute();
    }

    private List<CandleData> loadChartCandlesWithRetry(Asset asset, ChartPeriod period)
            throws TradingException {
        if (asset == null) {
            throw new TradingException("차트 종목을 찾을 수 없습니다.");
        }

        try {
            return marketDataProvider.getCandles(asset.getCode(), period);
        } catch (TradingException e) {
            if (!isKisRateLimitError(e)) {
                throw e;
            }

            sleepQuietly(1000);
            return marketDataProvider.getCandles(asset.getCode(), period);
        }
    }

    private List<CandleData> loadIndexCandlesWithRetry(String indexName, ChartPeriod period)
            throws TradingException {
        try {
            return marketDataProvider.getIndexCandles(indexName, period);
        } catch (TradingException e) {
            if (!isKisRateLimitError(e)) {
                throw e;
            }

            sleepQuietly(1000);
            return marketDataProvider.getIndexCandles(indexName, period);
        }
    }

    private boolean isKisRateLimitError(TradingException e) {
        String message = e.getMessage();
        return message != null && message.contains("EGW00201");
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isToday(String date) {
        String today = LocalDate.now(ZoneId.of("Asia/Seoul"))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return today.equals(date);
    }

    private String displayAsset(Asset asset) {
        return asset == null ? "-" : asset.getCode() + " " + asset.getName();
    }

    private boolean isIndexChartCode(String code) {
        return KOSPI_CHART_CODE.equals(code) || KOSDAQ_CHART_CODE.equals(code);
    }

    private String getChartLabel(String code) {
        if (KOSPI_CHART_CODE.equals(code)) {
            return KOSPI_CHART_CODE;
        }
        if (KOSDAQ_CHART_CODE.equals(code)) {
            return KOSDAQ_CHART_CODE;
        }
        return displayAsset(market.findAsset(code));
    }

    private String getChartPeriodLogName(ChartPeriod period) {
        if (period == ChartPeriod.MONTHLY) {
            return "월봉";
        }
        if (period == ChartPeriod.YEARLY) {
            return "년봉";
        }
        return "일봉";
    }

    private String extractAssetCode(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        String trimmed = text.trim();
        int spaceIndex = trimmed.indexOf(" ");
        return spaceIndex < 0 ? trimmed : trimmed.substring(0, spaceIndex);
    }

    private void selectChartAsset(String code) {
        for (int i = 0; i < chartAssetComboBox.getItemCount(); i++) {
            String item = chartAssetComboBox.getItemAt(i);

            if (code.equals(extractAssetCode(item))) {
                chartAssetComboBox.setSelectedIndex(i);
                return;
            }
        }
    }

    private PortfolioSummary calculateSummary() {
        long assetValue = 0;
        long stockValue = 0;
        long etfValue = 0;

        for (Map.Entry<String, Holding> entry : user.getPortfolio().entrySet()) {
            Holding holding = entry.getValue();
            Asset asset = market.findAsset(entry.getKey());

            if (asset == null) {
                continue;
            }

            long value = (long) asset.getPrice() * holding.getQuantity();
            assetValue += value;

            if ("Stock".equals(asset.getType())) {
                stockValue += value;
            } else if ("ETF".equals(asset.getType())) {
                etfValue += value;
            }
        }

        long totalAccountValue = user.getBalance() + assetValue;
        long totalDepositedCash = user.getTotalDepositedCash();
        long totalProfitLoss = totalAccountValue - totalDepositedCash;
        double totalReturnRate = totalDepositedCash == 0
                ? 0.0
                : (double) totalProfitLoss / totalDepositedCash * 100;
        double stockRate = totalAccountValue == 0 ? 0.0 : (double) stockValue / totalAccountValue * 100;
        double etfRate = totalAccountValue == 0 ? 0.0 : (double) etfValue / totalAccountValue * 100;
        double cashRate = totalAccountValue == 0 ? 0.0 : (double) user.getBalance() / totalAccountValue * 100;
        return new PortfolioSummary(assetValue, totalAccountValue, totalDepositedCash,
                totalProfitLoss, totalReturnRate, stockRate, etfRate, cashRate);
    }

    private void updateSelectedDetail() {
        Asset asset = getSelectedAsset();

        if (asset == null) {
        selectedCodeLabel.setText("-");
        detailCodeLabel.setText("-");
            detailNameLabel.setText("-");
            detailTypeLabel.setText("-");
            detailPriceLabel.setText("-");
            detailQuantityLabel.setText("-");
            detailAveragePriceLabel.setText("-");
            detailProfitLossLabel.setText("-");
            detailReturnRateLabel.setText("-");
            return;
        }

        Holding holding = user.getPortfolio().get(asset.getCode());
        int quantity = holding == null ? 0 : holding.getQuantity();
        int averagePrice = holding == null ? 0 : holding.getAveragePrice();
        long value = (long) asset.getPrice() * quantity;
        long buyCost = (long) averagePrice * quantity;
        long profitLoss = value - buyCost;
        double returnRate = buyCost == 0 ? 0.0 : (double) profitLoss / buyCost * 100;

        selectedCodeLabel.setText(displayAsset(asset));
        detailCodeLabel.setText(displayAsset(asset));
        detailNameLabel.setText(asset.getName());
        detailTypeLabel.setText(asset.getType());
        detailPriceLabel.setText(priceText(asset.getPrice()));
        detailQuantityLabel.setText(formatNumber(quantity));
        detailAveragePriceLabel.setText(holding == null ? "-" : formatNumber(averagePrice));
        detailProfitLossLabel.setText(holding == null ? "-" : formatSignedLong(profitLoss));
        detailReturnRateLabel.setText(holding == null ? "-" : formatSignedRate(returnRate));
    }

    private Asset getSelectedAsset() {
        String code = getSelectedCode();
        return code == null ? null : market.findAsset(code);
    }

    private String getSelectedCode() {
        int row = marketTable.getSelectedRow();

        if (row < 0) {
            return null;
        }
        return String.valueOf(marketTableModel.getValueAt(row, 0));
    }

    private void selectMarketRow(String code) {
        for (int i = 0; i < marketTableModel.getRowCount(); i++) {
            if (code.equals(marketTableModel.getValueAt(i, 0))) {
                marketTable.setRowSelectionInterval(i, i);
                return;
            }
        }
    }

    private boolean beginApiTask(String message) {
        if (apiTaskRunning) {
            appendLog("다른 API 작업이 진행 중입니다.");
            return false;
        }
        setBusy(true, message);
        return true;
    }

    private void setBusy(boolean busy, String message) {
        apiTaskRunning = busy;
        statusLabel.setText(message == null || message.isBlank() ? "준비 완료" : message);
        setButtonEnabled(updateSelectedButton, !busy);
        setButtonEnabled(updateAllButton, !busy);
        setButtonEnabled(addAssetButton, !busy);
        setButtonEnabled(refreshChartButton, !busy);
        setButtonEnabled(buyMaxButton, !busy);
        setButtonEnabled(sellMaxButton, !busy);
        updateTradingButtonsState();
    }

    private void setButtonEnabled(JButton button, boolean enabled) {
        if (button != null) {
            button.setEnabled(enabled);
        }
    }

    private String readyStatusText() {
        return "준비 완료";
    }

    private void startTradingStateTimer() {
        stopTradingStateTimer();
        tradingStateTimer = new javax.swing.Timer(5000, e -> updateTradingButtonsState());
        tradingStateTimer.start();
        updateTradingButtonsState();
    }

    private void stopTradingStateTimer() {
        if (tradingStateTimer != null) {
            tradingStateTimer.stop();
            tradingStateTimer = null;
        }
    }

    private void updateTradingButtonsState() {
        KRXSession currentSession = TradingTime.getCurrentSession();

        if (tradingStatusLabel != null) {
            tradingStatusLabel.setText(TradingTime.KRX_TRADING_TIME_TEXT);
        }
        if (currentSessionLabel != null) {
            currentSessionLabel.setText("현재 거래 방식: " + currentSession.getTitle());
        }

        Asset asset = getSelectedAsset();
        boolean canTrade = !apiTaskRunning
                && TradingTime.isKrxTradingTime()
                && asset != null
                && asset.getPrice() > 1;

        setButtonEnabled(buyButton, canTrade);
        setButtonEnabled(sellButton, canTrade);
    }

    private void connectWebSocket() {
        if (!marketDataProvider.isConfigured()) {
            statusLabel.setText("준비 완료");
            updateWebSocketStatus("WebSocket: 비활성화");
            appendLog("KIS API 설정이 필요합니다.");
            return;
        }

        closeWebSocket();
        setBusy(true, "WebSocket 연결 중...");
        updateWebSocketStatus("WebSocket: 연결 중...");
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws TradingException {
                webSocketPriceProvider = new RealtimePriceClient(
                        marketDataProvider,
                        new PriceUpdateListener() {
                            @Override
                            public void onPriceUpdated(String assetCode, int price) {
                                handleRealtimePrice(assetCode, price);
                            }

                            @Override
                            public void onWebSocketStatus(String message) {
                                SwingUtilities.invokeLater(() -> {
                                    updateWebSocketStatus(message);
                                    if (isUserVisibleWebSocketError(message)) {
                                        appendLog(message);
                                    }
                                });
                            }
                        });
                webSocketPriceProvider.connect();
                webSocketPriceProvider.subscribeAll(market.getListedAssets());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    updateWebSocketStatus("WebSocket: 연결됨");
                    appendLog("WebSocket 연결 완료.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    updateWebSocketStatus("WebSocket: 실패");
                    appendLog("WebSocket 연결이 중단되었습니다.");
                } catch (ExecutionException e) {
                    updateWebSocketStatus("WebSocket: 실패");
                    appendLog("WebSocket 연결 실패: " + getErrorMessage(e));
                }
                setBusy(false, readyStatusText());
            }
        }.execute();
    }

    private void backfillMissingPricesAsync() {
        if (initialPriceCorrectionStarted || !marketDataProvider.isConfigured()) {
            return;
        }

        List<Asset> targetAssets = new ArrayList<Asset>();
        for (Asset asset : market.getListedAssets()) {
            if (asset.getPrice() <= 1) {
                targetAssets.add(asset);
            }
        }
        if (targetAssets.isEmpty()) {
            return;
        }

        initialPriceCorrectionStarted = true;
        new SwingWorker<PriceBackfillResult, Void>() {
            @Override
            protected PriceBackfillResult doInBackground() {
                PriceBackfillResult result = new PriceBackfillResult();

                for (Asset asset : targetAssets) {
                    try {
                        int currentPrice = marketDataProvider.getCurrentPrice(asset);
                        if (currentPrice > 1) {
                            market.updatePriceFromRealtime(asset.getCode(), currentPrice);
                            result.successCount++;
                            SwingUtilities.invokeLater(() -> refreshPriceViews(asset.getCode()));
                        } else {
                            result.failureCount++;
                            System.err.println("초기 시세 보정 실패: "
                                    + asset.getCode() + " / 현재가가 올바르지 않습니다.");
                        }
                    } catch (TradingException e) {
                        result.failureCount++;
                        System.err.println("초기 시세 보정 실패: "
                                + asset.getCode() + " / " + e.getMessage());
                    }

                    try {
                        Thread.sleep(INITIAL_PRICE_CORRECTION_DELAY_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return result;
            }

            @Override
            protected void done() {
                try {
                    PriceBackfillResult result = get();

                    if (result.successCount > 0) {
                        fileManager.saveMarketData(market);
                    }
                    refreshAllPriceViews();
                    appendLog("초기 시세 보정 완료: "
                            + result.successCount + "개 성공, "
                            + result.failureCount + "개 실패");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    refreshAllPriceViews();
                    appendLog("초기 시세 보정 실패: " + getErrorMessage(e));
                }
            }
        }.execute();
    }

    private void refreshPriceViews(String assetCode) {
        updateMarketPriceCell(assetCode);
        updateSelectedDetail();
        updateTradingButtonsState();
        refreshPortfolioTable();
        refreshSummary();
    }

    private void refreshAllPriceViews() {
        refreshMarketTable();
        updateSelectedDetail();
        updateTradingButtonsState();
        refreshPortfolioTable();
        refreshSummary();
    }

    private boolean isUserVisibleWebSocketError(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("실패")
                || message.contains("오류")
                || message.contains("Error")
                || message.contains("Exception");
    }

    private void updateWebSocketStatus(String message) {
        webSocketStatusLabel.setText(message);
    }

    private void handleRealtimePrice(String assetCode, int price) {
        SwingUtilities.invokeLater(() -> {
            if (market.findAsset(assetCode) == null) {
                if (DEBUG_REALTIME_UNKNOWN_ASSET) {
                    System.out.println("unknown realtime asset code: " + assetCode);
                }
                return;
            }
            market.updatePriceFromRealtime(assetCode, price);
            updateMarketPriceCell(assetCode);
            updateSelectedDetail();
            updateTradingButtonsState();
            refreshPortfolioTable();
            refreshSummary();
            updateChartWithRealtimePrice(assetCode, price);
        });
    }

    private void updateChartWithRealtimePrice(String assetCode, int price) {
        if (loadedChartPeriod != ChartPeriod.DAILY || loadedChartCode == null
                || !assetCode.equals(loadedChartCode) || isIndexChartCode(loadedChartCode)
                || !TradingTime.isKrxTradingTime()) {
            return;
        }
        chartPanel.updateLastCandleWithCurrentPrice(price);
    }

    private void updateSelectedPrice() {
        updateSelectedPrice(false);
    }

    private void updateSelectedPrice(boolean autoUpdate) {
        Asset asset = getSelectedAsset();

        if (asset == null) {
            showError("종목을 선택하세요.");
            return;
        }
        if (!marketDataProvider.isConfigured()) {
            showError("KIS API 설정이 필요합니다.");
            return;
        }
        if (!beginApiTask(autoUpdate ? "자동 업데이트 실행 중..." : "선택 종목 시세 조회 중...")) {
            return;
        }

        String code = asset.getCode();
        appendLog(displayAsset(asset) + " 가격 업데이트 시작");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws TradingException {
                market.updatePrice(code, marketDataProvider);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    appendLog(displayAsset(market.findAsset(code)) + " 가격 업데이트 완료");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("가격 업데이트가 중단되었습니다.");
                } catch (ExecutionException e) {
                    showError(getErrorMessage(e));
                }
                refreshAll();
                setBusy(false, readyStatusText());
            }
        }.execute();
    }

    private void updateAllPrices() {
        if (!marketDataProvider.isConfigured()) {
            showError("KIS API 설정이 필요합니다.");
            return;
        }
        if (!beginApiTask("전체 시세 조회 중...")) {
            return;
        }

        appendLog("전체 가격 업데이트 시작");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                market.updatePrices(marketDataProvider);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    appendLog("전체 가격 업데이트 완료");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("가격 업데이트가 중단되었습니다.");
                } catch (ExecutionException e) {
                    showError(getErrorMessage(e));
                }
                refreshAll();
                setBusy(false, readyStatusText());
            }
        }.execute();
    }

    private void executeTrade(boolean buy) {
        if (!TradingTime.isKrxTradingTime()) {
            appendLog("현재는 거래 가능 시간이 아닙니다. " + TradingTime.KRX_TRADING_TIME_TEXT);
            updateTradingButtonsState();
            return;
        }

        Asset asset = getSelectedAsset();

        if (asset == null) {
            showError("종목을 선택하세요.");
            return;
        }

        int quantity = buy
                ? (Integer) buyQuantitySpinner.getValue()
                : (Integer) sellQuantitySpinner.getValue();

        try {
            if (buy) {
                tradingService.buy(user, asset, quantity);
            } else {
                tradingService.sell(user, asset, quantity);
            }

            Transaction transaction = tradingService.getLastTransaction();
            fileManager.saveTransaction(transaction);
            fileManager.saveUserData(user);
            appendLog((buy ? "매수 성공: " : "매도 성공: ") + transaction);
            resetOrderQuantities();
            refreshAll();
        } catch (TradingException e) {
            showError(e.getMessage());
        }
    }

    private void addCashBalance(int amount) {
        try {
            user.deposit(amount);
            fileManager.saveUserData(user);
            appendLog("예수금 " + formatNumber(amount) + "원 추가");
            refreshPortfolioTable();
            refreshSummary();
            updateSelectedDetail();
        } catch (TradingException e) {
            showError(e.getMessage());
        }
    }

    private void addCustomCashBalance() {
        String input = JOptionPane.showInputDialog(this, "추가할 예수금을 입력하세요.");

        if (input == null || input.trim().isEmpty()) {
            return;
        }

        try {
            int amount = Integer.parseInt(input.replace(",", "").trim());

            if (amount < 1) {
                showError("1원 이상 입력하세요.");
                return;
            }
            addCashBalance(amount);
        } catch (NumberFormatException e) {
            showError("숫자로 입력하세요.");
        }
    }

    private void resetOrderQuantities() {
        buyQuantitySpinner.setValue(1);
        sellQuantitySpinner.setValue(1);
    }

    private void setMaxBuyQuantity() {
        Asset asset = getSelectedAsset();

        if (asset == null) {
            showError("종목을 선택하세요.");
            return;
        }
        if (asset.getPrice() <= 1) {
            showError("현재가 업데이트 후 사용할 수 있습니다.");
            return;
        }

        int maxQuantity = user.getBalance() / asset.getPrice();
        if (maxQuantity < 1) {
            showError("잔고가 부족합니다.");
            return;
        }
        buyQuantitySpinner.setValue(maxQuantity);
    }

    private void setMaxSellQuantity() {
        Asset asset = getSelectedAsset();

        if (asset == null) {
            showError("종목을 선택하세요.");
            return;
        }

        int holdingQuantity = user.getQuantity(asset.getCode());
        if (holdingQuantity < 1) {
            showError("보유 수량이 없습니다.");
            return;
        }
        sellQuantitySpinner.setValue(holdingQuantity);
    }

    private void showAddAssetDialog() {
        JTextField keywordField = new JTextField(18);
        JPanel panel = new JPanel(new GridLayout(3, 1, 6, 6));
        panel.add(new JLabel("추가하고 싶은 종목 코드 또는 이름"));
        panel.add(keywordField);
        panel.add(new JLabel("종목코드 또는 정확한 종목명을 입력하세요.(알파벳은 대문자로 입력) 예: 030200 또는 KT"));

        int result = JOptionPane.showConfirmDialog(this, panel, "종목 추가",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) {
            return;
        }

        String keyword = keywordField.getText().trim();
        if (keyword.isBlank()) {
            return;
        }

        if (!marketDataProvider.isConfigured()) {
            showError("KIS API 설정이 필요합니다.");
            return;
        }

        if (!assetMaster.isAvailable()) {
            showError("kospi_code.mst 파일이 없어 종목명 자동 검색을 사용할 수 없습니다.");
            return;
        }

        AssetInfo assetInfo = assetMaster.findByCodeOrExactName(keyword);
        if (assetInfo == null) {
            showError("종목 마스터 파일에서 찾을 수 없습니다. 종목코드 또는 정확한 종목명을 확인해주세요.");
            return;
        }
        if (market.findAsset(assetInfo.getCode()) != null) {
            showError("이미 등록된 자산입니다.");
            return;
        }
        if (!beginApiTask("종목 추가 중...")) {
            return;
        }

        appendLog(assetInfo.getCode() + " 종목 정보 조회 시작");

        new SwingWorker<AddAssetResult, Void>() {
            @Override
            protected AddAssetResult doInBackground() throws TradingException {
                Asset asset = assetInfo.createAsset(1);

                try {
                    int currentPrice = marketDataProvider.getCurrentPrice(asset);
                    if (currentPrice <= 1) {
                        throw new TradingException("Invalid current price.");
                    }
                    asset.setPrice(currentPrice);
                    return new AddAssetResult(asset, currentPrice, false, "");
                } catch (TradingException e) {
                    throw new TradingException("현재가 조회 실패: 종목코드를 확인해주세요.");
                }
            }

            @Override
            protected void done() {
                try {
                    AddAssetResult result = get();
                    if (market.findAsset(result.getAsset().getCode()) != null) {
                        showError("이미 등록된 자산입니다.");
                    } else {
                        market.addAsset(result.getAsset());
                        fileManager.saveMarketData(market);
                        if (result.isTypeGuessed()) {
                            appendLog("종목 종류를 확인하지 못해 Stock으로 추가했습니다.");
                        }
                        if (!result.getPriceMessage().isBlank()) {
                            appendLog(result.getPriceMessage());
                            appendLog(result.getAsset().getCode() + " " + result.getAsset().getName()
                                    + " 추가 완료: 현재가 업데이트 필요");
                        } else {
                            appendLog(result.getAsset().getCode() + " " + result.getAsset().getName()
                                    + " 추가 완료: " + String.format("%,d", result.getPrice()) + "원");
                        }
                        refreshAll();
                        selectMarketRow(result.getAsset().getCode());
                        subscribeRealtime(result.getAsset());
                        appendLog("종목 목록 저장 완료");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    showError("종목 추가가 중단되었습니다.");
                } catch (ExecutionException e) {
                    showError(getErrorMessage(e));
                }
                setBusy(false, readyStatusText());
            }
        }.execute();
    }

    private void subscribeRealtime(Asset asset) {
        if (webSocketPriceProvider == null || asset == null) {
            return;
        }

        try {
            webSocketPriceProvider.subscribe(asset.getCode());
        } catch (TradingException e) {
            appendLog("WebSocket 구독 실패: " + e.getMessage());
        }
    }

    private void saveAndClose() {
        appendLog("User data saved. Closing program.");
        dispose();
        System.exit(0);
    }

    @Override
    public void dispose() {
        stopTradingStateTimer();
        closeWebSocket();
        fileManager.saveMarketData(market);
        fileManager.saveUserData(user);
        super.dispose();
    }

    private void closeWebSocket() {
        if (webSocketPriceProvider != null) {
            webSocketPriceProvider.close();
            webSocketPriceProvider = null;
        }
    }

    private String getErrorMessage(ExecutionException e) {
        Throwable cause = e.getCause();
        return cause == null ? e.getMessage() : cause.getMessage();
    }

    private void showError(String message) {
        appendLog(message);
        JOptionPane.showMessageDialog(this, message, "Notice", JOptionPane.WARNING_MESSAGE);
    }

    private void appendLog(String message) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> appendLog(message));
            return;
        }
        logArea.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                + " " + message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private String priceText(int price) {
        return price <= 1 ? "시세 수신 대기" : formatNumber(price);
    }

    private String formatNumber(long value) {
        return String.format("%,d", value);
    }

    private String formatSignedLong(long value) {
        return String.format("%+,d", value);
    }

    private String formatSignedRate(double value) {
        return String.format("%+.2f%%", value);
    }

    private String formatRate(double value) {
        return String.format("%.2f%%", value);
    }

    private static class ProfitColorRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component component = super.getTableCellRendererComponent(table, value,
                    isSelected, hasFocus, row, column);
            setHorizontalAlignment(JLabel.RIGHT);

            if (isSelected) {
                return component;
            }

            String text = value == null ? "" : value.toString().trim();
            if (text.startsWith("+") && !text.startsWith("+0")) {
                component.setForeground(Color.RED);
            } else if (text.startsWith("-") && !text.startsWith("-0")) {
                component.setForeground(Color.BLUE);
            } else {
                component.setForeground(table.getForeground());
            }
            return component;
        }
    }

private static class PortfolioSummary {
        private final long assetValue;
        private final long totalAccountValue;
        private final long totalDepositedCash;
        private final long totalProfitLoss;
        private final double totalReturnRate;
        private final double stockRate;
        private final double etfRate;
        private final double cashRate;

        private PortfolioSummary(long assetValue, long totalAccountValue,
                long totalDepositedCash, long totalProfitLoss, double totalReturnRate,
                double stockRate, double etfRate, double cashRate) {
            this.assetValue = assetValue;
            this.totalAccountValue = totalAccountValue;
            this.totalDepositedCash = totalDepositedCash;
            this.totalProfitLoss = totalProfitLoss;
            this.totalReturnRate = totalReturnRate;
            this.stockRate = stockRate;
            this.etfRate = etfRate;
            this.cashRate = cashRate;
        }
    }

    private static class AddAssetResult {
        private final Asset asset;
        private final int price;
        private final boolean typeGuessed;
        private final String priceMessage;

        private AddAssetResult(Asset asset, int price, boolean typeGuessed,
                String priceMessage) {
            this.asset = asset;
            this.price = price;
            this.typeGuessed = typeGuessed;
            this.priceMessage = priceMessage == null ? "" : priceMessage;
        }

        private Asset getAsset() {
            return asset;
        }

        private int getPrice() {
            return price;
        }

        private boolean isTypeGuessed() {
            return typeGuessed;
        }

        private String getPriceMessage() {
            return priceMessage;
        }
    }

    private static class PriceBackfillResult {
        private int successCount;
        private int failureCount;
    }
}
