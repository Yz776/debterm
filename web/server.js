// ==============================================================================
// DebTerm Web Terminal Server
// WebSocket Bridge between xterm.js (browser) and DebTerm PRoot Debian PTY
// ==============================================================================

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const PUBLIC_DIR = path.join(__dirname, 'public');
const CLI_BIN = path.join(__dirname, '..', 'core', 'debterm-cli');

const mimeTypes = {
    '.html': 'text/html; charset=utf-8',
    '.css': 'text/css; charset=utf-8',
    '.js': 'application/javascript; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png': 'image/png',
    '.svg': 'image/svg+xml'
};

const server = http.createServer((req, res) => {
    let reqPath = req.url.split('?')[0];
    if (reqPath === '/' || reqPath === '') reqPath = '/index.html';

    const safePath = path.normalize(reqPath).replace(/^(\.\.[\/\\])+/, '');
    const filePath = path.join(PUBLIC_DIR, safePath);

    fs.readFile(filePath, (err, data) => {
        if (err) {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            res.end('404 Not Found');
            return;
        }
        const ext = path.extname(filePath).toLowerCase();
        res.writeHead(200, { 'Content-Type': mimeTypes[ext] || 'application/octet-stream' });
        res.end(data);
    });
});

const wss = new WebSocketServer({ server });

wss.on('connection', (ws) => {
    console.log('[*] Web client connected, spawning DebTerm PRoot instance...');

    // Spawn debterm-cli session with clean environment
    const session = spawn(CLI_BIN, [], {
        env: {
            ...process.env,
            TERM: 'xterm-256color',
            COLUMNS: '100',
            LINES: '30'
        }
    });

    session.stdout.on('data', (chunk) => {
        if (ws.readyState === ws.OPEN) {
            ws.send(JSON.stringify({ type: 'output', data: chunk.toString('utf-8') }));
        }
    });

    session.stderr.on('data', (chunk) => {
        if (ws.readyState === ws.OPEN) {
            ws.send(JSON.stringify({ type: 'output', data: chunk.toString('utf-8') }));
        }
    });

    session.on('close', (code) => {
        console.log(`[*] DebTerm session exited with code ${code}`);
        if (ws.readyState === ws.OPEN) {
            ws.send(JSON.stringify({ type: 'output', data: `\r\n\x1b[33m[DebTerm Session Closed (Code: ${code})]\x1b[0m\r\n` }));
            ws.close();
        }
    });

    ws.on('message', (msg) => {
        try {
            const parsed = JSON.parse(msg);
            if (parsed.type === 'input') {
                session.stdin.write(parsed.data);
            }
        } catch (e) {
            // Raw text fallback
            session.stdin.write(msg.toString());
        }
    });

    ws.on('close', () => {
        console.log('[*] Web client disconnected, terminating session...');
        session.kill('SIGHUP');
    });

    ws.on('error', (err) => {
        console.error('[-] WebSocket error:', err.message);
        session.kill('SIGKILL');
    });
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`[+] DebTerm Web Terminal running at: http://0.0.0.0:${PORT}`);
    console.log(`[+] Access from browser or test via curl/wscat.`);
});
