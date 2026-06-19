package TermProject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

interface PriceUpdateListener {
    void onPriceUpdated(String assetCode, int price);

    void onWebSocketStatus(String message);
}

class RealtimePriceClient {
    private static final String REALTIME_URL = "ws://ops.koreainvestment.com:21000";
    private static final String REALTIME_PRICE_TR_ID = "H0STCNT0";
    private static final int REALTIME_PRICE_FIELD_COUNT = 46;
    private static final int WS_FIELD_ASSET_CODE = 0;
    // 한투 실시간체결 현재가 필드 위치
    private static final int WS_FIELD_CURRENT_PRICE = 2;
    private static final boolean DEBUG_WEBSOCKET_RAW = false;
    private static final boolean DEBUG_WEBSOCKET_PARSED_PRICE = false;

    private final HttpClient httpClient;
    private final MarketDataProvider marketDataProvider;
    private final PriceUpdateListener listener;
    private WebSocket webSocket;
    private String approvalKey;
    private String aesKey;
    private String aesIv;
    private final ArrayList<AesContext> aesContexts;

    public RealtimePriceClient(MarketDataProvider marketDataProvider,
            PriceUpdateListener listener) {
        httpClient = HttpClient.newHttpClient();
        this.marketDataProvider = marketDataProvider;
        this.listener = listener;
        aesContexts = new ArrayList<AesContext>();
    }

    public void connect() throws TradingException {
        if (!(marketDataProvider instanceof KisMarketDataProvider)) {
            throw new TradingException("KIS WebSocket provider를 사용할 수 없습니다.");
        }

        approvalKey = ((KisMarketDataProvider) marketDataProvider).getApprovalKey();
        try {
            webSocket = httpClient.newWebSocketBuilder()
                    .buildAsync(URI.create(REALTIME_URL), new KisWebSocketListener())
                    .join();
        } catch (RuntimeException e) {
            throw new TradingException("KIS WebSocket 연결 실패: " + e.getMessage());
        }
    }

    public void subscribeAll(List<Asset> assets) throws TradingException {
        if (assets == null || assets.isEmpty()) {
            throw new TradingException("구독할 종목이 없습니다.");
        }

        for (Asset asset : assets) {
            try {
                subscribe(asset.getCode());
            } catch (TradingException e) {
                notifyStatus("WebSocket 구독 실패: " + asset.getCode()
                        + " / " + e.getMessage());
            }
        }
    }

    public void subscribe(String assetCode) throws TradingException {
        if (webSocket == null) {
            throw new TradingException("WebSocket이 연결되지 않았습니다.");
        }
        if (assetCode == null || !assetCode.matches("[0-9A-Z]{6}")) {
            throw new TradingException("WebSocket 종목 코드가 올바르지 않습니다.");
        }

        String message = "{"
                + "\"header\":{"
                + "\"approval_key\":\"" + escapeJson(approvalKey) + "\","
                + "\"custtype\":\"P\","
                + "\"tr_type\":\"1\","
                + "\"content-type\":\"utf-8\""
                + "},"
                + "\"body\":{\"input\":{"
                + "\"tr_id\":\"" + REALTIME_PRICE_TR_ID + "\","
                + "\"tr_key\":\"" + assetCode + "\""
                + "}}"
                + "}";
        webSocket.sendText(message, true).join();
    }

