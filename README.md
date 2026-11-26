# ColabCode
Android agent (OpenCode-style tools) with three workspaces: **colab** (Colab Linux runtime via MCP), **local** (app sandbox) and **termux** (Termux on the same phone).

## Setup
Settings → LLM base URL / key / model (any OpenAI-compatible server) + Google OAuth *Desktop* client ID (for Colab). Then /login, /specs, /new N.

## Small / weak models (e.g. Llama-3-8B-Lexi-Uncensored)
* Tool mode **text** (default): the model writes `<tool name="read" path="x"/>`; no native function-calling needed. `/mode native` switches to OpenAI `tools`.
* Set *Model context window* to your server's context (Llama-3-8B = 8192 → 7000). Old turns are dropped automatically.
* Agent tools: read, write, edit (SEARCH/REPLACE blocks), ls, glob, grep, bash, fetch, search, todo, termux.
* `AGENTS.md` in the workspace root is loaded into the prompt; `/init` makes the agent write one.

## Termux
1. Open Termux once. In Termux: `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings`
2. Android Settings → Apps → ColabCode → Permissions → Additional permissions → "Run commands in Termux environment".
3. In ColabCode: `/ws termux`, then `/termux` to test. Recommended: `pkg install -y python curl poppler unzip ripgrep git`.
(Install Termux from F-Droid/GitHub, not the Play Store build.)

## Files
📎 button (any file type, multiple) or Share → ColabCode from any app. Files go to `uploads/` in the active workspace; the model gets the path plus a read-hint per type (pdf/docx/xlsx/zip/…); small text files are inlined; images are sent as vision input only if "Send images" = 1 and the model supports it.

## Web
`search` (DuckDuckGo HTML, or your SearXNG URL in Settings) and `fetch` (HTML→text). Manual: `/search q`, `/fetch url`.
