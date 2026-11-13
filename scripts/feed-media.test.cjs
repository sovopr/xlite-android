const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)][1][1];
const cache = new Map();
const requests = [];
const progress = { textContent: '', classList: { toggle(name, on) { this[name] = on; } } };
const refresh = { disabled: false, innerHTML: '' };
const loadMore = { disabled: false, textContent: 'Load More' };
const location = { hash: '', pathname: '/index.html', search: '' };
let restoredScrollY = -1;
const main = {
    _html: '',
    get innerHTML() { return this._html; },
    set innerHTML(value) { this._html = value; this._node = value ? { html: value } : null; },
    get firstChild() { return this._node; },
    appendChild(fragment) { this.innerHTML = fragment.firstChild?.html || ''; fragment.node = null; },
    insertAdjacentHTML(_place, value) { this.innerHTML = this._html + value; }
};
const context = {
    console, URL, setTimeout, clearTimeout,
    document: {
        getElementById(id) {
            if (id === 'mainContent') return main;
            if (id === 'feedContainer' && main.innerHTML.includes('id="feedContainer"')) return { insertAdjacentHTML() {} };
            if (id === 'feedProgress' && main.innerHTML.includes('id="feedProgress"')) return progress;
            if (id === 'feedRefreshButton' && main.innerHTML.includes('id="feedRefreshButton"')) return refresh;
            return null;
        },
        querySelector(selector) {
            if (selector === '#loadMoreBox button' && main.innerHTML.includes('id="loadMoreBox"')) return loadMore;
            return null;
        },
        querySelectorAll() { return []; },
        createDocumentFragment() {
            return {
                node: null,
                get firstChild() { return this.node; },
                appendChild(node) { this.node = node; main._node = null; main._html = ''; }
            };
        }
    },
    AndroidBridge: {
        getCache(key) { return cache.get(key) || ''; },
        saveCache(key, value) { cache.set(key, value); }
    },
    localStorage: { getItem() { return null; }, setItem() {} },
    location,
    scrollY: 0,
    scrollTo(_x, y) { restoredScrollY = y; }
};
context.window = context;
context.history = {
    state: null,
    pushState(state, _title, hash) { this.state = state; location.hash = hash; },
    replaceState(state) { this.state = state; location.hash = ''; },
    back() { this.state = null; location.hash = ''; context.onpopstate({ state: null }); }
};
vm.createContext(context);
vm.runInContext(script, context);
vm.runInContext(`
    performAsyncGet = (url, callback) => testRequests.push({ url, callback });
    parseTwitterGraphql = JSON.parse;
`, Object.assign(context, { testRequests: requests }));

const poster = 'https://pbs.twimg.com/ext_tw_video_thumb/test.jpg';
const low = 'https://video.twimg.com/ext_tw_video/test/low.mp4';
const high = 'https://video.twimg.com/ext_tw_video/test/high.mp4';
const parsed = vm.runInContext(`parseSingleTweet(${JSON.stringify({
    rest_id: '123',
    legacy: { full_text: 'A video', extended_entities: { media: [{
        media_url_https: poster, type: 'video', ext_alt_text: 'A giant monster near a bridge', video_info: { duration_millis: 4900, variants: [
            { content_type: 'video/mp4', bitrate: 832000, url: high },
            { content_type: 'application/x-mpegURL', url: 'https://video.twimg.com/test.m3u8' },
            { content_type: 'video/mp4', bitrate: 256000, url: low },
            { content_type: 'video/mp4', bitrate: 1000, url: 'https://evil.example/track.mp4' }
        ] } }] } }
})})`, context);
assert.equal(parsed.media[0].video_url, undefined, 'video URLs are not carried into feed cards');
assert.equal(parsed.media[0].duration_ms, 4900);
assert.equal(parsed.media[0].alt_text, 'A giant monster near a bridge');
assert.equal(vm.runInContext(`mediaPreviewUrl(${JSON.stringify(poster)}, true)`, context), `${poster}:thumb?format=jpg`);
assert.equal(vm.runInContext(`mediaPreviewUrl(${JSON.stringify(poster)}, false)`, context), poster);
const markup = vm.runInContext(`renderTweetHtml(${JSON.stringify(parsed)})`, context);
assert.match(markup, /Load poster frame/);
assert.match(markup, /:thumb\?format=jpg/, 'preview requests X thumbnail variant');
assert.match(markup, /data-hd-url="https:\/\/pbs\.twimg\.com\/ext_tw_video_thumb\/test\.jpg"/, 'HD remains an explicit separate request');
assert.match(markup, /Video · 5s · poster frame/);
assert.match(markup, /A giant monster near a bridge/);
assert.doesNotMatch(markup, /<video\b/, 'no video is fetched before a tap');
assert.doesNotMatch(markup, /\.mp4|playMedia|Play low quality/, 'video playback is not offered on slow data');
vm.runInContext("appConfig.image_mode = 'off'", context);
const textOnly = vm.runInContext(`renderTweetHtml(${JSON.stringify(parsed)})`, context);
assert.match(textOnly, /A giant monster near a bridge/, 'description remains available without an image request');
assert.doesNotMatch(textOnly, /<img|\.mp4/);
vm.runInContext("appConfig.image_mode = 'auto'", context);
const autoPreview = vm.runInContext(`renderTweetHtml(${JSON.stringify(parsed)})`, context);
assert.match(autoPreview, /%3Athumb%3Fformat%3Djpg/, 'automatic mode requests the small poster');
assert.match(autoPreview, /retryOriginalPreview\(this\)/, 'failed thumbnail can fall back once');
vm.runInContext("appConfig.image_mode = 'tap'", context);

