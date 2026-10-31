import worker from "../src/worker.mjs";
import { PhoneRelay as BasePhoneRelay } from "../src/worker.mjs";
export class PhoneRelay extends BasePhoneRelay {
  async fetch(req) {
    const p = new URL(req.url).pathname;
    if (p.endsWith("/__fixture_alarm")) {
      await this.alarm();
      return Response.json({ ok: true });
    }
    if (p.endsWith("/__fixture_meta"))
      return Response.json({
        alarm: await this.ctx.storage.getAlarm(),
        keys: [...(await this.ctx.storage.list()).keys()],
      });
    return super.fetch(req);
  }
}
export default {
  async fetch(req, env) {
    if (req.headers.get("Authorization") === "fixture-admin") {
      const u = new URL(req.url),
        device = u.pathname.split("/")[2];
      return env.PHONES.get(env.PHONES.idFromName(device)).fetch(req);
    }
    return worker.fetch(req, env);
  },
};
