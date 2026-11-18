package com.signalforge.knowledge;

import com.signalforge.infrastructure.BoundedHttpBody;
import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.platform.ApiException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Fixed, minimal connectivity probe. It never receives documents or user prompts. */
@Component
public class ModelConnection {
    public record Status(String provider,String model,boolean configured,boolean connected,String checkedAt,
                         String errorCode,int totalTokens,boolean extractionEnabled) {}
    private final String key,model;
    private final JsonCodec json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile Status last;
    private Instant attempted=Instant.EPOCH;
    public ModelConnection(JsonCodec json,@Value("${signalforge.deepseek-api-key:}") String key,
                           @Value("${signalforge.deepseek-model:deepseek-flash}") String model) {
        this.json=json; this.key=key; this.model=model;
        last=new Status("deepseek",model,!key.isBlank(),false,null,null,0,!key.isBlank());
    }
    public Status status() { return last; }
    public synchronized Status probe() {
        if (key.isBlank()) throw new ApiException(409,"MODEL_NOT_CONFIGURED","本地尚未配置 DeepSeek 密钥");
        if (attempted.plusSeconds(60).isAfter(Instant.now()))
            throw new ApiException(429,"MODEL_PROBE_RATE_LIMITED","连接测试每分钟最多一次");
        attempted=Instant.now();
        String checked=attempted.toString();
        String error="MODEL_UNAVAILABLE";
        try {
            var request=HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
                    .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+key).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.write(Map.of("model",model,"max_tokens",32,"stream",false,
                        "thinking",Map.of("type","disabled"),"messages",List.of(Map.of("role","user","content","Reply exactly OK.")))))).build();
            var response=client.send(request,info->new BoundedHttpBody(65_536));
            if (response.statusCode()!=200) error="MODEL_HTTP_"+response.statusCode();
            else {
                var root=json.tree(new String(response.body(),StandardCharsets.UTF_8));
                boolean ok=root.path("choices").path(0).path("message").path("content").asText().strip().equals("OK");
                last=new Status("deepseek",model,true,ok,checked,ok?null:"MODEL_UNEXPECTED_REPLY",root.path("usage").path("total_tokens").asInt(),true);
                return last;
            }
        } catch (Exception ignored) { /* Never log provider responses, headers or secrets. */ }
        last=new Status("deepseek",model,true,false,checked,error,0,!key.isBlank());
        return last;
    }
}
