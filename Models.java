package TermProject;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

abstract class Asset {
    protected String code;
    protected String name;
    protected int price;

    protected Asset(String code, String name, int price) {
        if (code == null || code.trim().isEmpty()) {
            throw new IllegalArgumentException("Asset code is required.");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Asset name is required.");
        }
        if (price < 1) {
            throw new IllegalArgumentException("Asset price must be positive.");
        }

        this.code = code.trim().toUpperCase();
        this.name = name.trim();
        this.price = price;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public int getPrice() {
        return price;
    }

    protected void setPrice(int price) {
        this.price = Math.max(1, price);
    }

    public abstract String getType();
}

class Stock extends Asset {
    public Stock(String code, String name, int price) {
        super(code, name, price);
    }

    @Override
    public String getType() {
        return "Stock";
    }
}

class ETF extends Asset {
    public ETF(String code, String name, int price) {
        super(code, name, price);
    }

    @Override
    public String getType() {
        return "ETF";
    }
}

class Holding {
    private final String assetCode;
    private int quantity;
    private int averagePrice;

    public Holding(String assetCode, int quantity, int averagePrice) {
        if (assetCode == null || assetCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Asset code is required.");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("Holding quantity must be positive.");
        }
        if (averagePrice < 1) {
            throw new IllegalArgumentException("Average price must be positive.");
        }

        this.assetCode = assetCode.trim().toUpperCase();
        this.quantity = quantity;
        this.averagePrice = averagePrice;
    }

    public String getAssetCode() {
        return assetCode;
    }

    public int getQuantity() {
        return quantity;
    }

    public int getAveragePrice() {
        return averagePrice;
    }

    public void addQuantity(int quantity, int buyPrice) {
        if (quantity < 1 || buyPrice < 1) {
            throw new IllegalArgumentException("Quantity and buy price must be positive.");
        }

        long totalCost = (long) averagePrice * this.quantity + (long) buyPrice * quantity;
        int totalQuantity = this.quantity + quantity;
        averagePrice = (int) (totalCost / totalQuantity);
        this.quantity = totalQuantity;
    }

    public void removeQuantity(int quantity) {
        if (quantity < 1 || quantity > this.quantity) {
            throw new IllegalArgumentException("Invalid holding quantity.");
        }
        this.quantity -= quantity;
    }
}

class User {
    public static final int DEFAULT_BALANCE = 10000000;

    private int balance;
    private long totalDepositedCash;
    private final HashMap<String, Holding> portfolio;

    public User() {
        this(DEFAULT_BALANCE);
    }

    public User(int balance) {
        if (balance < 0) {
            throw new IllegalArgumentException("Balance cannot be negative.");
        }

        this.balance = balance;
        totalDepositedCash = DEFAULT_BALANCE;
        portfolio = new HashMap<String, Holding>();
    }

    public int getBalance() {
        return balance;
    }

    public long getTotalDepositedCash() {
        return totalDepositedCash <= 0 ? DEFAULT_BALANCE : totalDepositedCash;
    }

    public void setTotalDepositedCash(long totalDepositedCash) {
        if (totalDepositedCash <= 0) {
            this.totalDepositedCash = DEFAULT_BALANCE;
        } else {
            this.totalDepositedCash = totalDepositedCash;
        }
    }

    public Map<String, Holding> getPortfolio() {
        return Collections.unmodifiableMap(portfolio);
    }

    public int getQuantity(String assetCode) {
        Holding holding = portfolio.get(normalizeCode(assetCode));
        return holding == null ? 0 : holding.getQuantity();
    }

    public void loadHolding(String assetCode, int quantity, int averagePrice) {
        if (quantity < 1) {
            throw new IllegalArgumentException("Holding quantity must be positive.");
        }
        portfolio.put(normalizeCode(assetCode), new Holding(assetCode, quantity, averagePrice));
    }

    void withdraw(int amount) throws TradingException {
        if (amount > balance) {
            throw new TradingException("잔고가 부족합니다.");
        }
        balance -= amount;
    }

