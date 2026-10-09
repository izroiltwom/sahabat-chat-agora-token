const http = require("http");
const { RtcTokenBuilder, RtcRole } = require("agora-token");

const PORT = Number(process.env.PORT || 3000);
const AGORA_APP_ID = String(process.env.AGORA_APP_ID || "").trim();
const AGORA_APP_CERTIFICATE = String(
  process.env.AGORA_APP_CERTIFICATE || ""
).trim();

function sendJson(res, statusCode, data) {
  const body = JSON.stringify(data);

  res.writeHead(statusCode, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Allow-Methods": "POST, OPTIONS"
  });

  res.end(body);
}

function isValidAppId(value) {
  return /^[A-Fa-f0-9]{32}$/.test(value);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = "";

    req.on("data", chunk => {
      body += chunk;

      if (body.length > 16 * 1024) {
        reject(new Error("Request terlalu besar."));
        req.destroy();
      }
    });

    req.on("end", () => {
      try {
        resolve(body ? JSON.parse(body) : {});
      } catch {
        reject(new Error("JSON tidak valid."));
      }
    });

    req.on("error", reject);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Content-Type",
      "Access-Control-Allow-Methods": "POST, OPTIONS"
    });
    return res.end();
  }

  if (req.method === "GET" && req.url === "/health") {
    return sendJson(res, 200, {
      ok: true,
      service: "sahabat-chat-agora-token-server"
    });
  }

  if (req.method !== "POST" || req.url !== "/issueAgoraRtcToken") {
    return sendJson(res, 404, {
      error: "Endpoint tidak ditemukan."
    });
  }

  if (!isValidAppId(AGORA_APP_ID) || !AGORA_APP_CERTIFICATE) {
    console.error("Konfigurasi Agora belum lengkap.");
    return sendJson(res, 500, {
      error: "Server Agora belum dikonfigurasi."
    });
  }

  try {
    const data = await readJson(req);

    const channelName = String(data.channelName || "").trim();
    const uid = Number(data.uid);

    if (
      !channelName ||
      Buffer.byteLength(channelName, "utf8") > 64
    ) {
      return sendJson(res, 400, {
        error: "Nama room tidak valid."
      });
    }

    if (
      !Number.isInteger(uid) ||
      uid < 1 ||
      uid > 2147483647
    ) {
      return sendJson(res, 400, {
        error: "UID Agora tidak valid."
      });
    }

    const expiresIn = 3600;
    const privilegeExpiredTs =
      Math.floor(Date.now() / 1000) + expiresIn;

    const token = RtcTokenBuilder.buildTokenWithUid(
      AGORA_APP_ID,
      AGORA_APP_CERTIFICATE,
      channelName,
      uid,
      RtcRole.PUBLISHER,
      privilegeExpiredTs
    );

    return sendJson(res, 200, {
      appId: AGORA_APP_ID,
      token,
      expiresIn
    });
  } catch (error) {
    console.error("Token error:", error);

    return sendJson(res, 500, {
      error: "Gagal membuat token Agora."
    });
  }
});

if (require.main === module) {
  server.listen(PORT, "0.0.0.0", () => {
    console.log(`Agora token server berjalan pada port ${PORT}`);
    console.log(`Health check: http://localhost:${PORT}/health`);
    console.log(`Token endpoint: http://localhost:${PORT}/issueAgoraRtcToken`);
  });
} else {
  module.exports = server;
}