    public void close() {
        try {
            if (webSocket != null) {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "close").join();
            }
        } catch (RuntimeException e) {
            // 종료 중 오류는 무시
        } finally {
            webSocket = null;
        }
    }

    private void handleMessage(WebSocket socket, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (message.contains("\n") || message.contains("\r")) {
            String[] messages = message.split("\\r?\\n");
            for (String singleMessage : messages) {
                handleMessage(socket, singleMessage);
            }
            return;
        }

        if (DEBUG_WEBSOCKET_RAW) {
            System.out.println("WS raw: " + shorten(message, 200));
        }

        if (isSubscribeSuccessMessage(message)) {
            saveAesKeyAndIv(message);
            return;
        }
        if (isEncryptedRealtimeMessage(message)) {
            handleEncryptedRealtimeMessage(message);
            return;
        }
        if (isPlainRealtimeMessage(message)) {
            handleRealtimePriceMessage(message);
            return;
        }
        if (message.contains("PINGPONG")) {
            socket.sendPong(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
            return;
        }
        if (message.contains("\"rt_cd\":\"1\"")) {
            notifyStatus("WebSocket: 실패");
        }
    }

    private boolean isSubscribeSuccessMessage(String message) {
        return message.contains("SUBSCRIBE SUCCESS")
                || message.contains("\"msg_cd\":\"OPSP0000\"")
                || (message.startsWith("{")
                        && message.contains(REALTIME_PRICE_TR_ID)
                        && message.contains("\"output\"")
                        && message.contains("\"key\"")
                        && message.contains("\"iv\""));
    }

    private void saveAesKeyAndIv(String message) {
        String key = readJsonStringValue(message, "key");
        String iv = readJsonStringValue(message, "iv");

        if (key != null && !key.isBlank() && iv != null && !iv.isBlank()) {
            aesKey = key;
            aesIv = iv;
            saveAesContext(key, iv);
            debug("websocket aes key/iv saved");
        }
    }

    private void saveAesContext(String key, String iv) {
        for (AesContext context : aesContexts) {
            if (context.key.equals(key) && context.iv.equals(iv)) {
                return;
            }
        }
        aesContexts.add(new AesContext(key, iv));
    }

    private void handleEncryptedRealtimeMessage(String message) {
        String[] parts = message.split("\\|", 4);

        if (parts.length < 4 || !REALTIME_PRICE_TR_ID.equals(parts[1])) {
            debug("invalid encrypted realtime message: " + shorten(message, 120));
            return;
        }
        if (aesContexts.isEmpty() && (aesKey == null || aesKey.isBlank()
                || aesIv == null || aesIv.isBlank())) {
            debug("missing websocket aes key/iv");
            return;
        }

        String decryptedText = decryptRealtimePayload(parts[3]);
        if (decryptedText == null || decryptedText.isBlank()) {
            return;
        }

        String plainRealtimeMessage = decryptedText.contains("|" + REALTIME_PRICE_TR_ID + "|")
                ? decryptedText
                : "0|" + REALTIME_PRICE_TR_ID + "|" + parts[2] + "|" + decryptedText;
        handleRealtimePriceMessage(plainRealtimeMessage);
    }

    private String decryptRealtimePayload(String encryptedPayload) {
        for (AesContext context : aesContexts) {
            String decryptedText = decryptRealtimePayload(encryptedPayload, context.key, context.iv);
            if (decryptedText != null && !decryptedText.isBlank()) {
                return decryptedText;
            }
        }
        return decryptRealtimePayload(encryptedPayload, aesKey, aesIv);
    }

    private String decryptRealtimePayload(String encryptedPayload, String key, String iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKeySpec keySpec = new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(
                    iv.getBytes(StandardCharsets.UTF_8));
            byte[] encryptedBytes = decodeBase64(encryptedPayload);

            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8).trim();
        } catch (RuntimeException e) {
            debug("websocket payload decrypt failed: " + e.getMessage());
            return null;
        } catch (Exception e) {
            debug("websocket payload decrypt failed: " + e.getMessage());
            return null;
        }
    }

    private byte[] decodeBase64(String encryptedPayload) {
        String text = encryptedPayload == null ? "" : encryptedPayload.trim();
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return Base64.getMimeDecoder().decode(text);
        }
    }

    private void handleRealtimePriceMessage(String message) {
        String[] parts = message.split("\\|", 4);

        if (parts.length < 4 || !REALTIME_PRICE_TR_ID.equals(parts[1])) {
            debug("invalid realtime message: " + shorten(message, 120));
            return;
        }

        int dataCount = parsePositiveInt(parts[2], 1);
        String[] fields = parts[3].split("\\^", -1);

        for (int i = 0; i < dataCount; i++) {
            int offset = i * REALTIME_PRICE_FIELD_COUNT;

            if (fields.length <= offset + 2) {
                debug("realtime field count is too small: " + fields.length);
                return;
            }

            String assetCode = normalizeRealtimeAssetCode(fields[offset + WS_FIELD_ASSET_CODE]);
            int price = parsePositiveInt(fields[offset + WS_FIELD_CURRENT_PRICE], -1);

            if (assetCode.matches("[0-9A-Z]{6}") && price > 1 && listener != null) {
                if (DEBUG_WEBSOCKET_PARSED_PRICE) {
                    System.out.println("parsed realtime price: " + assetCode + " " + price);
                }
                listener.onPriceUpdated(assetCode, price);
            } else {
                debug("ignored realtime price: code=" + assetCode + ", price=" + price);
            }
        }
    }

    private String normalizeRealtimeAssetCode(String assetCode) {
        if (assetCode == null) {
            return "";
        }
        String code = assetCode.trim().toUpperCase();
        if (code.length() == 7 && code.startsWith("A")) {
            return code.substring(1);
        }
        return code;
    }

    private boolean isPlainRealtimeMessage(String message) {
        return (message.startsWith("0|") || message.contains("|" + REALTIME_PRICE_TR_ID + "|"))
                && message.contains(REALTIME_PRICE_TR_ID);
    }

    private boolean isEncryptedRealtimeMessage(String message) {
        return message.startsWith("1|") && message.contains(REALTIME_PRICE_TR_ID);
    }

    private String shorten(String message, int limit) {
        if (message == null || message.length() <= limit) {
            return message;
        }
        return message.substring(0, limit) + "...";
    }

    private int parsePositiveInt(String text, int defaultValue) {
        try {
            String normalizedText = text.replace(",", "")
                    .replace("+", "")
                    .replace("-", "")
                    .trim();
            return Integer.parseInt(normalizedText);
        } catch (RuntimeException e) {
            debug("number parse failed: " + text);
            return defaultValue;
        }
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

    private void debug(String message) {
        if (DEBUG_WEBSOCKET_RAW || DEBUG_WEBSOCKET_PARSED_PRICE) {
            System.out.println(message);
        }
    }

    private void notifyStatus(String message) {
        if (listener != null) {
            listener.onWebSocketStatus(message);
        }
    }

    private String escapeJson(String text) {
        return text == null ? "" : text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private class KisWebSocketListener implements WebSocket.Listener {
        private final StringBuilder messageBuffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            messageBuffer.append(data);

            if (last) {
                String message = messageBuffer.toString();
                messageBuffer.setLength(0);
                handleMessage(webSocket, message);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            String message = StandardCharsets.UTF_8.decode(data).toString();
            handleMessage(webSocket, message);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            notifyStatus("WebSocket: 실패");
        }
    }

    private static class AesContext {
        private final String key;
        private final String iv;

        private AesContext(String key, String iv) {
            this.key = key;
            this.iv = iv;
        }
    }
}
