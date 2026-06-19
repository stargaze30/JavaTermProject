package TermProject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class FileManager {

    private static final Path API_CONFIG_FILE = Paths.get("kis_api_config.txt");
    private static final Path MARKET_DATA_FILE = Paths.get("market_data.txt");

    public static String[] loadApiConfig() {
        if (!Files.exists(API_CONFIG_FILE)) {
            return null;
        }

        String appKey = "";
        String appSecret = "";

        try (BufferedReader reader = Files.newBufferedReader(API_CONFIG_FILE)) {
            String line;

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=", 2);

                if (parts.length != 2) {
                    continue;
                }

                if ("appkey".equals(parts[0].trim())) {
                    appKey = parts[1].trim();
                } else if ("appsecret".equals(parts[0].trim())) {
                    appSecret = parts[1].trim();
                }
            }
        } catch (IOException e) {
            System.out.println("KIS API 설정 파일을 읽지 못했습니다: " + e.getMessage());
            return null;
        }

        if (appKey.isBlank() || appSecret.isBlank()) {
            return null;
        }
        return new String[] {appKey, appSecret};
    }

    public static void saveApiConfig(String appKey, String appSecret) {
        // app secret은 평문 저장함. 제출 시 kis_api_config.txt는 포함하지 말 것
        try (BufferedWriter writer = Files.newBufferedWriter(API_CONFIG_FILE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("appkey=" + appKey);
            writer.newLine();
            writer.write("appsecret=" + appSecret);
            writer.newLine();
        } catch (IOException e) {
            System.out.println("KIS API 설정 파일을 저장하지 못했습니다: " + e.getMessage());
        }
    }
    private final Path userDataPath;
    private final Path transactionHistoryPath;

    public FileManager(Path userDataPath, Path transactionHistoryPath) {
        this.userDataPath = userDataPath;
        this.transactionHistoryPath = transactionHistoryPath;
    }

    public void loadMarketData(Market market) {
        if (market == null || !Files.exists(MARKET_DATA_FILE)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(MARKET_DATA_FILE)) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",", 4);
                if (parts.length < 3) {
                    continue;
                }

                String code = parts[0].trim().toUpperCase();
                String name = parts[1].trim();
                String type = parts[2].trim();
                int price = 1;

                if (parts.length == 4) {
                    try {
                        price = Math.max(1, Integer.parseInt(parts[3].trim()));
                    } catch (NumberFormatException e) {
                        price = 1;
                    }
                }

                Asset existingAsset = market.findAsset(code);
                if (existingAsset == null) {
                    market.addAsset(new AssetInfo(code, name, type).createAsset(price));
                } else if (price > 1) {
                    existingAsset.setPrice(price);
                }
            }
        } catch (IOException e) {
            System.out.println("종목 목록 불러오기 실패: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.out.println("종목 목록 형식 오류. 저장된 일부 종목을 불러오지 못했습니다.");
        }
    }

    public void saveMarketData(Market market) {
        if (market == null) {
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(MARKET_DATA_FILE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Asset asset : market.getListedAssets()) {
                writer.write(asset.getCode() + "," + asset.getName()
                        + "," + asset.getType() + "," + asset.getPrice());
                writer.newLine();
            }
        } catch (IOException e) {
            System.out.println("종목 목록 저장 실패: " + e.getMessage());
        }
    }

    public User loadUserData() {
        if (!Files.exists(userDataPath)) {
            return new User();
        }

        try (BufferedReader reader = Files.newBufferedReader(userDataPath)) {
            String firstLine = reader.readLine();
            User user = new User(readBalance(firstLine));
            String line;

            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("=", 2);

                if (parts.length != 2) {
                    throw new IllegalArgumentException("Invalid holding line.");
                }
                String key = parts[0].trim();

                if ("totalDepositedCash".equals(key)) {
                    user.setTotalDepositedCash(Long.parseLong(parts[1].trim()));
                    continue;
                }

                String[] holdingParts = parts[1].split(",", 2);
                int quantity = Integer.parseInt(holdingParts[0].trim());
                int averagePrice = 1;

                if (holdingParts.length == 2) {
                    averagePrice = Integer.parseInt(holdingParts[1].trim());
                }
                user.loadHolding(key, quantity, averagePrice);
            }
            return user;
        } catch (IOException e) {
            System.out.println("사용자 데이터 불러오기 실패: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            System.out.println("사용자 데이터 형식 오류. 기본값으로 시작합니다.");
        }
        return new User();
    }

    public void saveUserData(User user) {
        try (BufferedWriter writer = Files.newBufferedWriter(userDataPath)) {
            writer.write("balance=" + user.getBalance());
            writer.newLine();
            writer.write("totalDepositedCash=" + user.getTotalDepositedCash());
            writer.newLine();

            for (Map.Entry<String, Holding> entry : user.getPortfolio().entrySet()) {
                Holding holding = entry.getValue();
                writer.write(entry.getKey() + "=" + holding.getQuantity()
                        + "," + holding.getAveragePrice());
                writer.newLine();
            }
            System.out.println("User data saved to " + userDataPath + ".");
        } catch (IOException e) {
            System.out.println("사용자 데이터 저장 실패: " + e.getMessage());
        }
    }

    public void saveTransaction(Transaction transaction) {
        if (transaction == null) {
            return;
        }

        try (BufferedWriter writer = Files.newBufferedWriter(transactionHistoryPath,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(transaction.toCsv());
            writer.newLine();
        } catch (IOException e) {
            System.out.println("거래 기록 저장 실패: " + e.getMessage());
        }
    }

    public List<String[]> loadTransactionHistoryRows() {
        ArrayList<String[]> rows = new ArrayList<String[]>();

        if (!Files.exists(transactionHistoryPath)) {
            return rows;
        }

        try (BufferedReader reader = Files.newBufferedReader(transactionHistoryPath)) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                String[] parts = line.split(",", -1);
                if (parts.length == 5) {
                    rows.add(new String[] {
                            parts[0].trim(),
                            parts[1].trim(),
                            parts[2].trim(),
                            parts[3].trim(),
                            parts[4].trim()
                    });
                }
            }
        } catch (IOException e) {
            System.out.println("거래 기록 불러오기 실패: " + e.getMessage());
        }
        return rows;
    }

    public void printTransactionHistory() {
        List<String[]> rows = loadTransactionHistoryRows();

        if (rows.isEmpty()) {
            System.out.println("거래 기록이 없습니다.");
            return;
        }

        System.out.printf("%-20s %-6s %-12s %8s %12s%n",
                "Time", "Type", "Asset Code", "Quantity", "Unit Price");

        for (String[] row : rows) {
            try {
                System.out.printf("%-20s %-6s %-12s %,8d %,12d%n",
                        row[0], row[1], row[2],
                        Integer.parseInt(row[3]), Integer.parseInt(row[4]));
            } catch (NumberFormatException e) {
                System.out.printf("%-20s %-6s %-12s %8s %12s%n",
                        row[0], row[1], row[2], row[3], row[4]);
            }
        }
    }

    private int readBalance(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Balance line is missing.");
        }

        String[] parts = line.split("=", 2);
        if (parts.length != 2 || !"balance".equals(parts[0].trim())) {
            throw new IllegalArgumentException("Balance line is invalid.");
        }
        return Integer.parseInt(parts[1].trim());
    }
}
