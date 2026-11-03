# Building the knowledge behind a search tool

A knowledge MCP server built by this app answers questions from documents you ingest here. The chain is: ingest documents into the knowledge base, optionally shape a RAG pipeline over them, check the search, then ship a `search_<name>` tool with the `saip_shipyard_*` tools (`saip://guide/shipyard`). The shipped server carries a frozen snapshot of the chunks and vectors; it does not index anything itself.

## Procedure

1. `saip_knowledge_list_documents`: what is already indexed, the folders you may ingest files from (`ingestRoots`) and declared documents that failed to load.
2. `saip_knowledge_ingest_document` per document. Prefer `source.path` for files the person put in an ingest root, given as the absolute path under one of the `ingestRoots`, `source.url` for public pages, `source.text` for short bodies you were given. Give a clear `title`; it is the key everything else refers to. The call blocks until the chunks are embedded; send a `progressToken` in the request `_meta` and the app reports each stage (reading, split, enriched chunk n of m, embedding) as progress notifications.
3. `saip_knowledge_search` with the document title and two or three questions the person wants answered. If the right excerpts come back, you can ship the document directly as a search tool; no pipeline is needed.
4. `saip_knowledge_apply_pipeline` only when plain retrieval is not enough: to scope several documents under one tool, to raise `topK`, to lower `similarityThreshold`, or to turn on query rewriting, translation or multi-query expansion. Then check again with `saip_knowledge_search` using the pipeline name.
5. `saip_knowledge_suggest_tools` when the server should cover what is indexed rather than one source you picked: it proposes one `search_` tool per pipeline that scopes documents, the all-documents pipeline for the documents no pipeline covers (or those documents one by one), with unique names and descriptions. Drop what the person did not ask for.
6. `saip_shipyard_validate` and `saip_shipyard_ship` with `knowledge: [{source: <pipeline or document>, name: search_<slug>}]`, then `saip_shipyard_run` and `saip_shipyard_status`.
7. `saip_knowledge_status` later tells which shipped servers are out of date because their documents or pipeline changed; ship them again to refresh the snapshot.

## Readers and chunks

- `extract.reader`: `PDF_PAGE` for PDFs (one document per page, `pdfPagesPerDocument` to merge pages), `PDF_PARAGRAPH` for PDFs with a table of contents, `MARKDOWN`, `HTML` (`htmlSelector` to pick the article), `JSON` (`jsonKeys` to pick fields), `TEXT`, `TIKA` for anything else (Word, spreadsheets, e-mail). Unset means by file extension.
- `split.chunkSize` is in tokens, default 800 with a 350-character minimum. Shorter chunks (300 to 500) help precise lookups, longer ones (800 to 1200) help summaries. One document is usually tens to a few hundred chunks.
- `enrich.keyword` and `enrich.summary` add metadata with the chat model: one call per chunk per enricher, which on a local model means minutes per hundred chunks. Turn them on only when the person asked for better recall on vague queries and a chat model is configured.

## Pipelines

- A pipeline is retrieval over a document scope plus optional stages. `documents` empty means every knowledge-base document, which also means documents ingested later are searched by it.
- Pre-retrieval stages (`rewrite`, `compression`, `translation`, `multiQuery`) each cost one chat-model call per query. A shipped server with such a pipeline needs `chatModel` in the ship request. Retrieval-only pipelines are model-free apart from the embedding model, which the app pins into the bundle.
- `retrieval.similarityThreshold` depends on the embedding model: with the default `qwen3-embedding:0.6b` a matching chunk scores about 0.48 to 0.65 and an unanswerable question about 0.25. Keep the cut below the matching range, and re-check after switching embedding models.
- `retrieval.topK` is how many excerpts a search returns; the shipped tool lets callers lower it per call but not raise it above 20.

## Names and rules

- Document titles and pipeline names are unique keys. Ingesting an existing title is refused; delete it first. Applying a pipeline with an existing name updates it.
- Documents and pipelines declared in configuration (`spring.ai.playground.rag.documents` and `rag.pipelines`) cannot be changed or deleted here; tell the person to edit the configuration.
- A `source.path` outside the ingest roots is refused. Ask the person to copy the file into an ingest root or to extend `spring.ai.playground.rag.ingest-roots`.
- Shipped knowledge tool names default to `search_<slug of the source name>`; names starting with `saip_` are reserved.

## What the person does

Putting files into an ingest root, configuring a chat model for enrichment or pipeline stages, and anything that needs Docker is the person's side. Say exactly what is missing and stop; choosing readers, chunking, pipeline settings and checking the answers is yours.
