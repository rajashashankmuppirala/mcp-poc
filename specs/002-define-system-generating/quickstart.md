# Quickstart: Natural Language Report Generator

## Prerequisites

- Java 21+
- Maven 3.9+
- (Optional) Docker + Docker Compose

## Run Locally

### 1. Start Domain API (port 8082)

```bash
cd domain-api
mvn spring-boot:run
```

### 2. Start MCP Server (port 8081)

```bash
cd mcp-server
mvn spring-boot:run
```

### 3. Start AI Gateway (port 8080)

```bash
cd report-gateway
export AZURE_OPENAI_ENDPOINT="https://your-resource.openai.azure.com"
export AZURE_OPENAI_API_KEY="your-key"
mvn spring-boot:run
```

## Test the Flow

```bash
curl -X POST http://localhost:8080/ai/request \
  -H "Content-Type: application/json" \
  -d '{"prompt": "Show me revenue for us-east"}'
```

Expected: NDJSON stream with report data.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `azure.openai.endpoint` | (required) | Azure OpenAI endpoint URL |
| `azure.openai.api-key` | (required) | Azure OpenAI API key |
| `azure.openai.deployment` | `gpt-4o-mini` | Model deployment name |
| `mcp.server.url` | `http://localhost:8081` | MCP server base URL |
| `domain-api.url` | `http://localhost:8082` | Domain API base URL |
| `llm.provider` | `azure-openai` | Active LLM provider |

## Run Tests

```bash
# All services
mvn test -pl domain-api,mcp-server,report-gateway

# Single service
cd report-gateway && mvn test
```

## Architecture Flow

```
Client → AI Gateway (:8080) → LLM (Azure/Anthropic)
                     ↓
              MCP Server (:8081) → Domain API (:8082)
                     ↓
              Stream back to Client
```
