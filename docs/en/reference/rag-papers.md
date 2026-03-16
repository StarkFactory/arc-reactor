# RAG System — Academic References

This document lists all academic papers referenced in Arc Reactor's RAG implementation.
We gratefully acknowledge the contributions of these researchers.

## Core RAG Techniques

### 1. HyDE (Hypothetical Document Embeddings)
- **Paper**: "Precise Zero-Shot Dense Retrieval without Relevance Labels"
- **Authors**: Luyu Gao, Xueguang Ma, Jimmy Lin, Jamie Callan
- **Venue**: ACL 2023
- **Link**: https://arxiv.org/abs/2212.10496
- **Applied in**: `HyDEQueryTransformer.kt` — Generates hypothetical answers for improved retrieval

### 2. BM25
- **Paper**: "The Probabilistic Relevance Framework: BM25 and Beyond"
- **Authors**: Stephen Robertson, Hugo Zaragoza
- **Venue**: Foundations and Trends in Information Retrieval, 2009
- **Applied in**: `Bm25Scorer.kt` — Keyword-based scoring for hybrid search

### 3. Reciprocal Rank Fusion (RRF)
- **Paper**: "Reciprocal Rank Fusion outperforms Condorcet and individual Rank Learning Methods"
- **Authors**: Gordon V. Cormack, Charles L. A. Clarke, Stefan Buettcher
- **Venue**: SIGIR 2009
- **Link**: https://dl.acm.org/doi/10.1145/1571941.1572114
- **Applied in**: `RrfFusion.kt` — Combines vector and BM25 ranking signals

### 4. MMR (Maximal Marginal Relevance)
- **Paper**: "The Use of MMR, Diversity-Based Reranking for Reordering Documents and Producing Summaries"
- **Authors**: Jaime Carbonell, Jade Goldstein
- **Venue**: SIGIR 1998
- **Applied in**: `DiversityReranker.kt` — Balances relevance with diversity in results

### 5. Adaptive-RAG
- **Paper**: "Adaptive-RAG: Learning to Adapt Retrieval-Augmented Large Language Models through Question Complexity"
- **Authors**: Soyeong Jeong, Jinheon Baek, Sukmin Cho, Sung Ju Hwang, Jong C. Park
- **Venue**: NAACL 2024
- **Link**: https://arxiv.org/abs/2403.14403
- **Applied in**: `AdaptiveQueryRouter.kt` — Routes queries by complexity level

### 6. Query Decomposition (Least-to-Most Prompting)
- **Paper**: "Least-to-Most Prompting Enables Complex Reasoning in Large Language Models"
- **Authors**: Denny Zhou, Nathanael Scharli, Le Hou, Jason Wei, Nathan Scales, Xuezhi Wang, Dale Schuurmans, Claire Cui, Olivier Bousquet, Quoc Le, Ed Chi
- **Venue**: ICLR 2023
- **Link**: https://arxiv.org/abs/2205.10625
- **Applied in**: `DecompositionQueryTransformer.kt` — Breaks complex queries into sub-queries

### 7. CRAG (Corrective Retrieval Augmented Generation)
- **Paper**: "Corrective Retrieval Augmented Generation"
- **Authors**: Shi-Qi Yan, Jia-Chen Gu, Yun Zhu, Zhen-Hua Ling
- **Venue**: 2024
- **Link**: https://arxiv.org/abs/2401.15884
- **Applied in**: `CragDocumentGrader.kt` — Evaluates and filters retrieved documents

### 8. RECOMP (Contextual Compression)
- **Paper**: "RECOMP: Improving Retrieval-Augmented LMs with Compression and Selective Augmentation"
- **Authors**: Fangyuan Xu, Weijia Shi, Eunsol Choi
- **Venue**: ICLR 2024
- **Link**: https://arxiv.org/abs/2310.04408
- **Applied in**: `LlmContextualCompressor.kt` — Extracts relevant passages from documents

### 9. Mixture-of-Granularity (Parent Document Retrieval)
- **Paper**: "Mixture-of-Granularity: Optimize the Chunking Granularity for RAG"
- **Authors**: Zijie Chen, Hailin Zhang, et al.
- **Venue**: 2024
- **Link**: https://arxiv.org/abs/2406.00456
- **Applied in**: `ParentDocumentRetriever.kt` — Expands chunk matches to parent context

## Chunking Strategy

### 10. FloTorch Benchmark
- **Report**: "FloTorch RAG Chunking Benchmark"
- **Organization**: FloTorch
- **Year**: 2026
- **Applied in**: `TokenBasedDocumentChunker.kt` — 512-token chunks based on benchmark results
