#!/usr/bin/env node
import fs from "node:fs/promises";
import crypto from "node:crypto";
import { keys, seal, open } from "./src/sealed.mjs";
const [action, file, requestFile] = process.argv.slice(2);
if (!file)
  throw Error(
    "Usage: node sealed-client.mjs init KEYFILE | call CONFIG.json REQUEST.json",
  );
if (action === "init") {
  const k = await keys();
  await fs.writeFile(file, JSON.stringify(k) + "\n", {
    mode: 0o600,
    flag: "wx",
  });
  console.log(JSON.stringify({ agent_hpke_public: k.public }));
} else if (action === "call") {
  const stat = await fs.stat(file);
  if (stat.mode & 0o077) throw Error("Config must be private: chmod 600");
  const config = JSON.parse(await fs.readFile(file, "utf8"));
  const base = new URL(config.base);
  if (base.protocol !== "https:" || base.username || base.password)
    throw Error("HTTPS relay required");
  const k = JSON.parse(await fs.readFile(config.key_file, "utf8"));
  if ((await fs.stat(config.key_file)).mode & 0o077)
    throw Error("Key file must be private");
  const lock = await fs.open(file + ".lock", "wx", 0o600);
  try {
    const stateFile = file + ".sequence",
      seq =
        Number(
          await fs.readFile(stateFile, "utf8").catch((e) => {
            if (e.code === "ENOENT") return "0";
            throw e;
          }),
        ) + 1;
    if (!Number.isSafeInteger(seq) || seq <= 0) throw Error("Invalid sequence");
    const temp = stateFile + ".tmp";
    const h = await fs.open(temp, "w", 0o600);
    await h.writeFile(String(seq));
    await h.sync();
    await h.close();
    await fs.rename(temp, stateFile);
    const dir = await fs.open(
      new URL(".", `file://${stateFile}`).pathname,
      "r",
    );
    await dir.sync();
    await dir.close();
    const request = JSON.parse(await fs.readFile(requestFile, "utf8")),
      id = crypto.randomUUID(),
      deadline = Date.now() + 55000,
      envelope = await seal(config, k, request, id, seq, deadline);
    const headers = {
      Authorization: "Bearer " + config.token,
      "Content-Type": "application/json",
    };
    let r = await fetch(config.base + "/jobs?wait=55", {
      method: "POST",
      headers,
      body: JSON.stringify({ deadline, envelope }),
      redirect: "error",
      signal: AbortSignal.timeout(60000),
    });
    let b = await r.json();
    while (r.status === 202 && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 500));
      r = await fetch(config.base + "/jobs/" + b.job_id, {
        headers,
        redirect: "error",
        signal: AbortSignal.timeout(10000),
      });
      b = await r.json();
    }
    if (!r.ok || !b.envelope) throw Error(JSON.stringify(b));
    console.log(
      JSON.stringify(
        await open(config, k, b.envelope, { request_id: id, deadline, seq }),
        null,
        2,
      ),
    );
  } finally {
    await lock.close();
    await fs.unlink(file + ".lock");
  }
} else throw Error("Unknown action");
