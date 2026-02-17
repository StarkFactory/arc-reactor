# Ingestion and Retrieval Operations

## Ingestion

- capture candidate from approved/eligible outputs
- persist review status
- ingest to vector store when policy allows

## Retrieval

- run semantic search with optional metadata filters
- deduplicate and rank documents
- keep `topK` and threshold aligned with response budget

## Operational Checks

- verify ingestion tables/stores are available in target profile
- monitor retrieval latency and token growth
- test fail paths (vector store unavailable, malformed filters)

## Next

- [Memory/RAG Architecture](architecture.md)
- [Deep Dive](deep-dive.md)
