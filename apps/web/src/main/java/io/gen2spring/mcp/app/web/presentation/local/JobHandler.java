package io.gen2spring.mcp.app.web.presentation.local;

import io.gen2spring.mcp.app.web.application.local.io.BoundedBodyReader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.app.web.application.local.job.JobSnapshot;
import io.gen2spring.mcp.app.web.application.local.service.LocalGenerationService;
import java.io.InputStream;
import java.util.Objects;

public final class JobHandler {
    private final LocalGenerationService generation;
    private final ObjectMapper json;
    private final BoundedBodyReader configurationReader;

    public JobHandler(
            LocalGenerationService generation,
            ObjectMapper json,
            int maximumConfigurationBytes) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.json = Objects.requireNonNull(json, "json");
        this.configurationReader = new BoundedBodyReader(maximumConfigurationBytes);
    }

    ObjectNode start(String specificationId, InputStream body) {
        byte[] configuration = configurationReader.read(body);
        JobSnapshot accepted = generation.submit(specificationId, configuration);
        ObjectNode response = json.createObjectNode();
        response.put("id", accepted.id());
        response.put("state", JobSnapshot.State.QUEUED.name());
        return response;
    }

    ObjectNode status(String id) {
        return snapshotPayload(generation.snapshot(id));
    }

    void delete(String id) {
        generation.delete(id);
    }

    /**
     * Serializes one snapshot. The polling endpoint and the event stream share this
     * method so the two transports can never drift into different payload shapes.
     */
    public ObjectNode snapshotPayload(JobSnapshot snapshot) {
        ObjectNode root = json.createObjectNode();
        root.put("id", snapshot.id());
        root.put("state", snapshot.state().name());
        if (snapshot.currentStage() == null) {
            root.putNull("currentStage");
        } else {
            root.put("currentStage", snapshot.currentStage());
        }
        ArrayNode stages = root.putArray("stages");
        snapshot.stages().forEach(stage -> {
            ObjectNode value = stages.addObject();
            value.put("stage", stage.stage());
            value.put("status", stage.status().name());
        });
        if (snapshot.validationStatus() == null) {
            root.putNull("validationStatus");
        } else {
            root.put("validationStatus", snapshot.validationStatus().name());
        }
        if (snapshot.error() == null) {
            root.putNull("error");
        } else {
            ObjectNode error = root.putObject("error");
            error.put("code", snapshot.error().code());
            error.put("stage", snapshot.error().stage());
            error.put("message", snapshot.error().message());
        }
        ArrayNode downloads = root.putArray("downloads");
        snapshot.downloads().forEach(downloads::add);
        return root;
    }
}
