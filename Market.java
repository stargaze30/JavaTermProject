package TermProject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

class Market {
    private static final int API_REQUEST_DELAY_MS = 850;

    private final ArrayList<Asset> listedAssets;

    public Market() {
        listedAssets = new ArrayList<Asset>();
    }

    public static Market createDefaultMarket() {
        Market market = new Market();
        for (AssetInfo assetInfo : AssetCatalog.getDefaultAssets()) {
            market.addAsset(assetInfo.createAsset(1));
        }
        return market;
    }

    public void addAsset(Asset asset) {
        if (asset == null) {
            throw new IllegalArgumentException("Asset is required.");
        }
        if (findAsset(asset.getCode()) != null) {
            throw new IllegalArgumentException("이미 등록된 자산입니다.");
        }
        listedAssets.add(asset);
    }

    public List<Asset> getListedAssets() {
        return Collections.unmodifiableList(listedAssets);
    }

    public Asset findAsset(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        String normalizedCode = code.trim().toUpperCase();
        for (Asset asset : listedAssets) {
            if (asset.getCode().equals(normalizedCode)) {
                return asset;
            }
        }
        return null;
    }

    public Asset findAssetByCodeOrName(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }
        String text = keyword.trim();
        String normalizedCode = text.toUpperCase();

        for (Asset asset : listedAssets) {
            if (asset.getCode().equals(normalizedCode) || asset.getName().equals(text)) {
                return asset;
            }
        }
        return null;
    }

    public void updatePrices(MarketDataProvider provider) {
        for (Asset asset : listedAssets) {
            try {
                int currentPrice = provider.getCurrentPrice(asset);
                asset.setPrice(currentPrice);
                System.out.println(asset.getCode() + " 가격 업데이트 완료: " + currentPrice);
            } catch (TradingException e) {
                System.out.println(asset.getCode() + " 가격 업데이트 실패: " + e.getMessage());
            }

            try {
                Thread.sleep(API_REQUEST_DELAY_MS);
            } catch (InterruptedException e) {
                System.out.println("가격 업데이트가 중단되었습니다.");
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void updatePrice(String code, MarketDataProvider provider) throws TradingException {
        Asset asset = findAsset(code);

        if (asset == null) {
            throw new TradingException("존재하지 않는 자산입니다.");
        }
        int currentPrice = provider.getCurrentPrice(asset);
        asset.setPrice(currentPrice);
        System.out.println(asset.getCode() + " 가격 업데이트 완료: " + currentPrice);
    }

    public void updatePriceFromRealtime(String code, int price) {
        Asset asset = findAsset(code);

        if (asset != null && price > 1) {
            asset.setPrice(price);
        }
    }
}

class AssetCatalog {
    private static final ArrayList<AssetInfo> ASSETS = new ArrayList<AssetInfo>();

    static {
        ASSETS.add(new AssetInfo("005930", "삼성전자", "Stock"));
        ASSETS.add(new AssetInfo("000660", "SK하이닉스", "Stock"));
        ASSETS.add(new AssetInfo("009150", "삼성전기", "Stock"));
        ASSETS.add(new AssetInfo("035420", "NAVER", "Stock"));
        ASSETS.add(new AssetInfo("035720", "카카오", "Stock"));
        ASSETS.add(new AssetInfo("005380", "현대차", "Stock"));
        ASSETS.add(new AssetInfo("000270", "기아", "Stock"));
        ASSETS.add(new AssetInfo("051910", "LG화학", "Stock"));
        ASSETS.add(new AssetInfo("068270", "셀트리온", "Stock"));
        ASSETS.add(new AssetInfo("207940", "삼성바이오로직스", "Stock"));
        ASSETS.add(new AssetInfo("069500", "KODEX 200", "ETF"));
        ASSETS.add(new AssetInfo("360750", "TIGER 미국S&P500", "ETF"));
        ASSETS.add(new AssetInfo("133690", "TIGER 미국나스닥100", "ETF"));
        ASSETS.add(new AssetInfo("0193T0", "KODEX SK하이닉스단일종목레버리지", "ETF"));
        ASSETS.add(new AssetInfo("0183J0", "TIGER 미국우주테크", "ETF"));
    }

    public static List<AssetInfo> getDefaultAssets() {
        return Collections.unmodifiableList(ASSETS);
    }
}

class AssetMaster {
    private static final Charset MASTER_CHARSET = Charset.forName("MS949");

    private final HashMap<String, AssetInfo> byCode;
    private final HashMap<String, AssetInfo> byName;
    private boolean available;

    public AssetMaster(Path path) {
        byCode = new HashMap<String, AssetInfo>();
        byName = new HashMap<String, AssetInfo>();
        load(path);
    }

    public boolean isAvailable() {
        return available;
    }

    public AssetInfo findByCode(String code) {
        if (code == null) {
            return null;
        }
        return byCode.get(code.trim().toUpperCase());
    }

    public AssetInfo findByExactName(String name) {
        if (name == null) {
            return null;
        }
        return byName.get(name.trim());
    }

    public AssetInfo findByCodeOrExactName(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }

        String text = keyword.trim();
        if (text.toUpperCase().matches("[0-9A-Z]{6}")) {
            return findByCode(text);
        }
        return findByExactName(text);
    }

    private void load(Path path) {
        if (path == null || !Files.exists(path)) {
            System.out.println("kospi_code.mst 파일이 없어 종목명 자동 검색을 사용할 수 없습니다.");
            available = false;
            return;
        }

        // 한국투자증권 종목 마스터 파일을 이용해 종목코드, 종목명, 주식/ETF 구분을 로컬에서 조회한다.
        try (BufferedReader reader = Files.newBufferedReader(path, MASTER_CHARSET)) {
            String line;

            while ((line = reader.readLine()) != null) {
                AssetInfo assetInfo = parseLine(line);

                if (assetInfo == null) {
                    continue;
                }
                byCode.put(assetInfo.getCode(), assetInfo);
                byName.putIfAbsent(assetInfo.getName(), assetInfo);
            }
            available = !byCode.isEmpty();
        } catch (IOException e) {
            System.out.println("kospi_code.mst 파일을 읽지 못했습니다: " + e.getMessage());
            available = false;
        }
    }

    private AssetInfo parseLine(String line) {
        if (line == null) {
            return null;
        }

        byte[] bytes = line.getBytes(MASTER_CHARSET);
        if (bytes.length < 63) {
            return null;
        }

        String code = readMasterField(bytes, 0, 9).toUpperCase();
        String name = readMasterField(bytes, 21, 40);
        String rawType = readMasterField(bytes, 61, 2);

        if (code.isBlank() || name.isBlank()) {
            return null;
        }

        String type = "Stock";
        if ("EF".equals(rawType)) {
            type = "ETF";
        } else if ("ST".equals(rawType)) {
            type = "Stock";
        }
        return new AssetInfo(code, name, type);
    }

    private String readMasterField(byte[] bytes, int start, int length) {
        return new String(bytes, start, length, MASTER_CHARSET).trim();
    }
}
