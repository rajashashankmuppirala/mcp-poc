package com.example.gateway.service;

import com.example.gateway.model.DocumentChunk;
import com.example.gateway.model.KnowledgeDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory RAG store using ConcurrentHashMap.
 * Uses simple keyword overlap scoring for retrieval (POC — no embeddings).
 */
@Service
public class InMemoryRagStore implements RagService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRagStore.class);

    private final ConcurrentHashMap<String, KnowledgeDocument> store = new ConcurrentHashMap<>();

    @Override
    public KnowledgeDocument ingest(String title, String content, List<String> tags) {
        KnowledgeDocument doc = KnowledgeDocument.create(title, content, tags);
        store.put(doc.id(), doc);
        log.info("Ingested document: {} ({} chunks)", doc.title(), doc.chunks().size());
        return doc;
    }

    @Override
    public KnowledgeDocument ingestWithId(String id, String title, String content, List<String> tags) {
        KnowledgeDocument doc = KnowledgeDocument.create(id, title, content, tags);
        store.put(doc.id(), doc);
        log.info("Ingested document with ID {}: {} ({} chunks)", id, doc.title(), doc.chunks().size());
        return doc;
    }

    @Override
    public List<ScoredChunk> retrieve(String query, int maxResults) {
        if (store.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        Set<String> queryTerms = extractQueryTerms(query);
        if (queryTerms.isEmpty()) return List.of();

        List<ScoredChunk> scored = new ArrayList<>();

        for (KnowledgeDocument doc : store.values()) {
            for (DocumentChunk chunk : doc.chunks()) {
                double score = computeScore(queryTerms, chunk.keywords());
                if (score > 0) {
                    scored.add(new ScoredChunk(chunk, score));
                }
            }
        }

        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    @Override
    public KnowledgeDocument getById(String id) {
        return store.get(id);
    }

    @Override
    public List<KnowledgeDocument> listAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public boolean delete(String id) {
        KnowledgeDocument removed = store.remove(id);
        if (removed != null) {
            log.info("Deleted document: {}", removed.title());
        }
        return removed != null;
    }

    @Override
    public void clear() {
        int count = store.size();
        store.clear();
        log.info("Cleared all {} documents", count);
    }

    private Set<String> extractQueryTerms(String query) {
        return Arrays.stream(query.toLowerCase().replaceAll("[^a-z0-9\\s]", "").split("\\s+"))
                .filter(w -> w.length() > 2)
                .collect(Collectors.toSet());
    }

    /**
     * Compute Jaccard-like overlap score between query terms and chunk keywords.
     */
    private double computeScore(Set<String> queryTerms, List<String> keywords) {
        if (keywords.isEmpty()) return 0;
        long matchCount = keywords.stream().filter(queryTerms::contains).count();
        if (matchCount == 0) return 0;
        return (double) matchCount / (queryTerms.size() + keywords.size() - matchCount);
    }
}
