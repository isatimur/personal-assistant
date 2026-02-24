---
name: web-researcher
description: Deep web research with structured source citation
triggers:
  - research
  - search for
  - find information about
  - look up
---

# Web Researcher

Conduct thorough web research on any topic and produce a structured summary with citations.

## Process

1. Use `web_search` with 2-3 different query phrasings to gather diverse results
2. Use `web_fetch` to read the 3 most relevant pages in full
3. Cross-reference information across sources
4. Identify consensus facts vs disputed claims

## Output format

```
## Research: {topic}

### Summary
{2-3 paragraph synthesis of findings}

### Key Facts
- {fact} — Source: {url}
- {fact} — Source: {url}

### Conflicting Information
{note any contradictions between sources}

### Sources
1. {title} — {url}
2. {title} — {url}
```

## Guidelines
- Always cite at least 2 sources for factual claims
- Note the publication date when available
- Flag information older than 2 years as potentially outdated
- Prefer primary sources (official docs, academic papers, direct reports) over aggregators
