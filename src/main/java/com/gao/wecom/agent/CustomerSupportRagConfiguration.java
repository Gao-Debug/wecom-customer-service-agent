package com.gao.wecom.agent;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;`r`nimport dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.content.injector.DefaultContentInjector;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class CustomerSupportRagConfiguration {
    @Bean
    DefaultRetrievalAugmentor customerSupportRag(
            EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) throws IOException {
        Document document = Document.from(
                new ClassPathResource("customer-support-knowledge.md")
                        .getContentAsString(StandardCharsets.UTF_8));
        EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(500, 80))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build()
                .ingest(document);

        var retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(4)
                .build();
        var injector = new DefaultContentInjector(
                "用户问题：\\n{{userMessage}}\\n\\n知识库资料：\\n{{contents}}\\n\\n只依据资料回答。",
                List.of());
        return DefaultRetrievalAugmentor.builder()
                .queryTransformer(query -> List.of(query))
                .contentRetriever(retriever)
                .contentInjector(injector)
                .build();
    }
}

