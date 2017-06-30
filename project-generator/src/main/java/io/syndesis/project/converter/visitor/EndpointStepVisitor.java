/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.syndesis.project.converter.visitor;


import io.syndesis.integration.model.StepKinds;
import io.syndesis.integration.model.steps.Endpoint;
import io.syndesis.model.connection.Connector;
import io.syndesis.model.integration.Step;
import io.syndesis.project.converter.GenerateProjectRequest;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class EndpointStepVisitor implements StepVisitor {

    private static final String PLACEHOLDER_FORMAT = "{{%s}}";

    private final GeneratorContext generatorContext;

    public static class Factory implements StepVisitorFactory<EndpointStepVisitor> {

        @Override
        public String getStepKind() {
            return StepKinds.ENDPOINT;
        }

        @Override
        public EndpointStepVisitor create(GeneratorContext generatorContext) {
            return new EndpointStepVisitor(generatorContext);
        }
    }

    public EndpointStepVisitor(GeneratorContext generatorContext) {
        this.generatorContext = generatorContext;
    }

    @Override
    public void visit(StepVisitorContext visitorContext) {
        Step step = visitorContext.getStep();
        GenerateProjectRequest request = generatorContext.getRequest();
        step.getAction().ifPresent(action -> {
                step.getConnection().ifPresent(connection -> {
                    try {
                        String connectorId = step.getConnection().get().getConnectorId().orElse(action.getConnectorId());
                        if (!request.getConnectors().containsKey(connectorId)) {
                            throw new IllegalStateException("Connector:[" + connectorId + "] not found.");
                        }

                        Connector connector = request.getConnectors().get(connectorId);
                        generatorContext.getFlow().addStep(createEndpointStep(connector, action.getCamelConnectorPrefix(),
                            connection.getConfiguredProperties(), step.getConfiguredProperties().orElse(new HashMap<String, String>())));
                    } catch (IOException | URISyntaxException e) {
                        e.printStackTrace();
                    }
                });
            });
    }

    private io.syndesis.integration.model.steps.Step createEndpointStep(Connector connector, String camelConnectorPrefix, Map<String, String> connectionConfiguredProperties, Map<String, String> stepConfiguredProperties) throws IOException, URISyntaxException {
        Map<String, String> properties = connector.filterProperties(aggregate(connectionConfiguredProperties, stepConfiguredProperties), connector.isEndpointProperty());
        Map<String, String> secrets = connector.filterProperties(properties, connector.isSecret(),
            e -> e.getKey(),
            e -> String.format(PLACEHOLDER_FORMAT, camelConnectorPrefix + "." + e.getKey()));

        // TODO Remove this hack... when we can read endpointValues from connector schema then we should use those as initial properties.
        if ("periodic-timer".equals(camelConnectorPrefix)) {
            properties.put("timerName", "every");
        }

        Map<String, String> maskedProperties = generatorContext.getGeneratorProperties().isSecretMaskingEnabled() ? aggregate(properties, secrets) : properties;
        String endpointUri = generatorContext.getConnectorCatalog().buildEndpointUri(camelConnectorPrefix, maskedProperties);
        return new Endpoint(endpointUri);
    }

    private static Map<String, String> aggregate(Map<String, String> ... maps) throws IOException {
        return Stream.of(maps).flatMap(map -> map.entrySet().stream()).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (oldValue, newValue) -> newValue));
    }
}
