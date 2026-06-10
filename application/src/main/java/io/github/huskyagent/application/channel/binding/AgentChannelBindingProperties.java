package io.github.huskyagent.application.channel.binding;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
public class AgentChannelBindingProperties implements EnvironmentAware, InitializingBean {
    private Map<String, List<String>> bindings = new LinkedHashMap<>();
    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        if (environment == null) {
            return;
        }
        Map<String, List<String>> bound = bind(environment);
        if (!bound.isEmpty()) {
            setBindings(bound);
        }
    }

    public void setBindings(Map<String, List<String>> bindings) {
        this.bindings = bindings != null ? new LinkedHashMap<>(bindings) : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> bind(Environment environment) {
        ResolvableType listType = ResolvableType.forClassWithGenerics(List.class, String.class);
        ResolvableType mapType = ResolvableType.forClassWithGenerics(
                Map.class,
                ResolvableType.forClass(String.class),
                listType);
        return (Map<String, List<String>>) Binder.get(environment)
                .bind("agent-channel-bindings", Bindable.of(mapType))
                .orElseGet(LinkedHashMap::new);
    }
}
