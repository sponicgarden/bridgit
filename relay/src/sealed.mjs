import {
  CipherSuite,
  DhkemP256HkdfSha256,
  HkdfSha256,
  Aes128Gcm,
} from "@hpke/core";
export const suite = new CipherSuite({
  kem: new DhkemP256HkdfSha256(),
  kdf: new HkdfSha256(),
  aead: new Aes128Gcm(),
});
const te = new TextEncoder(),
  un64 = (s) => Buffer.from(s, "base64"),
  b64 = (b) => Buffer.from(b).toString("base64");
export const aad = (c, id, deadline, seq, direction) =>
  te.encode(
    `bridgit-relay-v1\n${c.device_id}\n${c.agent_id}\n${c.generation}\n${id}\n${deadline}\n${seq}\n${direction}`,
  );
export async function keys() {
  const pair = await suite.kem.generateKeyPair();
  return {
    public: b64(await suite.kem.serializePublicKey(pair.publicKey)),
    private: b64(await suite.kem.serializePrivateKey(pair.privateKey)),
  };
}
async function pair(k) {
  return {
    publicKey: await suite.kem.deserializePublicKey(un64(k.public)),
    privateKey: await suite.kem.deserializePrivateKey(un64(k.private)),
  };
}
export async function seal(config, k, request, id, seq, deadline) {
  const ctx = await suite.createSenderContext({
    recipientPublicKey: await suite.kem.deserializePublicKey(
      un64(config.phone_hpke_public),
    ),
    senderKey: await pair(k),
    info: te.encode("bridgit-relay-v1"),
  });
  return {
    request_id: id,
    seq,
    deadline,
    enc: b64(ctx.enc),
    ct: b64(
      await ctx.seal(
        te.encode(JSON.stringify({ created_at: Date.now(), request })),
        aad(config, id, deadline, seq, "request"),
      ),
    ),
  };
}
export async function open(config, k, envelope, expected) {
  for (const field of ["request_id", "seq", "deadline"])
    if (envelope[field] !== expected[field])
      throw Error("Response binding mismatch");
  const ctx = await suite.createRecipientContext({
    recipientKey: await pair(k),
    senderPublicKey: await suite.kem.deserializePublicKey(
      un64(config.phone_hpke_public),
    ),
    enc: un64(envelope.enc),
    info: te.encode("bridgit-relay-v1"),
  });
  return JSON.parse(
    new TextDecoder().decode(
      await ctx.open(
        un64(envelope.ct),
        aad(
          config,
          expected.request_id,
          expected.deadline,
          expected.seq,
          "response",
        ),
      ),
    ),
  );
}
