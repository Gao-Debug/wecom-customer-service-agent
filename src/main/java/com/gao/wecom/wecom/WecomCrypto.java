package com.gao.wecom.wecom;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

final class WecomCrypto {
    private final byte[] aesKey;
    private final String corpId;
    private final SecureRandom random = new SecureRandom();

    WecomCrypto(String encodingAesKey, String corpId) {
        this.aesKey = encodingAesKey == null || encodingAesKey.isBlank()
                ? null : Base64.getDecoder().decode(encodingAesKey + "=");
        this.corpId = corpId == null ? "" : corpId;
    }

    boolean enabled() {
        return aesKey != null;
    }

    String decrypt(String encrypted) {
        try {
            byte[] ciphertext = Base64.getDecoder().decode(encrypted);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(aesKey, 0, 16));
            byte[] padded = cipher.doFinal(ciphertext);
            byte[] plain = removePadding(padded);
            ByteBuffer buffer = ByteBuffer.wrap(plain);
            byte[] randomBytes = new byte[16];
            buffer.get(randomBytes);
            int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                throw new IllegalArgumentException("企业微信消息长度无效");
            }
            byte[] message = new byte[length];
            buffer.get(message);
            String receivedCorpId = new String(buffer.remaining() > 0
                    ? Arrays.copyOfRange(plain, buffer.position(), plain.length) : new byte[0],
                    StandardCharsets.UTF_8);
            if (!corpId.isBlank() && !corpId.equals(receivedCorpId)) {
                throw new IllegalArgumentException("企业微信 CorpId 不匹配");
            }
            return new String(message, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalArgumentException("企业微信消息解密失败", exception);
        }
    }

    String encrypt(String message) {
        try {
            byte[] content = message.getBytes(StandardCharsets.UTF_8);
            byte[] corp = corpId.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(16 + 4 + content.length + corp.length);
            byte[] randomBytes = new byte[16];
            random.nextBytes(randomBytes);
            buffer.put(randomBytes).putInt(content.length).put(content).put(corp);
            byte[] padded = addPadding(buffer.array());
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(aesKey, 0, 16));
            return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (Exception exception) {
            throw new IllegalArgumentException("企业微信消息加密失败", exception);
        }
    }

    private static byte[] addPadding(byte[] input) {
        int block = 32;
        int count = block - input.length % block;
        byte[] output = Arrays.copyOf(input, input.length + count);
        Arrays.fill(output, input.length, output.length, (byte) count);
        return output;
    }

    private static byte[] removePadding(byte[] input) {
        int count = input[input.length - 1] & 0xff;
        if (count < 1 || count > 32) throw new IllegalArgumentException("企业微信填充无效");
        for (int i = input.length - count; i < input.length; i++) {
            if ((input[i] & 0xff) != count) throw new IllegalArgumentException("企业微信填充无效");
        }
        return Arrays.copyOf(input, input.length - count);
    }
}
