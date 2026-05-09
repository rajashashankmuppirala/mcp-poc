package com.example.gateway.service;

import com.example.gateway.model.DocumentChunk;
import com.example.gateway.model.KnowledgeDocument;

import java.util.List;

/**
 * RAG service for document ingestion and retrieval.
 */
public interface RagService {

    /**
     * Ingest a new document into the knowledge store.
     * Generates a random UUID for the document.
     */
    KnowledgeDocument ingest(String title, String content, List<String> tags);

    /**
     * Ingest a document with a specific ID (used for auto-ingestion with deterministic IDs).
     * Overwrites any existing document with the same ID.
     */
    KnowledgeDocument ingestWithId(String id, String title, String content, List<String> tags);

    /**
     * Retrieve relevant chunks for a query, up to maxResults.
     */
    List<ScoredChunk> retrieve(String query, int maxResults);

    /**
     * Get a document by ID.
     */
    KnowledgeDocument getById(String id);

    /**
     * List all documents.
     */
    List<KnowledgeDocument> listAll();

    /**
     * Delete a document by ID.
     */
    boolean delete(String id);

    /**
     * Clear all documents.
     */
    void clear();

    /**
     * A scored chunk returned by retrieval.
     */
    record ScoredChunk(DocumentChunk chunk, double score) {}
}
