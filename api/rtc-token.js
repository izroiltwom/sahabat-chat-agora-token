const { RtcTokenBuilder, RtcRole } = require("agora-token");

module.exports = (req, res) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");

  if (req.method === "OPTIONS") {
    return res.status(200).end();
  }

  const appId = process.env.AGORA_APP_ID;
  const appCertificate = process.env.AGORA_APP_CERTIFICATE;

  if (!appId || !appCertificate) {
    return res.status(500).json({
      error: "Missing AGORA_APP_ID or AGORA_APP_CERTIFICATE environment variables"
    });
  }

  const channelName = String(req.query.channel || "").trim();
  const uid = Number(req.query.uid ?? 0);

  if (!channelName) {
    return res.status(400).json({ error: "Parameter 'channel' wajib diisi" });
  }

  if (!Number.isInteger(uid) || uid < 0) {
    return res.status(400).json({ error: "Parameter 'uid' harus berupa bilangan bulat >= 0" });
  }

  const role = RtcRole.PUBLISHER;
  const expireSeconds = 3600;

  try {
    const token = RtcTokenBuilder.buildTokenWithUid(
      appId,
      appCertificate,
      channelName,
      uid,
      role,
      expireSeconds,
      expireSeconds
    );

    return res.status(200).json({
      token,
      channelName,
      uid,
      expiresIn: expireSeconds
    });
  } catch (error) {
    return res.status(500).json({ error: "Gagal membuat token Agora" });
  }
};
