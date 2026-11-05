#!/usr/bin/env node
/*
 * Mock fleQ backend for testing the kiosk without the real server.
 * Implements the two endpoints the fleQ_KIOSK APK uses, with the same request and response shapes:
 *
 *   POST /api/v1/login      form: username, password   -> token_type, token, workflow{id,...}
 *   POST /api/v1/add-queue  form: workflow_id           -> number, total, building, url_qr, worklfow_name (sic)
 *                           header Authorization: "<token_type> <token>"
 *
 * Usage:  node server.js
 * Env:    PORT=8080
 *         MOCK_PASSWORD=secret   (if set, only this password is accepted; otherwise any non-empty password)
 *         TOKEN_TTL_SEC=600      (expired tokens get 401, so you can test the app's re-login + retry)
 *         ORG_NAME="โรงพยาบาลตัวอย่าง"
 * No dependencies.
 */
'use strict';

const http = require('http');
const crypto = require('crypto');
const os = require('os');

const PORT = Number(process.env.PORT || 8080);
const MOCK_PASSWORD = process.env.MOCK_PASSWORD || '';
const TOKEN_TTL_SEC = Number(process.env.TOKEN_TTL_SEC || 600);
const ORG_NAME = process.env.ORG_NAME || 'โรงพยาบาลตัวอย่าง';
const WORKFLOW = { id: 7, name: 'ผู้ป่วยนอก', letter: 'A', branch_name: ORG_NAME, start: 1, button_style: null };

const tokens = new Map(); // token -> expiresAt (ms)
let lastNumber = 0;
const waiting = []; // numbers handed out, to fake "total" (queues waiting)

function send(res, status, body) {
  const json = JSON.stringify(body);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(json) });
  res.end(json);
}

function readForm(req) {
  return new Promise((resolve, reject) => {
    let raw = '';
    req.setEncoding('utf8');
    req.on('data', (c) => {
      raw += c;
      if (raw.length > 1e5) reject(new Error('body too large'));
    });
    req.on('end', () => resolve(Object.fromEntries(new URLSearchParams(raw))));
    req.on('error', reject);
  });
}

function bearer(req) {
  const h = req.headers.authorization || '';
  const m = /^Bearer\s+(.+)$/i.exec(h);
  return m ? m[1] : null;
}

async function handle(req, res) {
  const url = new URL(req.url, 'http://localhost');
  const started = Date.now();
  res.on('finish', () => console.log(`${new Date().toISOString()} ${req.method} ${url.pathname} -> ${res.statusCode} (${Date.now() - started} ms)`));

  if (req.method === 'POST' && url.pathname === '/api/v1/login') {
    const { username = '', password = '' } = await readForm(req);
    if (!username || !password || (MOCK_PASSWORD && password !== MOCK_PASSWORD)) {
      return send(res, 401, { status: 'error', message: 'invalid username or password' });
    }
    const token = crypto.randomBytes(16).toString('hex');
    tokens.set(token, Date.now() + TOKEN_TTL_SEC * 1000);
    return send(res, 200, {
      status: 'success',
      data: {
        app_name: 'kiosk', name: username, employee_id: null, branch: ORG_NAME, building: 'อาคาร C',
        department: 'OPD', counter: null, room: null,
        token_type: 'Bearer', token, expire: TOKEN_TTL_SEC,
        workflow: WORKFLOW,
      },
    });
  }

  if (req.method === 'POST' && url.pathname === '/api/v1/add-queue') {
    const token = bearer(req);
    const exp = token && tokens.get(token);
    if (!exp || exp < Date.now()) {
      if (token) tokens.delete(token);
      return send(res, 401, { status: 'error', message: 'token invalid or expired' });
    }
    const { workflow_id: workflowId } = await readForm(req);
    if (String(workflowId) !== String(WORKFLOW.id)) {
      return send(res, 422, { status: 'error', message: `unknown workflow_id ${workflowId}` });
    }
    lastNumber = (lastNumber % 999) + 1;
    const number = String(lastNumber).padStart(3, '0'); // 3 digits: the app pads to 4, as fleQ does
    waiting.push(number);
    if (waiting.length > 15) waiting.shift();
    return send(res, 200, {
      status: 'success',
      data: {
        worklfow_name: WORKFLOW.name, // the typo is part of the real contract
        number,
        total: String(waiting.length - 1),
        building: 'อาคาร C ชั้น 1',
        doctor: null,
        vn: null,
        url_qr: `https://example.com/queue/${WORKFLOW.letter}${number}`,
      },
    });
  }

  if (req.method === 'GET' && url.pathname === '/') {
    return send(res, 200, { status: 'ok', endpoints: ['POST /api/v1/login', 'POST /api/v1/add-queue'] });
  }
  send(res, 404, { status: 'error', message: 'not found' });
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((e) => {
    console.error(e);
    if (!res.headersSent) send(res, 500, { status: 'error', message: e.message });
  });
});

server.listen(PORT, '0.0.0.0', () => {
  const ips = Object.values(os.networkInterfaces()).flat()
    .filter((i) => i && i.family === 'IPv4' && !i.internal).map((i) => i.address);
  console.log(`Mock fleQ server listening on port ${PORT}`);
  console.log('Use one of these as Host/Url on the tablet:');
  for (const ip of ips) console.log(`  http://${ip}:${PORT}`);
  console.log(`Token TTL: ${TOKEN_TTL_SEC}s; password: ${MOCK_PASSWORD ? '(MOCK_PASSWORD)' : 'any non-empty'}`);
});
