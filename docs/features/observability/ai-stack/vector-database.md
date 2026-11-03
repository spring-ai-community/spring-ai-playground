title: Vector Database Observability
description: RAG-side observability - query rate, top_k distribution, similarity threshold bands, per-DB returned-doc counts. The indicators that determine RAG quality.

# Vector Database

![Vector Database dashboard - four KPI cards (Traces with RAG, Vector queries, Avg top_k, Avg query latency) and charts for Operations / minute stacked, Query latency p50/p95/p99, top_k distribution histogram, Similarity threshold distribution, DB systems donut, Avg returned docs by db (Recent searches sits below these)](../../../assets/images/observability/vector-db-full.png)

*Vector Database - Similarity threshold distribution buckets thresholds into 6 bands (0.0-0.1, 0.1-0.3, ..., 0.9-1.0) so an over-strict threshold is visible without per-query inspection.*

**Purpose** - RAG-side observability. Beyond simple *"did the agent retrieve"* questions, this tab surfaces *retrieval parameters* (top_k, similarity threshold) and per-DB returned-doc counts - the indicators that determine RAG quality.

## When to look here

- *"Did the agent actually use RAG?"* - Traces with RAG count + Vector queries count.
- *"Are queries slow against the configured store?"* - Query latency p50 / p95 / p99.
- *"Is `top_k` being set sensibly?"* - top_k distribution (a spike at 1 or above 50 usually indicates an agent miscalibration).
- *"Is the similarity threshold too strict?"* - Similarity threshold distribution (everything in 0.9-1.0 → almost no recall).
- *"Multi-store mix?"* - DB systems donut (Chroma / Pinecone / SimpleVectorStore / etc.).
- *"How many docs per query, on average?"* - Avg returned docs by db.

## Data source

Not trace spans: every vector-store operation is recorded in a **memory-only** operation log (`VectorOperationLog`) - up to 5000 operations plus the text of the last 20 queries, never written to disk and cleared on restart. Two kinds of search are left out: searches against the dynamic tool-discovery index (Agentic Chat's tool search) and the placeholder "list everything" query the Vector Database screen runs to fill its grid.

## Controls

All dashboards share the [Observability global settings](../index.md#global-settings) - time window, refresh interval, custom range. Vector Database has no tab-specific controls beyond those.

## KPI cards (four)

| Card | Shows | Source |
|---|---|---|
| Traces with RAG | Number of chat turns that included a vector query | `TraceRecord.hasRag` true count - set when a document-store query (RAG pipeline, document source, or chat attachment excerpt search) ran during the turn; tool-index searches do not count |
| Vector queries | Number of `query` operations (adds and deletes appear only in Operations / minute) | Operation log, `query` count |
| Avg top_k | Mean of the requested `top_k` parameter | `db.vector.query.top_k` attribute averaged |
| Avg query latency | Mean duration of `query` operations | Operation log durations |

## Charts (seven)

| Chart | Type | Reading |
|---|---|---|
| Operations / minute | Stacked bar (query / add / delete) | Add bursts during document ingestion; query bursts during chat. A pipeline with Multi-Query expansion issues one query per variant, so a single turn can show several |
| Query latency p50 / p95 / p99 | Multi-line, ms, `query` operations only | Tail latency on remote vector stores can be ten-fold higher than `SimpleVectorStore` |
| top_k distribution | Histogram | Distribution of requested k values. Top-K is a per-pipeline setting, so a spread here means different [RAG pipelines](../../rag/pipeline-studio.md) are in use rather than one value drifting |
| Similarity threshold distribution | Histogram, 6 bands (`[0.0, 0.1)` ... `[0.9, 1.0]`) | Where the agent is anchoring relevance - strictness vs recall |
| DB systems | Donut by `db.system` | One backend = single configuration; multiple = mixed stores |
| Avg returned docs by db | Horizontal bar | Combined with similarity threshold, this is the retrieval-recall signal |
| Recent searches | List, "last 20, kept in memory only" | One line per query: `HH:mm:ss · N docs · N ms · <query text>` (text cut at 200 characters). Empty state: *No searches yet.* The text never leaves memory and is gone after a restart |

## Cross-references

- [Vector Database (feature)](../../vector-database.md) - how documents are ingested and indexed
- [Tutorial 3 - Index a Document](../../../tutorials/3-index-document.md) - end-to-end ingestion walkthrough
- [Tutorial 5 - Chat with RAG](../../../tutorials/5-chat-rag.md) - consuming the indexed corpus
