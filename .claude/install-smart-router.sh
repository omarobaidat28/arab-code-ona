#!/usr/bin/env bash
# Installs the smart-router skill + helper agents into ~/.claude (all projects on this machine)
C="${1:-$HOME}/.claude"
B=https://raw.githubusercontent.com/omarobaidat28/arab-code-ona/claude/interactive-educational-game-jghjb2/.claude
mkdir -p "$C/agents" "$C/skills/smart-router"
for a in haiku-scout sonnet-worker opus-expert; do curl -fsSL "$B/agents/$a.md" -o "$C/agents/$a.md" || true; done
curl -fsSL "$B/skills/smart-router/SKILL.md" -o "$C/skills/smart-router/SKILL.md" || true
[ -s "$C/CLAUDE.md" ] || curl -fsSL "$B/global/CLAUDE.md" -o "$C/CLAUDE.md" || true
echo "Smart router installed in $C"
