# jolt-claude-plugin

A Claude Code plugin with hooks for Jolt/Clojure projects.

After every Write, Edit or MultiEdit of a `.clj`, `.cljc`, `.edn` or `.jolt`
file, the hook runs [jolt-lsp](https://github.com/jlt-commons/jolt-lsp) on it:

1. `jolt-lsp check --fix` closes a dropped trailing paren or an unclosed form
   the indentation places, on disk. The repair is reported back so the agent
   can check where the closers went.
2. `jolt-lsp diagnostics` lints the file with the project's
   `.clj-kondo/config.edn` and `.jolt-lsp/config.edn`.

Syntax it cannot repair, or any error-level finding, blocks: Claude gets the
`file:line:col` list and fixes the file. Warnings come back as context
without blocking. Nothing outside the edited file is reported.

It needs `jolt` and `jolt-lsp` on `PATH` (or `JOLT` / `JOLT_LSP` naming
them). Without jolt-lsp the hook does nothing.

## Install

```sh
claude plugin marketplace add /path/to/jolt-claude-plugin
claude plugin install jolt-claude@jolt-claude-plugin
```

## Develop

```sh
jolt test
echo '{"tool_input":{"file_path":"/abs/path/src/a/core.clj"}}' | bin/jolt-claude-hook
```

The idea of mechanical paren repair and a lint gate on every write comes from
[clojure-agent-starter](https://github.com/JoinIn-AI/clojure-agent-starter)
and [clojure-mcp-light](https://github.com/bhauman/clojure-mcp-light); this
plugin uses jolt-lsp for both and shares no code with either.
