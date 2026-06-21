package TermProject;

import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.SwingUtilities;

public class Tradingprog {
    private static final Path KOSPI_MASTER_FILE = Paths.get("kospi_code.mst");
    private static final Path KOSDAQ_MASTER_FILE = Paths.get("kosdaq_code.mst");

    public static void main(String[] args) {
        Market market = Market.createDefaultMarket();
        AssetMaster assetMaster = new AssetMaster(KOSPI_MASTER_FILE, KOSDAQ_MASTER_FILE);
        FileManager fileManager = new FileManager(
                Paths.get("user_data.txt"),
                Paths.get("transaction_history.txt"));
        fileManager.loadMarketData(market);
        User user = fileManager.loadUserData();
        TradingService tradingService = new TradingService();
        MarketDataProvider marketDataProvider = createMarketDataProvider();

        SwingUtilities.invokeLater(() ->
                new MainFrame(market, user, tradingService, fileManager,
                        marketDataProvider, assetMaster).setVisible(true));
    }

    private static MarketDataProvider createMarketDataProvider() {
        MarketDataProvider envProvider = new KisMarketDataProvider();

        if (envProvider.isConfigured()) {
            return envProvider;
        }

        String[] savedConfig = FileManager.loadApiConfig();
        if (savedConfig != null) {
            return new KisMarketDataProvider(savedConfig[0], savedConfig[1]);
        }
        return envProvider;
    }
}
