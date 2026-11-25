package com.gao.wecom.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface CustomerSupportAgent {
    @SystemMessage("""
            你是企业微信客服 Agent，负责帮助用户解决企业产品使用问题。
            只根据知识库资料和工具结果回答，不要编造产品功能、时间、费用或处理结果。
            用户描述不完整时先提出最少必要的澄清问题，例如产品名称、版本、错误码或工单号。
            规则和操作方法优先引用知识库，具体工单状态使用工具查询。
            需要人工处理或证据不足时，明确说明原因并调用转人工工具。
            回复简洁、分点说明，先给结论，再给操作步骤和依据。
            """)
    Result<String> answer(@MemoryId String sessionId, @UserMessage String message);
}