cache.set('feed_following', JSON.stringify([{ id: 'old', text: 'Old cached post', media: [] }]));
cache.set('feed_cursor_following', 'saved-cursor');
vm.runInContext(`currentView = 'feed'; currentTab = 'following'; cursors.following = 'saved-cursor'; loadFeed('following', true)`, context);
assert.equal(requests.length, 1);
assert.equal(refresh.disabled, true);
assert.match(refresh.innerHTML, /inline-spinner/);
assert.match(main.innerHTML, /Old cached post/);
vm.runInContext('refreshFeed()', context);
assert.equal(requests.length, 1, 'duplicate refresh is suppressed');
requests.shift().callback(JSON.stringify({ tweets: [], error: 'Network unavailable' }));
assert.equal(refresh.disabled, false);
assert.equal(progress.classList.error, true);
assert.match(progress.textContent, /Tap Refresh to retry/);
assert.match(main.innerHTML, /Old cached post/, 'refresh failure keeps cached posts');
assert.equal(vm.runInContext('cursors.following', context), 'saved-cursor');
vm.runInContext('refreshFeed()', context);
assert.equal(requests.length, 1);
assert.equal(refresh.disabled, true);
requests.shift().callback(JSON.stringify({ tweets: [parsed], nextCursor: 'new-cursor' }));
assert.equal(refresh.disabled, false);
assert.equal(progress.textContent, 'Updated just now');
assert.equal(vm.runInContext('cursors.following', context), 'new-cursor');
assert.match(main.innerHTML, /Load poster frame/);
vm.runInContext("cursors.following = null; loadFeed('following')", context);
assert.equal(vm.runInContext('cursors.following', context), 'new-cursor', 'cached feed restores its pagination cursor');
assert.equal(requests.length, 0, 'recent cache does not trigger another request');

vm.runInContext("loadMoreTweets('following')", context);
assert.equal(requests.length, 1);
requests.shift().callback(JSON.stringify({ tweets: [parsed, parsed], nextCursor: 'new-cursor' }));
assert.equal(vm.runInContext('currentTweetsList.length', context), 1, 'paging does not duplicate existing posts');
assert.equal(vm.runInContext('cursors.following', context), null, 'a repeated cursor ends pagination');
assert.equal(progress.textContent, 'No more posts.');
vm.runInContext("loadMoreTweets('following')", context);
assert.equal(requests.length, 0, 'an exhausted cursor cannot request the first page again');

vm.runInContext('refreshFeed()', context);
requests.shift().callback(JSON.stringify({ tweets: [parsed, parsed], nextCursor: 'cursor-a' }));
assert.equal(vm.runInContext('currentTweetsList.length', context), 1, 'refresh deduplicates a page');
vm.runInContext("loadMoreTweets('following')", context);
requests.shift().callback(JSON.stringify({ tweets: [{ id: 'older', text: 'Older post', media: [] }, { id: 'older', text: 'Older post', media: [] }], nextCursor: 'cursor-b' }));
assert.equal(vm.runInContext('currentTweetsList.length', context), 2, 'paging deduplicates within a page');
vm.runInContext("loadMoreTweets('following')", context);
requests.shift().callback(JSON.stringify({ tweets: [parsed], nextCursor: 'cursor-a' }));
assert.equal(vm.runInContext('cursors.following', context), null, 'a cursor cycle ends pagination');

