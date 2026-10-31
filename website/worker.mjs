import { handleDownloads, cleanupSubscribers } from './email.mjs';
import { handleAlerts, cleanupAlerts } from './alerts.mjs';
import { handleUsage, cleanupUsage } from './usage.mjs';
const canonicalOrigin = 'https://bridgit.cc';

export default {
  async fetch(request, env) {
    const incoming = new URL(request.url);
    if (incoming.origin !== canonicalOrigin) {
      const target = new URL(canonicalOrigin);
      target.pathname = incoming.pathname;
      target.search = incoming.search;
      return Response.redirect(target.href, 301);
    }
    const handled = await handleDownloads(request, env);
    if (handled) return handled;
    const alert = await handleAlerts(request, env);
    if (alert) return alert;
    const usage = await handleUsage(request, env);
    if (usage) return usage;
    return env.ASSETS.fetch(request);
  },
  async scheduled(event, env) { await cleanupSubscribers(env); await cleanupAlerts(env); await cleanupUsage(env); }
};
