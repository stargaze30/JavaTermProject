package TermProject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

interface Trading {
    void buy(User user, Asset asset, int quantity) throws TradingException;

    void sell(User user, Asset asset, int quantity) throws TradingException;
}

class TradingService implements Trading {
    private final ArrayList<Transaction> transactionHistory;

    public TradingService() {
        transactionHistory = new ArrayList<Transaction>();
    }

    @Override
    public void buy(User user, Asset asset, int quantity) throws TradingException {
        validateTrade(user, asset, quantity);
        int totalPrice = calculateTotalPrice(asset, quantity);

        user.withdraw(totalPrice);
        user.addHolding(asset.getCode(), quantity, asset.getPrice());
        record(Transaction.Type.BUY, asset, quantity);
    }

    @Override
    public void sell(User user, Asset asset, int quantity) throws TradingException {
        validateTrade(user, asset, quantity);
        int totalPrice = calculateTotalPrice(asset, quantity);

        user.removeHolding(asset.getCode(), quantity);
        user.receiveTradeProceeds(totalPrice);
        record(Transaction.Type.SELL, asset, quantity);
    }

    public Transaction getLastTransaction() {
        if (transactionHistory.isEmpty()) {
            return null;
        }
        return transactionHistory.get(transactionHistory.size() - 1);
    }

    public List<Transaction> getTransactionHistory() {
        return Collections.unmodifiableList(transactionHistory);
    }

    private void validateTrade(User user, Asset asset, int quantity) throws TradingException {
        if (user == null || asset == null) {
            throw new TradingException("거래할 사용자와 자산이 필요합니다.");
        }
        if (asset.getPrice() <= 1) {
            throw new TradingException("현재가 업데이트 후 거래할 수 있습니다.");
        }
        if (quantity < 1) {
            throw new TradingException("1 이상의 수량을 입력하세요.");
        }
    }

    private int calculateTotalPrice(Asset asset, int quantity) throws TradingException {
        long totalPrice = (long) asset.getPrice() * quantity;

        if (totalPrice > Integer.MAX_VALUE) {
            throw new TradingException("거래 금액이 너무 큽니다.");
        }
        return (int) totalPrice;
    }

    private void record(Transaction.Type type, Asset asset, int quantity) {
        transactionHistory.add(new Transaction(type, asset.getCode(), quantity, asset.getPrice()));
    }
}
