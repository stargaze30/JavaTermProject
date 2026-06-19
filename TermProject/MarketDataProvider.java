package TermProject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

interface MarketDataProvider {
    boolean isConfigured();

    AssetInfo searchAssetInfo(String keyword) throws TradingException;

    int getCurrentPrice(Asset asset) throws TradingException;

    List<CandleData> getCandles(String assetCode, ChartPeriod period) throws TradingException;

    List<CandleData> getIndexCandles(String indexCode, ChartPeriod period) throws TradingException;
}

class KisMarketDataProvider implements MarketDataProvider {
    private static final String APP_KEY_ENV = "KIS_APP_KEY";
    private static final String APP_SECRET_ENV = "KIS_APP_SECRET";
    private static final String BASE_URL = "https://openapivts.koreainvestment.com:29443";
    private static final String INQUIRE_PRICE_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-price";
    private static final String INQUIRE_PRICE_TR_ID = "FHKST01010100";
    private static final String DAILY_CHART_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
    private static final String DAILY_CHART_TR_ID = "FHKST03010100";
    private static final String INDEX_CHART_PATH =
            "/uapi/domestic-stock/v1/quotations/inquire-daily-indexchartprice";
    private static final String INDEX_CHART_TR_ID = "FHKUP03500100";
    private static final int MAX_DAILY_CANDLE_COUNT = 180;
    private static final int MAX_MONTHLY_CANDLE_COUNT = 120;
    private static final int MAX_YEARLY_CANDLE_COUNT = 30;
    private static final String KOSPI_INDEX_CODE = "0001";
    private static final String KOSDAQ_INDEX_CODE = "1001";
    private static final String PRODUCT_INFO_PATH =
            "/uapi/domestic-stock/v1/quotations/search-info";
    private static final String PRODUCT_INFO_TR_ID = "CTPF1604R";
    private static final String STOCK_INFO_PATH =
            "/uapi/domestic-stock/v1/quotations/search-stock-info";
    private static final String STOCK_INFO_TR_ID = "CTPF1002R";
    // 제출 시 kis_api_config.txt와 kis_token.txt는 포함하지 말 것
    private static final Path TOKEN_FILE = Paths.get("kis_token.txt");
    private static final long TOKEN_EXPIRY_SAFETY_MARGIN_MILLIS = 5 * 60 * 1000L;
    private static final long TOKEN_REFRESH_COOLDOWN_MILLIS = 60 * 1000L;

    private final HttpClient httpClient;
    private final String appKey;
    private final String appSecret;
    private String accessToken;
    private long tokenIssuedAt;
    private long tokenExpiresAt;
    private long lastTokenRefreshAttemptAt;
    private String approvalKey;

    public KisMarketDataProvider() {
        this(System.getenv(APP_KEY_ENV), System.getenv(APP_SECRET_ENV));
    }

    public KisMarketDataProvider(String appKey, String appSecret) {
        httpClient = HttpClient.newHttpClient();
        this.appKey = appKey;
        this.appSecret = appSecret;
    }

    @Override
    public boolean isConfigured() {
        return appKey != null && !appKey.isBlank()
                && appSecret != null && !appSecret.isBlank();
    }

