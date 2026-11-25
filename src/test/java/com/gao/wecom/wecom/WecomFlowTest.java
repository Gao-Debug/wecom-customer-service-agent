package com.gao.wecom.wecom;

import com.gao.wecom.agent.CustomerSupportController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WecomFlowTest {
    @Test
    void verifiesCallbackAndRepliesViaCustomerServiceApi() throws Exception {
        var agent = mock(CustomerSupportController.class);
        when(agent.reply(anyString(), eq("导入失败怎么办？"))).thenReturn(
                new CustomerSupportController.MessageReply("请检查文件格式。", List.of(), List.of()));

        var builder = RestClient.builder().baseUrl("https://example.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = new WecomCustomerService(agent, "corp", "secret", "kf-test", builder.build());

        try {
            server.expect(requestTo("https://example.invalid/cgi-bin/gettoken?corpid=corp&corpsecret=secret"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("""
                            {"errcode":0,"access_token":"api-token","expires_in":7200}
                            """, MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://example.invalid/cgi-bin/kf/sync_msg?access_token=api-token"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.token").value("event-token"))
                    .andRespond(withSuccess("""
                            {"errcode":0,"next_cursor":"cursor-1","has_more":0,"msg_list":[
                            {"msgid":"m1","open_kfid":"kf-test","external_userid":"customer","origin":3,
                             "send_time":%d,"msgtype":"text","text":{"content":"导入失败怎么办？"}}
                            ]}
                            """.formatted(Instant.now().getEpochSecond()), MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://example.invalid/cgi-bin/kf/send_msg?access_token=api-token"))
                    .andExpect(method(HttpMethod.POST))
                    .andExpect(jsonPath("$.touser").value("customer"))
                    .andExpect(jsonPath("$.open_kfid").value("kf-test"))
                    .andExpect(jsonPath("$.text.content").value("请检查文件格式。"))
                    .andRespond(withSuccess("{\"errcode\":0}", MediaType.APPLICATION_JSON));

            String key = Base64.getEncoder().withoutPadding().encodeToString(new byte[32]);
            var crypto = new WecomCrypto(key, "corp");
            var callback = new WecomCallbackController(service, "test-token", key, "corp");
            String echo = crypto.encrypt("hello");
            assertEquals("hello", callback.verify(signature(echo), "123", "nonce", echo));

            String encrypted = crypto.encrypt("""
                    <xml><Event>kf_msg_or_event</Event><OpenKfId>kf-test</OpenKfId><Token>event-token</Token></xml>
                    """);
            String body = "<xml><Encrypt>" + encrypted + "</Encrypt></xml>";
            assertThrows(ResponseStatusException.class, () -> callback.receive("bad", "123", "nonce", body));
            assertEquals("success", callback.receive(signature(encrypted), "123", "nonce", body));

            server.verify(Duration.ofSeconds(5));
            verify(agent).reply(anyString(), eq("导入失败怎么办？"));
        } finally {
            service.stop();
        }
    }

    private static String signature(String encrypted) throws Exception {
        String[] parts = {"test-token", "123", "nonce", encrypted};
        Arrays.sort(parts);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8)));
    }
}
