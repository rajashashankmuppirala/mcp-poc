package com.example.gateway.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A chunk of a knowledge document, used for retrieval.
 */
public record DocumentChunk(
        String documentId,
        int chunkIndex,
        String text,
        List<String> keywords
) {
    private static final int CHUNK_SIZE = 200; // characters per chunk for POC

    public static List<DocumentChunk> chunkContent(String documentId, String content) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) return chunks;

        String[] sentences = content.split("(?<=[.!?])\\s+");
        StringBuilder currentChunk = new StringBuilder();
        int index = 0;

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > CHUNK_SIZE && currentChunk.length() > 0) {
                String text = currentChunk.toString().trim();
                chunks.add(new DocumentChunk(documentId, index++, text, extractKeywords(text)));
                currentChunk = new StringBuilder();
            }
            currentChunk.append(sentence).append(" ");
        }

        if (currentChunk.length() > 0) {
            String text = currentChunk.toString().trim();
            chunks.add(new DocumentChunk(documentId, index++, text, extractKeywords(text)));
        }

        return chunks;
    }

    private static List<String> extractKeywords(String text) {
        if (text == null) return List.of();
        return List.of(text.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .split("\\s+"))
                .stream()
                .filter(w -> w.length() > 2)
                .distinct()
                .toList();
    }
}
