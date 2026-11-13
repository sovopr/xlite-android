const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../android/app/src/main/assets/index.html'), 'utf8');
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
assert.equal(parsed.media[0].video_url, high, 'valid video.twimg.com MP4 is available for Auto mode');
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
assert.match(autoPreview, /<video\b[^>]*controls muted loop playsinline/, 'automatic mode renders muted autoplay inline video player');
assert.match(autoPreview, /preload="none"/, 'defers off-screen video metadata fetch until near viewport');
assert.match(autoPreview, /class="video-mute-btn"/, 'renders compact SVG mute toggle button');
assert.doesNotMatch(autoPreview, /video-meta-row|video-hd-btn|HD ✓/, 'video box renders plain without bottom metadata bar');
assert.match(autoPreview, /poster="https:\/\/local\.xlite\/img\?url=https%3A%2F%2Fpbs\.twimg\.com%2Fext_tw_video_thumb%2Ftest\.jpg&mode=(?:rich|hd)"/, 'automatic mode requests full-resolution poster');
assert.match(autoPreview, /src="https:\/\/video\.twimg\.com\/ext_tw_video\/test\/high\.mp4"/);
assert.doesNotMatch(autoPreview, /evil\.example/, 'untrusted MP4 origins are stripped');

const evilOnlyParsed = vm.runInContext(`parseSingleTweet(${JSON.stringify({
    rest_id: '124',
    legacy: { full_text: 'Untrusted host', extended_entities: { media: [{
        media_url_https: poster, type: 'video', video_info: { variants: [
            { content_type: 'video/mp4', bitrate: 800000, url: 'https://evil.example/track.mp4' }
        ] } }] } }
})})`, context);
assert.equal(evilOnlyParsed.media[0].video_url, undefined, 'untrusted video domains are never kept');
const evilFallbackHtml = vm.runInContext(`renderTweetHtml(${JSON.stringify(evilOnlyParsed)})`, context);
assert.match(evilFallbackHtml, /retryOriginalPreview\(this\)/, 'fallback poster image keeps retry support');
assert.doesNotMatch(evilFallbackHtml, /<video\b|\.mp4/);
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
    const testHtml = testFs.readFileSync(testPath.join(__dirname, '../android/app/src/main/assets/index.html'), 'utf8');
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
    assert.match(hdBtn.innerHTML, /^<svg[^>]*icon-svg[^>]*>[\s\S]* HD · Reload$/);
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

    // Profile pictures: lazy _mini / _normal variants, 0 KB fallback in off mode, initial fallback
    const pfpTweet = hdCtx.parseSingleTweet({
        rest_id: '888',
        core: {
            user_results: {
                result: {
                    core: { name: 'Ada', screen_name: 'ada' },
                    avatar: { image_url: 'https://pbs.twimg.com/profile_images/123/abc_normal.jpg' }
                }
            }
        },
        legacy: { id_str: '888', full_text: 'Testing pfp' },
        quoted_status_result: {
            result: {
                rest_id: '887',
                core: {
                    user_results: {
                        result: {
                            core: { name: 'Grace', screen_name: 'grace' },
                            avatar: { image_url: 'https://pbs.twimg.com/profile_images/456/qwe_bigger.png' }
                        }
                    }
                },
                legacy: { id_str: '887', full_text: 'Quoted text' }
            }
        }
    });
    assert.equal(pfpTweet.user_avatar, 'https://pbs.twimg.com/profile_images/123/abc_normal.jpg');
    assert.equal(pfpTweet.quoted.user_avatar, 'https://pbs.twimg.com/profile_images/456/qwe_bigger.png');
    assert.equal(hdCtx.avatarVariantUrl('https://pbs.twimg.com/profile_images/123/abc_normal.jpg', 'mini'), 'https://pbs.twimg.com/profile_images/123/abc_mini.jpg');
    assert.equal(hdCtx.avatarVariantUrl('https://pbs.twimg.com/profile_images/456/qwe_bigger.png', 'normal'), 'https://pbs.twimg.com/profile_images/456/qwe_normal.png');
    assert.equal(hdCtx.avatarVariantUrl('https://abs.twimg.com/sticky/default_profile_images/default_profile_normal.png', 'mini'), '');
    assert.equal(hdCtx.avatarVariantUrl('https://pbs.twimg.com/profile_images/789/anim_normal.gif', 'mini'), '');

    vm.runInContext("appConfig.image_mode = 'tap'; appConfig.avatar_mode = 'mini';", hdCtx);
    const pfpMarkup = hdCtx.renderTweetHtml(pfpTweet);
    assert.match(pfpMarkup, /class="tweet-avatar"/);
    assert.match(pfpMarkup, /class="tweet-avatar mini"/, 'quoted tweet renders compact mini avatar');
    assert.match(pfpMarkup, /loading="lazy"/);
    assert.match(pfpMarkup, /abc_mini\.jpg/);
    assert.match(pfpMarkup, /qwe_mini\.png/);
    assert.match(pfpMarkup, /onerror="this\.remove\(\)"/);

    vm.runInContext("appConfig.avatar_mode = 'off';", hdCtx);
    const initialsMarkup = hdCtx.renderTweetHtml(pfpTweet);
    assert.doesNotMatch(initialsMarkup, /<img\b/, 'Initials-only mode loads zero avatar images');
    assert.match(initialsMarkup, /<span>A<\/span>/);
    vm.runInContext("appConfig.avatar_mode = 'mini'; appConfig.image_mode = 'off';", hdCtx);
    assert.doesNotMatch(hdCtx.renderTweetHtml(pfpTweet), /<img\b/, 'Text-only mode keeps 0 KB even when avatar_mode is mini');

    // Promoted posts (ads) badge + HTML entity decoding without breaking XSS escaping
    const promoResponse = JSON.stringify({
        data: {
            home: {
                home_timeline_urt: {
                    instructions: [
                        {
                            type: 'TimelineAddEntries',
                            entries: [
                                {
                                    entryId: 'promoted-tweet-9901',
                                    content: {
                                        itemContent: {
                                            itemType: 'TimelineTweet',
                                            promotedMetadata: { impressionId: 'imp-1' },
                                            tweet_results: {
                                                result: {
                                                    rest_id: '9901',
                                                    core: { user_results: { result: { core: { name: 'Emory &amp; Blanc', screen_name: 'emoryblanc' } } } },
                                                    legacy: { id_str: '9901', full_text: 'Silk &amp; Linen &gt; 50% off &lt;script&gt;alert(1)&lt;/script&gt; https://example.com?a=1&amp;b=2' }
                                                }
                                            }
                                        }
                                    }
                                },
                                {
                                    entryId: 'tweet-9902',
                                    content: {
                                        itemContent: {
                                            itemType: 'TimelineTweet',
                                            tweet_results: {
                                                result: {
                                                    rest_id: '9902',
                                                    core: { user_results: { result: { core: { name: 'Regular User', screen_name: 'reguser' } } } },
                                                    legacy: { id_str: '9902', full_text: 'Literal entity &amp;amp; &amp;lt;tag&amp;gt; stays literal' }
                                                }
                                            }
                                        }
                                    }
                                },
                                {
                                    entryId: 'home-conversation-9903',
                                    content: {
                                        items: [
                                            {
                                                entryId: 'promoted-tweet-9903',
                                                item: {
                                                    itemContent: {
                                                        itemType: 'TimelineTweet',
                                                        tweet_results: {
                                                            result: {
                                                                rest_id: '9903',
                                                                core: { user_results: { result: { core: { name: 'Module Ad', screen_name: 'modad' } } } },
                                                                legacy: { id_str: '9903', full_text: 'Promoted inside module' }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        ]
                                    }
                                },
                                {
                                    entryId: 'tweet-9904',
                                    content: {
                                        itemContent: {
                                            itemType: 'TimelineTweet',
                                            tweet_results: {
                                                result: {
                                                    rest_id: '9904',
                                                    core: { user_results: { result: { core: { name: 'Injected Ad', screen_name: 'injad' } } } },
                                                    legacy: { id_str: '9904', full_text: 'Marked via injectionType' }
                                                }
                                            }
                                        },
                                        clientEventInfo: { details: { timelinesDetails: { injectionType: 'ForYouPromoted' } } }
                                    }
                                },
                                {
                                    entryId: 'cursor-bottom-9999',
                                    content: { entryType: 'TimelineTimelineCursor', cursorType: 'Bottom', value: 'CURSOR_AFTER_ADS' }
                                }
                            ]
                        }
                    ]
                }
            }
        }
    });
    // Default behavior hides promoted ads across timelines while preserving organic posts & cursor
    const filteredParsed = hdCtx.parseTwitterGraphql(promoResponse);
    assert.equal(filteredParsed.tweets.length, 1, 'Promoted ads are hidden by default');
    assert.equal(filteredParsed.tweets[0].id, '9902');
    assert.equal(filteredParsed.nextCursor, 'CURSOR_AFTER_ADS');

    // Stale cached ads from earlier versions are also stripped on loadFeed when hide_ads is enabled
    const cacheMainEl = { innerHTML: '', insertAdjacentHTML() {} };
    hdCtx.document.getElementById = id => {
        if (id === 'mainContent') return cacheMainEl;
        if (id === 'hd-target') return targetImg;
        return null;
    };
    hdCtx.AndroidBridge = hdCtx.window.AndroidBridge = {
        getCache(key) {
            if (key === 'feed_foryou') {
                return JSON.stringify([
                    { id: '9901', text: 'Cached ad', user_name: 'Ad', user_screen_name: 'ad', is_ad: true },
                    { id: '9902', text: 'Cached organic', user_name: 'Regular User', user_screen_name: 'reguser', is_ad: false }
                ]);
            }
            if (key === 'feed_updated_foryou') return String(Date.now());
            return '';
        },
        saveCache() {},
        executeGetAsync() {}
    };
    hdCtx.loadFeed('foryou');
    assert.doesNotMatch(cacheMainEl.innerHTML, /Cached ad/, 'Previously cached ads are hidden immediately');
    assert.match(cacheMainEl.innerHTML, /Cached organic/);

    // Opting in to show ads displays them with the Ad badge and decoded HTML entities
    vm.runInContext("appConfig.hide_ads = false;", hdCtx);
    const promoParsed = hdCtx.parseTwitterGraphql(promoResponse);
    assert.equal(promoParsed.tweets.length, 4);
    const [adTweet, organicTweet, moduleAdTweet, injectedAdTweet] = promoParsed.tweets;
    assert.equal(adTweet.is_ad, true);
    assert.equal(adTweet.user_name, 'Emory & Blanc');
    assert.equal(organicTweet.is_ad, false);
    assert.equal(moduleAdTweet.is_ad, true);
    assert.equal(injectedAdTweet.is_ad, true);

    const adMarkup = hdCtx.renderTweetHtml(adTweet);
    assert.match(adMarkup, /<span class="ad-badge">Ad<\/span>/);
    assert.match(adMarkup, /Emory &amp; Blanc/);
    assert.doesNotMatch(adMarkup, /&amp;amp;/, 'HTML entity &amp; must not be double-escaped');
    assert.match(adMarkup, /Silk &amp; Linen &gt; 50% off &lt;script&gt;alert\(1\)&lt;\/script&gt;/);
    assert.doesNotMatch(adMarkup, /<script>alert/, 'Decoded tags are re-escaped safely by linkify');
    assert.match(adMarkup, /href="https:\/\/example\.com\?a=1&amp;b=2"/);

    const organicMarkup = hdCtx.renderTweetHtml(organicTweet);
    assert.doesNotMatch(organicMarkup, /ad-badge/);
    assert.match(organicMarkup, /&amp;amp; &amp;lt;tag&amp;gt;/, 'User-typed literal entities remain literal');
    vm.runInContext("appConfig.hide_ads = true;", hdCtx);
})();

(() => {
    // Rich Auto mode photos grid, lightbox, GIF autoplay, cards, polls, compact counts, and Seen sync
    const richDom = new Map();
    const richEl = (tag) => ({
        tagName: tag,
        innerHTML: '',
        textContent: '',
        style: {},
        dataset: {},
        _classes: new Set(),
        classList: {
            add(c) { this._parent._classes.add(c); },
            remove(c) { this._parent._classes.delete(c); },
            toggle(c, f) { if (f === undefined) { if (this._parent._classes.has(c)) this._parent._classes.delete(c); else this._parent._classes.add(c); } else if (f) this._parent._classes.add(c); else this._parent._classes.delete(c); },
            contains(c) { return this._parent._classes.has(c); }
        },
        removeAttribute(name) { delete this[name]; },
        insertAdjacentHTML(_pos, h) { this.innerHTML += h; },
        querySelectorAll() { return []; },
        querySelector() { return null; }
    });
    const makeRichEl = (id) => {
        const el = richEl('div');
        el.classList._parent = el;
        richDom.set(id, el);
        return el;
    };
    const rMain = makeRichEl('mainContent');
    ['mediaLightbox', 'lightboxImg', 'lightboxCounter', 'lightboxZoomBtn', 'lightboxPrev', 'lightboxNext', 'lightboxCaption', 'feedContainer', 'feedRefreshButton', 'feedProgress'].forEach(makeRichEl);

    const postedTimelines = [];
    const formPosts = [];
    const rCtx = vm.createContext({
        console, setTimeout, clearTimeout, Date, Math, JSON, URL, encodeURIComponent, decodeURIComponent, Set, Map,
        localStorage: (() => {
            const m = new Map();
            return { getItem: k => m.get(k) || null, setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k) };
        })(),
        history: { scrollRestoration: 'auto', state: null, replaceState() {}, pushState() {} },
        window: { location: { hash: '', pathname: '/', search: '' }, addEventListener() {} },
        document: {
            getElementById: id => richDom.get(id) || null,
            querySelector: () => null,
            querySelectorAll: () => [],
            addEventListener() {}
        },
        AndroidBridge: null,
        fetch: () => Promise.resolve({ text: () => Promise.resolve('{}') })
    });
    rCtx.window.AndroidBridge = rCtx.AndroidBridge = {
        getCookies: () => JSON.stringify({ auth_token: 'tok', ct0: 'csrf', image_mode: 'auto' }),
        getCache: () => '',
        saveCache: () => {},
        takeTempResult: () => '',
        executeGetAsync: () => {},
        executePostWithTransactionAsync: (url, payload, tid, cbId) => {
            postedTimelines.push({ url, payload: JSON.parse(payload), tid, cbId });
        },
        executeFormPostWithTransactionAsync: (url, formBody, tid, cbId) => {
            formPosts.push({ url, formBody, tid, cbId });
        }
    };
    vm.runInContext(script, rCtx);
    vm.runInContext(`
        appConfig = { auth_token: 'tok', ct0: 'csrf', image_mode: 'auto', avatar_mode: 'mini', hide_ads: true, sync_seen: true };
        searchTransactionClient = { generateTransactionId: (m, p) => 'tid:' + m + ':' + p };
        searchTransactionFetchedAt = Date.now();
    `, rCtx);

    assert.equal(rCtx.formatCount(999), '999');
    assert.equal(rCtx.formatCount(13549), '13.5K');
    assert.equal(rCtx.formatCount(2400000), '2.4M');

    const richTweetRaw = {
        rest_id: '777001',
        core: { user_results: { result: { rest_id: '55', is_blue_verified: true, core: { name: 'Ada', screen_name: 'ada' } } } },
        legacy: {
            full_text: 'Three crisp photos + poll',
            favorite_count: 1520,
            retweet_count: 42,
            reply_count: 8,
            extended_entities: {
                media: [
                    { media_url_https: 'https://pbs.twimg.com/media/one.jpg', type: 'photo', ext_alt_text: 'Alt one' },
                    { media_url_https: 'https://pbs.twimg.com/media/two.jpg', type: 'photo' },
                    { media_url_https: 'https://pbs.twimg.com/media/three.jpg', type: 'photo' }
                ]
            }
        },
        card: {
            legacy: {
                name: 'poll2choice_text_only',
                binding_values: [
                    { key: 'choice1_label', value: { string_value: 'Yes' } },
                    { key: 'choice1_count', value: { string_value: '75' } },
                    { key: 'choice2_label', value: { string_value: 'No' } },
                    { key: 'choice2_count', value: { string_value: '25' } },
                    { key: 'counts_are_final', value: { boolean_value: true } }
                ]
            }
        }
    };
    const richParsed = vm.runInContext(`parseSingleTweet(${JSON.stringify(richTweetRaw)})`, rCtx);
    assert.equal(richParsed.user_verified, true);
    assert.equal(richParsed.poll.total, 100);
    const richMarkup = vm.runInContext(`renderTweetHtml(${JSON.stringify(richParsed)})`, rCtx);
    assert.match(richMarkup, /class="media-grid media-grid-3"/);
    assert.match(richMarkup, /mode=rich/);
    assert.match(richMarkup, /verified-badge/);
    assert.match(richMarkup, /<svg[^>]*icon-svg[^>]*>[\s\S]*<span>1\.5K<\/span>/);
    assert.doesNotMatch(richMarkup, /[🔔👤🏠🔍⚙⌂⚲✎💬⇄♥♡❝★☆🔗📦●📍✓]/u, 'Rendered tweet UI uses SVG icons instead of pictographs or checkmarks');
    assert.match(richMarkup, /poll-box/);
    assert.match(richMarkup, /width:75%/);

    // Lightbox open / step / zoom / close
    rCtx.openLightbox('777001', 1);
    assert.equal(richDom.get('mediaLightbox').classList.contains('open'), true);
    assert.equal(richDom.get('lightboxCounter').textContent, '2 / 3');
    assert.match(richDom.get('lightboxImg').src, /two\.jpg.*mode=hd/);
    rCtx.stepLightbox(1);
    assert.equal(richDom.get('lightboxCounter').textContent, '3 / 3');
    rCtx.toggleLightboxZoom();
    assert.equal(richDom.get('lightboxImg').classList.contains('zoomed'), true);
    rCtx.closeLightbox();
    assert.equal(richDom.get('mediaLightbox').classList.contains('open'), false);

    // GIF renders muted loop autoplay video
    const gifTw = vm.runInContext(`parseSingleTweet(${JSON.stringify({
        rest_id: '777002',
        legacy: { full_text: 'Looping GIF', extended_entities: { media: [{
            media_url_https: 'https://pbs.twimg.com/tweet_video_thumb/g.jpg',
            type: 'animated_gif',
            video_info: { variants: [{ content_type: 'video/mp4', bitrate: 0, url: 'https://video.twimg.com/tweet_video/g.mp4' }] }
        }] } }
    })})`, rCtx);
    const gifHtml = vm.runInContext(`renderTweetHtml(${JSON.stringify(gifTw)})`, rCtx);
    assert.match(gifHtml, /autoplay loop muted playsinline/);
    assert.match(gifHtml, />GIF</);

    // Seen-post sync batches linger jot event + sends seenTweetIds in POST HomeTimeline + dedupes For You refresh
    rCtx.markTweetSeen('777001', 'home', 800);
    rCtx.flushSeenLinger(true);
    assert.equal(formPosts.length, 1);
    assert.match(formPosts[0].url, /\/i\/api\/1\.1\/jot\/client_event\.json$/);
    assert.match(decodeURIComponent(formPosts[0].formBody), /777001/);

    vm.runInContext("currentView = 'feed'; currentTab = 'foryou'; fetchFeedData('foryou', false);", rCtx);
    assert.equal(postedTimelines.length, 1);
    assert.match(postedTimelines[0].url, /\/HomeTimeline$/);
    assert.deepEqual(postedTimelines[0].payload.variables.seenTweetIds, ['777001']);
    assert.equal(postedTimelines[0].payload.variables.count, 20, 'Auto mode fetches 20 posts per page');

    // Feed response with 1 already-seen post (777001) and 1 unseen post (777099) drops repeat on For You refresh
    rCtx.window.__onAsyncFinish(postedTimelines[0].cbId, JSON.stringify({
        data: { home: { home_timeline_urt: { instructions: [{ type: 'TimelineAddEntries', entries: [
            { entryId: 'tweet-777001', content: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: richTweetRaw } } } },
            { entryId: 'tweet-777099', content: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: { rest_id: '777099', legacy: { full_text: 'Fresh post' } } } } } }
        ] }] } } }
    }));
    assert.doesNotMatch(rMain.innerHTML, /Three crisp photos/, 'Already-seen post filtered on For You refresh');
    assert.match(rMain.innerHTML, /Fresh post/, 'Unseen post shown');

    // Full-quality Auto mode: HD video default + SD fallback + mutually exclusive play + rich poster + quoted media + expanded URLs + zero ads
    const multiBitrateRaw = {
        rest_id: '777100',
        core: { user_results: { result: { rest_id: '55', legacy: { name: 'Studio', screen_name: 'studio', profile_image_url_https: 'https://pbs.twimg.com/profile_images/55/pfp_normal.jpg' } } } },
        legacy: {
            full_text: 'Read https://t.co/doc123 before watching https://t.co/quote999',
            entities: {
                urls: [
                    { url: 'https://t.co/doc123', expanded_url: 'https://example.com/guide' },
                    { url: 'https://t.co/quote999', expanded_url: 'https://x.com/studio/status/777101' }
                ]
            },
            extended_entities: {
                media: [{
                    media_url_https: 'https://pbs.twimg.com/ext_tw_video_thumb/777100/pu/img/poster.jpg',
                    type: 'video',
                    video_info: {
                        duration_millis: 12000,
                        variants: [
                            { content_type: 'video/mp4', bitrate: 432000, url: 'https://video.twimg.com/ext_tw_video/777100/sd.mp4' },
                            { content_type: 'video/mp4', bitrate: 2176000, url: 'https://video.twimg.com/ext_tw_video/777100/hd.mp4' }
                        ]
                    }
                }]
            }
        },
        quoted_status_result: {
            result: {
                rest_id: '777101',
                core: { user_results: { result: { rest_id: '56', legacy: { name: 'Quoted', screen_name: 'quoted', profile_image_url_https: 'https://pbs.twimg.com/profile_images/56/q_normal.jpg' } } } },
                legacy: {
                    full_text: 'Quoted visual breakdown',
                    extended_entities: { media: [{ media_url_https: 'https://pbs.twimg.com/media/quotedpic.jpg', type: 'photo' }] }
                }
            }
        }
    };
    const hdMultiParsed = vm.runInContext(`parseSingleTweet(${JSON.stringify(multiBitrateRaw)})`, rCtx);
    assert.equal(hdMultiParsed.video_url || hdMultiParsed.media[0].video_url, 'https://video.twimg.com/ext_tw_video/777100/sd.mp4');
    assert.equal(hdMultiParsed.media[0].hd_video_url, 'https://video.twimg.com/ext_tw_video/777100/hd.mp4');
    assert.equal(hdMultiParsed.text, 'Read https://example.com/guide before watching', 'expands entity URLs and trims redundant quote link');

    const hdMultiHtml = vm.runInContext(`appConfig.avatar_mode = 'normal'; renderTweetHtml(${JSON.stringify(hdMultiParsed)})`, rCtx);
    assert.match(hdMultiHtml, /src="https:\/\/video\.twimg\.com\/ext_tw_video\/777100\/hd\.mp4"/, 'Auto mode defaults directly to full HD video bitrate');
    assert.match(hdMultiHtml, /data-fallback-src="https:\/\/video\.twimg\.com\/ext_tw_video\/777100\/sd\.mp4"/, 'HD video retains SD fallback');
    assert.match(hdMultiHtml, /onplay="pauseOtherVideos\(this\)"/);
    assert.match(hdMultiHtml, /controls muted loop playsinline data-autoplay="1"/, 'Videos autoplay muted by default on X-like timeline');
    assert.doesNotMatch(hdMultiHtml, /video-meta-row|video-hd-btn|HD ✓/, 'Video card omits bottom Video/HD bar');
    assert.match(hdMultiHtml, /poster\.jpg[^"]*mode=rich/, 'Auto mode requests high-res video poster');
    assert.match(hdMultiHtml, /pfp_bigger\.jpg[^"]*mode=rich/, 'Auto mode upgrades normal avatars cleanly');
    assert.match(hdMultiHtml, /quotedpic\.jpg[^"]*mode=rich/, 'Auto mode renders media inside quoted tweets');

    // Disabling Video Autoplay disables muted autoplay attrs & mute button while keeping plain container
    vm.runInContext("appConfig.autoplay_videos = false;", rCtx);
    const manualVideoHtml = vm.runInContext(`renderTweetHtml(${JSON.stringify(hdMultiParsed)})`, rCtx);
    assert.match(manualVideoHtml, /controls playsinline preload="none"/);
    assert.doesNotMatch(manualVideoHtml, /data-autoplay="1"|video-mute-btn|video-meta-row/);
    vm.runInContext("appConfig.autoplay_videos = true;", rCtx);

    // Every ad vector is blocked when hide_ads is active
    const sneakyAds = rCtx.parseTwitterGraphql(JSON.stringify({
        data: {
            home: {
                home_timeline_urt: {
                    instructions: [{
                        type: 'TimelineAddEntries',
                        entries: [
                            { entryId: 'promoted-tweet-1', content: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: { rest_id: 'ad1', legacy: { full_text: 'Ad one' } } } } } },
                            { entryId: 'tweet-2', content: { clientEventInfo: { details: { timelinesDetails: { injectionType: 'FeedPromoted' } } }, itemContent: { itemType: 'TimelineTweet', tweetDisplayType: 'Promoted', tweet_results: { result: { rest_id: 'ad2', legacy: { full_text: 'Ad two' } } } } } },
                            { entryId: 'tweet-3', content: { itemContent: { itemType: 'TimelineTweet', promoted_metadata: { ad: true }, tweet_results: { result: { rest_id: 'ad3', legacy: { full_text: 'Ad three' } } } } } },
                            { entryId: 'tweet-ok', content: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: { rest_id: 'ok1', legacy: { full_text: 'Organic signal' } } } } } }
                        ]
                    }]
                }
            }
        }
    }));
    assert.equal(Array.from(sneakyAds.tweets, t => t.id).join(','), 'ok1', 'All promoted ad formats are filtered');

    const restAdParsed = rCtx.parseTwitterGraphql(JSON.stringify({
        statuses: [
            { id_str: 'rad1', promoted: true, full_text: 'REST Promoted' },
            { id_str: 'rok1', full_text: 'REST Organic' }
        ]
    }));
    assert.equal(Array.from(restAdParsed.tweets, t => t.id).join(','), 'rok1', 'REST fallback respects hide_ads');

    vm.runInContext("if (seenSaveDebounce) clearTimeout(seenSaveDebounce); if (seenFlushTimer) clearTimeout(seenFlushTimer);", rCtx);
})();

