package com.privatebank.business.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "private-bank.knowledge")
public record ProductKnowledgeProperties(
        Qdrant qdrant,
        Elasticsearch elasticsearch,
        Embedding embedding,
        Integer topK,
        Integer rrfK) {

    public ProductKnowledgeProperties {
        qdrant = qdrant == null ? new Qdrant(null, 0, null, null, false) : qdrant;
        elasticsearch = elasticsearch == null
                ? new Elasticsearch(null)
                : elasticsearch;
        embedding = embedding == null
                ? new Embedding(null, null, null, 1024)
                : embedding;
        topK = topK == null ? 10 : topK;
        rrfK = rrfK == null ? 60 : rrfK;
    }

    public record Qdrant(
            String host,
            Integer port,
            String apiKey,
            String collectionName,
            Boolean initializeSchema) {
        public Qdrant {
            port = port == null ? 6334 : port;
            collectionName = collectionName == null || collectionName.isBlank()
                    ? "private-bank-product-chunks-v1"
                    : collectionName;
            initializeSchema = initializeSchema == null ? Boolean.FALSE : initializeSchema;
        }
    }

    public record Elasticsearch(
            String index) {
        public Elasticsearch {
            index = index == null || index.isBlank()
                    ? "private-bank-product-chunks-v1"
                    : index;
        }
    }

    public record Embedding(
            String baseUrl,
            String apiKey,
            String model,
            Integer dimensions) {
        public Embedding {
            baseUrl = baseUrl == null || baseUrl.isBlank()
                    ? "https://dashscope.aliyuncs.com/compatible-mode/v1"
                    : baseUrl;
            model = model == null || model.isBlank() ? "text-embedding-v4" : model;
            dimensions = dimensions == null ? 1024 : dimensions;
        }
    }
}
