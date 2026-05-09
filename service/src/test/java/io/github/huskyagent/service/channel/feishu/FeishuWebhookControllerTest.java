package io.github.huskyagent.service.channel.feishu;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeishuWebhookControllerTest {

    @Test
    void returnsChallengeWhenVerificationTokenMatches() throws Exception {
        FeishuWebhookController controller = controller("expected", true);
        byte[] body = """
                {"type":"url_verification","token":"expected","challenge":"abc"}
                """.getBytes();

        var response = controller.webhook("bot-a", body, new HttpHeaders());

        assertEquals(200, response.getStatusCode().value());
        assertEquals("{\"challenge\":\"abc\"}", new String(response.getBody()));
    }

    @Test
    void rejectsInvalidVerificationToken() throws Exception {
        FeishuWebhookController controller = controller("expected", true);
        byte[] body = """
                {"type":"url_verification","token":"wrong","challenge":"abc"}
                """.getBytes();

        var response = controller.webhook("bot-a", body, new HttpHeaders());

        assertEquals(500, response.getStatusCode().value());
    }

    @Test
    void returnsNotFoundWhenInstanceIsDisabled() throws Exception {
        FeishuWebhookController controller = controller("expected", false);
        byte[] body = "{}".getBytes();

        var response = controller.webhook("bot-a", body, new HttpHeaders());

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void returnsNotFoundWhenInstanceIsMissing() throws Exception {
        FeishuInstanceRegistry registry = mock(FeishuInstanceRegistry.class);
        FeishuWebhookController controller = new FeishuWebhookController(registry);
        byte[] body = "{}".getBytes();

        var response = controller.webhook("missing", body, new HttpHeaders());

        assertEquals(404, response.getStatusCode().value());
    }

    private FeishuWebhookController controller(String token, boolean enabled) {
        FeishuProperties.InstanceProperties properties = new FeishuProperties.InstanceProperties();
        properties.setEnabled(enabled);
        properties.setVerificationToken(token);
        FeishuInstance instance = new FeishuInstance(
                "bot-a",
                properties,
                mock(FeishuApiClient.class),
                mock(FeishuInstanceAdapter.class),
                new FeishuInstanceEventHandler(
                        properties,
                        mock(FeishuInstanceAdapter.class),
                        mock(io.github.huskyagent.application.channel.ChannelRuntimeService.class),
                        Runnable::run,
                        new FeishuInboundDeduplicator()
                )
        );
        FeishuInstanceRegistry registry = mock(FeishuInstanceRegistry.class);
        when(registry.find("bot-a")).thenReturn(Optional.of(instance));
        when(registry.all()).thenReturn(Map.of("bot-a", instance));
        return new FeishuWebhookController(registry);
    }
}