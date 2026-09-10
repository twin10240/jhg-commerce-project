import assert from 'node:assert/strict';
import test from 'node:test';
import { runIntegration } from './system-integration.mjs';

test('OMS integration reports only the requested PR commit and propagates remote failures', async () => {
  const repository = 'twin10240/jhg-commerce-project';
  const sha = 'a'.repeat(40);
  const pull = () => ({ number: 6, state: 'open', author_association: 'OWNER',
    head: { sha, repo: { full_name: repository } }, base: { repo: { full_name: repository } } });
  for (const conclusion of ['success', 'failure', 'cancelled', 'timed_out']) {
    const event = { repository: { full_name: repository }, pull_request: pull() };
    const statuses = [];
    let inputs;
    let reads = 0;
    const source = async (path, body) => {
      if (!body) return pull();
      statuses.push({ path, ...body });
    };
    const remote = async (path, body) => {
      if (body) {
        inputs = body.inputs;
        assert.equal(body.ref, 'main');
        assert.equal(body.return_run_details, true);
        // A newer PR head arrives while the requested run is in flight.
        event.pull_request.head.sha = 'b'.repeat(40);
        return { workflow_run_id: 42, html_url: 'https://github.com/twin10240/jhg-system-tests/actions/runs/42' };
      }
      return { id: 42, event: 'workflow_dispatch', head_branch: 'main',
        display_title: `System integration · ${inputs.request_id} · oms`,
        status: ++reads === 1 ? 'in_progress' : 'completed', conclusion };
    };
    const result = await runIntegration(event, source, remote, async () => {});
    assert.equal(inputs.sha, sha);
    assert.equal(inputs.source_pr, '6');
    assert.equal(inputs.source_repository, repository);
    assert.equal(inputs.service, 'oms');
    assert.equal(result.state, conclusion === 'success' ? 'success' : 'failure');
    assert.equal(statuses.at(-1).state, result.state);
    assert.equal(statuses.at(-1).target_url, 'https://github.com/twin10240/jhg-system-tests/actions/runs/42');
    assert.ok(statuses.some((s) => s.state === 'pending'));
    assert.ok(statuses.every((s) => s.path === `statuses/${sha}`));
  }
  const event = { repository: { full_name: repository }, pull_request: pull() };
  const forbidden = async () => assert.fail('An untrusted or stale request must not dispatch or write status');
  const stale = { ...pull(), head: { sha: 'b'.repeat(40), repo: { full_name: repository } } };
  assert.equal((await runIntegration(event, async () => stale, forbidden)).state, 'skipped');
  assert.equal((await runIntegration(event, async () => ({ ...pull(), state: 'closed' }), forbidden)).state, 'skipped');
  for (const unsafe of [
    { ...pull(), author_association: 'NONE' },
    { ...pull(), head: { sha, repo: { full_name: 'someone/fork' } } },
  ]) await assert.rejects(runIntegration({ ...event, pull_request: unsafe }, forbidden, forbidden));
  const statuses = [];
  const source = async (path, body) => body ? statuses.push(body) : pull();
  const wrongRun = async (path, body) => body
    ? { workflow_run_id: 42, html_url: 'https://github.com/twin10240/jhg-system-tests/actions/runs/42' }
    : { id: 42, event: 'workflow_dispatch', head_branch: 'main', display_title: 'another request',
      status: 'completed', conclusion: 'success' };
  await assert.rejects(runIntegration(event, source, wrongRun), /Run\/request mismatch/);
  assert.ok(statuses.every((s) => s.state === 'pending'), 'An unrelated successful run must never produce success');
  let elapsed = 0;
  let request;
  const slowRun = async (path, body) => {
    if (body) {
      request = body.inputs;
      return { workflow_run_id: 42, html_url: 'https://github.com/twin10240/jhg-system-tests/actions/runs/42' };
    }
    elapsed += 15_000;
    return { id: 42, event: 'workflow_dispatch', head_branch: 'main',
      display_title: `System integration · ${request.request_id} · oms`, status: 'in_progress' };
  };
  await assert.rejects(runIntegration(event, source, slowRun,
    async (ms) => { elapsed += ms; }, () => elapsed), /exceeded 25 minutes/);
  assert.ok(elapsed <= 25 * 60_000 + 15_000, 'API latency must count toward the deadline');
});
