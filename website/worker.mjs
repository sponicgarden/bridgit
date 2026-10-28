const canonicalOrigin = 'https://bridgit.cc';

export default {
  fetch(request, env) {
    const incoming = new URL(request.url);
    if (incoming.origin !== canonicalOrigin) {
      const target = new URL(canonicalOrigin);
      target.pathname = incoming.pathname;
      target.search = incoming.search;
      return Response.redirect(target.href, 301);
    }
    return env.ASSETS.fetch(request);
  }
};
