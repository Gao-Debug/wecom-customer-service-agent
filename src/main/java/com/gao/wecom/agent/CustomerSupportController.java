package com.gao.wecom.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.tool.ToolExecution;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/support")
public class CustomerSupportController {
    private final CustomerSupportAgent agent;

    public CustomerSupportController(CustomerSupportAgent agent) {
        this.agent = agent;
    }

    public record MessageRequest(String sessionId, String message) {}
    public record MessageReply(String answer, List<String> tools, List<String> sources) {}

    @GetMapping("/health")
    public String health() {
        return "ready";
    }

    @PostMapping("/message")
    public MessageReply message(@RequestBody MessageRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        }
        if (request.message().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能超过 2000 字");
        }
        return reply(request.sessionId(), request.message());
    }

    public MessageReply reply(String sessionId, String message) {
        String actualSessionId = sessionId == null || sessionId.isBlank()
                ? UUID.randomUUID().toString() : sessionId;
        Result<String> result = agent.answer(actualSessionId, message);
        List<ToolExecution> executions = result.toolExecutions() == null
                ? List.of() : result.toolExecutions();
        List<String> sources = result.sources() == null
                ? List.of() : result.sources().stream()
                .map(source -> source.textSegment().text()).toList();
        return new MessageReply(result.content(),
                executions.stream().map(execution -> execution.request().name()).toList(), sources);
    }
}

