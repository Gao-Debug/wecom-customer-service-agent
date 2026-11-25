package com.gao.wecom.agent;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CustomerSupportTools {
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public record Ticket(String ticketNo, String summary, String status, String createdAt) {}

    @Tool("创建客服工单。当用户的问题无法通过知识库解决，或明确要求人工处理时使用。")
    public Ticket createSupportTicket(String summary) {
        String ticketNo = "CS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Ticket ticket = new Ticket(ticketNo, summary, "待人工处理", OffsetDateTime.now().toString());
        tickets.put(ticketNo, ticket);
        return ticket;
    }

    @Tool("查询客服工单状态。只有用户提供工单号时才调用。")
    public Ticket queryTicketStatus(String ticketNo) {
        return tickets.getOrDefault(ticketNo,
                new Ticket(ticketNo, "未找到对应工单", "不存在", OffsetDateTime.now().toString()));
    }

    @Tool("将当前会话转交人工客服。当用户明确要求人工，或证据不足无法安全回答时使用。")
    public String handoffToHuman(String reason) {
        return "已提交人工客服接管请求，原因：" + reason;
    }
}