main.innerHTML += '<div id="page-two">Older page remains visible</div>';
context.scrollY = 640;
vm.runInContext("viewThread('123')", context);
assert.equal(requests.length, 1, 'thread detail request starts');
assert.equal(vm.runInContext('!!savedFeedNodes.firstChild', context), true, 'feed nodes are retained for return');
assert.doesNotMatch(main.innerHTML, /page-two/);
vm.runInContext('goBackToFeed()', context);
assert.match(main.innerHTML, /page-two/, 'back restores the visible paginated feed');
assert.equal(restoredScrollY, 640, 'back restores the feed scroll position');
assert.equal(requests.length, 1, 'back does not replace the feed with a new request');
requests.length = 0;

vm.runInContext('refreshFeed()', context);
assert.equal(requests.length, 1);
assert.equal(refresh.disabled, true);
context.scrollY = 400;
vm.runInContext("viewThread('123')", context);
assert.equal(requests.length, 2);
vm.runInContext('goBackToFeed()', context);
assert.equal(refresh.disabled, false, 'interrupted refresh no longer leaves a spinner');
assert.match(main.innerHTML, /page-two/);
assert.equal(restoredScrollY, 400);
requests[0].callback(JSON.stringify({ tweets: [], error: 'Late response' }));
assert.match(main.innerHTML, /page-two/, 'late refresh cannot overwrite restored feed');

