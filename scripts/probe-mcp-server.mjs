#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
//
// A probe, not a product: the smallest MCP server that a Spring AI client will talk to, so that the
// governance layer over the host's own tool path can be exercised against something real. It speaks
// the Streamable HTTP transport — POST a JSON-RPC message, get a JSON result — and it offers exactly
// one tool, which echoes what it is given.
//
// It exists because the last mile of `D32` needs a *real* tool call, and there was no MCP server on
// this machine to make one against. Every `tools/call` it receives is printed, which is how a run
// proves the call actually arrived.
//
//   node scripts/probe-mcp-server.mjs      # listens on 127.0.0.1:8799
//
// Not part of the line's runtime: nothing starts it, nothing depends on it.
//
// 一个**探针，不是产品**：能让 Spring AI 客户端谈得起来的最小 MCP 服务，好让宿主自己那条工具路径之上的治理层
// 能对着**真东西**跑一遍。它说 Streamable HTTP（POST 一条 JSON-RPC、回一个 JSON 结果），只提供**一个**工具，
// 把它收到的原样回过去。
//
// 它存在，是因为 `D32` 的最后一哩需要一次**真的**工具调用，而这台机器上没有可用的 MCP 服务。它收到的每一次
// `tools/call` 都会打印出来——一次运行就是这样证明调用**确实到达**的。
//
//   node scripts/probe-mcp-server.mjs      # 监听 127.0.0.1:8799
//
// 不是本线运行时的一部分：没有东西启动它，也没有东西依赖它。
import http from 'node:http';
import { appendFileSync } from 'node:fs';

const PORT = Number(process.env.MCP_PORT || 8799);
const TOOL = process.env.MCP_TOOL || 'probe_echo';
const NOTE_TOOL = process.env.MCP_NOTE_TOOL || 'probe_note';
// Where the one tool with a side effect writes. A multi-step run proves itself by this file having the
// line in it — a call that was refused would leave it untouched.
// 那个**有副作用**的工具写在哪里。一次多步运行靠这个文件里有没有那一行来证明自己——被拒的调用不会动它。
const NOTE_FILE = process.env.MCP_NOTE_FILE || '/tmp/probe-notes.txt';
const SESSION = 'keelbase-probe-session';

const send = (res, status, payload) => {
  res.writeHead(status, { 'Content-Type': 'application/json', 'Mcp-Session-Id': SESSION });
  res.end(payload === undefined ? '' : JSON.stringify(payload));
};

const server = http.createServer((req, res) => {
  if (req.method === 'GET') {
    // The transport allows a server-pushed stream; this probe has nothing to push.
    // 该传输允许服务端推送流；本探针没有要推的东西。
    send(res, 405, { jsonrpc: '2.0', error: { code: -32000, message: 'no server stream here' }, id: null });
    return;
  }
  if (req.method !== 'POST') {
    send(res, 405, { jsonrpc: '2.0', error: { code: -32000, message: 'POST only' }, id: null });
    return;
  }

  let body = '';
  req.on('data', (chunk) => { body += chunk; });
  req.on('end', () => {
    let message;
    try {
      message = JSON.parse(body);
    } catch {
      send(res, 400, { jsonrpc: '2.0', error: { code: -32700, message: 'parse error' }, id: null });
      return;
    }
    const reply = (result) => send(res, 200, { jsonrpc: '2.0', id: message.id, result });

    switch (message.method) {
      case 'initialize':
        reply({
          protocolVersion: '2024-11-05',
          capabilities: { tools: {} },
          serverInfo: { name: 'keelbase-probe', version: '0.0.1' }
        });
        return;
      case 'notifications/initialized':
        send(res, 202, undefined);
        return;
      case 'ping':
        reply({});
        return;
      case 'tools/list':
        reply({
          tools: [
            {
              name: TOOL,
              description: 'Echo a string back. Read-only; the probe exists to be called, not to be useful.',
              inputSchema: {
                type: 'object',
                properties: { text: { type: 'string', description: '任何字符串' } },
                required: ['text']
              }
            },
            {
              name: NOTE_TOOL,
              description: 'Append a line to the probe\'s note file. This one has a side effect, so a'
                + ' run that reaches it leaves a mark that can be checked afterwards.',
              inputSchema: {
                type: 'object',
                properties: { text: { type: 'string', description: '要记下的一行' } },
                required: ['text']
              }
            }
          ]
        });
        return;
      case 'tools/call': {
        const name = message.params && message.params.name;
        const args = (message.params && message.params.arguments) || {};
        // The observable: a run that reaches here has really issued the tool call.
        // 可观测处：跑到这里的运行，是真的发出了这次工具调用。
        console.log(`[probe-mcp] tools/call name=${name} args=${JSON.stringify(args)}`);
        if (name === NOTE_TOOL) {
          appendFileSync(NOTE_FILE, `${args.text ?? ''}\n`);
          reply({ content: [{ type: 'text', text: `noted: ${args.text ?? ''}` }], isError: false });
          return;
        }
        reply({ content: [{ type: 'text', text: `echo: ${args.text ?? ''}` }], isError: false });
        return;
      }
      default:
        send(res, 200, { jsonrpc: '2.0', id: message.id, error: { code: -32601, message: `unknown method ${message.method}` } });
    }
  });
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[probe-mcp] listening on http://127.0.0.1:${PORT}/mcp (tool: ${TOOL})`);
});
