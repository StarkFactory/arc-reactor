# Memory/RAG Architecture

## Core Components

- memory store and conversation manager
- query transformer and retriever
- reranker and response assembly
- ingestion candidate and policy stores

## Request Path (Simplified)

1. load session-aware conversation context
2. transform query (optional)
3. retrieve and rank documents
4. inject RAG context into prompt
5. apply output and response policies

## Design Notes

- Retrieval is optional and feature-flag driven.
- Fail-open/fail-close behavior differs by stage; keep this explicit.
- Guard/hook behavior should stay consistent with non-RAG flows.

## Next

- [Ingestion and Retrieval Operations](ingestion-and-retrieval.md)
- [Deep Dive](deep-dive.md)
