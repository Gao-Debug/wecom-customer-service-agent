package com.gao.wecom.wecom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.RejectedExecutionException;

@RestController
@RequestMapping(value = "/api/wecom/callback", produces = "text/plain;charset=UTF-8")
@ConditionalOnProperty(name = "wecom.enabled", havingValue = "true")
public class WecomCallbackController {
    private final WecomCustomerService support;
    private final String token;
    private final WecomCrypto crypto;

    public WecomCallbackController(WecomCustomerService support,
                                   @Value("${wecom.callback-token}") String token,
                                   @Value("${wecom.encoding-aes-key}") String aesKey,
                                   @Value("${wecom.corp-id}") String corpId) {
        if (token.isBlank() || "change-me".equals(token) || !aesKey.matches("[a-zA-Z0-9+/]{43}")) {
            throw new IllegalArgumentException("启用微信客服前请配置回调 Token 和 43 位 EncodingAESKey");
        }
        this.support = support;
        this.token = token;
        this.crypto = new WecomCrypto(aesKey, corpId);
    }

    @GetMapping
    public String verify(@RequestParam("msg_signature") String signature,
                         @RequestParam String timestamp,
                         @RequestParam String nonce,
                         @RequestParam String echostr) {
        return verifyAndDecrypt(signature, timestamp, nonce, echostr);
    }

    @PostMapping
    public String receive(@RequestParam("msg_signature") String signature,
                          @RequestParam String timestamp,
                          @RequestParam String nonce,
                          @RequestBody String body) {
        String encrypted = text(parse(body), "Encrypt");
        Document event = parse(verifyAndDecrypt(signature, timestamp, nonce, encrypted));
        if ("kf_msg_or_event".equals(text(event, "Event"))) {
            try {
                support.onNotification(text(event, "OpenKfId"), text(event, "Token"));
            } catch (RejectedExecutionException ex) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
        return "success";
    }

    private String verifyAndDecrypt(String signature, String timestamp, String nonce, String encrypted) {
        String[] values = {token, timestamp, nonce, encrypted};
        Arrays.sort(values);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", values).getBytes(StandardCharsets.UTF_8));
            byte[] expected = HexFormat.of().formatHex(digest).getBytes(StandardCharsets.US_ASCII);
            if (encrypted.isBlank() || !MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.US_ASCII))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            }
            return crypto.decrypt(encrypted);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private static Document parse(String xml) {
        if (xml.length() > 65536) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
    }

    private static String text(Document document, String tag) {
        var nodes = document.getDocumentElement().getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }
}
