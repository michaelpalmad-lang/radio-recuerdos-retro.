// Inscribe a un suscriptor del sitio web (navegador) en el tema "todos" de Firebase,
// el mismo de la app Android. Así un solo aviso del panel llega a la app y a la web.
//
// Uso desde recuerdosretro.com:
//   POST https://avisos-recuerdosretro.netlify.app/api/suscribir
//   Content-Type: application/json
//   { "token": "<token FCM del navegador>" }
//
// Variables de entorno: FIREBASE_SERVICE_ACCOUNT (la misma del envío de avisos).

import crypto from "node:crypto";
import { getStore } from "@netlify/blobs";

const TOPIC = "todos";
const ALLOWED = ["https://recuerdosretro.com", "https://www.recuerdosretro.com"];
let cachedToken = null;

function cors(req) {
  const origin = req.headers.get("origin") || "";
  return {
    "Access-Control-Allow-Origin": ALLOWED.includes(origin) ? origin : ALLOWED[0],
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    Vary: "Origin",
  };
}

const json = (req, status, data) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...cors(req) },
  });

const b64url = (buf) =>
  Buffer.from(buf).toString("base64").replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");

async function getAccessToken() {
  const sa = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT || "{}");
  if (!sa.client_email || !sa.private_key) throw new Error("Falta FIREBASE_SERVICE_ACCOUNT.");
  sa.private_key = sa.private_key.replace(/\\n/g, "\n");
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.exp - 60 > now) return cachedToken.token;
  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const signer = crypto.createSign("RSA-SHA256");
  signer.update(`${header}.${claims}`);
  const jwt = `${header}.${claims}.${b64url(signer.sign(sa.private_key))}`;
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: jwt }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.access_token) throw new Error("Google rechazó la clave de servidor.");
  cachedToken = { token: data.access_token, exp: now + (data.expires_in || 3600) };
  return cachedToken.token;
}

// Contador de suscripciones (sin datos personales: solo una huella del token).
// Cada dispositivo cuenta una vez; si vuelve a activar avisos no se suma de nuevo.
async function contar(req, token) {
  try {
    const ua = req.headers.get("user-agent") || "";
    const tipo = /iPhone|iPad|iPod/i.test(ua) ? "iphone" : "web";
    const huella = crypto.createHash("sha256").update(token).digest("hex").slice(0, 32);
    const store = getStore("suscripciones");
    const clave = `${tipo}/${huella}`;
    if (!(await store.get(clave))) await store.set(clave, new Date().toISOString().slice(0, 10));
  } catch {
    // El contador nunca debe impedir la suscripción.
  }
}

export default async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(req) });
  if (req.method !== "POST") return json(req, 405, { ok: false, error: "Método no permitido." });

  const origin = req.headers.get("origin") || "";
  if (origin && !ALLOWED.includes(origin)) return json(req, 403, { ok: false, error: "Origen no permitido." });

  let input;
  try { input = await req.json(); } catch { return json(req, 400, { ok: false, error: "Solicitud inválida." }); }
  const token = String(input.token || "").trim();
  if (token.length < 100 || token.length > 400 || /\s/.test(token)) {
    return json(req, 400, { ok: false, error: "Token inválido." });
  }

  try {
    const access = await getAccessToken();
    const res = await fetch("https://iid.googleapis.com/iid/v1:batchAdd", {
      method: "POST",
      headers: { Authorization: `Bearer ${access}`, access_token_auth: "true", "Content-Type": "application/json" },
      body: JSON.stringify({ to: `/topics/${TOPIC}`, registration_tokens: [token] }),
    });
    const data = await res.json().catch(() => ({}));
    const r = data?.results?.[0] || {};
    if (!res.ok || r.error) return json(req, 502, { ok: false, error: r.error || data?.error || `Error ${res.status}` });
    await contar(req, token);
    return json(req, 200, { ok: true });
  } catch (e) {
    return json(req, 500, { ok: false, error: e.message || "Error inesperado." });
  }
};

export const config = { path: "/api/suscribir" };