(() => {
    // Reddit-style threaded replies hierarchy + ancestor spine + collapse support
    const domMap = new Map();
    const makeDomNode = () => ({ innerHTML: '', textContent: '' });
    ['mainContent', 'threadAncestors', 'threadFocal', 'repliesContainer', 'threadContainer'].forEach(id => domMap.set(id, makeDomNode()));
    const tCtx = vm.createContext({
        console, setTimeout, clearTimeout, Date, Math, JSON, URL, encodeURIComponent, decodeURIComponent, Set, Map,
        localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
        history: { scrollRestoration: 'auto', state: null, replaceState() {}, pushState() {} },
        window: { location: { hash: '', pathname: '/', search: '' }, addEventListener() {} },
        document: {
            getElementById: id => domMap.get(id) || null,
            querySelector: () => null,
            querySelectorAll: () => [],
            addEventListener() {}
        },
        AndroidBridge: null,
        fetch: () => Promise.resolve({ text: () => Promise.resolve('{}') })
    });
    tCtx.window.AndroidBridge = tCtx.AndroidBridge = {
        getCookies: () => JSON.stringify({ auth_token: 'tok', ct0: 'csrf', image_mode: 'auto' }),
        getCache: () => '',
        saveCache() {}
    };
    vm.runInContext(script, tCtx);

    const parsedThread = tCtx.parseTwitterGraphql(JSON.stringify({
        data: { threaded_conversation_with_injections_v2: { instructions: [{ type: 'TimelineAddEntries', entries: [
            { entryId: 'tweet-10', content: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: {
                rest_id: '10',
                core: { user_results: { result: { core: { name: 'Root', screen_name: 'rootuser' } } } },
                legacy: { full_text: 'Original parent post' }
            } } } } },
            { entryId: 'tweet-20', content: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: {
                rest_id: '20',
                core: { user_results: { result: { core: { name: 'OP', screen_name: 'opuser' } } } },
                legacy: { full_text: 'Focal post replying to root', in_reply_to_status_id_str: '10', in_reply_to_screen_name: 'rootuser' }
            } } } } },
            { entryId: 'conversationthread-1', content: { items: [
                { item: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: {
                    rest_id: '30',
                    core: { user_results: { result: { core: { name: 'Alice', screen_name: 'alice' } } } },
                    legacy: { full_text: 'First level comment', in_reply_to_status_id_str: '20', in_reply_to_screen_name: 'opuser' }
                } } } } },
                { item: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: {
                    rest_id: '31',
                    core: { user_results: { result: { core: { name: 'Bob', screen_name: 'bob' } } } },
                    legacy: { full_text: 'Nested reply to Alice', in_reply_to_status_id_str: '30', in_reply_to_screen_name: 'alice' }
                } } } } },
                { item: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: {
                    rest_id: '32',
                    core: { user_results: { result: { core: { name: 'Carol', screen_name: 'carol' } } } },
                    legacy: { full_text: 'Level 3 reply to Bob', in_reply_to_status_id_str: '31', in_reply_to_screen_name: 'bob' }
                } } } } }
            ] } },
            { entryId: 'tweet-40', content: { itemContent: { itemType: 'TimelineTweet', tweet_results: { result: {
                rest_id: '40',
                core: { user_results: { result: { core: { name: 'Orphan', screen_name: 'orphan' } } } },
                legacy: { full_text: 'Replying to missing tweet', in_reply_to_status_id_str: '999', in_reply_to_screen_name: 'deleteduser' }
            } } } } }
        ] }] } }
    }));

    assert.equal(parsedThread.tweets.find(t => t.id === '31').in_reply_to_id, '30');
    assert.equal(parsedThread.tweets.find(t => t.id === '31').in_reply_to_screen_name, 'alice');

    tCtx.renderThreadData('20', null, parsedThread);
    const ancHtml = domMap.get('threadAncestors').innerHTML;
    const focalHtml = domMap.get('threadFocal').innerHTML;
    const repliesHtml = domMap.get('repliesContainer').innerHTML;

    assert.match(ancHtml, /Original parent post/, 'Ancestor parent renders above focal post');
    assert.match(focalHtml, /Focal post replying to root/, 'Focal tweet populates when loaded from TweetDetail');
    assert.match(repliesHtml, /data-tweet-id="30" data-depth="0"/, 'Top-level reply renders at depth 0');
    assert.match(repliesHtml, /data-tweet-id="31" data-depth="1"/, 'Child reply nests at depth 1');
    assert.match(repliesHtml, /data-tweet-id="32" data-depth="2"/, 'Grandchild reply nests at depth 2');
    assert.match(repliesHtml, /Replying to .*@alice/, 'Shows explicit parent target handle');
    assert.match(repliesHtml, /Replying to .*@deleteduser/, 'Orphaned reply still shows who it replied to');
    assert.match(repliesHtml, /reddit-children/, 'Nested children wrapped inside thread guide container');
    assert.match(repliesHtml, /data-desc-count="2"/, 'Collapse button tracks descendant count');
})();

