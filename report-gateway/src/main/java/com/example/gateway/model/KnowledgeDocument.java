package com.example.gateway.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A knowledge document for the RAG layer.
 * Documents are chunked and stored in-memory for POC.
 */
public record KnowledgeDocument(
        String id,
        String title,
        String content,
        List<String> tags,
        Instant createdAt,
        List<DocumentChunk> chunks
) {
    public static KnowledgeDocument create(String title, String content, List<String> tags) {
        return create(UUID.randomUUID().toString(), title, content, tags);
    }

    public static KnowledgeDocument create(String id, String title, String content, List<String> tags) {
        List<DocumentChunk> chunks = DocumentChunk.chunkContent(id, content);
        return new KnowledgeDocument(id, title, content, tags != null ? tags : List.of(), Instant.now(), chunks);
    }
}
