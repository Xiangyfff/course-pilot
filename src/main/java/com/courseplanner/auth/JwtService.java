package com.courseplanner.auth;

import com.courseplanner.common.BizException;
import com.courseplanner.common.ErrorCode;
import com.courseplanner.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小的 HS256 JWT 实现：不引第三方库，规则看得见、可审计。
 * 生产环境请换掉手写实现（时钟偏移、kid 轮换、算法混淆都需要额外处理）。
 */
@Service
public class JwtService {

    private static final String HEADER = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");

    private final byte[] secret;
    private final long ttlSeconds;
    private final ObjectMapper mapper;

    public JwtService(AppProperties props, ObjectMapper mapper) {
        this.secret = props.getAuth().getJwtSecret().getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = props.getAuth().getTokenTtlHours() * 3600;
        this.mapper = mapper;
        if (secret.length < 32) {
            throw new IllegalStateException("app.auth.jwt-secret 至少 32 字节");
        }
    }

    public String issue(long userId, String username) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", String.valueOf(userId));
        claims.put("name", username);
        claims.put("iat", Instant.now().getEpochSecond());
        claims.put("exp", Instant.now().getEpochSecond() + ttlSeconds);
        String payload;
        try {
            payload = base64Url(mapper.writeValueAsString(claims));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String signingInput = HEADER + "." + payload;
        return signingInput + "." + base64Url(sign(signingInput));
    }

    public long parseUserId(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "token 格式错误");
            }
            String expected = base64Url(sign(parts[0] + "." + parts[1]));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "token 签名无效");
            }
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode node = mapper.readTree(payload);
            long exp = node.path("exp").asLong();
            if (Instant.now().getEpochSecond() > exp) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期");
            }
            return Long.parseLong(node.path("sub").asText());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "token 无法解析", e);
        }
    }

    private byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