    public String getApprovalKey() throws TradingException {
        if (!isConfigured()) {
            throw new TradingException("KIS API 키가 설정되지 않았습니다.");
        }
        ensureValidAccessToken();
        if (approvalKey != null && !approvalKey.isBlank()) {
            return approvalKey;
        }

        String requestBody = "{"
                + "\"grant_type\":\"client_credentials\","
                + "\"appkey\":\"" + escapeJson(appKey) + "\","
                + "\"secretkey\":\"" + escapeJson(appSecret) + "\""
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/oauth2/Approval"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new TradingException("KIS WebSocket approval key 발급 실패: HTTP "
                        + response.statusCode() + " / " + response.body());
            }

            approvalKey = readJsonStringValue(response.body(), "approval_key");
            if (approvalKey == null || approvalKey.isBlank()) {
                throw new TradingException("KIS WebSocket approval key를 찾지 못했습니다.");
            }
            return approvalKey;
        } catch (IOException e) {
            throw new TradingException("KIS WebSocket approval key 발급 중 통신 오류가 발생했습니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TradingException("KIS WebSocket approval key 발급이 중단되었습니다.");
        }
    }

    @Override
    public AssetInfo searchAssetInfo(String keyword) throws TradingException {
        if (!isConfigured()) {
            throw new TradingException("KIS API 키가 설정되지 않았습니다.");
        }
        if (keyword == null || keyword.trim().isEmpty()) {
            throw new TradingException("종목코드 또는 정확한 종목명을 입력하세요.");
        }

        String text = keyword.trim().toUpperCase();
        if (!text.matches("[0-9A-Z]{6}")) {
            throw new TradingException("종목명 검색은 아직 지원하지 않습니다. 종목코드로 입력해주세요.");
        }

        String responseBody = requestCurrentPrice(text);
        validateCurrentPriceResponse(responseBody);
        String productInfoBody = "";
        String stockInfoBody = "";
        String name = firstNonBlank(
                readJsonStringValue(responseBody, "hts_kor_isnm"),
                readJsonStringValue(responseBody, "prdt_abrv_name"),
                readJsonStringValue(responseBody, "prdt_name"),
                readJsonStringValue(responseBody, "kor_isnm"),
                readJsonStringValue(responseBody, "prdt_kor_name"),
                readJsonStringValue(responseBody, "stck_kor_isnm"));

        if (name == null || name.isBlank()) {
            try {
                productInfoBody = requestProductInfo(text);
            } catch (TradingException e) {
                productInfoBody = "";
            }
            name = firstNonBlank(
                    readJsonStringValue(productInfoBody, "prdt_name"),
                    readJsonStringValue(productInfoBody, "prdt_name120"),
                    readJsonStringValue(productInfoBody, "prdt_abrv_name"),
                    readJsonStringValue(productInfoBody, "kor_isnm"),
                    readJsonStringValue(productInfoBody, "prdt_kor_name"),
                    readJsonStringValue(productInfoBody, "stck_kor_isnm"));
        }

        if (name == null || name.isBlank()) {
            try {
                stockInfoBody = requestStockInfo(text);
            } catch (TradingException e) {
                stockInfoBody = "";
            }
            name = firstNonBlank(
                    readJsonStringValue(stockInfoBody, "prdt_name"),
                    readJsonStringValue(stockInfoBody, "prdt_name120"),
                    readJsonStringValue(stockInfoBody, "prdt_abrv_name"),
                    readJsonStringValue(stockInfoBody, "kor_isnm"),
                    readJsonStringValue(stockInfoBody, "prdt_kor_name"),
                    readJsonStringValue(stockInfoBody, "stck_kor_isnm"));
        }

        if (name == null || name.isBlank()) {
            name = text;
        }

        String type = detectAssetType(name, responseBody + productInfoBody + stockInfoBody);
        boolean typeGuessed = type == null;

        if (typeGuessed) {
            type = "Stock";
        }
        return new AssetInfo(text, name, type, typeGuessed);
    }

    @Override
    public int getCurrentPrice(Asset asset) throws TradingException {
        if (!isConfigured()) {
            throw new TradingException("KIS API 키가 설정되지 않았습니다.");
        }

        try {
            String kisCode = toKisCode(asset.getCode());
            String responseBody = requestCurrentPrice(kisCode);
            String priceText = readJsonStringValue(responseBody, "stck_prpr");
            if (priceText == null || priceText.isBlank()) {
                throw new TradingException("KIS 가격 응답에서 현재가를 찾지 못했습니다.");
            }
            return Integer.parseInt(priceText);
        } catch (NumberFormatException e) {
            throw new TradingException("KIS 가격 응답 형식이 올바르지 않습니다.");
        }
    }

    @Override
    public List<CandleData> getCandles(String assetCode, ChartPeriod period) throws TradingException {
        if (!isConfigured()) {
            throw new TradingException("KIS API 키가 설정되지 않았습니다.");
        }

        String kisCode = toKisCode(assetCode);
        ChartPeriod queryPeriod = period == null ? ChartPeriod.DAILY : period;
        LocalDate endDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate startDate = getChartStartDate(endDate, queryPeriod);
        List<CandleData> candles = normalizeCandles(
                fetchStockCandlePage(kisCode, queryPeriod, startDate, endDate),
                queryPeriod);

        if (candles.isEmpty()) {
            throw new TradingException("KIS 차트 응답에서 캔들 데이터를 찾지 못했습니다.");
        }
        return candles;
    }

    @Override
    public List<CandleData> getIndexCandles(String indexCode, ChartPeriod period)
            throws TradingException {
        if (!isConfigured()) {
            throw new TradingException("KIS API 키가 설정되지 않았습니다.");
        }

        String kisIndexCode = toKisIndexCode(indexCode);
        ChartPeriod queryPeriod = period == null ? ChartPeriod.DAILY : period;
        LocalDate endDate = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate startDate = getChartStartDate(endDate, queryPeriod);
        List<CandleData> candles = normalizeCandles(
                fetchIndexCandlePage(kisIndexCode, queryPeriod, startDate, endDate),
                queryPeriod);

        if (candles.isEmpty()) {
            throw new TradingException("KIS 지수 차트 응답에서 캔들 데이터를 찾지 못했습니다.");
        }
        return candles;
    }

    private List<CandleData> fetchStockCandlePage(String kisCode, ChartPeriod period,
            LocalDate startDate, LocalDate endDate) throws TradingException {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String body = requestKisGet(DAILY_CHART_PATH, DAILY_CHART_TR_ID,
                "?FID_COND_MRKT_DIV_CODE=J"
                        + "&FID_INPUT_ISCD=" + kisCode
                        + "&FID_INPUT_DATE_1=" + dateFormatter.format(startDate)
                        + "&FID_INPUT_DATE_2=" + dateFormatter.format(endDate)
                        + "&FID_PERIOD_DIV_CODE=" + period.getApiCode()
                        + "&FID_ORG_ADJ_PRC=0",
                "KIS 차트 조회 실패");
        return parseDailyCandles(body);
    }

    private List<CandleData> fetchIndexCandlePage(String kisIndexCode, ChartPeriod period,
            LocalDate startDate, LocalDate endDate) throws TradingException {
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        String body = requestKisGet(INDEX_CHART_PATH, INDEX_CHART_TR_ID,
                "?FID_COND_MRKT_DIV_CODE=U"
                        + "&FID_INPUT_ISCD=" + kisIndexCode
                        + "&FID_INPUT_DATE_1=" + dateFormatter.format(startDate)
                        + "&FID_INPUT_DATE_2=" + dateFormatter.format(endDate)
                        + "&FID_PERIOD_DIV_CODE=" + period.getApiCode(),
                "KIS 지수 차트 조회 실패");
        return parseIndexCandles(body);
    }

    private LocalDate getChartStartDate(LocalDate endDate, ChartPeriod period) {
        if (period == ChartPeriod.MONTHLY) {
            return endDate.minusYears(10);
        }
        if (period == ChartPeriod.YEARLY) {
            return endDate.minusYears(30);
        }
        return endDate.minusYears(1);
    }

    private List<CandleData> normalizeCandles(List<CandleData> candles, ChartPeriod period) {
        ArrayList<CandleData> normalizedCandles = new ArrayList<CandleData>();

        for (CandleData candle : candles) {
            if (candle.getDate() == null || candle.getDate().isBlank()) {
                continue;
            }

            boolean duplicate = false;
            for (CandleData savedCandle : normalizedCandles) {
                if (savedCandle.getDate().equals(candle.getDate())) {
                    duplicate = true;
                    break;
                }
            }

            if (!duplicate) {
                normalizedCandles.add(candle);
            }
        }

        Collections.sort(normalizedCandles,
                (left, right) -> left.getDate().compareTo(right.getDate()));

        int maxCount = getMaxCandleCount(period);
        if (normalizedCandles.size() > maxCount) {
            return new ArrayList<CandleData>(
                    normalizedCandles.subList(normalizedCandles.size() - maxCount,
                            normalizedCandles.size()));
        }
        return normalizedCandles;
    }

    private int getMaxCandleCount(ChartPeriod period) {
        if (period == ChartPeriod.MONTHLY) {
            return MAX_MONTHLY_CANDLE_COUNT;
        }
        if (period == ChartPeriod.YEARLY) {
            return MAX_YEARLY_CANDLE_COUNT;
        }
        return MAX_DAILY_CANDLE_COUNT;
    }

    private String requestProductInfo(String kisCode) throws TradingException {
        try {
            return requestKisGet(PRODUCT_INFO_PATH, PRODUCT_INFO_TR_ID,
                    "?PDNO=" + kisCode + "&PRDT_TYPE_CD=300", "KIS 상품 기본정보 조회 실패");
        } catch (TradingException e) {
            return requestKisGet(PRODUCT_INFO_PATH, PRODUCT_INFO_TR_ID,
                    "?pdno=" + kisCode + "&prdt_type_cd=300", "KIS 상품 기본정보 조회 실패");
        }
    }

    private String requestStockInfo(String kisCode) throws TradingException {
        return requestKisGet(STOCK_INFO_PATH, STOCK_INFO_TR_ID,
                "?PRDT_TYPE_CD=300&PDNO=" + kisCode, "KIS 주식 기본정보 조회 실패");
    }

    private String requestCurrentPrice(String kisCode) throws TradingException {
        return requestKisGet(INQUIRE_PRICE_PATH, INQUIRE_PRICE_TR_ID,
                "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + kisCode, "KIS 가격 조회 실패");
    }

    private void validateCurrentPriceResponse(String responseBody) throws TradingException {
        try {
            String priceText = readJsonStringValue(responseBody, "stck_prpr");

            if (priceText == null || priceText.isBlank() || parsePrice(priceText) <= 1) {
                throw new TradingException("종목 정보를 찾을 수 없습니다. 종목코드를 확인해주세요.");
            }
        } catch (NumberFormatException e) {
            throw new TradingException("종목 정보를 찾을 수 없습니다. 종목코드를 확인해주세요.");
        }
    }

    private String requestKisGet(String path, String trId, String query, String errorTitle)
            throws TradingException {
        HttpResponse<String> response = callApiWithTokenRetry(path, trId, query);

        if (response.statusCode() != 200) {
            throw new TradingException(errorTitle + ": HTTP "
                    + response.statusCode() + " / " + response.body());
        }

        String resultCode = readJsonStringValue(response.body(), "rt_cd");
        if (resultCode != null && !"0".equals(resultCode)) {
            String message = firstNonBlank(readJsonStringValue(response.body(), "msg1"),
                    readJsonStringValue(response.body(), "msg_cd"),
                    "종목 정보를 찾을 수 없습니다. 종목코드를 확인해주세요.");
            if (isTokenExpiredError(response.statusCode(), response.body())) {
                throw new TradingException("API 오류: 토큰이 유효하지 않습니다.");
            }
            throw new TradingException(message);
        }
        return response.body();
    }

    private HttpResponse<String> callApiWithTokenRetry(String path, String trId, String query)
            throws TradingException {
        try {
            HttpResponse<String> response = sendAuthorizedGet(path, trId, query);

            if (!isTokenExpiredError(response.statusCode(), response.body())) {
                return response;
            }

            debugLog("토큰 만료 감지: 새 토큰을 발급합니다.");
            forceRefreshAccessToken();
            response = sendAuthorizedGet(path, trId, query);

            if (isTokenExpiredError(response.statusCode(), response.body())) {
                throw new TradingException("API 오류: 토큰이 유효하지 않습니다.");
            }
            return response;
        } catch (IOException e) {
            throw new TradingException("KIS API 통신 오류가 발생했습니다.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TradingException("KIS API 요청이 중단되었습니다.");
        }
    }

    private HttpResponse<String> sendAuthorizedGet(String path, String trId, String query)
            throws IOException, InterruptedException, TradingException {
        String token = ensureValidAccessToken();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path + query))
                .header("authorization", "Bearer " + token)
                .header("appkey", appKey)
                .header("appsecret", appSecret)
                .header("tr_id", trId)
                .header("custtype", "P")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String ensureValidAccessToken() throws TradingException {
        loadSavedTokenIfNeeded();

        if (isAccessTokenUsable()) {
            return accessToken;
        }
        return issueAccessToken();
    }

    private boolean isAccessTokenUsable() {
        return accessToken != null && !accessToken.isBlank()
                && tokenExpiresAt > 0
                && System.currentTimeMillis() + TOKEN_EXPIRY_SAFETY_MARGIN_MILLIS < tokenExpiresAt;
    }

    private String forceRefreshAccessToken() throws TradingException {
        long now = System.currentTimeMillis();

        if (lastTokenRefreshAttemptAt > 0
                && now - lastTokenRefreshAttemptAt < TOKEN_REFRESH_COOLDOWN_MILLIS) {
            throw new TradingException("토큰 재발급 실패: 잠시 후 다시 시도하세요.");
        }
        accessToken = null;
        tokenIssuedAt = 0;
        tokenExpiresAt = 0;
        return issueAccessToken();
    }

    private String issueAccessToken() throws TradingException {
        lastTokenRefreshAttemptAt = System.currentTimeMillis();

        debugLog("토큰 발급 요청");

        String requestBody = "{"
                + "\"grant_type\":\"client_credentials\","
                + "\"appkey\":\"" + escapeJson(appKey) + "\","
                + "\"appsecret\":\"" + escapeJson(appSecret) + "\""
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/oauth2/tokenP"))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                debugLog("토큰 발급 실패 응답: " + response.body());
                throw new TradingException("토큰 재발급 실패: API 키를 확인하세요.");
            }

            accessToken = readJsonStringValue(response.body(), "access_token");
            if (accessToken == null || accessToken.isBlank()) {
                throw new TradingException("토큰 재발급 실패: API 키를 확인하세요.");
            }
            tokenIssuedAt = System.currentTimeMillis();
            tokenExpiresAt = resolveTokenExpiresAt(response.body(), tokenIssuedAt);
            saveTokenData();
            debugLog("토큰 재발급 성공");
            return accessToken;
        } catch (IOException e) {
            throw new TradingException("토큰 재발급 실패: API 키를 확인하세요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TradingException("토큰 재발급이 중단되었습니다.");
        }
    }

    private long resolveTokenExpiresAt(String body, long issuedAt) {
        String expiresInText = firstNonBlank(readJsonStringValue(body, "expires_in"),
                readJsonNumberValue(body, "expires_in"));

        if (expiresInText != null && !expiresInText.isBlank()) {
            try {
                return issuedAt + Long.parseLong(expiresInText.trim()) * 1000L;
            } catch (NumberFormatException e) {
                // 다른 필드 확인
            }
        }

        String expiredAtText = readJsonStringValue(body, "access_token_token_expired");
        long parsedTime = parseTokenExpiredAt(expiredAtText);

        if (parsedTime > 0) {
            return parsedTime;
        }
        return issuedAt + 24 * 60 * 60 * 1000L;
    }

    private long parseTokenExpiredAt(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            return LocalDateTime.parse(text.trim(), formatter)
                    .atZone(ZoneId.of("Asia/Seoul"))
                    .toInstant()
                    .toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void loadSavedTokenIfNeeded() {
        if (accessToken != null || !Files.exists(TOKEN_FILE)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(TOKEN_FILE)) {
            String firstLine = reader.readLine();

            if (firstLine == null) {
                return;
            }
            if (!firstLine.contains("=")) {
                accessToken = firstLine.trim();
                tokenExpiresAt = 0;
                return;
            }

            readTokenLine(firstLine);
            String line;
            while ((line = reader.readLine()) != null) {
                readTokenLine(line);
            }
        } catch (IOException e) {
            debugLog("토큰 파일 읽기 실패: " + e.getMessage());
        }
    }

    private void readTokenLine(String line) {
        String[] parts = line.split("=", 2);

        if (parts.length != 2) {
            return;
        }

        String key = parts[0].trim();
        String value = parts[1].trim();

        if ("accessToken".equals(key)) {
            accessToken = value;
        } else if ("tokenIssuedAt".equals(key)) {
            tokenIssuedAt = parseLong(value);
        } else if ("tokenExpiresAt".equals(key)) {
            tokenExpiresAt = parseLong(value);
        }
    }

    private long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void saveTokenData() {
        try (BufferedWriter writer = Files.newBufferedWriter(TOKEN_FILE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.write("accessToken=" + accessToken);
            writer.newLine();
            writer.write("tokenIssuedAt=" + tokenIssuedAt);
            writer.newLine();
            writer.write("tokenExpiresAt=" + tokenExpiresAt);
            writer.newLine();
        } catch (IOException e) {
            debugLog("토큰 파일 저장 실패: " + e.getMessage());
        }
    }

    private boolean isTokenExpiredError(int statusCode, String responseBody) {
        if (statusCode == 401 || statusCode == 403) {
            return true;
        }

        String body = responseBody == null ? "" : responseBody.toLowerCase();
        return body.contains("invalid_token")
                || body.contains("expired")
                || body.contains("기간이 만료")
                || body.contains("유효하지 않은 토큰")
                || body.contains("oauth");
    }

    private void debugLog(String message) {
        System.out.println(message);
    }

    private List<CandleData> parseDailyCandles(String json) {
        ArrayList<CandleData> candles = new ArrayList<CandleData>();
        String arrayText = readJsonArray(json, "output2");

        if (arrayText == null) {
            return candles;
        }

        for (String objectText : splitJsonObjects(arrayText)) {
            try {
                String date = readJsonStringValue(objectText, "stck_bsop_date");
                int openPrice = parsePrice(readJsonStringValue(objectText, "stck_oprc"));
                int highPrice = parsePrice(readJsonStringValue(objectText, "stck_hgpr"));
                int lowPrice = parsePrice(readJsonStringValue(objectText, "stck_lwpr"));
                int closePrice = parsePrice(readJsonStringValue(objectText, "stck_clpr"));

                if (date != null && !date.isBlank()) {
                    candles.add(new CandleData(date, openPrice, highPrice, lowPrice, closePrice));
                }
            } catch (NumberFormatException e) {
                // 일부 row 파싱 실패 시 건너뜀
            }
        }

        Collections.sort(candles, (left, right) -> left.getDate().compareTo(right.getDate()));
        return candles;
    }

    private List<CandleData> parseIndexCandles(String json) {
        ArrayList<CandleData> candles = new ArrayList<CandleData>();
        String arrayText = readJsonArray(json, "output2");

        if (arrayText == null) {
            return candles;
        }

        for (String objectText : splitJsonObjects(arrayText)) {
            try {
                String date = readJsonStringValue(objectText, "stck_bsop_date");
                int openPrice = parseIndexPrice(firstNonBlank(
                        readJsonStringValue(objectText, "bstp_nmix_oprc"),
                        readJsonStringValue(objectText, "stck_oprc")));
                int highPrice = parseIndexPrice(firstNonBlank(
                        readJsonStringValue(objectText, "bstp_nmix_hgpr"),
                        readJsonStringValue(objectText, "stck_hgpr")));
                int lowPrice = parseIndexPrice(firstNonBlank(
                        readJsonStringValue(objectText, "bstp_nmix_lwpr"),
                        readJsonStringValue(objectText, "stck_lwpr")));
                int closePrice = parseIndexPrice(firstNonBlank(
                        readJsonStringValue(objectText, "bstp_nmix_prpr"),
                        readJsonStringValue(objectText, "stck_clpr")));

                if (date != null && !date.isBlank()) {
                    candles.add(new CandleData(date, openPrice, highPrice, lowPrice, closePrice));
                }
            } catch (NumberFormatException e) {
                // 일부 row 파싱 실패 시 건너뜀
            }
        }

        Collections.sort(candles, (left, right) -> left.getDate().compareTo(right.getDate()));
        return candles;
    }

    private String readJsonArray(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);

        if (keyIndex < 0) {
            return null;
        }

        int arrayStart = json.indexOf("[", keyIndex + marker.length());
        if (arrayStart < 0) {
            return null;
        }

        int depth = 0;
        for (int i = arrayStart; i < json.length(); i++) {
            char ch = json.charAt(i);

            if (ch == '[') {
                depth++;
            } else if (ch == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(arrayStart + 1, i);
                }
            }
        }
        return null;
    }

    private List<String> splitJsonObjects(String arrayText) {
        ArrayList<String> objects = new ArrayList<String>();
        int depth = 0;
        int start = -1;

        for (int i = 0; i < arrayText.length(); i++) {
            char ch = arrayText.charAt(i);

            if (ch == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(arrayText.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private int parsePrice(String text) {
        if (text == null) {
            throw new NumberFormatException("Price is missing.");
        }
        return Integer.parseInt(text.replace(",", "").trim());
    }

    private int parseIndexPrice(String text) {
        if (text == null) {
            throw new NumberFormatException("Index price is missing.");
        }
        return (int) Math.round(Double.parseDouble(text.replace(",", "").trim()));
    }

    private String toKisCode(String assetCode) throws TradingException {
        if (assetCode != null && assetCode.matches("[0-9A-Z]{6}")) {
            return assetCode;
        }
        throw new TradingException("KIS 종목 코드가 준비되지 않았습니다: " + assetCode);
    }

    private String toKisIndexCode(String indexCode) throws TradingException {
        if ("KOSPI".equals(indexCode)) {
            return KOSPI_INDEX_CODE;
        }
        if ("KOSDAQ".equals(indexCode)) {
            return KOSDAQ_INDEX_CODE;
        }
        throw new TradingException("지원하지 않는 지수 코드입니다: " + indexCode);
    }

    private String detectAssetType(String name, String responseBody) {
        String text = (name + " " + responseBody).toUpperCase();

        if (text.contains("ETF") || text.contains("KODEX") || text.contains("TIGER")
                || text.contains("KOSEF") || text.contains("KBSTAR")
                || text.contains("ARIRANG") || text.contains("ACE")
                || text.contains("HANARO")) {
            return "ETF";
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String readJsonStringValue(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) {
            return null;
        }

        int colonIndex = json.indexOf(":", keyIndex + marker.length());
        int firstQuoteIndex = json.indexOf("\"", colonIndex + 1);
        int secondQuoteIndex = json.indexOf("\"", firstQuoteIndex + 1);

        if (colonIndex < 0 || firstQuoteIndex < 0 || secondQuoteIndex < 0) {
            return null;
        }
        return json.substring(firstQuoteIndex + 1, secondQuoteIndex);
    }

    private String readJsonNumberValue(String json, String key) {
        String marker = "\"" + key + "\"";
        int keyIndex = json.indexOf(marker);
        if (keyIndex < 0) {
            return null;
        }

        int colonIndex = json.indexOf(":", keyIndex + marker.length());
        if (colonIndex < 0) {
            return null;
        }

        int valueStart = colonIndex + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) {
            valueStart++;
        }

        int valueEnd = valueStart;
        while (valueEnd < json.length() && Character.isDigit(json.charAt(valueEnd))) {
            valueEnd++;
        }

        if (valueEnd == valueStart) {
            return null;
        }
        return json.substring(valueStart, valueEnd);
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
