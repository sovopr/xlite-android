const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)][1][1];
const delay = () => new Promise(resolve => setTimeout(resolve, 80));

function harness(modern, replies) {
    const elements = {
        searchInput: { value: 'Godzilla' },
        searchResults: {
            innerHTML: '',
            insertAdjacentHTML(_position, markup) { this.innerHTML += markup; }
        },
        searchLoadMoreBox: { innerHTML: '' },
        searchBtn: { disabled: false },
        mainContent: { innerHTML: '' },
        tabNav: { style: {} }
    };
    const requests = [];
    const bridge = modern ? {
        executeSearchGetAsync(url, transaction, callbackId) {
            requests.push({ url, transaction, variables: JSON.parse(new URL(url).searchParams.get('variables')) });
            const reply = replies.shift();
            setTimeout(() => context.window.__onAsyncFinish(callbackId, JSON.stringify(reply)), 0);
        }
    } : {
        executePost(url, body) {
            const payload = JSON.parse(body);
            requests.push({ url, variables: payload.variables, queryId: payload.queryId });
            return JSON.stringify(replies.shift());
        }
    };
    const context = {
        console,
        AndroidBridge: bridge,
        document: {
            getElementById(id) { return elements[id] || null; },
            querySelectorAll() { return [{}, {}, {}, {}].map(() => ({ classList: { toggle() {} } })); }
        },
        history: { pushState() {} },
        scrollY: 240,
        scrollTo(_x, y) { this.restoredScrollY = y; },
        setTimeout,
        clearTimeout,
        URL
    };
    context.window = context;
    vm.createContext(context);
    vm.runInContext(script, context);
    vm.runInContext(`
        currentView = 'search';
        renderTweetHtml = tweet => '<article data-id="' + tweet.id + '"></article>';
        parseTwitterGraphql = response => JSON.parse(response);
    `, context);
    if (modern) vm.runInContext(`
        searchTransactionClient = { generateTransactionId: () => 'test-transaction' };
        searchTransactionFetchedAt = Date.now();
    `, context);
    return { context, elements, requests, run: code => vm.runInContext(code, context) };
}

function testCursorParsing() {
    const context = { window: {}, console };
    vm.createContext(context);
    vm.runInContext(script, context);
    context.response = JSON.stringify({
        data: { search_by_raw_query: { search_timeline: { timeline: { instructions: [{
            type: 'TimelineAddEntries',
            entries: [
                { content: { itemContent: { itemType: 'TimelineTweet', tweet_results: {
                    result: { rest_id: '42', legacy: { full_text: 'A result' } }
                } } } },
                { content: { entryType: 'TimelineTimelineCursor', cursorType: 'Bottom', value: 'next-page' } }
            ]
        }] } } } }
    });
    const parsed = vm.runInContext('parseTwitterGraphql(response)', context);
    assert.equal(parsed.tweets.length, 1);
    assert.equal(parsed.nextCursor, 'next-page');
}

async function testLegacySearch() {
    const app = harness(false, [
        { tweets: [{ id: '1' }, { id: '2' }], nextCursor: 'page-2' },
        { error: 'Temporary network error', status: 503 },
        { tweets: [{ id: '2' }, { id: '3' }], nextCursor: 'page-3' },
        { tweets: [{ id: '3' }, { id: '4' }], nextCursor: 'page-3' }
    ]);
    app.run('executeSearch({preventDefault(){}})');
    await delay();
    assert.equal(app.requests[0].variables.cursor, undefined);
    assert.match(app.elements.searchLoadMoreBox.innerHTML, /Load More/);

    app.run('loadMoreSearch()');
    await delay();
    assert.equal(app.requests[1].variables.cursor, 'page-2');
    assert.match(app.elements.searchLoadMoreBox.innerHTML, /Retry Load More/);
    assert.match(app.elements.searchResults.innerHTML, /data-id="1"/);

    app.run('loadMoreSearch()');
    await delay();
    assert.equal(app.requests[2].variables.cursor, 'page-2');
    assert.equal((app.elements.searchResults.innerHTML.match(/data-id="2"/g) || []).length, 1);
    assert.match(app.elements.searchResults.innerHTML, /data-id="3"/);

    app.run('loadMoreSearch()');
    await delay();
    assert.equal(app.requests[3].variables.cursor, 'page-3');
    assert.match(app.elements.searchResults.innerHTML, /data-id="4"/);
    assert.equal(app.elements.searchLoadMoreBox.innerHTML, '');
    assert.equal(app.run('currentTweetsList.length'), 4);

    app.elements.mainContent.innerHTML = '<div>Saved search and results</div>';
    app.run("renderThreadView = () => {}; viewThread('4'); window.onpopstate({state:null})");
    assert.equal(app.run('currentView'), 'search');
    assert.equal(app.elements.mainContent.innerHTML, '<div>Saved search and results</div>');
    assert.equal(app.context.restoredScrollY, 240);
}

async function testModernSearch() {
    const app = harness(true, [
        { tweets: [{ id: '10' }], nextCursor: 'older' },
        { tweets: [{ id: '11' }], nextCursor: null }
    ]);
    app.run('executeSearch({preventDefault(){}})');
    await delay();
    app.run('loadMoreSearch()');
    await delay();
    assert.equal(app.requests.length, 2);
    assert.equal(app.requests[1].variables.cursor, 'older');
    assert.equal(app.requests[1].transaction, 'test-transaction');
    assert.equal(app.run('currentTweetsList.length'), 2);
    assert.equal(app.elements.searchLoadMoreBox.innerHTML, '');
}

testCursorParsing();
Promise.all([testLegacySearch(), testModernSearch()]).then(() => {
    console.log('Search pagination passed for legacy POST and modern GET bridges.');
}).catch(error => {
    console.error(error);
    process.exitCode = 1;
});
