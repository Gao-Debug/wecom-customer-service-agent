# 企业微信客服 Agent

这是一个独立的企业微信微信客服 Agent。个人微信用户通过企业微信微信客服入口发起咨询，后端使用 Spring Boot 和 LangChain4j 完成知识库检索、会话记忆、工单工具调用和人工转接。

## 当前能力

- 企业产品知识库 RAG 问答
- 多轮会话和澄清提问
- 创建、查询客服工单
- 无法确认时转人工
- 企业微信回调校验和 XML 消息处理
- 本地 API 联调，不依赖前端页面

## 启动

环境要求为 JDK 21、Maven 和可访问的模型 API。

```powershell
$env:DASHSCOPE_API_KEY = "你的百炼 API Key"
.\mvnw.cmd spring-boot:run
```

健康检查：`GET http://127.0.0.1:8090/api/support/health`

本地消息测试：

```powershell
Invoke-RestMethod http://127.0.0.1:8090/api/support/message `
  -Method Post -ContentType 'application/json' `
  -Body '{"sessionId":"user-001","message":"系统提示导入失败，应该怎么排查？"}'
```

## 企业微信配置

在企业微信微信客服后台配置回调地址：

```text
https://你的域名/api/wecom/callback
```

环境变量：

- `WECOM_CORP_ID`
- `WECOM_CALLBACK_TOKEN`
- `WECOM_ENCODING_AES_KEY`
- `DASHSCOPE_API_KEY`

当前代码保留了明文回调联调入口。接入正式环境前，需要在回调控制器中接入企业微信官方 AES 加解密流程，并使用 HTTPS 公网地址。

## 面试演示

1. 用户询问产品操作方法，Agent 从知识库检索并回答。
2. 用户描述报错但信息不足，Agent 追问产品版本和错误码。
3. 用户明确要求人工处理，Agent 创建工单并返回工单号。
4. 用户提供工单号，Agent 查询工单状态。
5. 知识库没有依据时，Agent 拒绝编造并转人工。
