package com.example.gateway.service;

import com.example.gateway.model.DocumentChunk;
import com.example.gateway.model.KnowledgeDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryRagStoreTest {

    private InMemoryRagStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRagStore();
    }

    @Test
    void ingestCreatesDocumentWithChunks() {
        KnowledgeDocument doc = store.ingest("Revenue Guide", "Revenue is calculated quarterly. The fiscal year starts in January. All revenue figures must be audited.", List.of("finance", "revenue"));

        assertNotNull(doc.id());
        assertEquals("Revenue Guide", doc.title());
        assertFalse(doc.chunks().isEmpty());
        assertEquals(2, doc.tags().size());
    }

    @Test
    void retrieveReturnsRelevantChunks() {
        store.ingest("Revenue Guide", "Revenue is calculated based on quarterly reports. The fiscal year starts in January.", List.of("finance"));
        store.ingest("Operations Guide", "Failed jobs can be retried using the operations dashboard. Check pipeline status.", List.of("ops"));

        List<RagService.ScoredChunk> results = store.retrieve("revenue quarterly", 5);

        assertFalse(results.isEmpty());
        // Revenue guide should score higher
        assertEquals("Revenue Guide", results.get(0).chunk().documentId() != null
                ? store.getById(results.get(0).chunk().documentId()).title()
                : "unknown");
    }

    @Test
    void retrieveReturnsEmptyForNoMatch() {
        store.ingest("Finance Doc", "Revenue and expenses are tracked monthly.", List.of("finance"));

        List<RagService.ScoredChunk> results = store.retrieve("basketball scores", 5);

        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveReturnsEmptyForEmptyStore() {
        List<RagService.ScoredChunk> results = store.retrieve("anything", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void retrieveReturnsEmptyForNullQuery() {
        store.ingest("Test", "Some content here.", List.of());
        assertTrue(store.retrieve(null, 5).isEmpty());
    }

    @Test
    void getByIdReturnsDocument() {
        KnowledgeDocument doc = store.ingest("Test Doc", "Test content.", List.of());
        KnowledgeDocument found = store.getById(doc.id());

        assertNotNull(found);
        assertEquals(doc.id(), found.id());
    }

    @Test
    void getByIdReturnsNullForMissingId() {
        assertNull(store.getById("nonexistent"));
    }

    @Test
    void listAllReturnsAllDocuments() {
        store.ingest("Doc 1", "Content one.", List.of());
        store.ingest("Doc 2", "Content two.", List.of());

        assertEquals(2, store.listAll().size());
    }

    @Test
    void deleteRemovesDocument() {
        KnowledgeDocument doc = store.ingest("To Delete", "Delete me.", List.of());

        assertTrue(store.delete(doc.id()));
        assertNull(store.getById(doc.id()));
    }

    @Test
    void deleteReturnsFalseForMissingId() {
        assertFalse(store.delete("nonexistent"));
    }

    @Test
    void clearRemovesAllDocuments() {
        store.ingest("Doc 1", "Content one.", List.of());
        store.ingest("Doc 2", "Content two.", List.of());

        store.clear();

        assertTrue(store.listAll().isEmpty());
    }

    @Test
    void scoringFavorsHigherOverlap() {
        store.ingest("Doc A", "The quarterly revenue report shows significant growth in revenue streams.", List.of("finance"));
        store.ingest("Doc B", "The weather is nice today.", List.of("general"));

        List<RagService.ScoredChunk> results = store.retrieve("quarterly revenue report growth", 5);

        assertFalse(results.isEmpty());
        assertEquals(1, results.size()); // Only Doc A should match
        assertTrue(results.get(0).score() > 0);
    }

    @Test
    void documentChunkingSplitsContent() {
        String longContent = "First sentence here. Second sentence is different. Third sentence adds more context. Fourth sentence completes the paragraph.";
        List<DocumentChunk> chunks = DocumentChunk.chunkContent("doc1", longContent);

        assertFalse(chunks.isEmpty());
        for (DocumentChunk chunk : chunks) {
            assertEquals("doc1", chunk.documentId());
            assertFalse(chunk.keywords().isEmpty());
        }
    }

    @Test
    void documentChunkingHandlesEmptyContent() {
        assertTrue(DocumentChunk.chunkContent("doc1", "").isEmpty());
        assertTrue(DocumentChunk.chunkContent("doc1", null).isEmpty());
    }

    @Test
    void documentChunkingHandlesSingleSentence() {
        List<DocumentChunk> chunks = DocumentChunk.chunkContent("doc1", "Just one sentence.");
        assertEquals(1, chunks.size());
    }
}
