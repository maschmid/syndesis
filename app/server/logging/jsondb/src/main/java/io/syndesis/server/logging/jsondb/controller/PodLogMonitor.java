/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.server.logging.jsondb.controller;

import static io.syndesis.server.jsondb.impl.JsonRecordSupport.validateKey;
import static java.lang.String.format;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

import io.fabric8.kubernetes.api.model.Pod;
import io.syndesis.common.util.Json;
import io.syndesis.common.util.KeyGenerator;
import io.syndesis.server.endpoint.v1.handler.activity.Activity;
import io.syndesis.server.endpoint.v1.handler.activity.ActivityStep;
import io.syndesis.server.jsondb.JsonDBException;
import io.syndesis.server.openshift.OpenShiftService;

/**
 *
 */
class PodLogMonitor implements Consumer<InputStream> {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityTrackingController.class);

    private final ActivityTrackingController logsController;
    protected final AtomicBoolean markInOpenshift = new AtomicBoolean(true);
    protected final AtomicBoolean keepTrying = new AtomicBoolean(true);
    protected final String podName;
    protected final String integrationId;
    protected final String deploymentVersion;
    protected PodLogState state;
    protected HashMap<String, InflightData> inflightActivities = new HashMap<>();

    public PodLogMonitor(ActivityTrackingController logsController, Pod pod) throws IOException {
        this.logsController = logsController;
        this.podName = pod.getMetadata().getName();
        if (this.podName == null) {
            throw new IOException("Could not determine the pod name");
        }

        Map<String, String> labels = pod.getMetadata().getLabels();
        this.integrationId = labels.get(OpenShiftService.INTEGRATION_ID_LABEL);
        if (this.integrationId == null) {
            throw new IOException("Could not determine the integration id that is being run on the pod: " + this.podName);
        }

        this.deploymentVersion = labels.get(OpenShiftService.DEPLOYMENT_VERSION_LABEL);
        if (this.deploymentVersion == null) {
            throw new IOException("Could not determine the deployment version that is being run on the pod: " + this.podName);
        }
    }

    public void start() throws IOException {

        // We are just getting started, means we are in the openshift pod
        // list and we need to track pod log state.
        state = logsController.getPodLogState(podName);
        if (state == null) {
            state = new PodLogState();
            logsController.setPodLogState(podName, state);
        }
        LOG.info("Recovered state: {}", state);
        logsController.executor.execute(this::run);
    }

    public void run() {

        if (logsController.stopped.get() || !keepTrying.get() || !logsController.isPodRunning(podName)) {
            // Seems we don't need to keep trying, lets bail.
            return;
        }

        LOG.info("Getting controller for pod: {}", podName);
        try {
            logsController.watchLog(this.podName, this, this.state.time);
        } catch (IOException e) {
            LOG.info("Failure occurred while processing controller for pod: {}", podName, e);
            logsController.schedule(this::run, 5, TimeUnit.SECONDS);
        }
    }

    @Override
    public void accept(InputStream is) {
        if (is != null) {
            try {
                try {
                    processLogStream(is);
                } finally {
                    is.close();
                }
            } catch (InterruptedException | IOException e) {
                LOG.info("Failure occurred while processing controller for pod: {}", podName, e);
                logsController.schedule(this::run, 5, TimeUnit.SECONDS);
            }
        } else {
            logsController.schedule(this::run, 5, TimeUnit.SECONDS);
        }
    }

    private void processLogStream(final InputStream is) throws IOException, InterruptedException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;

        while (!logsController.stopped.get()) {
            c = is.read();
            if (c < 0) {
                break;
            }

            line.write(c);
            if (c == '\n') {
                processLine(line.toByteArray());
                line.reset();
            }

            // drop really long lines to avoid blowing up our memory.
            if (line.size() > 1024 * 4) {
                line.reset();
            }
        }

        if (!logsController.stopped.get()) {
            if (logsController.isPodRunning(podName)) {
                // odd, why did our stream end??  try to resume processing..
                LOG.info("End of Log stream for running pod: {}", podName);
                logsController.schedule(this::run, 5, TimeUnit.SECONDS);
            } else {
                // Seems like the normal case where stream ends because pod is stopped.
                LOG.info("End of Log stream for terminated pod: {}", podName);
            }
        }
    }

    private static class InflightData {
        Activity activity = new Activity();
        Map<String, ActivityStep> steps = new HashMap<>();
        Map<String, Object> metadata = new HashMap<>();

        public ActivityStep getStep(String step, String id) throws IOException {
            ActivityStep rc = steps.get(step);
            if( rc == null ) {
                rc = new ActivityStep();
                rc.setId(id);
                rc.setAt(KeyGenerator.getKeyTimeMillis(id));
                steps.put(step, rc);
            }
            return rc;
        }
    }

    InflightData getInflightData(String exchangeId, String logts) throws IOException {
        InflightData data = inflightActivities.get(exchangeId);
        if( data==null ) {
            data = new InflightData();
            data.activity.setPod(podName);
            data.activity.setVer(deploymentVersion);
            data.activity.setId(exchangeId);
            data.activity.setAt(KeyGenerator.getKeyTimeMillis(exchangeId));
            data.activity.setLogts(logts);
            inflightActivities.put(exchangeId, data);
        }
        return data;
    }

    @SuppressWarnings("PMD.CyclomaticComplexity")
    private void processLine(byte[] line) throws IOException {
        // Could it be a data of json structured output?

        // Log lines look like:
        // 2018-01-12T21:22:02.068338027Z { ..... }
        if (
            line.length < 32 // not long enough
                || line[30] != ' ' // expecting space
                || line[31] != '{' // expecting the json data starting here.
            ) {
            return;
        }

        String time = new String(line, 0, 30, StandardCharsets.US_ASCII);
        try {


            @SuppressWarnings("unchecked")
            Map<String, Object> json = Json.reader().forType(HashMap.class).readValue(line, 31, line.length - 31); //NOPMD

            // are the required fields set?
            String id = validate((String) json.remove("id"));
            String exchange = validate((String) json.remove("exchange"));

            InflightData inflightData = getInflightData(exchange, time);
            String step = (String) json.remove("step");
            if( step == null ) {
                // Looks like an exchange level logging event.

                Boolean failed = (Boolean) json.remove("failed");
                if( failed != null ) {
                    inflightData.activity.setFailed(failed);
                }


                String status = (String) json.remove("status");

                inflightData.metadata.putAll(json);

                if( status != null ) {
                    inflightData.activity.setStatus(status);

                    if( "done".equals(status) ) {
                        inflightData.activity.setSteps(new ArrayList<>(inflightData.steps.values()));
                        if( !inflightData.metadata.isEmpty() ) {
                            inflightData.activity.setMetadata(toJsonNode(inflightData.metadata));
                        }

                        String activityAsString = Json.writer().writeValueAsString(inflightData.activity);
                        String transactionPath = format("/exchanges/%s/%s", integrationId, exchange);
                        inflightActivities.remove(exchange);

                        logsController.eventQueue.put(batch -> {
                            // Do as little as possible in here, single thread processes the event queue.
                            batch.put(transactionPath, activityAsString);
                            trackState(time, batch);
                        });

                    }
                }

            } else {

                // Looks like a step level logging event.
                ActivityStep as = inflightData.getStep(step, id);
                String message = (String) json.remove("message");
                if( message!=null ) {
                    if( as.getMessages() == null ) {
                        as.setMessages(new ArrayList<>());
                    }
                    as.getMessages().add(message);
                }

                String failure = (String) json.remove("failure");
                if( failure !=null ) {
                    as.setFailure(failure);
                }

                Number duration = (Number) json.remove("duration");
                if( duration!=null ) {
                    as.setDuration(duration.longValue());
                }

                if( !json.isEmpty() ) {
                    if( as.getEvents() == null)  {
                        as.setEvents(new ArrayList<>());
                    }
                    as.getEvents().add(toJsonNode(json));
                }
            }

        } catch (JsonDBException | ClassCastException | IOException ignored) {
            /// log record not in the expected format.
        } catch (InterruptedException e) {
            final InterruptedIOException rethrow = new InterruptedIOException(e.getMessage());
            rethrow.initCause(e);
            throw rethrow;
        }
    }

    private JsonNode toJsonNode(Map<String, Object> json) throws IOException {
        return Json.reader().readTree(Json.writer().writeValueAsString(json));
    }

    private void trackState(String time, Map<String, Object> batch) {
        state.time = time;
        String podStatPath = "/pods/" + podName;
        batch.put(podStatPath, state);
        batch.put("/integrations/" + integrationId, Boolean.TRUE);
    }

    private String validate(String value) {
        if (value == null) {
            return null;
        }
        return validateKey(value);
    }


}
