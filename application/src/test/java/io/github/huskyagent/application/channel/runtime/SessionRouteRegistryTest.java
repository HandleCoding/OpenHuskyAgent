package io.github.huskyagent.application.channel.runtime;

import io.github.huskyagent.infra.channel.ChannelIdentity;
import io.github.huskyagent.infra.channel.ChannelType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionRouteRegistryTest {

    @Test
    void registersFindsAndUnregistersRoute() {
        SessionRouteRegistry registry = new SessionRouteRegistry();
        SessionRoute route = new SessionRoute(
                "s1",
                ChannelType.FEISHU,
                ChannelIdentity.builder().channelType(ChannelType.FEISHU).build(),
                null,
                "m1"
        );

        registry.register(route);

        assertEquals(route, registry.find("s1").orElseThrow());
        registry.unregister("s1");
        assertTrue(registry.find("s1").isEmpty());
    }

    @Test
    void unregisterRouteDoesNotRemoveNewerRouteForSameSession() {
        SessionRouteRegistry registry = new SessionRouteRegistry();
        SessionRoute first = new SessionRoute(
                "s1",
                ChannelType.FEISHU,
                ChannelIdentity.builder().channelType(ChannelType.FEISHU).build(),
                null,
                "m1"
        );
        SessionRoute second = new SessionRoute(
                "s1",
                ChannelType.FEISHU,
                ChannelIdentity.builder().channelType(ChannelType.FEISHU).build(),
                null,
                "m2"
        );

        registry.register(first);
        registry.register(second);
        registry.unregister(first);

        assertEquals(second, registry.find("s1").orElseThrow());
    }

    @Test
    void ignoresBlankSessionIds() {
        SessionRouteRegistry registry = new SessionRouteRegistry();

        registry.register(new SessionRoute("", ChannelType.FEISHU, null, null, null));

        assertTrue(registry.find("").isEmpty());
    }
}