(() => {
    // Persistent compact ClientTransaction cache enables 0-roundtrip cold starts and recovers on 401
    const txScript = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)][0][1];
    const persistentKv = new Map();
    const validKey = Buffer.from(Array.from({ length: 48 }, (_, i) => (i * 11 + 7) % 256)).toString('base64');
    const makeSvg = id => `<svg id="loading-x-anim-${id}"><path d="M0 0"></path><path d="M 10 20 30 40 50 60 70 C 120 80 140 160 90 210 180 40 200 110"></path></svg>`;
    const homeHtmlFixture = `<html><head><meta name="twitter-site-verification" content="${validKey}"><script>var chunks={,12:"ondemand.s",12:"deadbeef"};</script></head><body>${makeSvg(0)}${makeSvg(1)}${makeSvg(2)}${makeSvg(3)}</body></html>`;
    const ondemandScriptFixture = 'function x(e){return (e[1],16)+(e[2],16)+(e[3],16)+(e[4],16);}';

    const makeBootCtx = (getLog, postLog) => {
        const ctx = vm.createContext({
            console, setTimeout, clearTimeout, Date, Math, JSON, URL, encodeURIComponent, decodeURIComponent, Set, Map, Uint8Array, TextEncoder, atob, btoa, Buffer,
            localStorage: { getItem: k => persistentKv.get('ls_' + k) || null, setItem: (k, v) => persistentKv.set('ls_' + k, String(v)), removeItem: k => persistentKv.delete('ls_' + k) },
            history: { scrollRestoration: 'auto', state: null, replaceState() {}, pushState() {} },
            window: { location: { hash: '', pathname: '/', search: '' }, addEventListener() {} },
            document: { getElementById: () => null, querySelector: () => null, querySelectorAll: () => [], addEventListener() {} },
            AndroidBridge: null
        });
        ctx.window.AndroidBridge = ctx.AndroidBridge = {
            getCookies: () => JSON.stringify({ auth_token: 'tok', ct0: 'csrf', image_mode: 'tap' }),
            getCache: k => persistentKv.get(k) || '',
            saveCache: (k, v) => { if (v) persistentKv.set(k, String(v)); else persistentKv.delete(k); },
            executeGetAsync: (url, cbId) => {
                getLog.push(url);
                const body = url.includes('ondemand.s.') ? ondemandScriptFixture : homeHtmlFixture;
                setTimeout(() => ctx.window.__onAsyncFinish(cbId, body), 0);
            },
            executePostWithTransactionAsync: (url, payload, tid, cbId) => {
                postLog.push({ url, payload, tid, cbId });
            }
        };
        vm.runInContext(txScript, ctx);
        vm.runInContext(script, ctx);
        return ctx;
    };

    const coldGets = [];
    const coldPosts = [];
    const ctx1 = makeBootCtx(coldGets, coldPosts);
    ctx1.performSignedPost('/i/api/graphql/qid1/HomeLatestTimeline', '{"variables":{}}', () => {});

    setTimeout(() => {
        assert.equal(coldGets.length, 2, 'Cold first run fetches home HTML + ondemand script once');
        assert.equal(coldPosts.length, 1);
        assert.ok(persistentKv.get('pref_tx_cache_v1').length < 2500, 'Persisted tx snippet stays ultra-compact');

        // Second cold launch reads cached tx synchronously and dispatches timeline with ZERO home/ondemand fetches
        const warmGets = [];
        const warmPosts = [];
        const ctx2 = makeBootCtx(warmGets, warmPosts);
        assert.equal(ctx2.hydrateCachedXTransaction(), true);
        let warmResult = '';
        ctx2.performSignedPost('/i/api/graphql/qid1/HomeLatestTimeline', '{"variables":{}}', (r) => { warmResult = r; });
        assert.equal(warmGets.length, 0, 'Warm launch sends timeline POST immediately without fetching x.com/home or JS');
        assert.equal(warmPosts.length, 1);

        // If cached transaction fails HTTP 401, client invalidates cache, refreshes once, and retries
        ctx2.window.__onAsyncFinish(warmPosts[0].cbId, JSON.stringify({ error: true, status: 401, message: 'HTTP 401' }));
        setTimeout(() => {
            assert.equal(warmGets.length, 1, '401 invalidation skips redundant ondemand fetch because ondemand hash is already cached');
            assert.equal(warmPosts.length, 2, 'Retries failed signed POST once with fresh transaction');
            ctx2.window.__onAsyncFinish(warmPosts[1].cbId, JSON.stringify({ data: { ok: true } }));
            assert.equal(warmResult, JSON.stringify({ data: { ok: true } }));
            txWarmChecksCompleted = true;
        }, 40);
    }, 40);
})();

let txWarmChecksCompleted = false;
process.on('beforeExit', () => {
    assert.equal(txWarmChecksCompleted, true, 'Async warm ClientTransaction checks must finish completely');
});

console.log('Feed navigation, refresh, and image retry tests passed.');
