# 企业微信客服 Agent

这是一个独立的企业微信客服 Agent。项目使用 Spring Boot 和 LangChain4j，提供产品知识库问答、会话记忆和演示工单。企业微信入口采用「微信客服」API。

## 当前能力

- 企业产品知识库 RAG 问答
- 多轮会话和澄清提问
- 创建、查询客服工单
- 模拟转人工，工单保存在进程内存
- 微信客服回调验签、解密、异步拉取消息和发送文本回复
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
- `WECOM_ENABLED=true`
- `WECOM_CALLBACK_TOKEN`
- `WECOM_ENCODING_AES_KEY`
- `WECOM_KF_SECRET`，微信客服 Secret
- `WECOM_OPEN_KFID`，独立测试客服账号 ID
- `DASHSCOPE_API_KEY`

公网反向代理只转发 `/api/wecom/callback`。回调接收 `kf_msg_or_event` 并快速确认，后台调用 `kf/sync_msg` 拉取配置的测试客服账号消息，再用 `kf/send_msg` 发送文本回复。重启会清空进程内的游标、会话和工单。

手机联调时，从企微后台取得测试客服链接，用个人微信打开，发送「导入失败应该怎么排查？」。等待手机收到知识库答复。真实企微平台联调待完成。

## 面试演示

1. 用户询问产品操作方法，Agent 从知识库检索并回答。
2. 用户描述报错但信息不足，Agent 追问产品版本和错误码。
3. 用户明确要求人工处理，Agent 创建工单并返回工单号。
4. 用户提供工单号，Agent 查询工单状态。
5. 知识库没有依据时，Agent 拒绝编造并转人工。
