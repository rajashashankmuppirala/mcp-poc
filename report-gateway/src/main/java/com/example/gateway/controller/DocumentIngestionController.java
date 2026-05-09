package com.example.gateway.controller;

import com.example.gateway.model.KnowledgeDocument;
import com.example.gateway.service.RagService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for managing RAG knowledge documents.
 */
@RestController
@RequestMapping("/rag/documents")
public class DocumentIngestionController {

    private final RagService ragService;

    public DocumentIngestionController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        KnowledgeDocument doc = ragService.ingest(request.title(), request.content(), request.tags());
        return Map.of(
                "id", doc.id(),
                "title", doc.title(),
                "chunks", doc.chunks().size(),
                "message", "Document ingested successfully"
        );
    }

    @GetMapping
    public Map<String, Object> list() {
        List<KnowledgeDocument> docs = ragService.listAll();
        return Map.of(
                "documents", docs.stream().map(d -> Map.of(
                        "id", d.id(),
                        "title", d.title(),
                        "tags", d.tags(),
                        "chunks", d.chunks().size()
                )).toList(),
                "count", docs.size()
        );
    }

    @GetMapping("/{id}")
    public KnowledgeDocument getById(@PathVariable String id) {
        KnowledgeDocument doc = ragService.getById(id);
        if (doc == null) throw new DocumentNotFoundException(id);
        return doc;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        boolean deleted = ragService.delete(id);
        return Map.of(
                "id", id,
                "deleted", deleted
        );
    }

    @DeleteMapping
    public Map<String, Object> clear() {
        ragService.clear();
        return Map.of("cleared", true);
    }

    /**
     * Request body for document ingestion.
     */
    public record IngestRequest(String title, String content, List<String> tags) {}

    @ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    static class DocumentNotFoundException extends RuntimeException {
        DocumentNotFoundException(String id) { super("Document not found: " + id); }
    }
}
