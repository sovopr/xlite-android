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
assert.equal(vm.runInContext(`mediaPreviewUrl(${JSON.stringify(poster)}, true)`, context), `${poster}?name=thumb`);
assert.equal(vm.runInContext(`mediaPreviewUrl(${JSON.stringify(poster)}, false)`, context), poster);
const markup = vm.runInContext(`renderTweetHtml(${JSON.stringify(parsed)})`, context);
assert.match(markup, /Load poster frame/);
assert.match(markup, /name=thumb/, 'preview requests X thumbnail variant');
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
assert.match(autoPreview, /name%3Dthumb/, 'automatic mode requests the small poster');
assert.match(autoPreview, /retryOriginalPreview\(this\)/, 'failed thumbnail can fall back once');
vm.runInContext("appConfig.image_mode = 'tap'", context);

cache.set('feed_following', JSON.stringify([{ id: 'old', text: 'Old cached post', media: [] }]));
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
    throw new Error(`unexpected element ${tag}`);
};
const imageButton = { innerHTML: '', disabled: false, dataset: {}, parentElement: {} };
context.testImageButton = imageButton;
vm.runInContext(`loadThumbImg(testImageButton, ${JSON.stringify(poster)}, 'test-image')`, context);
const failedUrl = images[0].src;
images[0].onerror();
assert.equal(imageButton.disabled, false);
assert.equal(imageButton.innerHTML, 'Retry loading');
vm.runInContext(`loadThumbImg(testImageButton, ${JSON.stringify(poster)}, 'test-image')`, context);
assert.notEqual(images[1].src, failedUrl, 'tap retry requests the image anew');

const autoImage = {
    id: 'auto-image',
    dataset: { previewUrl: `${poster}?name=thumb`, originalUrl: poster },
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

console.log('Feed navigation, refresh, and image retry tests passed.');
