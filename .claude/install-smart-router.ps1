# Installs the smart-router skill + helper agents into %USERPROFILE%\.claude (all projects on this Windows machine)
$C = Join-Path $HOME ".claude"
$B = "https://raw.githubusercontent.com/omarobaidat28/arab-code-ona/claude/interactive-educational-game-jghjb2/.claude"
New-Item -ItemType Directory -Force -Path (Join-Path $C "agents"), (Join-Path $C "skills\smart-router") | Out-Null
foreach ($a in "haiku-scout", "sonnet-worker", "opus-expert") {
  Invoke-WebRequest -UseBasicParsing "$B/agents/$a.md" -OutFile (Join-Path $C "agents\$a.md")
}
Invoke-WebRequest -UseBasicParsing "$B/skills/smart-router/SKILL.md" -OutFile (Join-Path $C "skills\smart-router\SKILL.md")
$md = Join-Path $C "CLAUDE.md"
if (-not (Test-Path $md)) {
  Invoke-WebRequest -UseBasicParsing "$B/global/CLAUDE.md" -OutFile $md
} else {
  Write-Host "You already have $md - add the router rules from $B/global/CLAUDE.md to it yourself."
}
Write-Host "Smart router installed in $C"
