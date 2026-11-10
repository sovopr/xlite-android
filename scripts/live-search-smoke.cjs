const fs = require('node:fs');
const vm = require('node:vm');

const auth = process.env.XLITE_TEST_AUTH_TOKEN;
const ct0 = process.env.XLITE_TEST_CT0;
if (!auth || !ct0) {
  console.error('Set XLITE_TEST_AUTH_TOKEN and XLITE_TEST_CT0 to run this smoke test.');
  process.exit(2);
}

const appHtml = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const bridgeSource = fs.readFileSync('app/src/main/java/com/sovopr/xlite/TwitterBridge.kt', 'utf8');
const scripts = [...appHtml.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]);
if (scripts.length !== 2) throw new Error('Expected the transaction library and app scripts.');
const context = { Buffer, TextEncoder, TextDecoder, Uint8Array, Math, Date, console, window: {}, document: {}, setTimeout, clearTimeout };
vm.createContext(context);
scripts.forEach(script => vm.runInContext(script, context));

const queryId = appHtml.match(/const queryIds = \['([^']+)'/)[1];
const bearer = bridgeSource.match(/const val BEARER_TOKEN = "([^"]+)"/)[1];
const userAgent = bridgeSource.match(/const val USER_AGENT = "([^"]+)"/)[1];
const baseHeaders = {
  authorization: `Bearer ${bearer}`,
  'x-csrf-token': ct0,
  cookie: `auth_token=${auth}; ct0=${ct0}`,
  'user-agent': userAgent,
  'x-twitter-active-user': 'yes',
  'x-twitter-client-language': 'en',
};

async function getText(url, headers) {
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(90000) });
  if (!response.ok) throw new Error(`${new URL(url).pathname}: HTTP ${response.status}`);
  return response.text();
}

(async () => {
  const home = await getText('https://x.com/home', {
    cookie: `auth_token=${auth}; ct0=${ct0}`,
    'user-agent': userAgent,
    accept: 'text/html',
  });
  const chunk = home.match(/,(\d+):"ondemand\.s"/);
  const hash = chunk && home.match(new RegExp(',' + chunk[1] + ':"([0-9a-f]+)"'));
  if (!hash) throw new Error('X transaction module was not found.');
  const moduleUrl = `https://abs.twimg.com/responsive-web/client-web/ondemand.s.${hash[1]}a.js`;
  const module = await getText(moduleUrl, { 'user-agent': userAgent });
  context.home = home;
  context.module = module;
  vm.runInContext('this.transactionClient = new XClientTransaction.ClientTransaction(home, module)', context);

  const path = `/i/api/graphql/${queryId}/SearchTimeline`;
  context.searchPath = path;
  const transactionId = vm.runInContext("transactionClient.generateTransactionId('GET', searchPath)", context);
  const variables = { rawQuery: 'Godzilla', count: 8, querySource: 'typed_query', product: 'Top' };
  const features = vm.runInContext('GRAPHQL_FEATURES', context);
  const query = new URLSearchParams({ variables: JSON.stringify(variables), features: JSON.stringify(features) });
  const result = await getText(`https://x.com${path}?${query}`, {
    ...baseHeaders,
    'x-client-transaction-id': transactionId,
    'x-twitter-auth-type': 'OAuth2Session',
    referer: 'https://x.com/search',
  });
  context.searchResponse = result;
  const parsed = vm.runInContext('parseTwitterGraphql(searchResponse)', context);
  if (parsed.error || parsed.tweets.length === 0) throw new Error(parsed.error || 'Search returned no posts.');
  console.log(`Live search passed: ${parsed.tweets.length} posts parsed.`);
})().catch(error => {
  console.error(`Live search failed: ${error.message}`);
  process.exitCode = 1;
});
