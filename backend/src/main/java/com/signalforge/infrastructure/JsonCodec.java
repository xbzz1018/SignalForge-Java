package com.signalforge.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class JsonCodec {
    private final JsonMapper mapper = JsonMapper.builder().build();
    public String write(Object value) { return mapper.writeValueAsString(value); }
    public <T> T read(String value, Class<T> type) { return mapper.readValue(value, type); }
    public JsonNode tree(String value) { return mapper.readTree(value); }
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
