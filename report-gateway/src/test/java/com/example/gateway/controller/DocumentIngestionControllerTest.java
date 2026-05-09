package com.example.gateway.controller;

import com.example.gateway.service.InMemoryRagStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class DocumentIngestionControllerTest {

    private WebTestClient webTestClient;
    private InMemoryRagStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryRagStore();
        webTestClient = WebTestClient
                .bindToController(new DocumentIngestionController(store))
                .build();
    }

    @Test
    void ingestCreatesDocument() {
        webTestClient.post()
                .uri("/rag/documents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\": \"Revenue Guide\", \"content\": \"Revenue is calculated quarterly.\", \"tags\": [\"finance\"]}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.title").isEqualTo("Revenue Guide")
                .jsonPath("$.chunks").isNumber();
    }

    @Test
    void listReturnsAllDocuments() {
        store.ingest("Doc 1", "Content one.", java.util.List.of());
        store.ingest("Doc 2", "Content two.", java.util.List.of());

        webTestClient.get()
                .uri("/rag/documents")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(2)
                .jsonPath("$.documents").isArray();
    }

    @Test
    void getByIdReturnsDocument() {
        var doc = store.ingest("Test", "Test content.", java.util.List.of());

        webTestClient.get()
                .uri("/rag/documents/{id}", doc.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(doc.id())
                .jsonPath("$.title").isEqualTo("Test");
    }

    @Test
    void getByIdReturns404ForMissingDocument() {
        webTestClient.get()
                .uri("/rag/documents/nonexistent")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deleteRemovesDocument() {
        var doc = store.ingest("ToDelete", "Delete me.", java.util.List.of());

        webTestClient.delete()
                .uri("/rag/documents/{id}", doc.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deleted").isEqualTo(true);
    }

    @Test
    void deleteReturnsFalseForMissingId() {
        webTestClient.delete()
                .uri("/rag/documents/nonexistent")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deleted").isEqualTo(false);
    }

    @Test
    void clearRemovesAllDocuments() {
        store.ingest("Doc 1", "Content one.", java.util.List.of());
        store.ingest("Doc 2", "Content two.", java.util.List.of());

        webTestClient.delete()
                .uri("/rag/documents")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.cleared").isEqualTo(true);

        webTestClient.get()
                .uri("/rag/documents")
                .exchange()
                .expectBody()
                .jsonPath("$.count").isEqualTo(0);
    }
}
