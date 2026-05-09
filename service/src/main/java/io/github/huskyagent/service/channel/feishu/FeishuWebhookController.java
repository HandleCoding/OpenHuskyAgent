package io.github.huskyagent.service.channel.feishu;

import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.core.response.EventResp;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/feishu")
public class FeishuWebhookController {

    private final FeishuInstanceRegistry registry;

    public FeishuWebhookController(FeishuInstanceRegistry registry) {
        this.registry = registry;
    }

    @PostConstruct
    public void init() {
        registry.all().values().stream()
                .filter(instance -> instance.properties().isEnabled())
                .filter(instance -> "webhook".equalsIgnoreCase(instance.properties().getTransport()))
                .forEach(instance -> instance.apiClient().initBotOpenId());
    }

    @PostMapping("/{instanceId}/webhook")
    public ResponseEntity<byte[]> webhook(@PathVariable String instanceId,
                                          @RequestBody byte[] body,
                                          @RequestHeader HttpHeaders headers) {
        FeishuInstance instance = registry.find(instanceId).orElse(null);
        if (instance == null || !instance.properties().isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        EventReq request = new EventReq();
        request.setHttpPath("/api/feishu/" + instanceId + "/webhook");
        request.setBody(body);
        request.setHeaders(headers.headerSet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toLowerCase(),
                        entry -> entry.getValue().stream().toList(),
                        (left, right) -> left
                )));
        try {
            EventResp response = instance.eventHandler().larkEventDispatcher().handle(request);
            return ResponseEntity.status(HttpStatus.valueOf(response.getStatusCode()))
                    .headers(toHttpHeaders(response.getHeaders()))
                    .body(response.getBody());
        } catch (Throwable error) {
            log.error("Failed to handle Feishu webhook: instanceId={}", instanceId, error);
            return ResponseEntity.internalServerError()
                    .body(("{\"msg\":\"" + error.getMessage() + "\"}").getBytes(StandardCharsets.UTF_8));
        }
    }

    private HttpHeaders toHttpHeaders(Map<String, List<String>> headers) {
        HttpHeaders result = new HttpHeaders();
        if (headers == null) {
            return result;
        }
        headers.forEach(result::put);
        return result;
    }
}
