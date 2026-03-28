# CLAUDE.md

Read AGENTS.md first — it contains the full project rules.

## Multi-Agent Rules (summary for Claude Code)

- Your working directories are the auto-managed worktrees under `.claude/worktrees/`
- Always branch from `main` with prefix `claude/`
- Open a PR after each task — never merge another agent's branch
- Never force-add `.claude/`, `.codex*`, `.gemini/`, `.maq/` to git
- Before starting a task, check open PRs to avoid file conflicts with Codex or MAQ
