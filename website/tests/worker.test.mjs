import test from 'node:test';
import assert from 'node:assert/strict';
import worker from '../worker.mjs';

for (const origin of ['http://bridgit.cc', 'http://www.bridgit.cc', 'https://www.bridgit.cc']) {
  test(`${origin} permanently redirects directly to HTTPS apex, preserving path and query`, async () => {
    const response = await worker.fetch(new Request(`${origin}/developers/?source=a%2Fb&x=1`), {
      ASSETS: { fetch() { assert.fail('Noncanonical requests must not serve assets'); } }
    });
    assert.equal(response.status, 301);
    assert.equal(response.headers.get('location'), 'https://bridgit.cc/developers/?source=a%2Fb&x=1');
  });
}

test('canonical requests retain the asset response and security headers', async () => {
  const request = new Request('https://bridgit.cc/privacy/');
  const response = new Response('policy', { headers: { 'X-Content-Type-Options': 'nosniff' } });
  const actual = await worker.fetch(request, { ASSETS: { fetch(incoming) {
    assert.equal(incoming, request);
    return response;
  } } });
  assert.equal(actual, response);
  assert.equal(actual.headers.get('location'), null);
});

test('unknown paths retain asset 404 status', async () => {
  const response = await worker.fetch(new Request('https://bridgit.cc/missing/'), {
    ASSETS: { fetch() { return new Response('Not found', { status: 404 }); } }
  });
  assert.equal(response.status, 404);
});
