// Envía un aviso push a todos los oyentes de la app Radio Recuerdos Retro.
// Usa Firebase Cloud Messaging (API HTTP v1) y el tema "todos", al que la app se suscribe sola.
//
// Variables de entorno (se configuran en Netlify, nunca en el código):
//   FIREBASE_SERVICE_ACCOUNT  -> contenido completo del archivo JSON de la cuenta de servicio de Firebase
//   PANEL_CLAVE               -> contraseña que usa el equipo para entrar al panel

import crypto from "node:crypto";

const TOPIC = "todos";
const CHANNEL = "avisos";
const COLOR = "#FF2A8C";

let cachedToken = null; // { token, exp }

const json = (status, data) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" },
  });

const b64url = (buf) =>
  Buffer.from(buf).toString("base64").replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");

function readServiceAccount() {
  const raw = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (!raw) throw new Error("Falta configurar FIREBASE_SERVICE_ACCOUNT en Netlify.");
  let sa;
  try {
    sa = JSON.parse(raw);
  } catch {
    throw new Error("FIREBASE_SERVICE_ACCOUNT no es un JSON válido. Pega el archivo completo.");
  }
  if (!sa.client_email || !sa.private_key || !sa.project_id) {
    throw new Error("El JSON de la cuenta de servicio está incompleto.");
  }
  sa.private_key = sa.private_key.replace(/\\n/g, "\n");
  return sa;
}

async function getAccessToken(sa) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.exp - 60 > now) return cachedToken.token;

  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claims = b64url(
    JSON.stringify({
      iss: sa.client_email,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    })
  );
  const signer = crypto.createSign("RSA-SHA256");
  signer.update(`${header}.${claims}`);
  const jwt = `${header}.${claims}.${b64url(signer.sign(sa.private_key))}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.access_token) {
    throw new Error("Google rechazó la clave de servidor. Revisa FIREBASE_SERVICE_ACCOUNT.");
  }
  cachedToken = { token: data.access_token, exp: now + (data.expires_in || 3600) };
  return cachedToken.token;
}

function safeEqual(a, b) {
  const x = Buffer.from(String(a));
  const y = Buffer.from(String(b));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

export function buildMessage({ title, body, image }) {
  const notification = { title, body };
  const androidNotification = { channel_id: CHANNEL, color: COLOR, default_sound: true };
  if (image) {
    notification.image = image;
    androidNotification.image = image;
  }
  return {
    message: {
      topic: TOPIC,
      notification,
      android: { priority: "high", notification: androidNotification },
      data: { title, body, ...(image ? { image } : {}) },
    },
  };
}

export default async (req) => {
  if (req.method !== "POST") return json(405, { ok: false, error: "Método no permitido." });

  const clave = process.env.PANEL_CLAVE;
  if (!clave) return json(500, { ok: false, error: "Falta configurar PANEL_CLAVE en Netlify." });

  let input;
  try {
    input = await req.json();
  } catch {
    return json(400, { ok: false, error: "Solicitud inválida." });
  }

  if (!safeEqual(input.clave || "", clave)) {
    await new Promise((r) => setTimeout(r, 1200)); // frena intentos al azar
    return json(401, { ok: false, error: "Contraseña incorrecta." });
  }

  // Solo verificar la contraseña (pantalla de entrada).
  if (input.soloVerificar) return json(200, { ok: true });

  const title = String(input.titulo || "").trim().slice(0, 65);
  const body = String(input.texto || "").trim().slice(0, 240);
  let image = String(input.imagen || "").trim();

  if (!title) return json(400, { ok: false, error: "Escribe un título." });
  if (!body) return json(400, { ok: false, error: "Escribe el texto del aviso." });

  if (image) {
    if (image.startsWith("/")) {
      const site = process.env.URL || new URL(req.url).origin;
      image = site + image;
    }
    if (!/^https:\/\/\S+$/i.test(image)) {
      return json(400, { ok: false, error: "El enlace de la imagen debe empezar con https://" });
    }
  }

  try {
    const sa = readServiceAccount();
    const token = await getAccessToken(sa);
    const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify(buildMessage({ title, body, image })),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      const msg = data?.error?.message || `Error ${res.status}`;
      return json(502, { ok: false, error: `Firebase no aceptó el aviso: ${msg}` });
    }
    return json(200, { ok: true, id: data.name || "" });
  } catch (e) {
    return json(500, { ok: false, error: e.message || "Error inesperado." });
  }
};

export const config = { path: "/api/enviar" };