    void deposit(int amount) throws TradingException {
        if (amount < 1) {
            throw new TradingException("1원 이상 입력하세요.");
        }
        if ((long) balance + amount > Integer.MAX_VALUE) {
            throw new TradingException("잔고가 저장 범위를 초과합니다.");
        }
        balance += amount;
        totalDepositedCash += amount;
    }

    void receiveTradeProceeds(int amount) throws TradingException {
        if ((long) balance + amount > Integer.MAX_VALUE) {
            throw new TradingException("잔고가 저장 범위를 초과합니다.");
        }
        balance += amount;
    }

    void addHolding(String assetCode, int quantity, int buyPrice) {
        String code = normalizeCode(assetCode);
        Holding holding = portfolio.get(code);

        if (holding == null) {
            portfolio.put(code, new Holding(code, quantity, buyPrice));
        } else {
            holding.addQuantity(quantity, buyPrice);
        }
    }

    void removeHolding(String assetCode, int quantity) throws TradingException {
        String code = normalizeCode(assetCode);
        Holding holding = portfolio.get(code);
        int ownedQuantity = holding == null ? 0 : holding.getQuantity();

        if (ownedQuantity < quantity) {
            throw new TradingException("보유 수량이 부족합니다.");
        }

        if (ownedQuantity == quantity) {
            portfolio.remove(code);
        } else {
            holding.removeQuantity(quantity);
        }
    }

    private String normalizeCode(String assetCode) {
        if (assetCode == null || assetCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Asset code is required.");
        }
        return assetCode.trim().toUpperCase();
    }
}

class Transaction {
    enum Type {
        BUY, SELL
    }

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Type type;
    private final String assetCode;
    private final int quantity;
    private final int unitPrice;
    private final LocalDateTime tradedAt;

    public Transaction(Type type, String assetCode, int quantity, int unitPrice) {
        this.type = type;
        this.assetCode = assetCode;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        tradedAt = LocalDateTime.now();
    }

    public String toCsv() {
        return TIME_FORMAT.format(tradedAt) + "," + type + "," + assetCode + "," + quantity + "," + unitPrice;
    }

    @Override
    public String toString() {
        return TIME_FORMAT.format(tradedAt) + " " + type + " " + assetCode
                + " x " + quantity + " at " + String.format("%,d", unitPrice) + " won";
    }
}

class CandleData {
    private final String date;
    private final int openPrice;
    private int highPrice;
    private int lowPrice;
    private int closePrice;

    public CandleData(String date, int openPrice, int highPrice, int lowPrice, int closePrice) {
        this.date = date;
        this.openPrice = openPrice;
        this.highPrice = highPrice;
        this.lowPrice = lowPrice;
        this.closePrice = closePrice;
    }

    public String getDate() {
        return date;
    }

    public int getOpenPrice() {
        return openPrice;
    }

    public int getHighPrice() {
        return highPrice;
    }

    public int getLowPrice() {
        return lowPrice;
    }

    public int getClosePrice() {
        return closePrice;
    }

    public boolean isRising() {
        return closePrice >= openPrice;
    }

    public void updateWithCurrentPrice(int currentPrice) {
        closePrice = currentPrice;
        highPrice = Math.max(highPrice, currentPrice);
        lowPrice = Math.min(lowPrice, currentPrice);
    }
}

class AssetInfo {
    private final String code;
    private final String name;
    private final String type;
    private final boolean typeGuessed;

    public AssetInfo(String code, String name, String type) {
        this(code, name, type, false);
    }

    public AssetInfo(String code, String name, String type, boolean typeGuessed) {
        this.code = code;
        this.name = name;
        this.type = type;
        this.typeGuessed = typeGuessed;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public boolean isTypeGuessed() {
        return typeGuessed;
    }

    public Asset createAsset(int price) {
        if ("ETF".equalsIgnoreCase(type)) {
            return new ETF(code, name, price);
        }
        return new Stock(code, name, price);
    }
}

enum ChartPeriod {
    DAILY("Daily", "D"),
    MONTHLY("Monthly", "M"),
    YEARLY("Yearly", "Y");

    private final String displayName;
    private final String apiCode;

    ChartPeriod(String displayName, String apiCode) {
        this.displayName = displayName;
        this.apiCode = apiCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getApiCode() {
        return apiCode;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
