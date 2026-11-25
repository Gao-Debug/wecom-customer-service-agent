package com.gao.wecom.agent;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CustomerSupportConfiguration {
    @Bean
    ChatMemoryProvider chatMemoryProvider() {
        return id -> MessageWindowChatMemory.builder().id(id).maxMessages(20).build();
    }

    @Bean
    EmbeddingModel embeddingModel(
            @Value("${DASHSCOPE_API_KEY:}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.base-url}") String baseUrl) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName("qwen3.7-text-embedding-flash")
                .timeout(Duration.ofSeconds(60))
                .maxRetries(0)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean
    EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    CustomerSupportAgent customerSupportAgent(
            dev.langchain4j.model.chat.ChatModel chatModel,
            RetrievalAugmentor retrievalAugmentor,
            ChatMemoryProvider chatMemoryProvider,
            CustomerSupportTools tools) {
        return AiServices.builder(CustomerSupportAgent.class)
                .chatModel(chatModel)
                .chatMemoryProvider(chatMemoryProvider)
                .retrievalAugmentor(retrievalAugmentor)
                .tools(tools)
                .build();
    }
}
