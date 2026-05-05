# Quickstart: MCP-Based Report Generation System

**Date**: 2026-05-05

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker (optional, for containerized deployment)
- Azure OpenAI resource with API key

## Architecture Overview

```
Client → [AI Gateway:8080] → [MCP Server:8081] → [Domain API:8082]
              ↑ Azure OpenAI (control plane only, no data plane access)
```

Three services, three ports, clean separation:
- **Gateway** (8080): User-facing, handles auth, LLM prompt, streaming response
- **MCP Server** (8081): Tool execution, JSON schema validation, domain API routing
- **Domain API** (8082): Data retrieval, report generation, streaming data source

## Local Development

### 1. Start services in order

```bash
# Terminal 1: Domain API (no dependencies on other services)
cd domain-api
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 2: MCP Server (depends on Domain API)
cd mcp-server
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Terminal 3: AI Gateway (depends on MCP Server and Azure OpenAI)
cd report-gateway
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

### 2. Configure Azure OpenAI

Create `report-gateway/src/main/resources/application-dev.yml`:

```yaml
azure:
  openai:
    endpoint: https://your-resource.openai.azure.com/
    api-key: ${AZURE_OPENAI_API_KEY}
    deployment: gpt-4o-mini
```

### 3. Test the flow

```bash
curl -N -X POST http://localhost:8080/api/v1/reports/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-jwt>" \
  -d '{"prompt": "Show me revenue by category for Q1 2026"}'
```

Expected: streaming JSON chunks arriving incrementally.

## Project Structure

| Service | Port | Responsibility |
|---------|------|---------------|
| `report-gateway/` | 8080 | AI Gateway — prompt-to-JSON, streaming to client |
| `mcp-server/` | 8081 | MCP Server — tool execution, validation |
| `domain-api/` | 8082 | Domain API — data retrieval, streaming source |

## Key Design Rules

1. **LLM isolation**: Azure OpenAI is only reachable from the gateway. It never sees MCP server or domain API endpoints.
2. **Validate at every boundary**: Gateway validates user input → MCP validates tool JSON → Domain API validates parameters.
3. **Stream everything**: Use `StreamingResponseBody` for all report data flow.
4. **Propagate identity**: JWT flows through all layers via headers.
