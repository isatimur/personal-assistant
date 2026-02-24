---
name: code-reviewer
description: Structured code review with configurable criteria
triggers:
  - review this code
  - code review
  - review my PR
  - review the diff
---

# Code Reviewer

Perform a thorough code review against defined quality criteria.

## Review Checklist

### Correctness
- Does the logic match the stated intent?
- Are edge cases handled (null, empty, overflow)?
- Are error paths covered?

### Security
- Any injection risks (SQL, shell, XSS)?
- Secrets or credentials hard-coded?
- Input validation at boundaries?

### Performance
- N+1 query patterns or unnecessary loops?
- Large allocations in hot paths?
- Blocking I/O on the main thread?

### Maintainability
- Is the code self-documenting?
- Functions/classes doing one thing?
- Test coverage for new behaviour?

## Output format

```
## Code Review

### Summary
{overall assessment in 2-3 sentences}

### Issues Found

#### Critical (must fix before merge)
- {file}:{line} — {issue} | Suggestion: {fix}

#### Warnings (should fix)
- {file}:{line} — {issue}

#### Suggestions (nice to have)
- {file}:{line} — {idea}

### Verdict
[ ] Approve  [ ] Request changes  [ ] Needs discussion
```

## Configuration
Override criteria by adding to your IDENTITY.md:
```
code-review-criteria: security, performance, test-coverage
code-review-language: kotlin
```