const firstAttempt = vm.runInContext(`localImageUrl(${JSON.stringify(poster)}, 'thumb', true)`, context);
const secondAttempt = vm.runInContext(`localImageUrl(${JSON.stringify(poster)}, 'thumb', true)`, context);
assert.notEqual(firstAttempt, secondAttempt, 'each retry bypasses a cached failed response');
assert.match(firstAttempt, /&retry=/);
assert.match(decodeURIComponent(new URL(firstAttempt).searchParams.get('url')), /#xlite-retry=/, 'retry also bypasses an old APK image cache');
const images = [];
context.document.createElement = tag => {
    if (tag === 'img') {
        const img = { dataset: {} };
        images.push(img);
        return img;
    }
    if (tag === 'button') return { dataset: {} };
    if (tag === 'div') return { style: '', innerHTML: '' };
    throw new Error(`unexpected element ${tag}`);
};
const parentChildren = [];
const imageButton = {
    innerHTML: '',
    disabled: false,
    dataset: {},
    parentElement: {
        innerHTML: '',
        appendChild(node) { parentChildren.push(node); }
    }
};
context.testImageButton = imageButton;
vm.runInContext(`loadThumbImg(testImageButton, ${JSON.stringify(poster)}, 'test-image')`, context);
const failedUrl = images[0].src;
images[0].onerror();
assert.equal(imageButton.disabled, false);
assert.equal(imageButton.innerHTML, 'Retry loading');
vm.runInContext(`loadThumbImg(testImageButton, ${JSON.stringify(poster)}, 'test-image')`, context);
assert.notEqual(images[1].src, failedUrl, 'tap retry requests the image anew');
images[1].onload();
assert.equal(parentChildren.length, 2, 'loaded thumbnail renders image and HD View control');
assert.match(parentChildren[1].innerHTML, /HD View/);

const autoImage = {
    id: 'auto-image',
    dataset: { previewUrl: `${poster}:thumb?format=jpg`, originalUrl: poster },
    parentElement: {},
    replaceWith(button) { this.retryButton = button; }
};
context.testAutoImage = autoImage;
vm.runInContext('retryOriginalPreview(testAutoImage)', context);
assert.match(autoImage.src, /&retry=/, 'automatic preview tries the original once');
vm.runInContext('retryOriginalPreview(testAutoImage)', context);
assert.equal(autoImage.retryButton.textContent, 'Retry small preview');
assert.equal(autoImage.retryButton.dataset.retryRequested, '1');
assert.equal(typeof autoImage.retryButton.onclick, 'function');

(() => {
    const testFs = require('node:fs');
    const testPath = require('node:path');
    const testHtml = testFs.readFileSync(testPath.join(__dirname, '../app/src/main/assets/index.html'), 'utf8');
    const testScript = [...testHtml.matchAll(/<script>([\s\S]*?)<\/script>/g)][1][1];

    // viewThread must capture scrollY before moving feed nodes collapses document height
    let dynamicScrollY = 1480;
    let restoredTo = null;
    const feedChild = { id: 'feedContainer' };
    const feedChildren = [feedChild];
    const mainEl = {
        innerHTML: '<div id="feedContainer"></div>',
        get firstChild() { return feedChildren[0] || null; },
        appendChild(node) {
            if (node && Array.isArray(node.children)) {
                feedChildren.push(...node.children.splice(0));
            } else if (node) {
                feedChildren.push(node);
            }
        }
    };
    const scrollCtx = {
        console, URL, setTimeout, clearTimeout,
        requestAnimationFrame: cb => cb(),
        location: { hash: '', pathname: '/', search: '' },
        navigator: { onLine: true },
        get scrollY() { return dynamicScrollY; },
        scrollTo(_x, y) { dynamicScrollY = y; restoredTo = y; },
        document: {
            documentElement: { get scrollTop() { return dynamicScrollY; } },
            body: { get scrollTop() { return dynamicScrollY; } },
            getElementById(id) {
                if (id === 'mainContent') return mainEl;
                if (id === 'tabNav') return { style: {} };
                return null;
            },
            querySelectorAll() { return []; },
            querySelector() { return null; },
            createDocumentFragment() {
                return {
                    children: [],
                    get firstChild() { return this.children[0] || null; },
                    appendChild(el) {
                        const idx = feedChildren.indexOf(el);
                        if (idx >= 0) feedChildren.splice(idx, 1);
                        this.children.push(el);
                        if (feedChildren.length === 0) dynamicScrollY = 0;
                    }
                };
            }
        },
        AndroidBridge: { executeGetAsync() {} }
    };
    scrollCtx.history = {
        scrollRestoration: 'auto',
        state: null,
        pushState(s) { this.state = s; },
        replaceState(s) { this.state = s; },
        back() {
            this.state = null;
            if (typeof scrollCtx.onpopstate === 'function') scrollCtx.onpopstate({ state: this.state });
        }
    };
    scrollCtx.window = scrollCtx;
    vm.createContext(scrollCtx);
    vm.runInContext(testScript, scrollCtx);
    assert.equal(scrollCtx.history.scrollRestoration, 'manual', 'scrollRestoration is manual so WebView does not clobber back scroll');
    vm.runInContext("currentView = 'feed'; currentTab = 'following';", scrollCtx);
    scrollCtx.viewThread('555');
    assert.equal(dynamicScrollY, 0, 'moving feed nodes out simulates document height collapsing to 0');
    assert.equal(vm.runInContext('savedFeedScrollY', scrollCtx), 1480, 'scrollY is captured before mainContent is emptied');
    scrollCtx.goBackToFeed();
    assert.equal(restoredTo, 1480, 'returning from thread restores pre-collapse feed scroll position');

    // loadHdImg converts PNGs to WebP, falls back once to raw PNG, remains reloadable, and handles errors
    const loadedHdImages = [];
    class FakeHdImage {
        constructor() {
            this.dataset = {};
            this.src = '';
            loadedHdImages.push(this);
        }
    }
    const targetImg = { id: 'hd-target', src: 'thumb-initial' };
    const hdCtx = {
        console, URL, setTimeout, clearTimeout,
        Image: FakeHdImage,
        document: {
            getElementById(id) { return id === 'hd-target' ? targetImg : null; }
        }
    };
    hdCtx.window = hdCtx;
    vm.createContext(hdCtx);
    vm.runInContext(testScript, hdCtx);

    const hdBtn = { innerHTML: 'HD View', disabled: false, dataset: {}, style: {} };
    const pngUrl = 'https://pbs.twimg.com/media/sample.png?name=small';
    hdCtx.loadHdImg(hdBtn, pngUrl, 'hd-target');
    assert.equal(loadedHdImages.length, 1);
    assert.match(decodeURIComponent(new URL(loadedHdImages[0].src).searchParams.get('url')), /format=webp/, 'PNG HD requests prefer smaller WebP variant');
    loadedHdImages[0].onerror();
    assert.equal(loadedHdImages[0].dataset.fallbackTried, '1');
    assert.doesNotMatch(decodeURIComponent(new URL(loadedHdImages[0].src).searchParams.get('url')), /format=webp/, 'failed WebP falls back once to raw PNG URL');
    loadedHdImages[0].onload();
    assert.equal(targetImg.src, loadedHdImages[0].src);
    assert.equal(hdBtn.innerHTML, '✓ HD · Reload');
    assert.equal(hdBtn.disabled, false, 'HD button stays enabled so user can reload cleanly');
    assert.equal(hdBtn.dataset.retryRequested, '1');

    // Second click reloads fresh with retry parameter and recovers cleanly on error
    hdCtx.loadHdImg(hdBtn, pngUrl, 'hd-target');
    assert.match(loadedHdImages[1].src, /&retry=/, 'reloading HD forces fresh request');
    loadedHdImages[1].onerror();
    loadedHdImages[1].onerror();
    assert.equal(hdBtn.innerHTML, 'Retry HD');
    assert.equal(hdBtn.disabled, false);
    assert.equal(hdBtn.dataset.retryRequested, '1');
})();

console.log('Feed navigation, refresh, and image retry tests passed.');
