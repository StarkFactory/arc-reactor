# AI Agent — Academic References

This document lists all academic papers referenced in Arc Reactor's agent architecture, security, memory, and RAG subsystems.
We gratefully acknowledge the contributions of these researchers.

## Agent Architecture & Reliability

### 1. ToolTree (Dual-Feedback MCTS for Tool Planning)
- **Paper**: "ToolTree: Efficient LLM Agent Tool Planning via Dual-Feedback MCTS and Bidirectional Pruning"
- **Venue**: ICLR 2026
- **Link**: https://arxiv.org/abs/2603.12740
- **Applied in**: `CostAwareModelRouter.kt` — Complexity analysis for model routing draws inspiration from ToolTree's tree-based tool planning evaluation

### 2. AutoTool (Efficient Tool Selection)
- **Paper**: "AutoTool: Efficient Tool Selection for Large Language Model Agents"
- **Year**: 2025
- **Link**: https://arxiv.org/abs/2511.14650
- **Applied in**: `ContextAwareToolFilter.kt` — Context-driven tool filtering pattern inspired by AutoTool's selective tool provisioning approach

### 3. Science of AI Agent Reliability
- **Paper**: "Towards a Science of AI Agent Reliability"
- **Year**: 2026
- **Link**: https://arxiv.org/abs/2602.16666
- **Applied in**: `StepBudgetTracker.kt`, `ExecutionCheckpoint.kt` — Reliability framework for step budgeting and execution checkpointing aligned with the paper's agent reliability taxonomy

## Security & Defense

### 4. Diverse Data Synthesis for LLM Security
- **Paper**: "Know Thy Enemy: Securing LLMs via Diverse Data Synthesis and Instruction-Level CoT Learning"
- **Year**: 2026
- **Link**: https://arxiv.org/abs/2601.04666
- **Applied in**: `InjectionPatterns.kt` — Diverse injection pattern design informed by the paper's adversarial data synthesis methodology

### 5. Zero-Shot Embedding Drift Detection
- **Paper**: "Zero-Shot Embedding Drift Detection: Lightweight Defense Against Prompt Injections"
- **Year**: 2026
- **Link**: https://arxiv.org/abs/2601.12359
- **Applicable to**: Guard stage — Embedding drift detection could be added as a lightweight prompt injection defense without requiring training data

### 6. ARLAS (Adversarial Reinforcement Learning for Agent Safety)
- **Paper**: "Adversarial Reinforcement Learning for LLM Agent Safety (ARLAS)"
- **Year**: 2025
- **Link**: https://arxiv.org/abs/2510.05442
- **Applied in**: `AdversarialRedTeam.kt` — Red-team engine design based on the paper's adversarial RL framework for safety evaluation

## Memory & Context

### 7. A-MEM (Agentic Memory)
- **Paper**: "A-MEM: Agentic Memory for LLM Agents"
- **Year**: 2025
- **Link**: https://arxiv.org/abs/2502.12110
- **Applied in**: `ConversationManager.kt` — Hierarchical summarization design referenced from A-MEM's structured memory organization

### 8. Unified LTM and STM Management
- **Paper**: "Agentic Memory: Learning Unified LTM and STM Management"
- **Year**: 2026
- **Link**: https://arxiv.org/abs/2601.01885
- **Applicable to**: `UserMemoryStore` — Long-term and short-term memory unification pattern could enhance the current user memory architecture

## RAG Enhancement

### 9. RankRAG (Unified Context Ranking with RAG)
- **Paper**: "RankRAG: Unifying Context Ranking with Retrieval-Augmented Generation"
- **Year**: 2024
- **Link**: https://arxiv.org/abs/2407.02485
- **Applied in**: `DefaultRagPipeline.kt` — Unified rerank + retrieve pattern referenced from RankRAG's joint ranking-generation approach

## Tool Description Quality

### 10. Learning to Rewrite Tool Descriptions
- **Paper**: "Learning to Rewrite Tool Descriptions for Reliable LLM-Agent Tool Use"
- **Year**: 2025
- **Link**: https://arxiv.org/abs/2602.20426
- **Applied in**: `ToolDescriptionEnricher.kt` — Automated tool description quality analysis and warning system inspired by the paper's tool description rewriting methodology
