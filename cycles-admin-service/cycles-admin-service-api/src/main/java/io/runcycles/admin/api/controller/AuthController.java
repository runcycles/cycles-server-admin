package io.runcycles.admin.api.controller;
import io.runcycles.admin.data.repository.ApiKeyRepository;
import io.runcycles.admin.model.auth.ApiKeyValidationRequest;
import io.runcycles.admin.model.auth.ApiKeyValidationResponse;
import io.runcycles.admin.api.service.EventService;
import io.runcycles.admin.model.event.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/v1/auth") @Tag(name = "Authentication")
public class AuthController {
    @Autowired private ApiKeyRepository repository;
    @Autowired private EventService eventService;
    @Autowired private ObjectMapper objectMapper;
    @PostMapping("/validate") @Operation(operationId = "validateApiKey")
    public ResponseEntity<ApiKeyValidationResponse> validate(@Valid @RequestBody ApiKeyValidationRequest request, HttpServletRequest httpRequest) {
        ApiKeyValidationResponse response = repository.validate(request.getKeySecret());
        if (!response.isValid()) {
            try {
                eventService.emit(EventType.API_KEY_AUTH_FAILED, response.getTenantId(), null, "cycles-admin",
                    Actor.builder().type(ActorType.API_KEY)
                        .sourceIp(httpRequest.getRemoteAddr()).build(),
                    objectMapper.convertValue(EventDataApiKey.builder()
                        .keyId(response.getKeyId()).failureReason(response.getReason())
                        .sourceIp(httpRequest.getRemoteAddr()).build(), Map.class),
                    null, httpRequest.getAttribute("requestId") != null ? httpRequest.getAttribute("requestId").toString() : null);
            } catch (Exception e) {
                // Non-blocking: don't break the business operation
            }
        }
        return ResponseEntity.ok(response);
    }
}
