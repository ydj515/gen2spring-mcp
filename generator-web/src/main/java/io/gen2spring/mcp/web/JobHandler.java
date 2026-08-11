package io.gen2spring.mcp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.gen2spring.mcp.application.GeneratorApplication;
import java.io.InputStream;
import java.util.Objects;

final class JobHandler {
    private final GeneratorApplication application;
    private final SpecificationStore specifications;
    private final GenerationJobManager jobs;
    private final ObjectMapper json;
    private final BoundedBodyReader configurationReader;

    JobHandler(
            GeneratorApplication application,
            SpecificationStore specifications,
            GenerationJobManager jobs,
            ObjectMapper json) {
        this.application = Objects.requireNonNull(application, "application");
        this.specifications = Objects.requireNonNull(specifications, "specifications");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.json = Objects.requireNonNull(json, "json");
        this.configurationReader = new BoundedBodyReader(
                io.gen2spring.mcp.application.GenerationConfigurationParser.MAX_BYTES);
    }

    ObjectNode start(String specificationId, InputStream body) {
        SpecificationStore.StoredSpecification specification = specifications.retain(specificationId);
        JobSnapshot accepted;
        try {
            byte[] configuration = configurationReader.read(body);
            var request = application.configurationParser().parseJson(configuration);
            accepted = jobs.submit(
                    specification.path(), request, () -> specifications.release(specificationId));
        } catch (Error | RuntimeException failure) {
            specifications.release(specificationId);
            throw failure;
        }
        ObjectNode response = json.createObjectNode();
        response.put("id", accepted.id());
        response.put("state", JobSnapshot.State.QUEUED.name());
        return response;
    }

    ObjectNode status(String id) {
        return snapshot(jobs.snapshot(id));
    }

    void delete(String id) {
        jobs.delete(id);
    }

    private ObjectNode snapshot(JobSnapshot snapshot) {
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
