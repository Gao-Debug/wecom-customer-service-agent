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

    public WecomCallbackController(CustomerSupportController support,
                                   @Value("${wecom.callback-token}") String token) {
        this.support = support;
        this.token = token;
    }

    /** 企业微信回调校验入口。正式环境应接入官方加解密组件，并使用 HTTPS 暴露。 */
    @GetMapping
    public String verify(@RequestParam String msg_signature,
                         @RequestParam String timestamp,
                         @RequestParam String nonce,
                         @RequestParam String echostr) {
        if (!validSignature(msg_signature, timestamp, nonce, echostr)) {
            throw new IllegalArgumentException("企业微信回调签名校验失败");
        }
        // 本地演示直接返回明文 echostr。配置 EncodingAESKey 后替换为 AES 解密结果。
        return echostr;
    }

    /** 接收企业微信 XML 消息。明文模式用于本地联调，加密模式接入官方 AES 解密后复用 handleMessage。 */
    @PostMapping(consumes = MediaType.APPLICATION_XML_VALUE, produces = MediaType.APPLICATION_XML_VALUE)
    public String receive(@RequestBody String body) {
        IncomingMessage message = parse(body);
        if (!"text".equalsIgnoreCase(message.type()) || message.content().isBlank()) {
            return "success";
        }
        String sessionId = message.userId().isBlank() ? message.messageId() : message.userId();
        MessageReply reply = support.reply(sessionId, message.content());
        log.info("企业微信客服消息已处理，userId={}, tools={}", message.userId(), reply.tools());
        return "<xml><ToUserName><![CDATA[" + escape(message.userId()) + "]]></ToUserName>"
                + "<FromUserName><![CDATA[agent]]></FromUserName>"
                + "<CreateTime>" + (System.currentTimeMillis() / 1000) + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + cdata(reply.answer()) + "]]></Content></xml>";
    }

    private boolean validSignature(String signature, String timestamp, String nonce, String echo) {
        try {
            String[] values = {token, timestamp, nonce, echo};
            Arrays.sort(values);
            String raw = String.join("", values);
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format("%02x", item));
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            return false;
        }
    }

    private static IncomingMessage parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return new IncomingMessage(text(document, "FromUserName"), text(document, "MsgType"),
                    text(document, "Content"), text(document, "MsgId"));
        } catch (Exception exception) {
            throw new IllegalArgumentException("企业微信消息 XML 无法解析", exception);
        }
    }

    private static String text(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private record IncomingMessage(String userId, String type, String content, String messageId) {}

    private static String escape(String value) {
        return value.replace("]", "");
    }

    private static String cdata(String value) {
        return value == null ? "" : value.replace("]]>", "]]]]><![CDATA[>");
    }
}
