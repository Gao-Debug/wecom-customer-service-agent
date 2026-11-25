package com.gao.wecom.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.gao.wecom.agent.CustomerSupportController;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "wecom.enabled", havingValue = "true")
class WecomCustomerService {
    private static final Logger LOG = LoggerFactory.getLogger(WecomCustomerService.class);
    private static final int CUSTOMER_ORIGIN = 3;
    private final CustomerSupportController support;
    private final RestClient client;
    private final String corpId;
    private final String secret;
    private final String accountId;
    private final long startedAt = Instant.now().getEpochSecond();
    // 单个测试客服账号的通知串行处理，确保游标按页推进。
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
    private final LinkedHashMap<String, PendingReply> replies = new LinkedHashMap<>();
    private String cursor = "";
    private String accessToken;
    private long refreshAt;

    @Autowired
    WecomCustomerService(CustomerSupportController support,
                         @Value("${wecom.corp-id}") String corpId,
                         @Value("${wecom.kf-secret}") String secret,
                         @Value("${wecom.open-kfid}") String accountId) {
        this(support, corpId, secret, accountId, createClient());
    }

    WecomCustomerService(CustomerSupportController support, String corpId, String secret,
                         String accountId, RestClient client) {
        if (corpId.isBlank() || secret.isBlank() || accountId.isBlank()) {
            throw new IllegalArgumentException("启用微信客服前请配置企业 ID、微信客服 Secret 和测试客服账号 ID");
        }
        this.support = support;
        this.corpId = corpId;
        this.secret = secret;
        this.accountId = accountId;
        this.client = client;
    }

    private static RestClient createClient() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(10000);
        return RestClient.builder().requestFactory(requestFactory)
                .baseUrl("https://qyapi.weixin.qq.com").build();
    }

    void onNotification(String openKfId, String eventToken) {
        if (!accountId.equals(openKfId) || eventToken.isBlank()) return;
        worker.execute(() -> {
            try {
                sync(eventToken);
            } catch (PlatformException ex) {
                LOG.warn("微信客服处理失败，接口 {}，平台错误码 {}", ex.operation, ex.code);
            } catch (RuntimeException ex) {
                LOG.warn("微信客服处理失败，异常类型 {}", ex.getClass().getSimpleName());
            }
        });
    }

    private void sync(String eventToken) {
        boolean hasMore;
        do {
            JsonNode page = post("sync_msg", Map.of(
                    "cursor", cursor, "token", eventToken, "limit", 100, "open_kfid", accountId));
            for (JsonNode message : page.path("msg_list")) {
                handle(message);
            }
            hasMore = page.path("has_more").asInt() == 1;
            String next = page.path("next_cursor").asText();
            if (hasMore && (next.isBlank() || next.equals(cursor))) {
                throw new PlatformException("sync_msg", -1);
            }
            if (!next.isBlank()) {
                cursor = next;
            }
        } while (hasMore);
    }

    private void handle(JsonNode message) {
        if (!accountId.equals(message.path("open_kfid").asText())
                || message.path("origin").asInt() != CUSTOMER_ORIGIN
                || message.path("send_time").asLong() < startedAt
                || !"text".equals(message.path("msgtype").asText())) return;

        String messageId = message.path("msgid").asText();
        String userId = message.path("external_userid").asText();
        String question = message.path("text").path("content").asText();
        if (messageId.isBlank() || userId.isBlank() || question.isBlank()) return;

        PendingReply reply = replies.computeIfAbsent(messageId, id -> {
            String sessionId = UUID.nameUUIDFromBytes((accountId + ":" + userId)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            String answer = support.reply(sessionId, question).answer();
            if (answer == null || answer.isBlank()) {
                answer = "暂未获得答复，请稍后重试。";
            }
            return new PendingReply(limitText(answer), false);
        });
        if (reply.sent()) return;

        String replyId = UUID.nameUUIDFromBytes((accountId + ":" + messageId)
                .getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        post("send_msg", Map.of("touser", userId, "open_kfid", accountId, "msgid", replyId,
                "msgtype", "text", "text", Map.of("content", reply.content())));
        replies.put(messageId, new PendingReply(reply.content(), true));
        if (replies.size() > 1000) {
            replies.pollFirstEntry();
        }
    }

    private JsonNode post(String operation, Map<String, ?> payload) {
        JsonNode response = request(operation, payload);
        int code = response == null ? -1 : response.path("errcode").asInt(-1);
        if (code == 40014 || code == 42001) {
            accessToken = null;
            response = request(operation, payload);
        }
        return checked(operation, response);
    }

    private JsonNode request(String operation, Map<String, ?> payload) {
        String currentToken = token();
        return client.post().uri(builder -> builder.path("/cgi-bin/kf/" + operation)
                        .queryParam("access_token", currentToken).build())
                .body(payload).retrieve().body(JsonNode.class);
    }

    private String token() {
        long now = Instant.now().getEpochSecond();
        if (accessToken != null && now < refreshAt) return accessToken;
        JsonNode response = checked("gettoken", client.get()
                .uri(builder -> builder.path("/cgi-bin/gettoken")
                        .queryParam("corpid", corpId).queryParam("corpsecret", secret).build())
                .retrieve().body(JsonNode.class));
        accessToken = response.path("access_token").asText();
        if (accessToken.isBlank()) throw new PlatformException("gettoken", -1);
        refreshAt = now + Math.max(1, response.path("expires_in").asLong() - 120);
        return accessToken;
    }

    private static JsonNode checked(String operation, JsonNode response) {
        int code = response == null ? -1 : response.path("errcode").asInt(-1);
        if (code != 0) throw new PlatformException(operation, code);
        return response;
    }

    private static String limitText(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length <= 2048) return text;
        // 每个 Unicode 码点最多占 4 个 UTF-8 字节。
        return text.substring(0, text.offsetByCodePoints(0, 500)) + "…";
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }

    private record PendingReply(String content, boolean sent) {}

    private static final class PlatformException extends RuntimeException {
        private final String operation;
        private final int code;

        private PlatformException(String operation, int code) {
            this.operation = operation;
            this.code = code;
        }
    }
}
