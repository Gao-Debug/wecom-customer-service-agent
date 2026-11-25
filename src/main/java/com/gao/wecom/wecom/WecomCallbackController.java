package com.gao.wecom.wecom;

import com.gao.wecom.agent.CustomerSupportController;
import com.gao.wecom.agent.CustomerSupportController.MessageReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

@RestController
@RequestMapping("/api/wecom/callback")
public class WecomCallbackController {
    private static final Logger log = LoggerFactory.getLogger(WecomCallbackController.class);
    private final CustomerSupportController support;
    private final String token;
    private final WecomCrypto crypto;

    public WecomCallbackController(CustomerSupportController support,
                                   @Value("${wecom.callback-token}") String token,
                                   @Value("${wecom.encoding-aes-key:}") String aesKey,
                                   @Value("${wecom.corp-id:}") String corpId) {
        this.support = support;
        this.token = token;
        this.crypto = new WecomCrypto(aesKey, corpId);
    }

    @GetMapping
    public String verify(@RequestParam("msg_signature") String signature,
                         @RequestParam String timestamp,
                         @RequestParam String nonce,
                         @RequestParam String echostr) {
        if (!validSignature(signature, timestamp, nonce, echostr)) {
            throw new IllegalArgumentException("企业微信回调签名校验失败");
        }
        return crypto.enabled() ? crypto.decrypt(echostr) : echostr;
    }

    @PostMapping(consumes = MediaType.APPLICATION_XML_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public String receive(@RequestParam(value = "msg_signature", required = false) String signature,
                           @RequestParam(value = "timestamp", required = false) String timestamp,
                           @RequestParam(value = "nonce", required = false) String nonce,
                           @RequestBody String body) {
        String encrypted = text(parse(body), "Encrypt");
        String plain = encrypted.isBlank() ? body : decryptAndVerify(signature, timestamp, nonce, encrypted);
        IncomingMessage message = parseMessage(plain);
        if (!"text".equalsIgnoreCase(message.type()) || message.content().isBlank()) return "success";
        String sessionId = message.userId().isBlank() ? message.messageId() : message.userId();
        MessageReply reply = support.reply(sessionId, message.content());
        log.info("企业微信客服消息已处理，userId={}, tools={}", message.userId(), reply.tools());
        String response = textReply(message.userId(), reply.answer());
        if (!crypto.enabled()) return response;
        String encryptedReply = crypto.encrypt(response);
        String time = String.valueOf(System.currentTimeMillis() / 1000);
        return "<xml><Encrypt><![CDATA[" + encryptedReply + "]]></Encrypt><MsgSignature><![CDATA["
                + signature(time, nonce, encryptedReply) + "]]></MsgSignature><TimeStamp>" + time
                + "</TimeStamp><Nonce><![CDATA[" + cdata(nonce) + "]]></Nonce></xml>";
    }

    private String decryptAndVerify(String signature, String timestamp, String nonce, String encrypted) {
        if (signature == null || timestamp == null || nonce == null
                || !validSignature(signature, timestamp, nonce, encrypted)) {
            throw new IllegalArgumentException("企业微信消息签名校验失败");
        }
        return crypto.decrypt(encrypted);
    }

    private boolean validSignature(String supplied, String timestamp, String nonce, String encrypted) {
        return MessageDigest.isEqual(signature(timestamp, nonce, encrypted)
                .getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private String signature(String timestamp, String nonce, String encrypted) {
        try {
            String[] values = {token, timestamp, nonce, encrypted};
            Arrays.sort(values);
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", values).getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (byte item : digest) output.append(String.format("%02x", item));
            return output.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算企业微信签名", exception);
        }
    }

    private static IncomingMessage parseMessage(String xml) {
        Document document = parse(xml);
        return new IncomingMessage(text(document, "FromUserName"), text(document, "MsgType"),
                text(document, "Content"), text(document, "MsgId"));
    }

    private static Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("企业微信消息 XML 无法解析", exception);
        }
    }

    private static String text(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private record IncomingMessage(String userId, String type, String content, String messageId) {}

    private static String textReply(String userId, String answer) {
        return "<xml><ToUserName><![CDATA[" + cdata(userId) + "]]></ToUserName>"
                + "<FromUserName><![CDATA[agent]]></FromUserName><CreateTime>"
                + (System.currentTimeMillis() / 1000) + "</CreateTime><MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + cdata(answer) + "]]></Content></xml>";
    }

    private static String cdata(String value) {
        return value == null ? "" : value.replace("]]>", "]]]]><![CDATA[>");
    }
}
