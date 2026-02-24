---
name: github-tracker
description: Daily review of GitHub PRs and issues across key repositories
triggers:
  - github review
  - pr review
  - daily github
---

# GitHub Tracker

Review open pull requests and issues for the repositories I care about.

## Steps

1. Use `github_list_prs` to fetch open PRs for each configured repository (state: open)
2. Flag PRs that are more than 2 days old without activity or review
3. Use `github_list_issues` to fetch open issues labeled `bug` or `priority`
4. Summarize: how many PRs need review, how many blocking bugs are open

## Output format

```
GitHub Daily Summary — {date}

PRs needing review ({count}):
- #{number} {title} (by {author}, {age} old)

Blocking issues ({count}):
- #{number} {title} [{labels}]
```

## Notes
- Skip PRs in draft state
- Prioritize issues with labels: bug, critical, P0, P1
